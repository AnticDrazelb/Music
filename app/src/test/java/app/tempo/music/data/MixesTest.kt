package app.tempo.music.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.data.db.MusicDatabase
import app.tempo.music.data.mixes.MixBuilder
import app.tempo.music.data.mixes.MixRepository
import app.tempo.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixesTest {

    private val day = LocalDate.of(2026, 10, 4)
    private fun song(id: String, artist: String, seconds: Long = 200) = Song(id, "Song $id", artist, durationSec = seconds)
    private fun station(prefix: String, artist: String, n: Int = 30) = (1..n).map { song("$prefix$it", artist) }

    private val kooks = (1..5).map { song("k$it", "The Kooks") }
    private val bloc = (1..3).map { song("b$it", "Bloc Party") }
    private val strokes = (1..2).map { song("s$it", "The Strokes & Someone") }
    private val oasis = listOf(song("o1", "Oasis"))
    private val mostPlayed = kooks + bloc + strokes + oasis

    @Test
    fun dailyMixesFollowYourMostPlayedArtists() {
        val plan = MixBuilder.plan(mostPlayed, liked = oasis, day)
        assertEquals(listOf("The Kooks", "Bloc Party", "The Strokes"), plan.dailyArtists.map { it.first })
        // Each daily mix seeds a station from that artist's most played song.
        assertEquals(listOf("k1", "b1", "s1"), plan.dailyArtists.map { it.second.first().id })
        assertEquals(3, plan.discoverySeeds.size)
        assertEquals(plan.discoverySeeds, MixBuilder.plan(mostPlayed, oasis, day).discoverySeeds) // stable through the day
    }

    @Test
    fun buildsEveryKindOfMix() {
        val plan = MixBuilder.plan(mostPlayed, oasis, day)
        val stations = plan.seeds.associate { it.id to station("new-${it.id}-", "Artist ${it.id}") + song("long-${it.id}", "Mixer", 3600) }
        val mixes = MixBuilder.build(plan, mostPlayed, oasis, stations, day)
        assertEquals(listOf("daily-1", "daily-2", "daily-3", "discovery", "favourites", "replay"), mixes.map { it.id })

        val daily = mixes[0]
        assertEquals("Daily Mix 1", daily.title)
        assertEquals(25, daily.songs.size)
        assertEquals(daily.songs.size, daily.songs.distinctBy { it.id }.size)
        // All five of your Kooks songs, one every fourth track, among the station's new finds.
        val mine = daily.songs.withIndex().filter { it.value.artist == "The Kooks" }.map { it.index }
        assertEquals(listOf(0, 4, 8, 12, 16), mine)
        assertTrue(daily.songs.none { it.id.startsWith("long") })
        assertTrue(daily.subtitle, daily.subtitle.startsWith("The Kooks"))

        val discovery = mixes.first { it.id == "discovery" }
        val known = mostPlayed.map { it.id }.toSet()
        assertTrue(discovery.songs.none { it.id in known })

        assertEquals(mostPlayed.take(25).map { it.id }, mixes.first { it.id == "replay" }.songs.map { it.id })
        assertEquals((oasis + mostPlayed).distinctBy { it.id }.map { it.id }.toSet(), mixes.first { it.id == "favourites" }.songs.map { it.id }.toSet())
    }

    @Test
    fun noStationsMeansOnlyTheMixesMadeFromYourOwnSongs() {
        val plan = MixBuilder.plan(mostPlayed, oasis, day)
        val mixes = MixBuilder.build(plan, mostPlayed, oasis, emptyMap(), day)
        assertEquals(listOf("favourites", "replay"), mixes.map { it.id })
    }

    @Test
    fun artistsLine() {
        assertEquals("The Kooks", MixBuilder.artistsLine(kooks))
        assertEquals("The Kooks, Bloc Party, The Strokes and more", MixBuilder.artistsLine(kooks + bloc + strokes + oasis))
        assertEquals("Bloc Party, The Kooks", MixBuilder.artistsLine(kooks + bloc, lead = "Bloc Party"))
        assertEquals("Drake", MixBuilder.primaryArtist("Drake & Sexyy Red"))
    }

    @Test
    fun mixesAreMadeOnceADayAndRetriedIfIncomplete() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        val library = LibraryRepository(db)
        mostPlayed.forEach { library.markPlayed(it) }
        repeat(3) { library.markPlayed(kooks[0]) }
        val calls = mutableListOf<String>()
        var failing = true
        val dir = File(context.filesDir, "mix-test").apply { deleteRecursively() }
        val repo = MixRepository(library, { id ->
            calls += id
            if (failing) error("offline") else station("new-$id-", "Artist $id")
        }, dir)

        repo.refresh(day)
        assertEquals(listOf("favourites", "replay"), repo.mixes.value.map { it.id }) // stations failed
        val firstCalls = calls.size
        failing = false
        repo.refresh(day) // incomplete, so it tries again
        assertTrue(repo.mixes.value.any { it.id == "daily-1" })
        val afterRetry = calls.size
        repo.refresh(day) // complete for today: nothing fetched
        assertEquals(afterRetry, calls.size)
        assertTrue(afterRetry > firstCalls)

        // A fresh repository (app restart) reads today's mixes back without fetching.
        val reopened = MixRepository(library, { error("should not fetch") }, dir)
        assertEquals(repo.mixes.value, reopened.mixes.value)
        reopened.refresh(day)
        repo.refresh(day.plusDays(1)) // tomorrow: rebuilt
        assertTrue(calls.size > afterRetry)
        db.close()
    }
}
