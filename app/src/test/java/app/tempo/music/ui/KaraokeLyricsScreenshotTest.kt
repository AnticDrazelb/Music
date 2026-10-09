package app.tempo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.tempo.music.data.lyrics.LyricEntry
import app.tempo.music.data.lyrics.LyricsTiming
import app.tempo.music.ui.screens.player.InterludeDots
import app.tempo.music.ui.screens.player.LyricLineText
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Karaoke lyrics part-way through a line, drawn offline. Run with -Dscreenshots=true; the image
 * goes to app/screenshots/09c_karaoke.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class KaraokeLyricsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun line(text: String, start: Long, length: Long): LyricEntry.Line {
        val words = LyricsTiming.estimateWords(text, start, length)
        return LyricEntry.Line(text, words, start, words.last().endMs, exact = false)
    }

    @Test
    fun karaokeLine() {
        assumeTrue(System.getProperty("screenshots") == "true")
        val past = line("And I will always love you", 0, 4_000)
        val current = line("I hope life treats you kind, and I hope you have all you dreamed of", 4_000, 6_000)
        val next = line("I wish you joy and happiness", 10_000, 4_000)
        val interlude = LyricEntry.Interlude(14_000, 20_000)
        val now = { 7_200L }
        compose.setContent {
            Column(
                Modifier
                    .background(Brush.verticalGradient(listOf(Color(0xFF7A3B52), Color(0xFF2B1530))))
                    .padding(horizontal = 26.dp, vertical = 60.dp),
            ) {
                LyricLineText(past, active = false, wordByWord = true, alpha = 0.32f, position = now, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LyricLineText(current, active = true, wordByWord = true, alpha = 1f, position = now, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LyricLineText(next, active = false, wordByWord = true, alpha = 0.45f, position = now, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                InterludeDots(interlude, active = true, position = { 17_500L })
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/09c_karaoke.png")
    }
}
