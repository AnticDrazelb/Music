@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.tempo.music.data.model.Song
import app.tempo.music.playback.MediaItems
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject
import java.net.Inet4Address

object CastSupport {
    private var available: Boolean? = null

    /**
     * Starts the Cast framework (main thread). False without Google Play services, where only
     * the phone and Bluetooth can play.
     */
    fun init(context: Context): Boolean = available ?: try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS &&
            run {
                @Suppress("DEPRECATION")
                CastContext.getSharedInstance(context.applicationContext)
                true
            }
    } catch (e: Exception) {
        android.util.Log.w("Cast", "Cast unavailable", e)
        false
    }.also { available = it }

    fun controlCategory(): String =
        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
}

/**
 * Cast settings, named in the manifest. Songs play in Google's standard Default Media Receiver,
 * which every Chromecast, Google TV and Cast speaker has. Tempo's own media session already
 * drives the notification and lock screen, so the Cast SDK's are turned off.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .setResumeSavedSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Turns queue items into what a Cast device can play (a URL on [server] plus title, artist
 * and artwork for the TV screen) and back again when playback returns to the phone.
 */
class CastMediaItemConverter(
    private val context: Context,
    private val server: CastStreamServer,
    /** The audio's MIME type when known without a network request. */
    private val mimeHint: (videoId: String) -> String? = { null },
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val song = MediaItems.toSong(mediaItem)
        val host = lanAddress(context) ?: "127.0.0.1"
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.title)
            putString(MediaMetadata.KEY_ARTIST, song.artist)
            song.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            mediaItem.mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(server.urlFor(host, song.id))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            // YouTube's best audio is usually Opus in WebM; receivers sniff the data either way.
            .setContentType(server.knownMimeType(song.id) ?: mimeHint(song.id) ?: "audio/webm")
            .setMetadata(metadata)
            .apply { if (song.durationSec > 0) setStreamDuration(song.durationSec * 1000) }
            .setCustomData(songJson(song, MediaItems.isAutoplay(mediaItem)))
            .build()
        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val data = info?.customData
        if (data != null && data.has(ID)) {
            val song = Song(
                id = data.getString(ID),
                title = data.optString(TITLE, "Unknown"),
                artist = data.optString(ARTIST, "Unknown Artist"),
                artistUrl = data.optString(ARTIST_URL).ifBlank { null },
                album = data.optString(ALBUM).ifBlank { null },
                thumbnailUrl = data.optString(THUMBNAIL).ifBlank { null },
                durationSec = data.optLong(DURATION, -1),
                isVideo = data.optBoolean(IS_VIDEO, false),
            )
            return MediaItems.from(song, autoplay = data.optBoolean(AUTOPLAY, false))
        }
        // Started by another app or phone: show what we can.
        val md = info?.metadata
        return MediaItem.Builder()
            .setMediaId(info?.contentId.orEmpty())
            .setUri(info?.contentId?.toUri())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(md?.getString(MediaMetadata.KEY_TITLE))
                    .setArtist(md?.getString(MediaMetadata.KEY_ARTIST))
                    .build(),
            )
            .build()
    }

    private fun songJson(song: Song, autoplay: Boolean) = JSONObject().apply {
        put(ID, song.id)
        put(TITLE, song.title)
        put(ARTIST, song.artist)
        song.artistUrl?.let { put(ARTIST_URL, it) }
        song.album?.let { put(ALBUM, it) }
        song.thumbnailUrl?.let { put(THUMBNAIL, it) }
        put(DURATION, song.durationSec)
        put(IS_VIDEO, song.isVideo)
        put(AUTOPLAY, autoplay)
    }

    private companion object {
        const val ID = "tempoId"
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val ARTIST_URL = "artistUrl"
        const val ALBUM = "album"
        const val THUMBNAIL = "thumbnail"
        const val DURATION = "durationSec"
        const val IS_VIDEO = "isVideo"
        const val AUTOPLAY = "autoplay"
    }
}

/**
 * The phone's IPv4 address on the local network, where a TV can reach it: Wi-Fi (or Ethernet,
 * or the phone's own hotspot) rather than mobile data or a VPN tunnel.
 */
@Suppress("UNUSED_PARAMETER")
fun lanAddress(context: Context): String? {
    val candidates = runCatching { java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it } }
        .filter { (_, address) -> address.isSiteLocalAddress }
    val preferred = listOf("wlan", "eth", "ap", "swlan")
    return (candidates.firstOrNull { (name, _) -> preferred.any(name::startsWith) } ?: candidates.firstOrNull { (name, _) -> !name.startsWith("tun") && !name.startsWith("rmnet") })
        ?.second?.hostAddress
}

/** Keeps the CPU and Wi-Fi awake while a Cast device streams from this phone. */
class CastLocks(context: Context) {
    private val wake = context.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Tempo:cast")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Tempo:cast")
        ?.apply { setReferenceCounted(false) }

    @android.annotation.SuppressLint("WakelockTimeout")
    fun acquire() {
        wake.acquire()
        wifi?.acquire()
    }

    fun release() {
        if (wake.isHeld) wake.release()
        if (wifi?.isHeld == true) wifi.release()
    }
}
