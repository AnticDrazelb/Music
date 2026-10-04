package app.tempo.music.ui

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.MainActivity
import app.tempo.music.TempoApp
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.Song
import app.tempo.music.playback.PlaybackService
import app.tempo.music.playback.eq.EqSettings
import app.tempo.music.ui.components.ArtworkInterceptor
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.imageDecoderEnabled
import coil3.request.allowHardware
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real app (live chart/search data) to PNGs under app/screenshots/ for visual
 * review. Run with: ./gradlew :app:testDebugUnitTest -Dscreenshots=true --tests '*ScreenshotTest*'
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
open class ScreenshotTest {

    /** Binds the MediaController to a real PlaybackService before the activity starts. */
    @get:Rule(order = 0)
    val serviceRule = object : ExternalResource() {
        private var controller: org.robolectric.android.controller.ServiceController<PlaybackService>? = null

        override fun after() {
            controller?.destroy()
        }

        override fun before() {
            val app = ApplicationProvider.getApplicationContext<TempoApp>()
            val service = Robolectric.buildService(PlaybackService::class.java).create().also { controller = it }.get()
            val intent = Intent(MediaSessionService.SERVICE_INTERFACE)
                .setClassName(app.packageName, PlaybackService::class.java.name)
            shadowOf(app).setComponentNameAndServiceForBindService(
                ComponentName(app, PlaybackService::class.java),
                service.onBind(intent),
            )
        }
    }

    @Suppress("DEPRECATION")
    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    open val suffix = ""

    companion object {
        @OptIn(DelicateCoilApi::class)
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue(System.getProperty("screenshots") == "true")
            SingletonImageLoader.setUnsafe { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(ArtworkInterceptor())
                        add(OkHttpNetworkFetcherFactory())
                    }
                    .allowHardware(false)
                    .imageDecoderEnabled(false) // Robolectric has no ImageDecoder
                    .build()
            }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Gives Coil a moment to fetch and decode artwork. */
    private fun settleImages(ms: Long = 4000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(200)
            compose.waitForIdle()
        }
    }

    private fun snap(name: String) {
        compose.onRoot().captureRoboImage("screenshots/$name$suffix.png")
    }

    private fun clickText(text: String) {
        compose.onAllNodesWithText(text, substring = false).onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun home() {
        waitForText("Top Songs:")
        settleImages()
        snap("01_home")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(6)
        settleImages(2500)
        snap("02_home_scrolled")
    }

    @Test
    fun browseAndChart() {
        clickText("Browse")
        settleImages(1500)
        snap("03_browse")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(4)
        settleImages(1000)
        snap("04_browse_genres")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        clickText("Top 100: Global")
        waitForText("Week ending")
        settleImages()
        snap("05_chart_top100")
    }

    @Test
    fun search() {
        clickText("Search")
        settleImages(1000)
        snap("06_search_home")
        compose.onNode(hasSetTextAction()).performTextInput("the weeknd")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitForText("Playlists")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("The Weeknd", substring = true).fetchSemanticsNodes().size > 2
        }
        settleImages()
        snap("07_search_results")
    }

    @Test
    fun libraryAndPlayer() {
        val app = ApplicationProvider.getApplicationContext<TempoApp>()
        val chart = runBlocking { app.container.charts.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL) }
        val songs = chart.entries.map { it.song }
        compose.runOnUiThread {
            app.container.playerConnection.play(songs.take(12))
            app.container.playerConnection.togglePlayPause()
        }
        runBlocking {
            songs.take(3).forEach { app.container.library.setLiked(it, true) }
            app.container.library.createPlaylist("Road Trip", songs.drop(3).take(6))
            app.container.library.createPlaylist("Late Night", songs.drop(20).take(4))
        }
        // The controller connects asynchronously; give the main looper time to settle.
        repeat(20) {
            Thread.sleep(250)
            compose.waitForIdle()
        }
        compose.waitUntil(15_000) { app.container.playerConnection.state.value.current != null }

        clickText("Library")
        settleImages()
        snap("08_library")

        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Now Playing", substring = true)).onFirst().performClick()
        settleImages(5000)
        snap("09_now_playing")

        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Lyrics")).onFirst().performClick()
        settleImages(6000)
        snap("09b_lyrics")

        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Playing Next")).onFirst().performClick()
        settleImages(2000)
        snap("10_queue")

        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More")).onFirst().performClick()
        settleImages(1500)
        snap("10b_player_menu")
    }

    @Test
    fun settings() {
        clickText("Library")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Settings")).onFirst().performClick()
        waitForText("Crossfade")
        settleImages(1000)
        snap("14_settings")
    }

    @Test
    fun artist() {
        val app = ApplicationProvider.getApplicationContext<TempoApp>()
        val top = runBlocking { app.container.charts.artists("us") }.entries.first().artist
        waitForText("Top Picks")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Top Artists", substring = true))
        compose.onAllNodesWithText("Top Artists", substring = true).onFirst().performClick()
        waitForText(top.name)
        clickText(top.name)
        waitForText("Top Songs", 60_000)
        settleImages(5000)
        snap("16_artist")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(9)
        settleImages(4000)
        snap("16b_artist_rows")
    }

    @Test
    fun madeForYou() {
        val app = ApplicationProvider.getApplicationContext<TempoApp>()
        runBlocking {
            val history = app.container.youtube.radio("dQw4w9WgXcQ").take(12)
            history.forEachIndexed { i, song -> repeat(12 - i) { app.container.library.markPlayed(song) } }
            history.take(4).forEach { app.container.library.setLiked(it, true) }
            app.container.mixes.refresh()
        }
        waitForText("Made for You", 60_000)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Made for You"))
        settleImages(3000)
        snap("17_made_for_you")
        clickText("Daily Mix 1")
        waitForText("Updated today")
        settleImages(4000)
        snap("17b_mix")
    }

    @Test
    fun searchCancelGoesHome() {
        clickText("Search")
        compose.onNode(hasSetTextAction()).performClick()
        waitForText("Cancel")
        clickText("Cancel")
        waitForText("Top Picks")
        assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun equalizer() {
        val app = ApplicationProvider.getApplicationContext<TempoApp>()
        app.container.equalizer.save(
            EqSettings(preset = null, customGains = listOf(5f, 4f, 2.5f, 0.5f, -1.5f, -1f, 1f, 2.5f, 3.5f, 4f)),
        )
        clickText("Library")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Settings")).onFirst().performClick()
        waitForText("Crossfade")
        clickText("Equalizer")
        waitForText("Reset")
        settleImages(1000)
        snap("15_equalizer")
    }

    @Test
    fun downloads() {
        val app = ApplicationProvider.getApplicationContext<TempoApp>()
        val chart = runBlocking { app.container.charts.chart(ChartKind.TOP_SONGS, ChartsRepository.GLOBAL) }
        val playable = Song(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        )
        runBlocking {
            app.container.library.setLiked(playable, true)
            app.container.downloads.download(listOf(playable, chart.entries[0].song, chart.entries[1].song))
            // Robolectric doesn't start services on its own, so drive the queue directly.
            app.container.downloads.runQueue { _, _, _ -> }
        }
        compose.waitForIdle()
        clickText("Library")
        settleImages(2000)
        snap("11_library_downloads_row")
        clickText("Downloaded")
        settleImages()
        snap("12_downloaded")
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More")).onFirst().performClick()
        settleImages(1500)
        snap("13_song_menu")
    }
}

@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi")
class DarkScreenshotTest : ScreenshotTest() {
    override val suffix = "_dark"
}
