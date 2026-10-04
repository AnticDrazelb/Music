package app.tempo.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tempo.music.ui.TempoRoot
import app.tempo.music.ui.theme.TempoTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Intents (shared links, notification taps) waiting to be handled by the UI. */
    private val incoming = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TempoApp).container
        if (savedInstanceState == null) incoming.value = intent
        setContent {
            TempoTheme {
                TempoRoot(container, incoming, onIntentHandled = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
