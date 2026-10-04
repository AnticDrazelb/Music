package app.tempo.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tempo.music.data.model.Song

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistUrl: String?,
    val album: String?,
    val thumbnailUrl: String?,
    val durationSec: Long,
    val isVideo: Boolean,
    val likedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
) {
    fun toSong() = Song(id, title, artist, artistUrl, album, thumbnailUrl, durationSec, isVideo)

    companion object {
        fun from(song: Song) = SongEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            artistUrl = song.artistUrl,
            album = song.album,
            thumbnailUrl = song.thumbnailUrl,
            durationSec = song.durationSec,
            isVideo = song.isVideo,
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long,
)

/** A YouTube album, playlist or artist the user added to their library. */
@Entity(tableName = "saved_items")
data class SavedItemEntity(
    @PrimaryKey val url: String,
    /** ALBUM, PLAYLIST, MIX or ARTIST */
    val type: String,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val savedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

data class PlaylistSummaryRow(
    @Embedded val playlist: PlaylistEntity,
    @ColumnInfo(name = "songCount") val songCount: Int,
)

data class PlaylistThumbRow(
    val playlistId: Long,
    val thumbnailUrl: String?,
)

data class PlaylistSongRow(
    @Embedded val song: SongEntity,
    @ColumnInfo(name = "entryId") val entryId: Long,
    @ColumnInfo(name = "position") val position: Int,
)
