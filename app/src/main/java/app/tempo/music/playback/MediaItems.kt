package app.tempo.music.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.tempo.music.data.model.Song
import app.tempo.music.data.youtube.ArtworkUrls

/** Conversions between our [Song] model and Media3 [MediaItem]s. */
object MediaItems {
    const val SCHEME = "tempo"
    private const val EXTRA_ARTIST_URL = "artist_url"
    private const val EXTRA_DURATION = "duration_sec"
    private const val EXTRA_IS_VIDEO = "is_video"
    private const val EXTRA_AUTOPLAY = "autoplay"

    fun uriFor(videoId: String) = "$SCHEME://youtube/$videoId".toUri()

    fun from(song: Song, autoplay: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_ARTIST_URL, song.artistUrl)
            putLong(EXTRA_DURATION, song.durationSec)
            putBoolean(EXTRA_IS_VIDEO, song.isVideo)
            putBoolean(EXTRA_AUTOPLAY, autoplay)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            // Lock screen and notification artwork, at a size that stays sharp there.
            .setArtworkUri(song.thumbnailUrl?.let { ArtworkUrls.candidates(it, 600).first().toUri() })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uriFor(song.id))
            .setCustomCacheKey(song.id)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Items crossing the session boundary lose their URI; rebuild it from the media id. */
    fun withPlaybackUri(item: MediaItem): MediaItem =
        if (item.localConfiguration != null) item
        else item.buildUpon().setUri(uriFor(item.mediaId)).setCustomCacheKey(item.mediaId).build()

    fun toSong(item: MediaItem): Song {
        val md = item.mediaMetadata
        val extras = md.extras
        return Song(
            id = item.mediaId,
            title = md.title?.toString() ?: md.displayTitle?.toString() ?: "Unknown",
            artist = md.artist?.toString() ?: "Unknown Artist",
            artistUrl = extras?.getString(EXTRA_ARTIST_URL),
            album = md.albumTitle?.toString(),
            thumbnailUrl = md.artworkUri?.toString(),
            durationSec = extras?.getLong(EXTRA_DURATION, -1) ?: -1,
            isVideo = extras?.getBoolean(EXTRA_IS_VIDEO, false) ?: false,
        )
    }

    fun isAutoplay(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getBoolean(EXTRA_AUTOPLAY, false) ?: false
}
