package app.tempo.music.playback.eq

import app.tempo.music.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Connects the saved equaliser settings to the audio threads. While a slider is being dragged
 * the UI calls [preview] so the change is heard immediately, and [save] when it's let go.
 */
class EqualizerController(private val settings: SettingsRepository, private val scope: CoroutineScope) {
    val state = EqualizerState(settings.equalizer.value)
    val saved: StateFlow<EqSettings> = settings.equalizer

    init {
        scope.launch { settings.equalizer.collect { state.settings = it } }
    }

    fun preview(value: EqSettings) {
        state.settings = value
    }

    fun save(value: EqSettings) {
        state.settings = value
        scope.launch { settings.setEqualizer(value) }
    }
}
