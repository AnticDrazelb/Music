package app.tempo.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SavedItemEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun saved(): SavedDao
    abstract fun searchHistory(): SearchHistoryDao

    companion object {
        fun build(context: Context): MusicDatabase =
            Room.databaseBuilder(context, MusicDatabase::class.java, "tempo.db").build()
    }
}
