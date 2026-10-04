package app.tempo.music.data

import androidx.room.withTransaction
import app.tempo.music.data.db.MusicDatabase
import app.tempo.music.data.db.PlaylistEntity
import app.tempo.music.data.db.PlaylistEntryEntity
import app.tempo.music.data.db.SavedItemEntity
import app.tempo.music.data.db.SearchHistoryEntity
import app.tempo.music.data.db.SongEntity
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.MusicItem
import app.tempo.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class UserPlaylist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val thumbnails: List<String>,
)

data class PlaylistSong(val entryId: Long, val song: Song)

/** The user's own music: favourites, playlists, history and saved albums/artists. */
class LibraryRepository(private val db: MusicDatabase) {

    private val songs = db.songs()
    private val playlists = db.playlists()
    private val saved = db.saved()
    private val history = db.searchHistory()

    // ---- songs

    val likedSongs: Flow<List<Song>> = songs.liked().map { list -> list.map { it.toSong() } }
    val likedIds: Flow<Set<String>> = songs.likedIds().map { it.toSet() }

    fun recentlyPlayed(limit: Int = 50): Flow<List<Song>> =
        songs.recentlyPlayed(limit).map { list -> list.map { it.toSong() } }

    fun mostPlayed(limit: Int = 25): Flow<List<Song>> =
        songs.mostPlayed(limit).map { list -> list.map { it.toSong() } }

    suspend fun ensureSong(song: Song) {
        songs.insertIgnore(SongEntity.from(song))
        songs.updateMeta(
            id = song.id,
            title = song.title,
            artist = song.artist,
            artistUrl = song.artistUrl,
            album = song.album,
            thumbnailUrl = song.thumbnailUrl,
            durationSec = song.durationSec,
        )
    }

    suspend fun setLiked(song: Song, liked: Boolean) {
        ensureSong(song)
        songs.setLikedAt(song.id, if (liked) System.currentTimeMillis() else null)
    }

    suspend fun markPlayed(song: Song) {
        ensureSong(song)
        songs.markPlayed(song.id, System.currentTimeMillis())
    }

    suspend fun clearHistory() = songs.clearHistory()

    // ---- playlists

    val userPlaylists: Flow<List<UserPlaylist>> =
        combine(playlists.summaries(), playlists.thumbnails()) { summaries, thumbs ->
            val byPlaylist = thumbs.groupBy({ it.playlistId }, { it.thumbnailUrl })
            summaries.map { row ->
                UserPlaylist(
                    id = row.playlist.id,
                    name = row.playlist.name,
                    songCount = row.songCount,
                    thumbnails = byPlaylist[row.playlist.id].orEmpty().filterNotNull().distinct().take(4),
                )
            }
        }

    fun userPlaylist(id: Long): Flow<UserPlaylist?> =
        userPlaylists.map { list -> list.firstOrNull { it.id == id } }

    fun playlistSongs(id: Long): Flow<List<PlaylistSong>> =
        playlists.songs(id).map { rows -> rows.map { PlaylistSong(it.entryId, it.song.toSong()) } }

    suspend fun createPlaylist(name: String, initial: List<Song> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val id = playlists.insert(PlaylistEntity(name = name.trim().ifBlank { "New Playlist" }, createdAt = now, updatedAt = now))
        if (initial.isNotEmpty()) addToPlaylist(id, initial)
        return id
    }

    suspend fun renamePlaylist(id: Long, name: String) =
        playlists.rename(id, name.trim().ifBlank { "Playlist" }, System.currentTimeMillis())

    suspend fun deletePlaylist(id: Long) = playlists.delete(id)

    suspend fun addToPlaylist(id: Long, items: List<Song>) {
        if (items.isEmpty()) return
        db.withTransaction {
            items.forEach { ensureSong(it) }
            var position = playlists.maxPosition(id)
            val now = System.currentTimeMillis()
            playlists.insertEntries(
                items.map { PlaylistEntryEntity(playlistId = id, songId = it.id, position = ++position, addedAt = now) },
            )
            playlists.touch(id, now)
        }
    }

    suspend fun removeFromPlaylist(id: Long, entryId: Long) {
        db.withTransaction {
            playlists.deleteEntry(entryId)
            playlists.reorder(id, playlists.entries(id).map { it.entryId })
            playlists.touch(id, System.currentTimeMillis())
        }
    }

    suspend fun movePlaylistSong(id: Long, from: Int, to: Int) {
        db.withTransaction {
            val ids = playlists.entries(id).map { it.entryId }.toMutableList()
            if (from !in ids.indices || to !in ids.indices) return@withTransaction
            ids.add(to, ids.removeAt(from))
            playlists.reorder(id, ids)
            playlists.touch(id, System.currentTimeMillis())
        }
    }

    // ---- saved albums, playlists and artists

    val savedItems: Flow<List<MusicItem>> = saved.all().map { list -> list.map { it.toItem() } }

    fun isSaved(url: String): Flow<Boolean> = saved.isSaved(url)

    suspend fun save(item: MusicItem) {
        val now = System.currentTimeMillis()
        val entity = when (item) {
            is CollectionRef -> SavedItemEntity(item.url, item.type.name, item.title, item.subtitle, item.thumbnailUrl, now)
            is ArtistRef -> SavedItemEntity(item.url, "ARTIST", item.name, null, item.thumbnailUrl, now)
            is Song -> return
        }
        saved.upsert(entity)
    }

    suspend fun unsave(url: String) = saved.delete(url)

    private fun SavedItemEntity.toItem(): MusicItem =
        if (type == "ARTIST") {
            ArtistRef(url = url, name = title, thumbnailUrl = thumbnailUrl)
        } else {
            CollectionRef(
                url = url,
                title = title,
                subtitle = subtitle,
                thumbnailUrl = thumbnailUrl,
                type = runCatching { CollectionType.valueOf(type) }.getOrDefault(CollectionType.PLAYLIST),
            )
        }

    // ---- search history

    val recentSearches: Flow<List<String>> = history.recent(12).map { list -> list.map { it.query } }

    suspend fun addSearch(query: String) {
        if (query.isNotBlank()) history.upsert(SearchHistoryEntity(query.trim(), System.currentTimeMillis()))
    }

    suspend fun deleteSearch(query: String) = history.delete(query)

    suspend fun clearSearches() = history.clear()
}
