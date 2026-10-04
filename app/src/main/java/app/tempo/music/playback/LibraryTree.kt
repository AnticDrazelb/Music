package app.tempo.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import app.tempo.music.AppContainer
import app.tempo.music.data.charts.ChartsRepository
import app.tempo.music.data.charts.Countries
import app.tempo.music.data.download.DownloadState
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.SearchFilter
import app.tempo.music.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and any other media browser) can browse: Made for You, the library and the
 * charts. Songs in a list carry their list in the media id ("favourites::<videoId>"), so picking
 * one plays the whole list from there.
 */
class LibraryTree(private val container: AppContainer) {

    companion object {
        const val ROOT = "root"
        const val FOR_YOU = "foryou"
        const val LIBRARY = "library"
        const val CHARTS = "charts"
        const val RECENT = "recent"
        const val FAVOURITES = "favourites"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val MIX = "mix/"
        const val PLAYLIST = "playlist/"
        const val CHART = "chart/"
        const val SEARCH = "search/"
        private const val SEPARATOR = "::"

        fun songMediaId(parentId: String, videoId: String) = "$parentId$SEPARATOR$videoId"

        /** (list, video id) for a song picked from a list, or null for anything else. */
        fun parse(mediaId: String): Pair<String, String>? {
            val at = mediaId.lastIndexOf(SEPARATOR)
            if (at <= 0) return null
            return mediaId.substring(0, at) to mediaId.substring(at + SEPARATOR.length)
        }

        /** Extras telling Android Auto to show a folder's contents as a grid or a list. */
        fun style(browsable: Int, playable: Int = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
        }
    }

    private val searches = ConcurrentHashMap<String, List<Song>>()

    fun root(): MediaItem = folder(ROOT, "Tempo")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(FOR_YOU, "Made for You", grid = true),
            folder(LIBRARY, "Library"),
            folder(CHARTS, "Charts", grid = true),
        )
        parentId == FOR_YOU -> {
            // Only wait for today's mixes when there are none to show yet.
            if (container.mixes.mixes.value.isEmpty()) runCatching { container.mixes.refresh() }
            else container.appScope.launch { runCatching { container.mixes.refresh() } }
            val recent = songs(RECENT)
            container.mixes.mixes.value.map { mix ->
                folder(MIX + mix.id, mix.title, subtitle = mix.subtitle, artwork = mix.songs.firstOrNull()?.thumbnailUrl)
            } + listOfNotNull(
                recent.takeIf { it.isNotEmpty() }?.let { folder(RECENT, "Recently Played", artwork = it.first().thumbnailUrl) },
            )
        }
        parentId == LIBRARY -> listOf(
            folder(FAVOURITES, "Favourite Songs"),
            folder(PLAYLISTS, "Playlists", grid = true),
            folder(DOWNLOADS, "Downloaded"),
            folder(RECENT, "Recently Played"),
        )
        parentId == CHARTS -> {
            val country = container.settings.country.value
            listOf(
                folder(CHART + ChartsRepository.GLOBAL, "Top 100: Global"),
                folder(CHART + country.lowercase(), "Top 100: ${Countries.name(country)}"),
            )
        }
        parentId == PLAYLISTS -> container.library.userPlaylists.first().map { playlist ->
            folder(PLAYLIST + playlist.id, playlist.name, subtitle = "${playlist.songCount} songs", artwork = playlist.thumbnails.firstOrNull())
        }
        else -> songs(parentId).map { song -> playable(parentId, song) }
    }

    /** The songs a list plays, in order. */
    suspend fun songs(parentId: String): List<Song> = when {
        parentId == RECENT -> container.library.recentlyPlayed(50).first()
        parentId == FAVOURITES -> container.library.likedSongs.first()
        parentId == DOWNLOADS -> container.downloads.items.first().filter { it.status.state == DownloadState.DONE }.map { it.song }
        parentId.startsWith(MIX) -> container.mixes.mix(parentId.removePrefix(MIX))?.songs.orEmpty()
        parentId.startsWith(PLAYLIST) -> parentId.removePrefix(PLAYLIST).toLongOrNull()
            ?.let { id -> container.library.playlistSongs(id).first().map { it.song } }.orEmpty()
        parentId.startsWith(CHART) ->
            container.charts.chart(ChartKind.TOP_SONGS, parentId.removePrefix(CHART)).entries.map { it.song }
        parentId.startsWith(SEARCH) -> searchResults(parentId.removePrefix(SEARCH))
        else -> emptyList()
    }

    suspend fun search(query: String): List<Song> =
        container.youtube.search(query, SearchFilter.SONGS).items.filterIsInstance<Song>().also { searches[query] = it }

    suspend fun searchResults(query: String): List<Song> = searches[query] ?: search(query)

    fun searchItems(query: String, songs: List<Song>): List<MediaItem> = songs.map { playable(SEARCH + query, it) }

    /** A single item by id, for browsers that look one up directly. */
    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT) return root()
        parse(mediaId)?.let { (parentId, videoId) ->
            return songs(parentId).firstOrNull { it.id == videoId }?.let { playable(parentId, it) }
        }
        val parents = listOf(ROOT, FOR_YOU, LIBRARY, CHARTS) + if (mediaId.startsWith(PLAYLIST)) listOf(PLAYLISTS) else emptyList()
        return parents.firstNotNullOfOrNull { parent -> children(parent).firstOrNull { it.mediaId == mediaId } }
    }

    /** "Hey Google, play Coldplay on Tempo": the top song results, best match first. */
    suspend fun playSearch(query: String): MediaSession.MediaItemsWithStartPosition? {
        val songs = if (query.isBlank()) songs(FAVOURITES).shuffled().ifEmpty { songs(RECENT) } else search(query)
        if (songs.isEmpty()) return null
        return MediaSession.MediaItemsWithStartPosition(songs.map { MediaItems.from(it) }, 0, 0)
    }

    /** The song behind an id from this tree, or null if it isn't one. */
    suspend fun song(mediaId: String): Song? {
        val (parentId, videoId) = parse(mediaId) ?: return null
        return songs(parentId).firstOrNull { it.id == videoId }
    }

    /** What to play when a song is picked from a list: the whole list, starting at that song. */
    suspend fun playFrom(mediaId: String): MediaSession.MediaItemsWithStartPosition? {
        val (parentId, videoId) = parse(mediaId) ?: return null
        val songs = songs(parentId)
        val index = songs.indexOfFirst { it.id == videoId }
        if (index < 0) return null
        return MediaSession.MediaItemsWithStartPosition(songs.map { MediaItems.from(it) }, index, 0)
    }

    private fun folder(id: String, title: String, subtitle: String? = null, artwork: String? = null, grid: Boolean = false) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork?.let(ArtworkProvider::uriFor))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(
                        style(
                            if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                        ),
                    )
                    .build(),
            )
            .build()

    /** A song in a list, with artwork Android Auto can load. */
    fun playable(parentId: String, song: Song): MediaItem {
        val item = MediaItems.from(song)
        return item.buildUpon()
            .setMediaId(songMediaId(parentId, song.id))
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setArtworkUri(song.thumbnailUrl?.let(ArtworkProvider::uriFor))
                    .build(),
            )
            .build()
    }
}
