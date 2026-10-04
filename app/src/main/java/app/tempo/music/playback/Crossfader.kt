@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfades between songs. ExoPlayer plays one item at a time, so the end of the outgoing
 * song is handed to a second, hidden player:
 *
 *  1. About [PREPARE_AHEAD_MS] before the fade, the [secondary] player loads the current song
 *     paused at the fade start position (usually straight from the audio cache).
 *  2. When [main] reaches that position (a precise [PlayerMessage]), the secondary starts
 *     playing the tail and the main player moves on to the next song at volume 0.
 *  3. Over the fade duration the two volumes follow equal-power curves; then the secondary
 *     stops.
 *
 * The media session only ever sees [main], so notifications and controls show the next song
 * as soon as the fade begins. Manual skips, seeks and pauses cancel a fade instantly; only
 * natural song endings are crossfaded.
 */
class Crossfader(
    private val main: ExoPlayer,
    private val createSecondary: () -> ExoPlayer,
    /** Fade length in milliseconds; 0 disables crossfading. */
    private val durationMs: () -> Long,
) {
    private val handler: HandlerWrapper = main.clock.createHandler(main.applicationLooper, null)
    private var secondary: ExoPlayer? = null

    private var preparedIndex = C.INDEX_UNSET
    private var preparedMediaId: String? = null
    private var fadeStartPositionMs = 0L
    private var pendingMessage: PlayerMessage? = null

    private var fading = false
    private var fadeStartedAt = 0L
    private var fadeLengthMs = 0L
    private var ownTransition = false

    /** True while two songs are overlapping. */
    val isFading: Boolean get() = fading

    private val poll = object : Runnable {
        override fun run() {
            maybePrepare()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val ramp = object : Runnable {
        override fun run() {
            if (!fading) return
            val progress = ((main.clock.elapsedRealtime() - fadeStartedAt).toFloat() / fadeLengthMs).coerceIn(0f, 1f)
            // Equal-power curves keep the overall loudness steady through the blend.
            main.volume = sin(progress * PI / 2).toFloat()
            secondary?.volume = cos(progress * PI / 2).toFloat()
            if (progress >= 1f) finishFade() else handler.postDelayed(this, RAMP_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (ownTransition) return
            // A manual skip (or the song ending without a fade) cancels anything in flight.
            finishFade()
            resetPreparation()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && !ownTransition) {
                finishFade()
                resetPreparation()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(poll)
            if (isPlaying) {
                handler.post(poll)
            } else if (main.playbackState != Player.STATE_BUFFERING) {
                // Paused, ended or lost audio focus. (Buffering the incoming song mid-fade is
                // fine: the outgoing tail keeps playing meanwhile.)
                stop()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) stop()
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) stop()
        }

        override fun onRepeatModeChanged(repeatMode: Int) = resetPreparation()

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !fading) resetPreparation()
        }

        override fun onPlayerError(error: PlaybackException) = stop()
    }

    private val tailListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // The outgoing tail failed: bring the new song straight up instead.
            if (fading) finishFade() else resetPreparation()
        }
    }

    fun start() {
        main.addListener(listener)
        if (main.isPlaying) handler.post(poll)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        main.removeListener(listener)
        pendingMessage?.cancel()
        secondary?.release()
        secondary = null
        main.volume = 1f
    }

    private fun maybePrepare() {
        val fadeMs = durationMs()
        if (fadeMs <= 0 || fading || !main.isPlaying) return
        val index = main.currentMediaItemIndex
        val item = main.currentMediaItem ?: return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < fadeMs * 3) return
        if (!hasDifferentNext()) return
        if (preparedIndex == index && preparedMediaId == item.mediaId) return

        val fadeStart = duration - fadeMs
        val remainingToFade = fadeStart - main.currentPosition
        // Too late to set up cleanly (e.g. the user seeked into the last seconds).
        if (remainingToFade < MIN_LEAD_MS) return
        if (remainingToFade > PREPARE_AHEAD_MS) return

        val player = secondary ?: createSecondary().also {
            it.addListener(tailListener)
            secondary = it
        }
        player.volume = 1f
        player.playWhenReady = false
        player.setMediaItem(item, fadeStart)
        player.prepare()

        preparedIndex = index
        preparedMediaId = item.mediaId
        fadeStartPositionMs = fadeStart
        pendingMessage?.cancel()
        pendingMessage = main.createMessage { _, _ -> beginFade(index, item.mediaId) }
            .setLooper(main.applicationLooper)
            .setPosition(index, fadeStart)
            .setDeleteAfterDelivery(true)
            .send()
    }

    private fun beginFade(index: Int, mediaId: String) {
        val tail = secondary
        val stillValid = durationMs() > 0 &&
            main.currentMediaItemIndex == index &&
            main.currentMediaItem?.mediaId == mediaId &&
            main.isPlaying &&
            hasDifferentNext()
        if (tail == null || !stillValid || tail.playbackState != Player.STATE_READY) {
            // Not ready in time: let the song end normally rather than glitch.
            resetPreparation()
            return
        }
        // Line the tail up with exactly where the main player is now.
        val position = main.currentPosition
        if (abs(position - fadeStartPositionMs) > SYNC_TOLERANCE_MS) tail.seekTo(position)
        tail.volume = 1f
        tail.play()

        fadeLengthMs = (main.duration - position).coerceIn(500, durationMs())
        fading = true
        fadeStartedAt = main.clock.elapsedRealtime()
        main.volume = 0f
        // Listener callbacks for this seek run synchronously, so the flag covers exactly them.
        ownTransition = true
        try {
            main.seekToNextMediaItem()
        } finally {
            ownTransition = false
        }
        handler.post(ramp)
    }

    /** Repeat-one and a single song on repeat-all loop the same item, which isn't faded. */
    private fun hasDifferentNext(): Boolean {
        val next = main.nextMediaItemIndex
        return main.repeatMode != Player.REPEAT_MODE_ONE && next != C.INDEX_UNSET && next != main.currentMediaItemIndex
    }

    /** Ends any fade and frees the tail player. */
    private fun stop() {
        finishFade()
        resetPreparation()
    }

    private fun finishFade() {
        handler.removeCallbacks(ramp)
        if (fading) {
            fading = false
            secondary?.let {
                it.stop()
                it.clearMediaItems()
            }
        }
        main.volume = 1f
    }

    private fun resetPreparation() {
        pendingMessage?.cancel()
        pendingMessage = null
        preparedIndex = C.INDEX_UNSET
        preparedMediaId = null
        if (!fading) secondary?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    companion object {
        const val POLL_MS = 250L
        const val RAMP_MS = 40L
        /** How long before the fade the outgoing tail is loaded. */
        const val PREPARE_AHEAD_MS = 12_000L
        private const val MIN_LEAD_MS = 1_500L
        private const val SYNC_TOLERANCE_MS = 60L
    }
}
