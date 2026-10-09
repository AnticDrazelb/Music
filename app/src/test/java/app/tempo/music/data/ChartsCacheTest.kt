package app.tempo.music.data

import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.model.ChartKind
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** YouTube Charts refusing requests (HTTP 429) shouldn't leave the charts empty. */
class ChartsCacheTest {

    private val chartJson = """
        {"contents":{"sectionListRenderer":{"contents":[{"musicAnalyticsSectionRenderer":{"content":
        {"trackTypes":[{"trackViews":[
          {"name":"Song One","encryptedVideoId":"aaaaaaaaaaa","artists":[{"name":"Singer"}],"chartEntryMetadata":{"currentPosition":1}},
          {"name":"Song Two","encryptedVideoId":"bbbbbbbbbbb","artists":[{"name":"Band"}],"chartEntryMetadata":{"currentPosition":2}}
        ]}]}}}]}}}
    """.trimIndent()

    /** Answers with [codes] in turn (200 carries the chart), counting requests. */
    private fun client(requests: AtomicInteger, vararg codes: Int) = OkHttpClient.Builder().addInterceptor { chain ->
        val code = codes[minOf(requests.getAndIncrement(), codes.lastIndex)]
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .header("Retry-After", "0")
            .body((if (code == 200) chartJson else "").toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    @Test
    fun retriesWhenBusy() = runBlocking<Unit> {
        val requests = AtomicInteger()
        val chart = ChartsRepository(client(requests, 429, 429, 200)).chart(ChartKind.TOP_SONGS, "gb")
        assertEquals(listOf("Song One", "Song Two"), chart.entries.map { it.song.title })
        assertEquals(3, requests.get())
    }

    @Test
    fun showsTheSavedChartWhenYouTubeRefuses() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("charts").toFile()
        ChartsRepository(client(AtomicInteger(), 200), dir).chart(ChartKind.TOP_SONGS, "gb")
        // Make the saved copy old enough to be refreshed.
        dir.listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - 24 * 3_600_000L) }

        val requests = AtomicInteger()
        val chart = ChartsRepository(client(requests, 429), dir).chart(ChartKind.TOP_SONGS, "gb")
        assertEquals(2, chart.entries.size)
        assertTrue(requests.get() > 0)
        dir.deleteRecursively()
    }

    @Test
    fun usesARecentSavedChartWithoutAsking() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("charts").toFile()
        ChartsRepository(client(AtomicInteger(), 200), dir).chart(ChartKind.TOP_SONGS, "gb")
        val requests = AtomicInteger()
        ChartsRepository(client(requests, 500), dir).chart(ChartKind.TOP_SONGS, "gb")
        assertEquals(0, requests.get())
        dir.deleteRecursively()
    }

    @Test
    fun explainsWhenThereIsNothingSaved() = runBlocking<Unit> {
        val error = runCatching { ChartsRepository(client(AtomicInteger(), 429)).chart(ChartKind.TOP_SONGS, "gb") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("YouTube Charts is busy right now. Try again in a minute.", error!!.message)
    }
}
