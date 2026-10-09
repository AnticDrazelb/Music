package app.tempo.music.ui

import androidx.compose.ui.test.junit4.createComposeRule
import app.tempo.music.ui.screens.player.OutputPickerSheet
import app.tempo.music.ui.theme.TempoTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Play On sheet. Run with -Dscreenshots=true; written to app/screenshots/09e_play_on.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OutputPickerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun playOnSheet() {
        assumeTrue(System.getProperty("screenshots") == "true")
        compose.setContent { TempoTheme { OutputPickerSheet(onDismiss = {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/09e_play_on.png")
    }
}
