package app.tempo.music.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.TempoApp
import app.tempo.music.data.UserPlaylist
import app.tempo.music.data.model.Song
import app.tempo.music.ui.components.SaveAsPlaylistDialog
import app.tempo.music.ui.navigation.LocalAppContainer
import app.tempo.music.ui.navigation.LocalPlayer
import app.tempo.music.ui.theme.TempoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Saving a radio set or mix as a playlist. */
// No screen-size qualifier: with one, Robolectric never lets a Compose dialog settle.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SaveAsPlaylistDialogTest {
    @get:Rule val compose = createComposeRule()

    private val songs = (1..20).map { Song("id$it", "Song $it", "Artist $it") }

    @Test
    fun savesTheWholeSetAsAPlaylist() {
        val tempo = ApplicationProvider.getApplicationContext<TempoApp>()
        var dismissed = false
        compose.setContent {
            TempoTheme {
                CompositionLocalProvider(LocalAppContainer provides tempo.container, LocalPlayer provides tempo.container.playerConnection) {
                    SaveAsPlaylistDialog("Naive Radio", songs, onDismiss = { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("Naive Radio").assertExists() // the name is prefilled
        compose.onNodeWithText("20 songs").assertExists()
        if (System.getProperty("screenshots") == "true") {
            compose.onNode(isDialog()).captureRoboImage("screenshots/17c_save_as_playlist.png")
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Indie Night")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertTrue(dismissed)
        val playlist = compose.waitUntilPlaylist(tempo, "Indie Night")
        assertEquals(20, playlist.songCount)
        val saved = runBlocking { tempo.container.library.playlistSongs(playlist.id).first() }
        assertEquals(songs.map { it.id }, saved.map { it.song.id })
    }

    /** Lets the main thread run (the save happens there) while waiting for the playlist to fill. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitUntilPlaylist(tempo: TempoApp, name: String): UserPlaylist {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val found = runBlocking(kotlinx.coroutines.Dispatchers.IO) { tempo.container.library.userPlaylists.first() }
                .firstOrNull { it.name == name && it.songCount == songs.size }
            if (found != null) return found
            Thread.sleep(50)
        }
        error("Playlist '$name' not saved: " + runBlocking(kotlinx.coroutines.Dispatchers.IO) { tempo.container.library.userPlaylists.first() })
    }
}
