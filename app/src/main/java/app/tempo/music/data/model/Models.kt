package app.tempo.music.data.model

import kotlinx.serialization.Serializable

/** Anything that can appear in a list of results: a song, an album/playlist, or an artist. */
sealed interface MusicItem {
    val key: String
}

@Serializable
data class Song(
    /** YouTube video id. */
    val id: String,
    val title: String,
    val artist: String,
    /** Channel URL of the artist, when known. */
    val artistUrl: String? = null,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Long = -1,
    val isVideo: Boolean = false,
) : MusicItem {
    override val key: String get() = "song:$id"
    val shareUrl: String get() = "https://music.youtube.com/watch?v=$id"
}

enum class CollectionType { ALBUM, PLAYLIST, MIX }

@Serializable
data class CollectionRef(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val type: CollectionType = CollectionType.PLAYLIST,
    val itemCount: Long = -1,
) : MusicItem {
    override val key: String get() = "collection:$url"
}

@Serializable
data class ArtistRef(
    val url: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val subscribers: Long = -1,
) : MusicItem {
    override val key: String get() = "artist:$url"
}

data class CollectionDetail(
    val ref: CollectionRef,
    val songs: List<Song>,
    val nextPage: Any? = null,
)

data class ArtistDetail(
    val ref: ArtistRef,
    val bannerUrl: String?,
    val description: String?,
    val topSongs: List<Song>,
    /** Rows after Top Songs, in YouTube Music's order: albums, singles, similar artists... (never videos) */
    val sections: List<ArtistSection>,
    /** Every song by the artist, behind Top Songs' "See All". */
    val allSongs: CollectionRef? = null,
)

/** [more] opens the full list behind the row's title. */
data class ArtistSection(val title: String, val items: List<MusicItem>, val more: BrowseRef? = null)

/** A YouTube Music page listing items, such as an artist's complete discography. */
data class BrowseRef(val browseId: String, val params: String?, val title: String)

data class Page<T>(val items: List<T>, val nextPage: Any?)

enum class SearchFilter(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
}

// ---- Charts ----

enum class ChartKind(val apiType: String, val title: String) {
    TOP_SONGS("TRACKS", "Top Songs"),
    TOP_ARTISTS("ARTISTS", "Top Artists"),
}

data class ChartEntry(
    val rank: Int,
    val previousRank: Int?,
    val weeksOnChart: Int,
    val song: Song,
) {
    val isNew: Boolean get() = previousRank == null || previousRank <= 0
    val movement: Int get() = if (isNew) 0 else (previousRank ?: rank) - rank
}

data class Chart(
    val kind: ChartKind,
    val countryCode: String,
    val countryName: String,
    val periodLabel: String?,
    val entries: List<ChartEntry>,
)

data class ArtistChartEntry(
    val rank: Int,
    val previousRank: Int?,
    val artist: ArtistRef,
)

data class ArtistChart(
    val countryCode: String,
    val countryName: String,
    val periodLabel: String?,
    val entries: List<ArtistChartEntry>,
)
