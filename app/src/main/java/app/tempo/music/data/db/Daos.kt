package app.tempo.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity)

    @Query(
        """UPDATE songs SET title = :title, artist = :artist,
           artistUrl = COALESCE(:artistUrl, artistUrl), album = COALESCE(:album, album),
           thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl),
           durationSec = CASE WHEN :durationSec > 0 THEN :durationSec ELSE durationSec END
           WHERE id = :id""",
    )
    suspend fun updateMeta(
        id: String,
        title: String,
        artist: String,
        artistUrl: String?,
        album: String?,
        thumbnailUrl: String?,
        durationSec: Long,
    )

    @Query("UPDATE songs SET likedAt = :likedAt WHERE id = :id")
    suspend fun setLikedAt(id: String, likedAt: Long?)

    @Query("UPDATE songs SET lastPlayedAt = :time, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: String, time: Long)

    @Query("SELECT * FROM songs WHERE likedAt IS NOT NULL ORDER BY likedAt DESC")
    fun liked(): Flow<List<SongEntity>>

    @Query("SELECT id FROM songs WHERE likedAt IS NOT NULL")
    fun likedIds(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("UPDATE songs SET lastPlayedAt = NULL, playCount = 0")
    suspend fun clearHistory()
}

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :time WHERE id = :id")
    suspend fun rename(id: Long, name: String, time: Long)

    @Query("UPDATE playlists SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """SELECT p.*, (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS songCount
           FROM playlists p ORDER BY p.updatedAt DESC""",
    )
    fun summaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun playlist(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT e.playlistId AS playlistId, s.thumbnailUrl AS thumbnailUrl
           FROM playlist_entries e JOIN songs s ON s.id = e.songId
           ORDER BY e.playlistId, e.position""",
    )
    fun thumbnails(): Flow<List<PlaylistThumbRow>>

    @Query(
        """SELECT s.*, e.entryId AS entryId, e.position AS position
           FROM playlist_entries e JOIN songs s ON s.id = e.songId
           WHERE e.playlistId = :playlistId ORDER BY e.position""",
    )
    fun songs(playlistId: Long): Flow<List<PlaylistSongRow>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE entryId = :entryId")
    suspend fun setPosition(entryId: Long, position: Int)

    @Transaction
    suspend fun reorder(playlistId: Long, orderedEntryIds: List<Long>) {
        orderedEntryIds.forEachIndexed { index, id -> setPosition(id, index) }
    }
}

@Dao
interface SavedDao {
    @Upsert
    suspend fun upsert(item: SavedItemEntity)

    @Query("DELETE FROM saved_items WHERE url = :url")
    suspend fun delete(url: String)

    @Query("SELECT * FROM saved_items ORDER BY savedAt DESC")
    fun all(): Flow<List<SavedItemEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_items WHERE url = :url)")
    fun isSaved(url: String): Flow<Boolean>
}

@Dao
interface SearchHistoryDao {
    @Upsert
    suspend fun upsert(item: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}
