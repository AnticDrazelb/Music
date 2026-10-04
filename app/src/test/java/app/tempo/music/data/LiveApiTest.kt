package app.tempo.music.data

import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.download.AudioDownloader
import app.tempo.music.data.download.DownloadCancelledException
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.SearchFilter
import app.tempo.music.data.model.Song
import app.tempo.music.data.youtube.NewPipeDownloader
import app.tempo.music.data.youtube.StreamResolver
import app.tempo.music.data.youtube.YouTubeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Hits the real YouTube endpoints. Skipped unless run with -Dlive=true, because results
 * depend on network access and on YouTube not rate-limiting the machine running them.
 */
class LiveApiTest {

    companion object {
        private val client = OkHttpClient()
        private val youtube = YouTubeRepository(client)
        private val charts = ChartsRepository(client)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue(System.getProperty("live") == "true")
            youtube.init("US")
        }
    }

    @Test
    fun topSongsCharts() = runBlocking {
        for (country in listOf("global", "us", "gb")) {
            val chart = charts.chart(ChartKind.TOP_SONGS, country)
            println("${chart.countryName} ${chart.periodLabel}: ${chart.entries.size} entries")
            chart.entries.take(3).forEach { println("  #${it.rank} (${it.previousRank}) ${it.song.title} — ${it.song.artist} [${it.song.id}] ${it.song.thumbnailUrl}") }
            assertTrue(chart.entries.size >= 90)
            assertEquals(1, chart.entries.first().rank)
            assertTrue(chart.entries.all { it.song.id.length == 11 })
        }
    }

    /** Titles and artists as an Apple Music listener expects them, not as YouTube uploads name them. */
    @Test
    fun playlistsAndRadioHaveCleanTitles() = runBlocking {
        val noise = Regex("""(?i)\bofficial\b|\b4k\b|\bhd\b|\blyrics?\b|visuali[sz]er""")
        fun check(label: String, songs: List<app.tempo.music.data.model.Song>) {
            songs.take(8).forEach { println("  $label: ${it.title} — ${it.artist}") }
            assertTrue("$label: ${songs.size} songs", songs.size >= 10)
            assertTrue(label, songs.none { it.artist == "Unknown Artist" })
            val noisy = songs.filter { noise.containsMatchIn(it.title) || " - " in it.title }
            assertTrue("$label: $noisy", noisy.size <= songs.size / 10)
        }
        // A YouTube Music editorial playlist made of music videos.
        check("The Hit List", youtube.collection("https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs").songs)
        // A user playlist and a radio station, both full of "Artist - Song (Official Video)" uploads.
        check("user playlist", youtube.collection("https://www.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj").songs)
        check("radio", youtube.radio("dQw4w9WgXcQ"))
        // An album playlist (OLAK), now read through YouTube Music too.
        check("album playlist", youtube.collection("https://music.youtube.com/playlist?list=OLAK5uy_kTAuc2ZOu5ihiGDql2Ax8X7zSYEe_z8ns").songs)
    }

    @Test
    fun artistChart() = runBlocking {
        val artists = charts.artists("us")
        println("artists=${artists.entries.size} #1 ${artists.entries.first().artist}")
        assertTrue(artists.entries.size >= 50)
    }

    @Test
    fun searchAndCollections() = runBlocking {
        for (filter in SearchFilter.entries) {
            val page = youtube.search("taylor swift", filter)
            println("$filter -> ${page.items.size} items, next=${page.nextPage != null}: ${page.items.take(2)}")
            // Albums need YouTube Music's catalogue, which isn't served to every IP/region.
            if (filter != SearchFilter.ALBUMS) assertTrue("$filter returned nothing", page.items.isNotEmpty())
        }
        val first = youtube.search("taylor swift", SearchFilter.PLAYLISTS)
        val more = youtube.search("taylor swift", SearchFilter.PLAYLISTS, first.nextPage)
        assertTrue(more.items.isNotEmpty())

        val suggestions = youtube.suggestions("tayl")
        println("suggestions: $suggestions")
        assertTrue(suggestions.isNotEmpty())

        val playlist = youtube.collection("https://music.youtube.com/playlist?list=PLie3YXEeyQbbkPhPHiiVXFieXzm74szSG")
        println("playlist ${playlist.ref} songs=${playlist.songs.size} first=${playlist.songs.firstOrNull()}")
        assertTrue(playlist.songs.isNotEmpty())
    }

    @Test
    fun radioArtistAndStream() = runBlocking {
        val radio = youtube.radio("dQw4w9WgXcQ")
        println("radio ${radio.size}: ${radio.take(3).map(Song::title)}")
        assertTrue(radio.size >= 10)

        val artist = youtube.artist("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw")
        println("artist ${artist.ref} top=${artist.topSongs.size} sections=${artist.sections.map { it.title + ":" + it.items.size }}")
        assertTrue(artist.topSongs.isNotEmpty())

        val resolver = StreamResolver(youtube) { true }
        val resolved = resolver.resolve("dQw4w9WgXcQ")
        println("resolved ${resolved.mimeType} expires=${resolved.expiresAtMs}")

        // Same request shape the player uses: POST "x\0", rn parameter, Range header.
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(resolved.url + "&rn=1")
            .post(byteArrayOf(0x78, 0).toRequestBody())
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Range", "bytes=0-")
            .build()
        client.newCall(request).execute().use { response ->
            val total = response.body.byteStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var sum = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    sum += n
                }
                sum
            }
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            println("downloaded ${response.code} $total bytes in ${"%.1f".format(seconds)}s")
            assertTrue(response.code in 200..206)
            assertTrue(total > 1_000_000)
        }
    }

    @Test
    fun downloadsAudioInChunks() {
        val dir = kotlin.io.path.createTempDirectory("tempo-dl").toFile()
        val resolver = StreamResolver(youtube) { true }
        // Small chunks so the download needs several ranged requests.
        val downloader = AudioDownloader(client, resolver, chunkSize = 512L * 1024)
        var lastProgress = 0L
        val started = System.currentTimeMillis()
        val file = downloader.download("dQw4w9WgXcQ", dir, onProgress = { done, total ->
            assertTrue(total <= 0 || done <= total)
            lastProgress = done
        })
        val header = file.inputStream().use { it.readNBytes(12) }
        val isWebm = header.take(4) == listOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).map { it.toByte() }
        val isMp4 = String(header.copyOfRange(4, 8)) == "ftyp"
        println("downloaded ${file.name} ${file.length()} bytes in ${System.currentTimeMillis() - started}ms webm=$isWebm mp4=$isMp4")
        assertTrue(file.length() > 1_000_000)
        assertEquals(file.length(), lastProgress)
        assertTrue(isWebm || isMp4)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })

        // Cancelling mid-way throws and leaves no partial file behind.
        var bytes = 0L
        val result = runCatching {
            downloader.download("dQw4w9WgXcQ", File(dir, "cancel"), isCancelled = { bytes > 600_000 }, onProgress = { done, _ -> bytes = done })
        }
        assertTrue(result.exceptionOrNull() is DownloadCancelledException)
        assertTrue(File(dir, "cancel").listFiles().orEmpty().isEmpty())
        dir.deleteRecursively()
    }
}
