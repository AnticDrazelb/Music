@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.tempo.music.MainActivity
import app.tempo.music.R
import app.tempo.music.TempoApp
import app.tempo.music.playback.cast.CastLocks
import app.tempo.music.playback.cast.CastMediaItemConverter
import app.tempo.music.playback.cast.CastStreamServer
import app.tempo.music.playback.cast.CastSupport
import app.tempo.music.playback.eq.EqualizerRenderersFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays music, and lets Android Auto (or any media browser) browse the library: see [LibraryTree].
 */
class PlaybackService : MediaLibraryService() {

    private val container by lazy { (application as TempoApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Plays on the phone. */
    private lateinit var exoPlayer: ExoPlayer
    /** What the session controls: [exoPlayer], or a Cast device while casting. */
    private lateinit var player: Player
    private var castServer: CastStreamServer? = null
    private var castLocks: CastLocks? = null
    private lateinit var crossfader: Crossfader
    private var session: MediaLibrarySession? = null
    private val tree by lazy { LibraryTree(container) }
    private lateinit var queueStore: QueueStore

    private var autoplayJob: Job? = null
    private var lastAutoplaySeed: String? = null
    private val retried = mutableSetOf<String>()
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        queueStore = QueueStore(this)

        val dataSourceFactory = container.audioSource
        val music = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer = ExoPlayer.Builder(this, EqualizerRenderersFactory(this, container.equalizer.state, container.karaoke.state))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(music, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = castPlayer(dataSourceFactory) ?: exoPlayer
        player.addListener(listener)

        crossfader = Crossfader(
            exoPlayer,
            createSecondary = {
                // Only ever plays the outgoing song's last seconds; the main player keeps audio
                // focus, the wake lock and the media session.
                ExoPlayer.Builder(this, EqualizerRenderersFactory(this, container.equalizer.state, container.karaoke.state))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .setAudioAttributes(music, /* handleAudioFocus = */ false)
                    .build()
            },
            durationMs = {
                val settings = container.settings
                if (!settings.crossfade.value || container.stopAfterCurrentSong.value || isCasting()) 0L
                else settings.crossfadeSeconds.value * 1000L
            },
        )
        crossfader.start()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, sessionCallback)
            .setSessionActivity(openApp)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        restoreQueue()
        scope.launch {
            // Periodically remember the playback position.
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveQueue()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        scope.cancel()
        crossfader.release()
        session?.run {
            player.release()
            if (player !== exoPlayer) exoPlayer.release()
            release()
        }
        session = null
        castLocks?.release()
        castServer?.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ casting

    /**
     * A player that hands playback over to a Chromecast, Google TV or Cast speaker when one is
     * picked, and back to the phone when casting stops, keeping the queue and position. Null
     * without Google Play services, where only the phone (and Bluetooth) can play.
     */
    private fun castPlayer(dataSourceFactory: androidx.media3.datasource.DataSource.Factory): Player? = try {
        if (!CastSupport.init(this)) {
            null
        } else {
            val server = CastStreamServer(dataSourceFactory).also { castServer = it }
            castLocks = CastLocks(this)
            val remote = RemoteCastPlayer.Builder(this)
                .setMediaItemConverter(
                    CastMediaItemConverter(this, server) { id ->
                        when (container.downloads.fileFor(id)?.extension) {
                            "webm", "opus" -> "audio/webm"
                            "m4a", "mp4" -> "audio/mp4"
                            else -> container.streamResolver.cachedMimeType(id)
                        }
                    },
                )
                .build()
            CastPlayer.Builder(this)
                .setLocalPlayer(exoPlayer)
                .setRemotePlayer(remote)
                .build()
        }
    } catch (e: Exception) {
        android.util.Log.w("PlaybackService", "Cast unavailable", e)
        null
    }

    private fun isCasting(): Boolean =
        ::player.isInitialized && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    // ------------------------------------------------------------------ session callback

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Folders as tiles, songs as rows, unless a folder asks otherwise.
            val extras = LibraryTree.style(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            return Futures.immediateFuture(
                LibraryResult.ofItem(tree.root(), LibraryParams.Builder().setExtras(extras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            runCatching { tree.children(parentId) }.fold(
                onSuccess = { LibraryResult.ofItemList(it.page(page, pageSize), params) },
                onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            runCatching { tree.item(mediaId) }.getOrNull()?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val count = runCatching { tree.search(query).size }.getOrDefault(0)
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            runCatching { tree.searchResults(query) }.fold(
                onSuccess = { LibraryResult.ofItemList(tree.searchItems(query, it).page(page, pageSize), params) },
                onFailure = { LibraryResult.ofError(SessionError.ERROR_IO) },
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            // A song picked in the car plays its whole list; a voice request plays search results.
            val expanded = when {
                single == null -> null
                LibraryTree.parse(single.mediaId) != null -> tree.playFrom(single.mediaId)
                single.mediaId.isEmpty() && single.requestMetadata.searchQuery != null ->
                    tree.playSearch(single.requestMetadata.searchQuery.orEmpty())
                else -> null
            }
            expanded ?: MediaSession.MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = queueStore.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    saved.songs.map { MediaItems.from(it) },
                    saved.index.coerceIn(0, saved.songs.lastIndex),
                    saved.positionMs,
                ),
            )
        }
    }

    /** Turns items from a controller into playable ones: browse ids become their songs. */
    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        when {
            LibraryTree.parse(item.mediaId) != null -> tree.song(item.mediaId)?.let { MediaItems.from(it) }
            item.mediaId.isEmpty() -> null
            else -> MediaItems.withPlaybackUri(item)
        }
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): ImmutableList<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(this)
        val from = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
        return ImmutableList.copyOf(subList(from, (from + pageSize).coerceAtMost(size)))
    }

    // ------------------------------------------------------------------ player listener

    private val listener = object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
            // The TV streams from this phone, so keep it awake and on Wi-Fi while casting.
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                castServer?.start()
                castLocks?.acquire()
            } else {
                castLocks?.release()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val song = MediaItems.toSong(mediaItem)
            scope.launch(Dispatchers.IO) { container.library.markPlayed(song) }
            saveQueue()
            maybeExtendQueue()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) maybeExtendQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                consecutiveFailures = 0
                player.currentMediaItem?.mediaId?.let(retried::remove)
            } else {
                saveQueue()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) maybeExtendQueue(force = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            val id = item.mediaId
            if (id !in retried) {
                // Most often an expired/invalidated stream URL: resolve again and resume.
                retried += id
                container.streamResolver.invalidate(id)
                player.prepare()
                player.play()
                return
            }
            consecutiveFailures++
            if (consecutiveFailures < 3 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    /**
     * Apple Music style "Autoplay": when the queue is about to run out, append songs
     * similar to the one playing now.
     */
    private fun maybeExtendQueue(force: Boolean = false) {
        if (!container.settings.autoplay.value) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val current = player.currentMediaItem ?: return
        if (!force && remainingItems() >= 2) return
        val seed = current.mediaId
        if (autoplayJob?.isActive == true || seed == lastAutoplaySeed) return
        lastAutoplaySeed = seed

        autoplayJob = scope.launch {
            val songs = runCatching {
                withContext(Dispatchers.IO) { container.youtube.radio(seed) }
            }.getOrDefault(emptyList())
            val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val fresh = songs
                .filter { it.id !in existing }
                .filter { it.durationSec <= 600 } // skip long mixes and compilations
                .take(15)
            if (fresh.isEmpty()) return@launch
            val wasEnded = player.playbackState == Player.STATE_ENDED
            player.addMediaItems(fresh.map { MediaItems.from(it, autoplay = true) })
            if (wasEnded) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun remainingItems(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        var index = player.currentMediaItemIndex
        var count = 0
        while (true) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return count
            count++
        }
    }

    // ------------------------------------------------------------------ persistence

    private fun saveQueue() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val songs = (0 until player.mediaItemCount).map { MediaItems.toSong(player.getMediaItemAt(it)) }
        val saved = SavedQueue(songs, player.currentMediaItemIndex, player.currentPosition)
        scope.launch(Dispatchers.IO) { queueStore.save(saved) }
    }

    private fun restoreQueue() {
        val saved = queueStore.load() ?: return
        // Don't let the restored queue immediately trigger autoplay fetching.
        lastAutoplaySeed = saved.songs.getOrNull(saved.index)?.id
        player.setMediaItems(
            saved.songs.map { MediaItems.from(it) },
            saved.index.coerceIn(0, saved.songs.lastIndex),
            saved.positionMs,
        )
    }
}
