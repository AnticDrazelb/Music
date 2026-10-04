package app.tempo.music.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tempo.music.data.charts.Countries
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

    suspend fun setCountry(code: String) = context.dataStore.edit { it[Keys.country] = code.uppercase() }
    suspend fun setHighQuality(value: Boolean) = context.dataStore.edit { it[Keys.highQuality] = value }
    suspend fun setAutoplay(value: Boolean) = context.dataStore.edit { it[Keys.autoplay] = value }
    suspend fun setDataSaverOnCellular(value: Boolean) =
        context.dataStore.edit { it[Keys.dataSaverOnCellular] = value }
}
