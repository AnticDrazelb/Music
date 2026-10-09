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
 * (enhanced LRC); their texts joined together are exactly [text].
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList())

sealed interface Lyrics {
    /** Time-stamped lines, for karaoke-style highlighting. */
    /** [durationSec] is the length of the recording the times belong to, when known. */
    data class Synced(val lines: List<LyricLine>, val durationSec: Double? = null) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

/**
 * Lyrics from LRCLIB (lrclib.net), a free, open lyrics database with time-synced lyrics.
 * Results are cached on disk so lyrics also work offline for songs you've seen before.
 */
class LyricsRepository(
    private val client: OkHttpClient,
    private val cacheDir: File,
    private val userAgent: String,
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
        val synced: String? = null,
        val plain: String? = null,
        val instrumental: Boolean = false,
        val durationSec: Double? = null,
    )

    /** [durationSec] helps pick the right version when several exist; pass <= 0 if unknown. */
    suspend fun lyrics(song: Song, durationSec: Long): Lyrics = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[song.id] }?.let { return@withContext it }
        readCache(song.id)?.let { cached ->
            synchronized(memory) { memory[song.id] = cached }
            return@withContext cached
        }

        val title = cleanTitle(song.title)
        val artist = primaryArtist(song.artist)
        val candidates = search(mapOf("track_name" to title, "artist_name" to artist))
            .ifEmpty { search(mapOf("q" to "$title $artist")) }
        val result = choose(candidates, title, artist, durationSec)

        synchronized(memory) { memory[song.id] = result }
        if (result != Lyrics.NotFound) writeCache(song.id, result)
        result
    }

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

    private fun readCache(id: String): Lyrics? = runCatching {
        val file = cacheFile(id)
        if (!file.exists()) return null
        val cached = json.decodeFromString<Cached>(file.readText())
        when {
            cached.synced != null -> Lyrics.Synced(parseLrc(cached.synced), cached.durationSec)
            cached.plain != null -> Lyrics.Plain(cached.plain)
            cached.instrumental -> Lyrics.Instrumental
            else -> null
        }
    }.getOrNull()

    private fun writeCache(id: String, result: Lyrics) {
        runCatching {
            cacheDir.mkdirs()
            val cached = when (result) {
                is Lyrics.Synced -> Cached(
                    synced = result.lines.joinToString("\n", transform = ::formatLine),
                    durationSec = result.durationSec,
                )
                is Lyrics.Plain -> Cached(plain = result.text)
                Lyrics.Instrumental -> Cached(instrumental = true)
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

        private fun formatLine(line: LyricLine): String {
            if (line.words.isEmpty()) return "[${formatTag(line.timeMs)}]${line.text}"
            return buildString {
                append("[${formatTag(line.timeMs)}]")
                line.words.forEach { append("<${formatTag(it.startMs)}>${it.text}") }
                line.words.last().endMs.takeIf { it >= 0 }?.let { append("<${formatTag(it)}>") }
            }
        }

        private fun formatTag(ms: Long) = "%02d:%02d.%02d".format(ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10)

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
