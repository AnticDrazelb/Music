package app.tempo.music.data.lyrics

/** One word (or syllable) and when it's sung. [text] includes the space after it, if any. */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/** What the lyrics view shows: sung lines, and the dots that count through instrumental breaks. */
sealed interface LyricEntry {
    val startMs: Long
    val endMs: Long

    /**
     * A line with a time for every word. [exact] is true when the times came with the lyrics
     * and false when they were estimated from the line's start and length.
     */
    data class Line(
        val text: String,
        val words: List<LyricWord>,
        override val startMs: Long,
        override val endMs: Long,
        val exact: Boolean,
    ) : LyricEntry

    data class Interlude(override val startMs: Long, override val endMs: Long) : LyricEntry
}

/**
 * Word timing for karaoke. Most synced lyrics only time each line, so the words of a line are
 * spread over it by how long each one takes to sing (its syllables), the way a singer would
 * pace them. A line is never stretched over a long pause: once the words would be slower than
 * [MAX_MS_PER_SYLLABLE], the line ends early and the rest is silence.
 */
object LyricsTiming {
    /** Breaks at least this long show Apple Music's three dots. */
    const val MIN_INTERLUDE_MS = 4_000L
    const val MAX_MS_PER_SYLLABLE = 450L
    private const val MIN_LINE_MS = 600L

    /** [songEndMs] closes the last line; pass 0 or less if unknown. */
    fun entries(lines: List<LyricLine>, songEndMs: Long): List<LyricEntry> {
        if (lines.isEmpty()) return emptyList()
        val songEnd = if (songEndMs > lines.last().timeMs) songEndMs else lines.last().timeMs + 10_000
        val entries = mutableListOf<LyricEntry>()
        val first = lines.first { it.text.isNotBlank() || it === lines.last() }
        if (first.timeMs >= MIN_INTERLUDE_MS) entries += LyricEntry.Interlude(0, first.timeMs)

        lines.forEachIndexed { i, line ->
            val next = lines.getOrNull(i + 1)?.timeMs ?: songEnd
            if (line.text.isBlank()) {
                val previous = entries.lastOrNull()
                if (next - line.timeMs >= MIN_INTERLUDE_MS && previous !is LyricEntry.Interlude) {
                    entries += LyricEntry.Interlude(line.timeMs, next)
                }
                return@forEachIndexed
            }
            val exact = line.words.isNotEmpty()
            val words = if (exact) closeWords(line.words, next) else estimateWords(line.text, line.timeMs, next - line.timeMs)
            val end = words.last().endMs
            entries += LyricEntry.Line(line.text, words, line.timeMs, end, exact)

            val following = lines.getOrNull(i + 1)
            if (following != null && following.text.isNotBlank() && following.timeMs - end >= MIN_INTERLUDE_MS + 2_000) {
                entries += LyricEntry.Interlude(end, following.timeMs)
            }
        }
        return entries
    }

    /** Gives a word with no end time (-1) one that's sensible for its length. */
    private fun closeWords(words: List<LyricWord>, nextLineMs: Long): List<LyricWord> = words.mapIndexed { i, w ->
        if (w.endMs > w.startMs) return@mapIndexed w
        val limit = words.getOrNull(i + 1)?.startMs ?: nextLineMs
        val natural = w.startMs + syllables(w.text) * MAX_MS_PER_SYLLABLE
        w.copy(endMs = minOf(limit, natural).coerceAtLeast(w.startMs + 1))
    }

    /** Japanese and Chinese are written without spaces, so each character is sung on its own. */
    private fun isCharacterWord(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /**
     * Splits a line into words (with the spaces after them) and CJK characters. Written by hand
     * because Android's regex engine doesn't accept Java's \p{IsHan}-style script classes.
     */
    internal fun tokens(text: String): List<String> {
        val starts = mutableListOf<Int>()
        var i = 0
        var inWord = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isCharacterWord(cp) -> {
                    starts += i
                    inWord = false
                }
                Character.isWhitespace(cp) -> inWord = false
                !inWord -> {
                    starts += i
                    inWord = true
                }
            }
            i += Character.charCount(cp)
        }
        if (starts.isEmpty()) return emptyList()
        starts[0] = 0
        return starts.mapIndexed { n, start -> text.substring(start, starts.getOrNull(n + 1) ?: text.length) }
    }

    /** Spreads [text] (one line) over up to [availableMs] from [startMs]. */
    fun estimateWords(text: String, startMs: Long, availableMs: Long): List<LyricWord> {
        val pieces = tokens(text)
        if (pieces.isEmpty()) return listOf(LyricWord(startMs, startMs + availableMs.coerceAtLeast(1), text))
        val weights = pieces.map { syllables(it).toLong() }
        val total = weights.sum()
        val duration = minOf(availableMs, maxOf(MIN_LINE_MS, total * MAX_MS_PER_SYLLABLE)).coerceAtLeast(1)

        var elapsed = 0L
        return pieces.mapIndexed { i, piece ->
            val start = startMs + duration * elapsed / total
            elapsed += weights[i]
            LyricWord(start, startMs + duration * elapsed / total, piece)
        }
    }

    private val vowels = Regex("""[aeiouyàáâãäåæèéêëìíîïòóôõöøœùúûüýÿаеёиоуыэюяіїєαεηιουω]+""")

    /** Roughly how many syllables it takes to sing [word]; at least 1. */
    fun syllables(word: String): Int {
        val w = word.lowercase().trim()
        // Each Chinese or Japanese character, and each Korean syllable block, is one syllable.
        w.codePoints().filter { isCharacterWord(it) || Character.UnicodeScript.of(it) == Character.UnicodeScript.HANGUL }
            .count().toInt().takeIf { it > 0 }?.let { return it }
        val letters = w.count { it.isLetter() }
        if (letters == 0) return 1
        var count = vowels.findAll(w).count()
        if (count == 0) return maxOf(1, letters / 3) // a script without listed vowels
        // A silent final e: "time", "love", but not "the" or "little".
        if (count > 1 && w.trimEnd { !it.isLetter() }.let { it.endsWith("e") && !it.endsWith("le") && !it.endsWith("ee") }) count--
        return count
    }

    /**
     * How many characters of [line] have been sung at [positionMs], including a fraction of the
     * word being sung, for drawing the highlight across it.
     */
    fun sungCharacters(line: LyricEntry.Line, positionMs: Long): Float {
        var offset = 0
        for (w in line.words) {
            if (positionMs < w.startMs) return offset.toFloat()
            val core = w.text.trimEnd().length
            if (positionMs < w.endMs) {
                return offset + core * (positionMs - w.startMs).toFloat() / (w.endMs - w.startMs)
            }
            offset += w.text.length
        }
        return offset.toFloat()
    }
}
