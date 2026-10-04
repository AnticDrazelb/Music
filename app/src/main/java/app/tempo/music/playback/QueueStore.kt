package app.tempo.music.playback

import android.content.Context
import app.tempo.music.data.model.Song
import app.tempo.music.data.youtube.YouTubeUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedQueue(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
)

/** Remembers the queue between launches so the mini player picks up where you left off. */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(queue: SavedQueue) {
        runCatching {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(json.encodeToString(SavedQueue.serializer(), queue))
            tmp.renameTo(file)
        }
    }

    fun load(): SavedQueue? = runCatching {
        if (!file.exists()) null else json.decodeFromString(SavedQueue.serializer(), file.readText())
    }.getOrNull()?.takeIf { it.songs.isNotEmpty() }?.let { queue ->
        // A queue saved by an older version may still have uncleaned video titles.
        queue.copy(
            songs = queue.songs.map { song ->
                val (title, artist) = YouTubeUtils.recleanStored(song.title, song.artist)
                song.copy(title = title, artist = artist)
            },
        )
    }
}
