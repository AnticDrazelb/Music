@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tempo.music.MainActivity
import app.tempo.music.R
import app.tempo.music.TempoApp
import app.tempo.music.playback.eq.EqualizerRenderersFactory
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private val container by lazy { (application as TempoApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var crossfader: Crossfader
    private var session: MediaSession? = null
    private lateinit var queueStore: QueueStore

    private var autoplayJob: Job? = null
    private var lastAutoplaySeed: String? = null
    private val retried = mutableSetOf<String>()
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        queueStore = QueueStore(this)

        val dataSourceFactory = YouTubeDataSource.factory(
            container.httpClient,
            container.streamResolver,
            container.mediaCache,
            container.downloads::fileFor,
        )
        val music = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this, EqualizerRenderersFactory(this, container.equalizer.state))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(music, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(listener)

        crossfader = Crossfader(
            player,
            createSecondary = {
                // Only ever plays the outgoing song's last seconds; the main player keeps audio
                // focus, the wake lock and the media session.
                ExoPlayer.Builder(this, EqualizerRenderersFactory(this, container.equalizer.state))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .setAudioAttributes(music, /* handleAudioFocus = */ false)
                    .build()
            },
            durationMs = {
                val settings = container.settings
                if (!settings.crossfade.value || container.stopAfterCurrentSong.value) 0L
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
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(sessionCallback)
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session callback

    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItems::withPlaybackUri).toMutableList())

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

    // ------------------------------------------------------------------ player listener

    private val listener = object : Player.Listener {
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
