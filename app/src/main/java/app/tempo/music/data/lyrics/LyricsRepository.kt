package app.tempo.music.data.lyrics

import app.tempo.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * One line of synced lyrics. [words] is filled only when the lyrics give each word its own time
 * (word-timed TTML or enhanced LRC); their texts joined together are exactly [text].
 * [background] is backing vocals, and [alignEnd] marks a duet's second singer.
 */
@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val background: List<LyricWord> = emptyList(),
    val alignEnd: Boolean = false,
)

sealed interface Lyrics {
    /**
     * Time-stamped lines. [durationSec] is the length of the recording the times belong to,
     * when known; [matchesRecording] means they were timed to the very video playing, so they
     * need no lining up. [source] names where they came from, for the credit.
     */
    data class Synced(
        val lines: List<LyricLine>,
        val durationSec: Double? = null,
        val source: String = LRCLIB,
        val matchesRecording: Boolean = false,
    ) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics

    companion object {
        const val LRCLIB = "LRCLIB"
        const val UNISON = "Unison"
        const val YOUTUBE = "YouTube captions"
    }
}

/**
 * Finds lyrics, best first:
 *
 * 1. Unison (unison.boidu.dev), Better Lyrics' crowdsourced database, for this exact YouTube
 *    video: timed to the recording playing, often word by word in Apple Music's TTML format.
 * 2. Unison for the song by title, artist and length, when it's timed word by word.
 * 3. The video's own lyric captions, written by a person (never automatic ones), which are
 *    timed to this exact video.
 * 4. LRCLIB (lrclib.net), the largest open database of synced lyrics, timed line by line.
 *
 * Results are cached on disk so lyrics also work offline for songs you've seen before.
 */
