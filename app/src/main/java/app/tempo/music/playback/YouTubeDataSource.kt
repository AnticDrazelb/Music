@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tempo.music.data.youtube.NewPipeDownloader
import app.tempo.music.data.youtube.StreamResolver
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Builds the [DataSource.Factory] used by ExoPlayer:
 *
 *   cache  ->  resolve "tempo://youtube/<id>" to a googlevideo URL  ->  OkHttp
 *
 * Requests mimic YouTube's own clients the same way NewPipe does: POST with a tiny body,
 * an incrementing `rn` parameter and a matching user agent. The cache key is the video id,
 * so replays come straight from disk even after the signed URL has expired.
 */
object YouTubeDataSource {

    private val POST_BODY = byteArrayOf(0x78, 0)

    fun factory(client: OkHttpClient, resolver: StreamResolver, cache: Cache): DataSource.Factory {
        val http = OkHttpDataSource.Factory(client)
        val requestNumbers = ConcurrentHashMap<String, AtomicInteger>()

        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != MediaItems.SCHEME) return@Factory spec
            val videoId = spec.uri.lastPathSegment ?: throw IOException("Invalid media id")
            val resolved = resolver.resolve(videoId)
            val rn = requestNumbers.getOrPut(videoId) { AtomicInteger(1) }.getAndIncrement()
            val url = "${resolved.url}&rn=$rn"

            val headers = HashMap(spec.httpRequestHeaders)
            headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            } else {
                NewPipeDownloader.USER_AGENT
            }
            if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }

            spec.buildUpon()
                .setUri(url)
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(POST_BODY)
                .setHttpRequestHeaders(headers)
                .build()
        }

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolving)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.lastPathSegment ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
