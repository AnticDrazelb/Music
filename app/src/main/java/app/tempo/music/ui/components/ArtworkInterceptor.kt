package app.tempo.music.ui.components

import app.tempo.music.data.youtube.ArtworkUrls
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.pxOrElse

/** Loads YouTube artwork at the resolution it's drawn at, falling back if a size doesn't exist. */
class ArtworkInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val px = maxOf(chain.size.width.pxOrElse { 0 }, chain.size.height.pxOrElse { 0 })
        val candidates = ArtworkUrls.candidates(url, px)
        if (candidates == listOf(url)) return chain.proceed()
        var result: ImageResult? = null
        for (candidate in candidates) {
            result = chain.withRequest(chain.request.newBuilder().data(candidate).build()).proceed()
            if (result is SuccessResult) return result
        }
        return result ?: chain.proceed()
    }
}
