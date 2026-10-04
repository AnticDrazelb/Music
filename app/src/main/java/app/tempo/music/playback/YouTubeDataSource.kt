@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tempo.music.data.youtube.StreamRequests
import app.tempo.music.data.youtube.StreamResolver
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient

/**
 * Builds the [DataSource.Factory] used by ExoPlayer:
 *
 *   downloaded file?  ->  play it from disk
 *   otherwise         ->  cache -> resolve "tempo://youtube/<id>" to a googlevideo URL -> OkHttp
 *
 * Streaming requests follow [StreamRequests]. The cache key is the video id, so replays come
 * straight from disk even after the signed URL has expired.
 */
object YouTubeDataSource {

    fun factory(
        client: OkHttpClient,
        resolver: StreamResolver,
        cache: Cache,
        downloadedFile: (String) -> File?,
    ): DataSource.Factory {
        val http = OkHttpDataSource.Factory(client)
        val requestNumbers = ConcurrentHashMap<String, AtomicInteger>()

        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != MediaItems.SCHEME) return@Factory spec
            val videoId = spec.uri.lastPathSegment ?: throw IOException("Invalid media id")
            val resolved = resolver.resolve(videoId)
            val rn = requestNumbers.getOrPut(videoId) { AtomicInteger(1) }.getAndIncrement()
            val url = StreamRequests.withRequestNumber(resolved.url, rn)
            spec.buildUpon()
                .setUri(url)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(StreamRequests.POST_BODY)
                .setHttpRequestHeaders(spec.httpRequestHeaders + StreamRequests.headers(url))
                .build()
        }

        val streaming = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolving)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val files = FileDataSource.Factory()
        return DataSource.Factory {
            OfflineFirstDataSource(downloadedFile, streaming.createDataSource(), files.createDataSource())
        }
    }
}

/** Plays a song from its downloaded file when there is one, otherwise streams it. */
private class OfflineFirstDataSource(
    private val downloadedFile: (String) -> File?,
    private val streaming: DataSource,
    private val file: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        streaming.addTransferListener(transferListener)
        file.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val videoId = if (dataSpec.uri.scheme == MediaItems.SCHEME) dataSpec.uri.lastPathSegment else null
        val local = videoId?.let(downloadedFile)
        return if (local != null) {
            current = file
            file.open(dataSpec.buildUpon().setUri(Uri.fromFile(local)).build())
        } else {
            current = streaming
            streaming.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("Data source not opened")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}
