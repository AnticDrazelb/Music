@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tempo.music.data.LibraryRepository
import app.tempo.music.data.model.Song
import app.tempo.music.data.youtube.YouTubeRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class QueueEntry(
    /** Index in the player's underlying playlist. */
    val index: Int,
    val song: Song,
    val isAutoplay: Boolean,
)

data class PlayerUiState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playWhenReady: Boolean = false,
    val durationMs: Long = 0,
    /** Every queue entry in play order. */
    val queue: List<QueueEntry> = emptyList(),
    /** Position of the current song within [queue]. */
    val queuePosition: Int = -1,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
) {
    val upNext: List<QueueEntry> get() = if (queuePosition < 0) queue else queue.drop(queuePosition + 1)
}

/**
 * UI-side handle on the playback service. Wraps a [MediaController] and exposes its state as
 * flows; all methods must be called on the main thread.
 */
class PlayerConnection(
    private val context: Context,
    private val library: LibraryRepository,
    private val youtube: YouTubeRepository,
    private val scope: CoroutineScope,
    private val stopAfterCurrentSong: MutableStateFlow<Boolean> = MutableStateFlow(false),
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short user-facing messages ("Added to queue", playback errors, ...). */
    val messages: SharedFlow<String> = _messages

    val likedIds: StateFlow<Set<String>> =
        library.likedIds.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _sleepTimerEnd = MutableStateFlow<Long?>(null)
    val sleepTimerEnd: StateFlow<Long?> = _sleepTimerEnd.asStateFlow()
    private var sleepJob: Job? = null

    /** Our own shuffle: upcoming songs are physically reordered so "Play Next" stays predictable. */
    private var shuffleOn = false
    private var originalOrder: Map<String, Int> = emptyMap()

    val positionMs: Long get() = controller?.currentPosition ?: 0L
    val bufferedPositionMs: Long get() = controller?.bufferedPosition ?: 0L

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                controllerFuture = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            val cause = generateSequence<Throwable>(error) { it.cause }
                .firstOrNull { it is java.io.IOException && !it.message.isNullOrBlank() && it.javaClass == java.io.IOException::class.java }
            // Errors from resolving the stream carry a readable message; others (e.g. an
            // expired URL) are retried or skipped silently by the service.
            cause?.message?.let { _messages.tryEmit(it) }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val count = c.mediaItemCount
        val timeline = c.currentTimeline
        val order = mutableListOf<Int>()
        if (!timeline.isEmpty) {
            var i = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && order.size < count) {
                order += i
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        val queue = order.map { index ->
            val item = c.getMediaItemAt(index)
            QueueEntry(index, MediaItems.toSong(item), MediaItems.isAutoplay(item))
        }
        val currentItem: MediaItem? = c.currentMediaItem
        _state.value = PlayerUiState(
            current = currentItem?.let(MediaItems::toSong),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            playWhenReady = c.playWhenReady,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: currentItem?.let { MediaItems.toSong(it).durationSec * 1000 }?.takeIf { it > 0 }
                ?: 0L,
            queue = queue,
            queuePosition = order.indexOf(c.currentMediaItemIndex),
            shuffle = shuffleOn || c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
        )
    }

    // ------------------------------------------------------------------ playback

    /** Name of the station now playing ("Naive Radio"), used when saving it as a playlist. */
    var stationName: String? = null
        private set

    /** The current song and everything after it, autoplay included: what "Save as Playlist" keeps. */
    fun queueSongs(): List<Song> = _state.value.let { s -> listOfNotNull(s.current) + s.upNext.map { it.song } }

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        stationName = null
        withController { c ->
            val list = if (shuffle) songs.shuffled() else songs
            val start = if (shuffle) 0 else startIndex.coerceIn(0, songs.lastIndex)
            shuffleOn = shuffle
            originalOrder = songs.withIndex().associate { it.value.id to it.index }
            c.shuffleModeEnabled = false
            c.setMediaItems(list.map { MediaItems.from(it) }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun playSong(song: Song) = play(listOf(song))

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else Util.handlePlayButtonAction(c)
    }

    fun skipNext() = withController { it.seekToNext() }
    fun skipPrevious() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun playQueueEntry(entry: QueueEntry) = withController { c ->
        if (entry.index < c.mediaItemCount) {
            c.seekToDefaultPosition(entry.index)
            Util.handlePlayButtonAction(c)
        }
    }

    fun playNext(song: Song) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(listOf(song))
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, MediaItems.from(song))
            _messages.tryEmit("Playing next")
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        if (songs.isEmpty()) return@withController
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            // Insert before autoplay suggestions so user picks come first.
            val firstAutoplay = (c.currentMediaItemIndex + 1 until c.mediaItemCount)
                .firstOrNull { MediaItems.isAutoplay(c.getMediaItemAt(it)) }
            val items = songs.map { MediaItems.from(it) }
            if (firstAutoplay != null) c.addMediaItems(firstAutoplay, items) else c.addMediaItems(items)
            _messages.tryEmit(if (songs.size == 1) "Added to queue" else "Added ${songs.size} songs to queue")
        }
    }

    /** Plays [song] followed by a station of similar songs. */
    fun startRadio(song: Song) {
        play(listOf(song))
        stationName = "${song.title} Radio"
        _messages.tryEmit("Starting station…")
        scope.launch {
            val songs = runCatching { youtube.radio(song.id) }.getOrElse {
                _messages.tryEmit("Couldn't load similar songs")
                return@launch
            }
            withController { c ->
                if (c.currentMediaItem?.mediaId != song.id) return@withController
                val existing = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
                val items = songs.filter { it.id !in existing }.take(40).map { MediaItems.from(it) }
                c.addMediaItems(items)
            }
        }
    }

    fun removeFromQueue(entry: QueueEntry) = withController { c ->
        if (entry.index < c.mediaItemCount && entry.index != c.currentMediaItemIndex) {
            c.removeMediaItem(entry.index)
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount && from != to) {
            c.moveMediaItem(from, to)
        }
    }

    fun clearUpNext() = withController { c ->
        val start = c.currentMediaItemIndex + 1
        if (start < c.mediaItemCount) c.removeMediaItems(start, c.mediaItemCount)
    }

    fun toggleShuffle() = withController { c ->
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        val start = c.currentMediaItemIndex + 1
        val end = c.mediaItemCount
        val upcoming = (start until end).map { c.getMediaItemAt(it) }
        if (!shuffleOn) {
            originalOrder = (0 until end).associate { c.getMediaItemAt(it).mediaId to it }
            if (upcoming.size > 1) c.replaceMediaItems(start, end, upcoming.shuffled())
            shuffleOn = true
        } else {
            if (upcoming.size > 1) {
                c.replaceMediaItems(start, end, upcoming.sortedBy { originalOrder[it.mediaId] ?: Int.MAX_VALUE })
            }
            shuffleOn = false
        }
        refresh()
    }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------------------------ library shortcuts

    fun toggleLike(song: Song) {
        val liked = song.id in likedIds.value
        scope.launch {
            library.setLiked(song, !liked)
            _messages.tryEmit(if (liked) "Removed from Favorites" else "Added to Favorites")
        }
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ sleep timer

    /** [minutes] = 0 cancels; -1 stops at the end of the current song. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleepTimerEnd.value = null
        stopAfterCurrentSong.value = false
        if (minutes == 0) {
            _messages.tryEmit("Sleep timer off")
            return
        }
        val c = controller ?: return
        val delayMs = if (minutes < 0) {
            (c.duration - c.currentPosition).coerceAtLeast(0)
        } else {
            minutes * 60_000L
        }
        val end = System.currentTimeMillis() + delayMs
        _sleepTimerEnd.value = end
        stopAfterCurrentSong.value = minutes < 0
        sleepJob = scope.launch {
            delay(delayMs)
            controller?.pause()
            _sleepTimerEnd.value = null
            stopAfterCurrentSong.value = false
        }
        _messages.tryEmit(if (minutes < 0) "Stopping after this song" else "Sleep timer: $minutes min")
    }
}
