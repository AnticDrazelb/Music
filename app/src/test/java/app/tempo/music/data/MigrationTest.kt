package app.tempo.music.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.data.db.MusicDatabase
import app.tempo.music.data.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a database exactly as version 1.0.0 created it (from the exported v1 schema), fills
 * it with library data, then opens it with the current code to prove the upgrade keeps
 * everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @Test
    fun upgradeFromV1KeepsLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }

        createV1Database(dbFile)

        val db = Room.databaseBuilder(context, MusicDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val library = LibraryRepository(db)

        val playlists = library.userPlaylists.first()
        assertEquals(listOf("Road Trip"), playlists.map { it.name })
        assertEquals(2, playlists.single().songCount)
        assertEquals(listOf("a1"), library.likedSongs.first().map { it.id })
        assertEquals(listOf("b2", "a1"), library.recentlyPlayed().first().map { it.id })
        assertEquals(listOf("taylor swift"), library.recentSearches.first())

        // The new downloads table exists and works.
        assertTrue(db.downloads().allIds().isEmpty())
        db.downloads().upsert(
            app.tempo.music.data.db.DownloadEntity("a1", app.tempo.music.data.db.DownloadEntity.QUEUED, createdAt = 1),
        )
        assertEquals(listOf("a1"), db.downloads().allIds())

        // And the library keeps working after the upgrade.
        library.addToPlaylist(playlists.single().id, listOf(Song("c3", "Third", "Artist C")))
        assertEquals(3, library.userPlaylists.first().single().songCount)
        db.close()
    }

    private fun createV1Database(file: File) {
        val schema = Json.parseToJsonElement(
            File("schemas/app.tempo.music.data.db.MusicDatabase/1.json").readText(),
        ).jsonObject["database"]!!.jsonObject
        assertEquals(1, schema["version"]!!.jsonPrimitive.content.toInt())

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        schema["entities"]!!.jsonArray.forEach { entity ->
            val e = entity.jsonObject
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }

        // Library data as a 1.0.0 user would have it.
        db.execSQL(
            """INSERT INTO songs (id, title, artist, artistUrl, album, thumbnailUrl, durationSec, isVideo, likedAt, lastPlayedAt, playCount)
               VALUES ('a1', 'First', 'Artist A', NULL, NULL, 'https://img/a1', 200, 0, 1000, 5000, 3),
                      ('b2', 'Second', 'Artist B', NULL, NULL, NULL, 180, 0, NULL, 6000, 1)""",
        )
        db.execSQL("INSERT INTO playlists (id, name, createdAt, updatedAt) VALUES (1, 'Road Trip', 100, 200)")
        db.execSQL(
            """INSERT INTO playlist_entries (playlistId, songId, position, addedAt)
               VALUES (1, 'a1', 0, 300), (1, 'b2', 1, 301)""",
        )
        db.execSQL("INSERT INTO search_history (`query`, searchedAt) VALUES ('taylor swift', 400)")
        db.version = 1
        db.close()
    }
}
