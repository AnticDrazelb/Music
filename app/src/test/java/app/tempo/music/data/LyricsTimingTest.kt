package app.tempo.music.data

import app.tempo.music.data.lyrics.LyricEntry
import app.tempo.music.data.lyrics.LyricLine
import app.tempo.music.data.lyrics.LyricWord
import app.tempo.music.data.lyrics.LyricsRepository
import app.tempo.music.data.lyrics.LyricsTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingTest {

    @Test
    fun countsSyllables() {
        mapOf(
            "the" to 1, "time" to 1, "love," to 1, "little" to 2, "free" to 1, "you" to 1,
            "beautiful" to 3, "yesterday" to 3, "corazón" to 3, "любовь" to 2, "♪" to 1, "愛してる" to 4,
        ).forEach { (word, expected) -> assertEquals(word, expected, LyricsTiming.syllables(word)) }
    }

    @Test
    fun spreadsWordsOverTheLineBySyllables() {
        val words = LyricsTiming.estimateWords("I love you, beautiful", 10_000, 2_000)
        assertEquals("I love you, beautiful", words.joinToString("") { it.text })
        assertEquals(listOf("I ", "love ", "you, ", "beautiful"), words.map { it.text })
        assertEquals(10_000L, words.first().startMs)
        assertEquals(12_000L, words.last().endMs)
        // Three syllables take three times as long as one.
        val lengths = words.map { it.endMs - it.startMs }
        assertEquals(lengths[0] * 3.0, lengths[3].toDouble(), 2.0)
        words.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    @Test
    fun doesNotStretchALineOverALongPause() {
        val words = LyricsTiming.estimateWords("Hey there", 0, 20_000)
        assertEquals(2 * LyricsTiming.MAX_MS_PER_SYLLABLE, words.last().endMs)
    }

    @Test
    fun splitsCjkIntoCharacters() {
        val words = LyricsTiming.estimateWords("君の名は", 0, 2_000)
        assertEquals(listOf("君", "の", "名", "は"), words.map { it.text })
    }

    @Test
    fun splitsMixedScripts() {
        assertEquals(listOf("Hello ", "世", "界 ", "again"), LyricsTiming.tokens("Hello 世界 again"))
        assertEquals(listOf("사랑해 ", "baby"), LyricsTiming.tokens("사랑해 baby"))
        assertEquals(emptyList<String>(), LyricsTiming.tokens("   "))
    }

    @Test
    fun addsInterludeDotsForIntroBreaksAndBlankLines() {
        val lines = LyricsRepository.parseLrc(
            """
            [00:00.00]
            [00:12.00]First line here
            [00:14.00]Second line
            [00:16.00]
            [00:30.00]After the solo
            [00:32.00]Short
            [00:33.00]
            [00:34.00]Last one
            """.trimIndent(),
        )
        val entries = LyricsTiming.entries(lines, 40_000)
        assertEquals(
            listOf("…", "First line here", "Second line", "…", "After the solo", "Short", "Last one"),
            entries.map { if (it is LyricEntry.Line) it.text else "…" },
        )
        assertEquals(0L to 12_000L, entries[0].startMs to entries[0].endMs)
        assertEquals(16_000L to 30_000L, entries[3].startMs to entries[3].endMs)
    }

    @Test
    fun addsInterludeDotsForLongGapsWithoutBlankLines() {
        val lines = listOf(LyricLine(1_000, "Go"), LyricLine(20_000, "Back again"))
        val entries = LyricsTiming.entries(lines, 25_000)
        assertTrue(entries[1] is LyricEntry.Interlude)
        assertEquals(entries[0].endMs, entries[1].startMs)
        assertEquals(20_000L, entries[1].endMs)
    }

    @Test
    fun usesExactWordTimesWhenGiven() {
        val words = listOf(LyricWord(5_000, 5_400, "Hold "), LyricWord(5_400, -1, "on"))
        val entry = LyricsTiming.entries(listOf(LyricLine(5_000, "Hold on", words), LyricLine(9_000, "Next")), 12_000)
            .filterIsInstance<LyricEntry.Line>().first()
        assertTrue(entry.exact)
        assertEquals(5_400L + LyricsTiming.MAX_MS_PER_SYLLABLE, entry.words.last().endMs)
        assertFalse((LyricsTiming.entries(listOf(LyricLine(0, "Plain")), 3_000)[0] as LyricEntry.Line).exact)
    }

    @Test
    fun tracksHowMuchOfTheLineIsSung() {
        val line = LyricEntry.Line(
            "ab cd",
            listOf(LyricWord(1_000, 2_000, "ab "), LyricWord(2_000, 3_000, "cd")),
            1_000, 3_000, exact = true,
        )
        assertEquals(0f, LyricsTiming.sungCharacters(line, 500), 0f)
        assertEquals(1f, LyricsTiming.sungCharacters(line, 1_500), 0.001f)
        assertEquals(3f, LyricsTiming.sungCharacters(line, 2_000), 0.001f)
        assertEquals(4f, LyricsTiming.sungCharacters(line, 2_500), 0.001f)
        assertEquals(5f, LyricsTiming.sungCharacters(line, 9_000), 0f)
    }
}
