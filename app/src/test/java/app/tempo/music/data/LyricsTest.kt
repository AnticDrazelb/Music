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
    fun parsesWordTimes() {
        val lines = LyricsRepository.parseLrc(
            """
            [00:10.00]<00:10.00>Hello <00:10.50>beau<00:10.80>ti<00:11.00>ful <00:11.60>world<00:12.40>
            [00:13.00] <00:13.00> Spaced <00:13.40> out
            [00:15.00]Plain line
            """.trimIndent(),
        )
        val (first, second, plain) = lines
        assertEquals("Hello beautiful world", first.text)
        assertEquals(listOf("Hello ", "beau", "ti", "ful ", "world"), first.words.map { it.text })
        assertEquals(listOf(10_000L, 10_500L, 10_800L, 11_000L, 11_600L), first.words.map { it.startMs })
        assertEquals(12_400L, first.words.last().endMs)
        assertEquals("Spaced out", second.text)
        assertEquals(-1L, second.words.last().endMs)
        assertEquals("Plain line", plain.text)
        assertTrue(plain.words.isEmpty())
    }

    @Test
    fun wordTimesSurviveTheDiskCache() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("lyrics").toFile()
        val lrc = (1..6).joinToString("\\n") { "[00:0$it.00]<00:0$it.00>Line <00:0$it.50>$it<00:0$it.90>" }
        val body = """[{"trackName":"Song","artistName":"Singer","duration":200,"syncedLyrics":"$lrc"}]"""
        val online = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okhttp3.ResponseBody.create(null, body))
                .build()
        }.build()
        val song = Song("abc", "Song", "Singer")
        val fetched = LyricsRepository(online, dir, "Tempo/test").lyrics(song, 200) as Lyrics.Synced

        val offline = OkHttpClient.Builder().addInterceptor { error("network used") }.build()
        val cached = LyricsRepository(offline, dir, "Tempo/test").lyrics(song, 200) as Lyrics.Synced
        assertEquals(fetched.lines, cached.lines)
        assertEquals(listOf("Line ", "3"), cached.lines[2].words.map { it.text })
        assertEquals(3_900L, cached.lines[2].words.last().endMs)
        dir.deleteRecursively()
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
