package app.tempo.music.data.youtube

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * How googlevideo audio URLs must be requested, shared by playback and downloads. This mimics
 * YouTube's own clients the way NewPipe does: POST with a tiny body, an incrementing `rn`
 * parameter, and a user agent matching the client that produced the URL.
 */
object StreamRequests {
    val POST_BODY = byteArrayOf(0x78, 0)

    fun withRequestNumber(url: String, rn: Int) = "$url&rn=$rn"

    fun headers(url: String): Map<String, String> = buildMap {
        put(
            "User-Agent",
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            } else {
                NewPipeDownloader.USER_AGENT
            },
        )
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            put("Sec-Fetch-Dest", "empty")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Site", "cross-site")
        }
    }

    /** File extension for a downloaded audio stream's MIME type. */
    fun extensionFor(mimeType: String?): String = when {
        mimeType == null -> "audio"
        "mp4" in mimeType || "m4a" in mimeType -> "m4a"
        "webm" in mimeType -> "webm"
        "ogg" in mimeType || "opus" in mimeType -> "opus"
        else -> "audio"
    }
}
