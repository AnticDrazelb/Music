package app.tempo.music

import android.app.Application
import app.tempo.music.ui.components.ArtworkInterceptor
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class TempoApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ArtworkInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient }))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
