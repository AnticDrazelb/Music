package app.tempo.music.data

import app.tempo.music.data.lyrics.Lyrics
import app.tempo.music.data.lyrics.LyricsRepository
import app.tempo.music.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LyricsTest {

    @Test
    fun parsesLrc() {
        val lines = LyricsRepository.parseLrc(
            """
            [ar:Someone]
            [00:12.30]First line
            [00:15.5]Second line
            [01:02.123][01:30.00]Chorus
            [01:40.00]
            """.trimIndent(),
        )
        assertEquals(listOf(12_300L, 15_500L, 62_123L, 90_000L, 100_000L), lines.map { it.timeMs })
        assertEquals(listOf("First line", "Second line", "Chorus", "Chorus", ""), lines.map { it.text })
    }

    @Test
    fun cleansTitlesAndArtists() {
        assertEquals("Song", LyricsRepository.cleanTitle("Song (feat. Someone)"))
        assertEquals("Here Comes The Sun", LyricsRepository.cleanTitle("Here Comes The Sun - Remastered 2009"))
        assertEquals("Shakira", LyricsRepository.primaryArtist("Shakira, Burna Boy"))
        assertEquals("KAROL G", LyricsRepository.primaryArtist("KAROL G & Judeline"))
    }

    @Test
    fun fetchesLiveLyrics() = runBlocking<Unit> {
        assumeTrue(System.getProperty("live") == "true")
        val dir = kotlin.io.path.createTempDirectory("lyrics").toFile()
        val repo = LyricsRepository(OkHttpClient(), dir, "Tempo/test")

        // LRCLIB's exact match for this song is a junk "probe" entry; ranking must skip it.
        val rick = repo.lyrics(Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"), 213)
        println("rick -> ${rick.javaClass.simpleName} ${(rick as? Lyrics.Synced)?.lines?.take(3)}")
        assertTrue(rick is Lyrics.Synced && rick.lines.size > 20)

        val chart = repo.lyrics(Song("xukbqwRuN5w", "Choosin' Texas", "Ella Langley"), -1)
        println("chart -> ${chart.javaClass.simpleName} ${(chart as? Lyrics.Synced)?.lines?.take(2)}")
        assertTrue(chart is Lyrics.Synced)

        val none = repo.lyrics(Song("zzzzzzzzzzz", "Qwzx Plorb Vrrnt", "Nobody Atall"), -1)
        assertEquals(Lyrics.NotFound, none)

        // Second lookup comes from the disk cache (works offline).
        val offline = LyricsRepository(OkHttpClient.Builder().addInterceptor { error("network used") }.build(), dir, "Tempo/test")
        assertTrue(offline.lyrics(Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley"), 213) is Lyrics.Synced)
        dir.deleteRecursively()
    }
}
