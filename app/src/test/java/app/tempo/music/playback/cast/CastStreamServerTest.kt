@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.cast

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.data.model.Song
import app.tempo.music.playback.MediaItems
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** Requests songs from the server the way a Cast receiver does, over real HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastStreamServerTest {

    /** A WebM file's signature followed by random "audio". */
    private val song = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Random(5).nextBytes(300_000)
    private val requested = mutableListOf<String>()
    private val factory = DataSource.Factory { RecordingSource() }

    /** Serves [song] and notes which URI was asked for. */
    private inner class RecordingSource(private val bytes: ByteArrayDataSource = ByteArrayDataSource(song)) : DataSource by bytes {
        override fun open(dataSpec: DataSpec): Long {
            synchronized(requested) { requested += dataSpec.uri.toString() }
            return bytes.open(dataSpec)
        }
    }
    private val server = CastStreamServer(factory)

    @After
    fun tearDown() = server.stop()

    private fun get(url: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        val body = if (code < 400) connection.inputStream.use { it.readBytes() } else ByteArray(0)
        connection.disconnect()
        return Triple(code, headers, body)
    }

    @Test
    fun servesTheWholeSong() {
        val (code, headers, body) = get(server.urlFor("127.0.0.1", "dQw4w9WgXcQ"))
        assertEquals(200, code)
        assertEquals("audio/webm", headers["Content-Type"])
        assertEquals(song.size.toString(), headers["Content-Length"])
        assertEquals("bytes", headers["Accept-Ranges"])
        assertArrayEquals(song, body)
        assertTrue(requested.all { it == MediaItems.uriFor("dQw4w9WgXcQ").toString() })
        assertEquals("audio/webm", server.knownMimeType("dQw4w9WgXcQ"))
    }

    @Test
    fun seeksWithRanges() {
        val url = server.urlFor("127.0.0.1", "dQw4w9WgXcQ")
        val (code, headers, body) = get(url, "bytes=1000-")
        assertEquals(206, code)
        assertEquals("bytes 1000-${song.size - 1}/${song.size}", headers["Content-Range"])
        assertArrayEquals(song.copyOfRange(1000, song.size), body)

        val (code2, headers2, body2) = get(url, "bytes=10-19")
        assertEquals(206, code2)
        assertEquals("bytes 10-19/${song.size}", headers2["Content-Range"])
        assertArrayEquals(song.copyOfRange(10, 20), body2)
    }

    @Test
    fun headHasNoBody() {
        val (code, headers, body) = get(server.urlFor("127.0.0.1", "dQw4w9WgXcQ"), method = "HEAD")
        assertEquals(200, code)
        assertEquals(song.size.toString(), headers["Content-Length"])
        assertEquals(0, body.size)
    }

    @Test
    fun refusesRequestsWithoutTheKey() {
        val url = server.urlFor("127.0.0.1", "dQw4w9WgXcQ")
        assertEquals(404, get(url.substringBefore("?k=") + "?k=guess").first)
        assertEquals(404, get(url.replace("/song/", "/etc/")).first)
        assertEquals(404, get(url.replace("dQw4w9WgXcQ", "..%2F..%2Fdata")).first)
    }

    @Test
    fun parsesRanges() {
        assertEquals(0L to null, CastStreamServer.parseRange("bytes=0-"))
        assertEquals(5L to 9L, CastStreamServer.parseRange("bytes=5-9"))
        assertNull(CastStreamServer.parseRange("bytes=9-5"))
        assertNull(CastStreamServer.parseRange("bytes=0-1,5-6"))
        assertNull(CastStreamServer.parseRange("items=0-"))
        assertNull(CastStreamServer.parseRange(null))
        assertEquals("audio/mp4", CastStreamServer.mimeTypeOf("\u0000\u0000\u0000 ftypM4A ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun convertsQueueItemsForTheTvAndBack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val converter = CastMediaItemConverter(context, server)
        val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "https://music.youtube.com/channel/x", "Whenever", "https://i.ytimg.com/a.jpg", 213)
        val item = MediaItems.from(song, autoplay = true)
        val queueItem = converter.toMediaQueueItem(item)
        val media = queueItem.media!!
        assertTrue(media.contentId, media.contentId.contains("/song/dQw4w9WgXcQ?k="))
        assertEquals("Never Gonna Give You Up", media.metadata!!.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
        assertEquals(213_000L, media.streamDuration)

        val back = converter.toMediaItem(queueItem)
        assertEquals(MediaItems.uriFor(song.id), back.localConfiguration!!.uri)
        assertEquals(MediaItems.toSong(item), MediaItems.toSong(back))
        assertTrue(MediaItems.isAutoplay(back))
    }
}
