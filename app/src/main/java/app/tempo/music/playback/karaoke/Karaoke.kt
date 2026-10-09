package app.tempo.music.playback.karaoke

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Karaoke settings the audio threads read; written from the main thread. */
class KaraokeState {
    /** How loud the lead vocal is: 1 is the original mix, 0 removes as much as possible. */
    @Volatile var vocals: Float = 1f
}

/**
 * Karaoke mode, like Apple Music Sing: the lead vocal is turned down to [vocals] and the
 * lyrics follow word by word. Turning it off restores the original mix; the vocal level is
 * remembered for next time.
 */
class KaraokeController {
    val state = KaraokeState()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _vocals = MutableStateFlow(DEFAULT_VOCALS)
    /** Vocal level while karaoke is on, 0–1. */
    val vocals: StateFlow<Float> = _vocals.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        apply()
    }

    fun toggle() = setEnabled(!_enabled.value)

    fun setVocals(level: Float) {
        _vocals.value = level.coerceIn(0f, 1f)
        apply()
    }

    private fun apply() {
        state.vocals = if (_enabled.value) _vocals.value else 1f
    }

    companion object {
        /** Apple Music Sing starts with the vocals mostly, but not entirely, gone. */
        const val DEFAULT_VOCALS = 0.1f
    }
}
