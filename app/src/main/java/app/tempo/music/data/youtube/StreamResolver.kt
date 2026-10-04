package app.tempo.music.data.youtube

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a YouTube video id into a direct, playable audio URL. URLs are signed and expire
 * after a few hours, so results are cached until shortly before they expire.
 */
class StreamResolver(
    private val youtube: YouTubeRepository,
    private val highQuality: () -> Boolean,
) {
    data class Resolved(val url: String, val mimeType: String?, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Resolved>()

    /** Blocking; call from a loader/IO thread. */
    @Throws(IOException::class)
    fun resolve(videoId: String): Resolved {
        cache[videoId]?.let { cached ->
            if (cached.expiresAtMs - System.currentTimeMillis() > 2 * 60_000) return cached
        }
        val info = try {
            youtube.streamInfoBlocking(videoId)
        } catch (e: Exception) {
            throw IOException(friendlyMessage(e), e)
        }
        val resolved = pick(info) ?: throw IOException("No playable audio found for this song")
        cache[videoId] = resolved
        return resolved
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun pick(info: StreamInfo): Resolved? {
        val candidates = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .let { all ->
                // Prefer the original-language track when YouTube offers dubbed ones.
                val original = all.filter {
                    it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
                }
                original.ifEmpty { all }
            }

        val chosen: AudioStream? = if (highQuality()) {
            candidates.maxByOrNull { it.averageBitrate }
        } else {
            candidates.filter { it.averageBitrate >= 48 }.minByOrNull { it.averageBitrate }
                ?: candidates.minByOrNull { it.averageBitrate }
        }
        if (chosen != null) {
            return Resolved(chosen.content, chosen.format?.mimeType, expiry(chosen.content))
        }
        // Rare: no separate audio track; fall back to a muxed progressive stream.
        val muxed = info.videoStreams
            .filter { !it.isVideoOnly() && it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .minByOrNull { it.getResolution().filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
            ?: return null
        return Resolved(muxed.content, muxed.format?.mimeType, expiry(muxed.content))
    }

    private fun expiry(url: String): Long {
        val expire = Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (expire != null) expire * 1000 else System.currentTimeMillis() + 5 * 3_600_000L
    }

    private fun friendlyMessage(e: Exception): String {
        val name = e.javaClass.simpleName
        val msg = e.message.orEmpty()
        return when {
            "SignInConfirmNotBot" in name || "not a bot" in msg ->
                "YouTube is asking to confirm you're not a bot. Try again later or on another network."
            "AgeRestricted" in name -> "This song is age-restricted on YouTube"
            "Geographic" in name || "country" in msg -> "This song isn't available in your country"
            "Private" in name -> "This video is private"
            "MusicPremium" in name -> "This song requires YouTube Music Premium"
            "Paid" in name -> "This song requires a paid membership"
            "ContentNotAvailable" in name -> msg.substringAfter(": ").trim('"').ifBlank { "This song is unavailable" }
            e is IOException -> "Network error — check your connection"
            else -> msg.ifBlank { "Couldn't load this song" }
        }
    }
}
