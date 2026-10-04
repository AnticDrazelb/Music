package app.tempo.music.data.mixes

import app.tempo.music.data.LibraryRepository
import app.tempo.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** A playlist made from the listener's own history, refreshed daily ("Made for You"). */
@Serializable
data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
    /** Index into the app's gradient palette, so each mix keeps its colour. */
    val palette: Int,
)

/**
 * Decides and assembles the mixes. Pure, so it can be tested: [plan] picks which songs to seed
 * radio stations from, then [build] turns the stations into mixes.
 */
object MixBuilder {

    data class Plan(
        /** One favourite artist per daily mix, with the songs of theirs to include and seed from. */
        val dailyArtists: List<Pair<String, List<Song>>>,
        val discoverySeeds: List<Song>,
    ) {
        val seeds: List<Song> get() = (dailyArtists.map { it.second.first() } + discoverySeeds).distinctBy { it.id }
    }

    private const val DAILY_MIXES = 3
    private const val MIX_LENGTH = 25

    /** [mostPlayed] is ordered by play count, most played first. */
    fun plan(mostPlayed: List<Song>, liked: List<Song>, day: LocalDate): Plan {
        // Score artists by how much they're played (earlier = more) and loved.
        val scores = mutableMapOf<String, Int>()
        val songsByArtist = mutableMapOf<String, MutableList<Song>>()
        mostPlayed.forEachIndexed { index, song ->
            val artist = primaryArtist(song.artist) ?: return@forEachIndexed
            scores[artist] = (scores[artist] ?: 0) + (mostPlayed.size - index) + 10
            songsByArtist.getOrPut(artist) { mutableListOf() }.addIfNew(song)
        }
        liked.forEach { song ->
            val artist = primaryArtist(song.artist) ?: return@forEach
            scores[artist] = (scores[artist] ?: 0) + 8
            songsByArtist.getOrPut(artist) { mutableListOf() }.addIfNew(song)
        }
        val dailyArtists = scores.entries.sortedByDescending { it.value }.take(DAILY_MIXES)
            .map { (artist, _) -> artist to songsByArtist.getValue(artist) }

        // Discovery seeds rotate through favourites so each day explores somewhere new.
        val pool = (mostPlayed.take(30) + liked).distinctBy { it.id }
        val discoverySeeds = pool.shuffled(Random(day.toEpochDay())).take(3)
        return Plan(dailyArtists, discoverySeeds)
    }

    fun build(
        plan: Plan,
        mostPlayed: List<Song>,
        liked: List<Song>,
        stations: Map<String, List<Song>>,
        day: LocalDate,
    ): List<Mix> {
        val random = Random(day.toEpochDay())
        val known = (mostPlayed + liked).map { it.id }.toSet()
        val mixes = mutableListOf<Mix>()

        plan.dailyArtists.forEachIndexed { index, (artist, own) ->
            val station = stations[own.first().id].orEmpty().filter { it.isSong() }
            if (station.isEmpty()) return@forEachIndexed
            // Your songs by the artist, spread through their station: every fourth track is yours.
            val mine = own.take(6).shuffled(random)
            val fresh = station.filter { s -> mine.none { it.id == s.id } }
            val songs = buildList {
                var m = 0
                var f = 0
                while (size < MIX_LENGTH && (m < mine.size || f < fresh.size)) {
                    if (size % 4 == 0 && m < mine.size) add(mine[m++]) else if (f < fresh.size) add(fresh[f++]) else add(mine[m++])
                }
            }.distinctBy { it.id }
            mixes += Mix("daily-${index + 1}", "Daily Mix ${index + 1}", artistsLine(songs, lead = artist), songs, palette = index)
        }

        val discovery = plan.discoverySeeds.flatMap { stations[it.id].orEmpty() }
            .filter { it.isSong() && it.id !in known }
            .distinctBy { it.id }
            .shuffled(random)
            .take(MIX_LENGTH)
        if (discovery.size >= 8) mixes += Mix("discovery", "Discovery Mix", "New songs picked for you", discovery, palette = 3)

        val favourites = (liked + mostPlayed.take(30)).distinctBy { it.id }.shuffled(random).take(50)
        if (favourites.size >= 5) mixes += Mix("favourites", "Favourites Mix", "Songs you love", favourites, palette = 5)

        val replay = mostPlayed.take(MIX_LENGTH)
        if (replay.size >= 5) mixes += Mix("replay", "Replay", "Your most played songs", replay, palette = 4)
        return mixes
    }

    /** "The Kooks, Bloc Party, The Strokes and more", with the mix's own artist ([lead]) first. */
    fun artistsLine(songs: List<Song>, lead: String? = null): String {
        val byCount = songs.mapNotNull { primaryArtist(it.artist) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
        val artists = if (lead != null && lead in byCount) listOf(lead) + (byCount - lead) else byCount
        return when {
            artists.isEmpty() -> ""
            artists.size <= 3 -> artists.joinToString(", ")
            else -> artists.take(3).joinToString(", ") + " and more"
        }
    }

    /** "Drake & Sexyy Red" -> "Drake". */
    fun primaryArtist(credits: String): String? =
        credits.split(Regex("""\s*(?:,|&| x | X |\bfeat\.?|\bft\.?)\s*""")).firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it != "Unknown Artist" }

    /** Leaves out long mixes and compilations. */
    private fun Song.isSong() = durationSec in 1..600 || durationSec <= 0

    private fun MutableList<Song>.addIfNew(song: Song) {
        if (none { it.id == song.id }) add(song)
    }
}

/** Builds the day's mixes from listening history and keeps them until tomorrow. */
class MixRepository(
    private val library: LibraryRepository,
    /** A radio station for a song id (YouTubeRepository.radio). */
    private val radio: suspend (String) -> List<Song>,
    dir: File,
) {
    @Serializable
    private data class Saved(val day: String, val complete: Boolean, val mixes: List<Mix>)

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(dir, "mixes.json")
    private val mutex = Mutex()
    private val _mixes = MutableStateFlow(read()?.mixes.orEmpty())
    val mixes: StateFlow<List<Mix>> = _mixes.asStateFlow()

    fun mix(id: String): Mix? = _mixes.value.firstOrNull { it.id == id }

    /** Rebuilds the mixes if they're from an earlier day, or couldn't all be made last time. */
    suspend fun refresh(day: LocalDate = LocalDate.now()) = mutex.withLock {
        val saved = read()
        if (saved != null && saved.day == day.toString() && saved.complete) return@withLock
        val mostPlayed = library.mostPlayed(100).first()
        val liked = library.likedSongs.first()
        if (mostPlayed.isEmpty() && liked.isEmpty()) return@withLock

        val plan = MixBuilder.plan(mostPlayed, liked, day)
        val stations = coroutineScope {
            plan.seeds.map { seed ->
                async(Dispatchers.IO) { seed.id to runCatching { radio(seed.id) }.getOrNull() }
            }.awaitAll()
        }
        val mixes = MixBuilder.build(plan, mostPlayed, liked, stations.associate { (id, songs) -> id to songs.orEmpty() }, day)
        _mixes.value = mixes
        write(Saved(day.toString(), complete = stations.all { it.second != null }, mixes = mixes))
    }

    private fun read(): Saved? = runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()

    private suspend fun write(saved: Saved) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Saved.serializer(), saved))
        }
    }
}