class LyricsRepository(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val userAgent: String,
    /** The video's caption tracks; empty if it has none or they can't be loaded. */
    private val captions: (videoId: String) -> List<CaptionTrack> = { emptyList() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = object : LinkedHashMap<String, Lyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) = size > 50
    }

    @Serializable
    private data class Candidate(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    @Serializable
    private data class Cached(
        /** Lines saved by versions before 1.9, as LRC. */
        val synced: String? = null,
        val lines: List<LyricLine>? = null,
        val plain: String? = null,
        val instrumental: Boolean = false,
        val durationSec: Double? = null,
        val source: String? = null,
        val matchesRecording: Boolean = false,
    )

    /** [durationSec] helps pick the right version when several exist; pass <= 0 if unknown. */
    suspend fun lyrics(song: Song, durationSec: Long): Lyrics = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[song.id] }?.let { return@withContext it }
        val (cached, stale) = readCache(song.id) ?: (null to false)
        if (cached != null && !stale) {
            synchronized(memory) { memory[song.id] = cached }
            return@withContext cached
        }
        // Saved by an older version, before the better sources: look again, but keep what we
        // have if that fails (offline, say).
        if (cached != null) {
            val fresh = runCatching { find(song, durationSec) }.getOrNull()?.takeIf { it != Lyrics.NotFound } ?: cached
            synchronized(memory) { memory[song.id] = fresh }
            if (fresh !== cached) writeCache(song.id, fresh)
            return@withContext fresh
        }

        val result = find(song, durationSec)
        synchronized(memory) { memory[song.id] = result }
        if (result != Lyrics.NotFound) writeCache(song.id, result)
        result
    }

    private fun find(song: Song, durationSec: Long): Lyrics {

        val title = cleanTitle(song.title)
        val artist = primaryArtist(song.artist)
        val result = unison(mapOf("v" to song.id))?.let { it.copy(matchesRecording = true) }
            ?: unison(unisonQuery(title, artist, durationSec))?.takeIf { synced -> synced.lines.any { it.words.isNotEmpty() } }
            ?: run {
                val candidates = search(mapOf("track_name" to title, "artist_name" to artist))
                    .ifEmpty { search(mapOf("q" to "$title $artist")) }
                val lrclib = choose(candidates, title, artist, durationSec)
                fromCaptions(song.id, lrclib) ?: lrclib
            }
        return result
    }

    // ---------------------------------------------------------------- Unison

    @Serializable
    private data class UnisonResponse(val success: Boolean = false, val data: UnisonLyrics? = null)

    @Serializable
    private data class UnisonLyrics(
        val videoId: String? = null,
        val duration: Double? = null,
        val format: String? = null,
        val syncType: String? = null,
        val lyrics: String? = null,
        val hidden: Boolean = false,
    )

    private fun unisonQuery(title: String, artist: String, durationSec: Long) = buildMap {
        put("song", title)
        put("artist", artist)
        if (durationSec > 0) put("duration", durationSec.toString())
    }

    /** Synced lyrics from Unison, or null if it has none (or can't be reached). */
    private fun unison(params: Map<String, String>): Lyrics.Synced? = runCatching {
        val url = "https://unison.boidu.dev/lyrics".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val data = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            json.decodeFromString<UnisonResponse>(response.body.string()).data
        } ?: return null
        if (data.hidden || data.syncType == "plain") return null
        val text = data.lyrics ?: return null
        val lines = when (data.format) {
            "ttml" -> Ttml.parse(text)
            "lrc" -> parseLrc(text)
            else -> return null
        }
        // Skip junk and test entries.
        if (lines.count { it.text.isNotBlank() } < 5) return null
        Lyrics.Synced(lines, data.duration, Lyrics.UNISON, matchesRecording = data.videoId == params["v"] && data.videoId != null)
    }.getOrNull()

    // ---------------------------------------------------------------- YouTube captions

    /**
     * The video's lyric captions, if it has some written by a person in the song's language.
     * When LRCLIB found lyrics, the captions must say mostly the same words (so translations
     * and unrelated subtitles are skipped).
     */
    private fun fromCaptions(videoId: String, lrclib: Lyrics): Lyrics.Synced? = runCatching {
        val tracks = captions(videoId).filter { !it.autoGenerated }
        if (tracks.isEmpty()) return null
        val reference = when (lrclib) {
            is Lyrics.Synced -> lrclib.lines.joinToString(" ") { it.text }
            is Lyrics.Plain -> lrclib.text
            else -> null
        }
        val candidates = tracks.mapNotNull { track ->
            val lines = Captions.parseJson3(fetchText(Captions.json3Url(track.url)) ?: return@mapNotNull null)
            if (lines.count { it.text.isNotBlank() } < 8) null else track to lines
        }
        val best = if (reference != null) {
            candidates.map { (track, lines) -> Triple(track, lines, Captions.overlap(lines, reference)) }
                .filter { it.third >= 0.5 }.maxByOrNull { it.third }?.let { it.first to it.second }
        } else {
            candidates.singleOrNull()
        } ?: return null
        Lyrics.Synced(best.second, source = Lyrics.YOUTUBE, matchesRecording = true)
    }.getOrNull()

    private fun fetchText(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            return if (response.isSuccessful) response.body.string() else null
        }
    }

    // ---------------------------------------------------------------- LRCLIB

    private fun search(params: Map<String, String>): List<Candidate> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) throw IOException("Lyrics service unavailable (HTTP ${response.code})")
            return json.decodeFromString<List<Candidate>>(response.body.string())
        }
    }

    private fun choose(candidates: List<Candidate>, title: String, artist: String, durationSec: Long): Lyrics {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)

        fun score(c: Candidate): Int {
            var s = 0
            val synced = c.syncedLyrics?.let(::parseLrc).orEmpty()
            when {
                synced.count { it.text.isNotBlank() } >= 5 -> s += 6
                (c.plainLyrics?.lines()?.count { it.isNotBlank() } ?: 0) >= 4 -> s += 3
                c.instrumental -> s += 1
                else -> return Int.MIN_VALUE // empty or junk entry
            }
            val name = normalize(c.trackName.orEmpty())
            if (name == wantedTitle) s += 3 else if (wantedTitle in name || name in wantedTitle) s += 1
            if (wantedArtist.isNotEmpty() && normalize(c.artistName.orEmpty()).contains(wantedArtist)) s += 2
            if (durationSec > 0 && c.duration != null) {
                val diff = abs(c.duration - durationSec)
                s += when {
                    diff <= 3 -> 4
                    diff <= 10 -> 2
                    diff <= 30 -> 0
                    else -> -4
                }
            }
            return s
        }

        val best = candidates.maxByOrNull(::score)?.takeIf { score(it) > Int.MIN_VALUE } ?: return Lyrics.NotFound
        val synced = best.syncedLyrics?.let(::parseLrc).orEmpty()
        return when {
            synced.count { it.text.isNotBlank() } >= 5 -> Lyrics.Synced(synced, best.duration)
            !best.plainLyrics.isNullOrBlank() -> Lyrics.Plain(best.plainLyrics.trim())
            best.instrumental -> Lyrics.Instrumental
            else -> Lyrics.NotFound
        }
    }

    // ---------------------------------------------------------------- disk cache

    private fun cacheFile(id: String) = File(cacheDir, "$id.json")

    /** The saved lyrics, and whether they were saved in the old format (before 1.9). */
    private fun readCache(id: String): Pair<Lyrics, Boolean>? = runCatching {
        val file = cacheFile(id)
        if (!file.exists()) return null
        val cached = json.decodeFromString<Cached>(file.readText())
        val stale = cached.source == null
        val lyrics = when {
            cached.lines != null -> Lyrics.Synced(cached.lines, cached.durationSec, cached.source ?: Lyrics.LRCLIB, cached.matchesRecording)
            cached.synced != null -> Lyrics.Synced(parseLrc(cached.synced), cached.durationSec)
            cached.plain != null -> Lyrics.Plain(cached.plain)
            cached.instrumental -> Lyrics.Instrumental
            else -> null
        } ?: return null
        lyrics to stale
    }.getOrNull()

    private fun writeCache(id: String, result: Lyrics) {
        runCatching {
            cacheDir.mkdirs()
            val cached = when (result) {
                is Lyrics.Synced -> Cached(
                    lines = result.lines,
                    durationSec = result.durationSec,
                    source = result.source,
                    matchesRecording = result.matchesRecording,
                )
                is Lyrics.Plain -> Cached(plain = result.text, source = Lyrics.LRCLIB)
                Lyrics.Instrumental -> Cached(instrumental = true, source = Lyrics.LRCLIB)
                Lyrics.NotFound -> return
            }
            cacheFile(id).writeText(json.encodeToString(Cached.serializer(), cached))
        }
    }

    companion object {
        private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

        /**
         * Parses LRC text ("[01:02.34] words") into lines sorted by time. Enhanced LRC word
         * times ("[01:02.34] <01:02.34> some <01:02.90> words <01:03.50>") become [LyricLine.words].
         */
        fun parseLrc(lrc: String): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val tags = timeTag.findAll(raw).toList()
                if (tags.isEmpty()) return@forEach
                val body = timeTag.replace(raw, "")
                val words = if (tags.size == 1) parseWords(body) else emptyList()
                val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else wordTag.replace(body, "").trim()
                tags.forEach { tag ->
                    lines += LyricLine(tagMs(tag), text, words)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        /** Word-timed text, or empty if the line has no word times. An end of -1 means "until the next line". */
        private fun parseWords(body: String): List<LyricWord> {
            val tags = wordTag.findAll(body).toList()
            if (tags.isEmpty()) return emptyList()
            val words = mutableListOf<LyricWord>()
            var spaceBefore = false
            tags.forEachIndexed { i, tag ->
                val segment = body.substring(tag.range.last + 1, tags.getOrNull(i + 1)?.range?.first ?: body.length)
                val word = segment.trim()
                if (word.isEmpty()) {
                    if (segment.isNotEmpty()) spaceBefore = true
                    return@forEachIndexed
                }
                if (words.isNotEmpty() && (spaceBefore || segment.first().isWhitespace())) {
                    words[words.lastIndex] = words.last().let { it.copy(text = it.text + " ") }
                }
                val end = tags.getOrNull(i + 1)?.let(::tagMs) ?: -1L
                words += LyricWord(tagMs(tag), end, word)
                spaceBefore = segment.last().isWhitespace()
            }
            return words
        }

        private fun tagMs(tag: MatchResult): Long {
            val (min, sec, frac) = tag.destructured
            val fracMs = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            return min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
        }

        private val noise = Regex("""\s*[(\[](?:feat\.?|ft\.?|with|from|official|lyrics?|audio|video|remaster|live|explicit)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

        fun cleanTitle(title: String): String =
            noise.replace(title, "")
                .replace(Regex("""\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { title.trim() }

        fun primaryArtist(artist: String): String =
            artist.split(Regex("""\s*(?:,|&| feat\.?| ft\.?| x | X | and )\s*""")).firstOrNull()?.trim().orEmpty()
                .ifBlank { artist.trim() }

        private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
