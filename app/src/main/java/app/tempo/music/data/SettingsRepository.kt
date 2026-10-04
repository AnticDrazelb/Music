package app.tempo.music.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tempo.music.data.charts.Countries
import app.tempo.music.playback.eq.Eq
import app.tempo.music.playback.eq.EqPreset
import app.tempo.music.playback.eq.EqSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val country = stringPreferencesKey("chart_country")
        val highQuality = booleanPreferencesKey("high_quality")
        val autoplay = booleanPreferencesKey("autoplay")
        val dataSaverOnCellular = booleanPreferencesKey("data_saver_cellular")
        val crossfade = booleanPreferencesKey("crossfade")
        val crossfadeSeconds = intPreferencesKey("crossfade_seconds")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqCustomGains = stringPreferencesKey("eq_custom_gains")
        val cleanupVersion = intPreferencesKey("metadata_cleanup_version")
    }

    private val prefs: Flow<Preferences> = context.dataStore.data

    private val initial: Preferences = runBlocking { prefs.first() }

    /** Two-letter country used for charts and YouTube content, e.g. "US". */
    val country: StateFlow<String> = prefs.map { it[Keys.country] ?: Countries.defaultFor() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.country] ?: Countries.defaultFor())

    val highQuality: StateFlow<Boolean> = prefs.map { it[Keys.highQuality] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.highQuality] ?: true)

    /** Keep playing similar songs when the queue runs out (Apple Music's ∞ Autoplay). */
    val autoplay: StateFlow<Boolean> = prefs.map { it[Keys.autoplay] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.autoplay] ?: true)

    val dataSaverOnCellular: StateFlow<Boolean> = prefs.map { it[Keys.dataSaverOnCellular] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.dataSaverOnCellular] ?: false)

    /** Blend the end of each song into the next. */
    val crossfade: StateFlow<Boolean> = prefs.map { it[Keys.crossfade] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.crossfade] ?: true)

    val crossfadeSeconds: StateFlow<Int> = prefs.map { it[Keys.crossfadeSeconds] ?: DEFAULT_CROSSFADE_SECONDS }
        .stateIn(scope, SharingStarted.Eagerly, initial[Keys.crossfadeSeconds] ?: DEFAULT_CROSSFADE_SECONDS)

    val equalizer: StateFlow<EqSettings> = prefs.map(::readEqualizer)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, readEqualizer(initial))

    private fun readEqualizer(p: Preferences): EqSettings {
        val custom = p[Keys.eqCustomGains]
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(-Eq.MAX_GAIN_DB, Eq.MAX_GAIN_DB) }
            ?.takeIf { it.size == Eq.BAND_COUNT }
            ?: Eq.FLAT
        val preset = when (val name = p[Keys.eqPreset]) {
            null -> EqPreset.FLAT
            CUSTOM -> null
            else -> EqPreset.entries.firstOrNull { it.name == name } ?: EqPreset.FLAT
        }
        return EqSettings(enabled = p[Keys.eqEnabled] ?: true, preset = preset, customGains = custom)
    }

    suspend fun setCountry(code: String) = context.dataStore.edit { it[Keys.country] = code.uppercase() }
    suspend fun setHighQuality(value: Boolean) = context.dataStore.edit { it[Keys.highQuality] = value }
    suspend fun setAutoplay(value: Boolean) = context.dataStore.edit { it[Keys.autoplay] = value }
    suspend fun setDataSaverOnCellular(value: Boolean) =
        context.dataStore.edit { it[Keys.dataSaverOnCellular] = value }
    suspend fun setCrossfade(value: Boolean) = context.dataStore.edit { it[Keys.crossfade] = value }
    suspend fun setCrossfadeSeconds(value: Int) =
        context.dataStore.edit { it[Keys.crossfadeSeconds] = value.coerceIn(CROSSFADE_RANGE) }

    /** Version of the one-off library clean-up already applied. */
    suspend fun cleanupVersion(): Int = prefs.first()[Keys.cleanupVersion] ?: 0
    suspend fun setCleanupVersion(value: Int) = context.dataStore.edit { it[Keys.cleanupVersion] = value }

    suspend fun setEqualizer(value: EqSettings) = context.dataStore.edit {
        it[Keys.eqEnabled] = value.enabled
        it[Keys.eqPreset] = value.preset?.name ?: CUSTOM
        it[Keys.eqCustomGains] = value.customGains.joinToString(",")
    }

    companion object {
        private const val CUSTOM = "CUSTOM"
        const val DEFAULT_CROSSFADE_SECONDS = 6
        val CROSSFADE_RANGE = 1..12
    }
}
