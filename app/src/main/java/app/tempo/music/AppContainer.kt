@file:OptIn(UnstableApi::class)

package app.tempo.music

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.tempo.music.data.LibraryRepository
import app.tempo.music.data.SettingsRepository
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.db.MusicDatabase
import app.tempo.music.data.download.AudioDownloader
import app.tempo.music.data.download.DownloadRepository
import app.tempo.music.data.lyrics.LyricsRepository
import app.tempo.music.data.youtube.StreamResolver
import app.tempo.music.data.youtube.YouTubeRepository
import app.tempo.music.playback.PlayerConnection
import app.tempo.music.playback.eq.EqualizerController
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Hand-rolled dependency container, created once by [TempoApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val database: MusicDatabase = MusicDatabase.build(appContext)
    val settings = SettingsRepository(appContext, appScope)
    val library = LibraryRepository(database)
    val youtube = YouTubeRepository(httpClient)
    val charts = ChartsRepository(httpClient)
    val lyrics = LyricsRepository(
        httpClient,
        File(appContext.filesDir, "lyrics"),
        userAgent = "Tempo/${BuildConfig.VERSION_NAME} (Android music player)",
    )

    val equalizer = EqualizerController(settings, appScope)

    val streamResolver = StreamResolver(youtube) {
        settings.highQuality.value && !(settings.dataSaverOnCellular.value && NetworkState.isMetered(appContext))
    }

    val downloads = DownloadRepository(
        appContext,
        database,
        library,
        AudioDownloader(httpClient, streamResolver),
        appScope,
    )

    /** Audio cache shared by the playback service (SimpleCache must be a singleton per folder). */
    val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(appContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /** Set while the "end of song" sleep timer runs, so that song isn't crossfaded away. */
    val stopAfterCurrentSong = MutableStateFlow(false)

    val playerConnection: PlayerConnection by lazy {
        PlayerConnection(appContext, library, youtube, appScope, stopAfterCurrentSong)
    }

    init {
        youtube.init(settings.country.value)
        downloads.start()
        appScope.launch(Dispatchers.IO) {
            // Songs saved before 1.5.0 can carry "(Official 4K Video)"-style titles.
            if (settings.cleanupVersion() < METADATA_CLEANUP_VERSION) {
                runCatching { library.recleanSongs() }
                settings.setCleanupVersion(METADATA_CLEANUP_VERSION)
            }
        }
        appScope.launch {
            settings.country.drop(1).collect { youtube.setCountry(it) }
        }
    }

    private companion object {
        const val METADATA_CLEANUP_VERSION = 1
    }
}
