package app.tempo.music.data.download

import android.content.Context
import android.os.Environment
import app.tempo.music.data.LibraryRepository
import app.tempo.music.data.db.DownloadEntity
import app.tempo.music.data.db.MusicDatabase
import app.tempo.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadStatus(val state: DownloadState, val progress: Float)

data class DownloadItem(val song: Song, val status: DownloadStatus, val sizeBytes: Long)

/**
 * Offline copies of songs. Downloads run one at a time from a queue stored in Room, driven
 * by [DownloadService]; finished files live in the app's private music folder.
 */
class DownloadRepository(
    private val context: Context,
    db: MusicDatabase,
    private val library: LibraryRepository,
    private val downloader: AudioDownloader,
    private val scope: CoroutineScope,
) {
    private val dao = db.downloads()
    private val songs = db.songs()

    val directory: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"),
        "downloads",
    )

    /** Completed downloads by song id, read on the playback loader thread. */
    private val files = ConcurrentHashMap<String, File>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val running = AtomicBoolean(false)

    /** Live progress of the active download (kept out of the database to avoid churn). */
    private val liveProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    val statuses: StateFlow<Map<String, DownloadStatus>> =
        combine(dao.observeAll(), liveProgress) { rows, live ->
            rows.associate { row ->
                val state = runCatching { DownloadState.valueOf(row.state) }.getOrDefault(DownloadState.FAILED)
                val progress = when (state) {
                    DownloadState.DONE -> 1f
                    else -> live[row.songId] ?: if (row.totalBytes > 0) row.bytesDownloaded.toFloat() / row.totalBytes else 0f
                }
                row.songId to DownloadStatus(state, progress)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val items: Flow<List<DownloadItem>> = combine(dao.observeRows(), liveProgress) { rows, live ->
        rows.map { row ->
            val state = runCatching { DownloadState.valueOf(row.state) }.getOrDefault(DownloadState.FAILED)
            val progress = if (state == DownloadState.DONE) 1f else live[row.song.id]
                ?: if (row.totalBytes > 0) row.bytesDownloaded.toFloat() / row.totalBytes else 0f
            DownloadItem(row.song.toSong(), DownloadStatus(state, progress), row.totalBytes)
        }
    }

    /** Total size of finished downloads in bytes. */
    val totalBytes: Flow<Long> = dao.observeAll().map { rows ->
        rows.filter { it.state == DownloadEntity.DONE }.sumOf { it.totalBytes }
    }

    fun fileFor(songId: String): File? = files[songId]?.takeIf { it.exists() }

    fun isDownloaded(songId: String): Boolean = fileFor(songId) != null

    /** Call once at startup: indexes finished files and resumes interrupted downloads. */
    fun start() {
        scope.launch {
            val pending = withContext(Dispatchers.IO) {
                directory.mkdirs()
                dao.requeueInterrupted()
                dao.completed().forEach { row ->
                    val file = row.filePath?.let(::File)
                    if (file != null && file.exists()) files[row.songId] = file else dao.delete(row.songId)
                }
                dao.pendingCount()
            }
            if (pending > 0) DownloadService.start(context)
        }
    }

    /** Queues [items] for download; returns how many were newly queued. */
    suspend fun download(items: List<Song>): Int {
        val added = withContext(Dispatchers.IO) {
            var count = 0
            val now = System.currentTimeMillis()
            items.forEachIndexed { index, song ->
                val existing = dao.get(song.id)
                if (existing == null || existing.state == DownloadEntity.FAILED) {
                    library.ensureSong(song)
                    cancelled.remove(song.id)
                    // Keep the requested order: later songs get a slightly later timestamp.
                    dao.upsert(DownloadEntity(song.id, DownloadEntity.QUEUED, createdAt = now + index))
                    count++
                }
            }
            count
        }
        if (added > 0) DownloadService.start(context)
        return added
    }

    fun remove(songId: String) {
        cancelled += songId
        scope.launch(Dispatchers.IO) {
            files.remove(songId)?.delete()
            directory.listFiles { f -> f.name.startsWith("$songId.") }?.forEach { it.delete() }
            dao.delete(songId)
        }
    }

    fun removeAll() {
        scope.launch(Dispatchers.IO) {
            cancelled += dao.allIds()
            files.clear()
            directory.listFiles()?.forEach { it.delete() }
            dao.deleteAll()
        }
    }

    suspend fun hasPending(): Boolean = withContext(Dispatchers.IO) { dao.pendingCount() > 0 }

    /**
     * Downloads queued songs one at a time until the queue is empty. [onUpdate] receives the
     * current song, its progress (0..1) and how many songs are left, for the notification.
     */
    suspend fun runQueue(onUpdate: (song: Song?, progress: Float, remaining: Int) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        try {
            while (true) {
                val next = withContext(Dispatchers.IO) { dao.nextQueued() } ?: break
                val id = next.songId
                val song = withContext(Dispatchers.IO) { songs.get(id) }?.toSong()
                val remaining = withContext(Dispatchers.IO) { dao.pendingCount() }
                withContext(Dispatchers.IO) { dao.setState(id, DownloadEntity.DOWNLOADING) }
                onUpdate(song, 0f, remaining)

                var lastReport = 0L
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        downloader.download(
                            videoId = id,
                            dir = directory,
                            isCancelled = { id in cancelled },
                            onProgress = { done, total ->
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastReport > 400) {
                                    lastReport = now
                                    val fraction = done.toFloat() / total
                                    liveProgress.update { it + (id to fraction) }
                                    onUpdate(song, fraction, remaining)
                                }
                            },
                        )
                    }
                }
                liveProgress.update { it - id }
                withContext(Dispatchers.IO) {
                    result.onSuccess { file ->
                        if (id in cancelled) {
                            file.delete()
                        } else {
                            files[id] = file
                            dao.markDone(id, file.path, file.length(), System.currentTimeMillis())
                        }
                    }.onFailure {
                        if (id !in cancelled && dao.get(id) != null) dao.setState(id, DownloadEntity.FAILED)
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }
}
