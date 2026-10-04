@file:OptIn(UnstableApi::class)

package app.tempo.music.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plays fake 20-second songs on a simulated clock, so the timing of the handoff and the
 * volume curves can be checked exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrossfaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(/* isAutoAdvancing = */ true)
    private val sources = FakeMediaSourceFactory(
        FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(SONG_MS * 1000),
    )
    private val tails = mutableListOf<ExoPlayer>()
    private var fadeMs = FADE_MS

    private lateinit var main: ExoPlayer
    private lateinit var crossfader: Crossfader

    private fun newPlayer() = TestExoPlayerBuilder(context)
        .setClock(clock)
        .setMediaSourceFactory(sources)
        .build()

    @Before
    fun setUp() {
        main = newPlayer()
        crossfader = Crossfader(main, createSecondary = { newPlayer().also(tails::add) }, durationMs = { fadeMs })
        crossfader.start()
    }

    @After
    fun tearDown() {
        crossfader.release()
        main.release()
    }

    private fun playQueue(vararg ids: String) {
        main.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).setUri("tempo://youtube/$it").build() })
        main.prepare()
        main.play()
    }

    @Test
    fun outgoingSongFadesOutWhileNextFadesIn() {
        var handoffAt = -1L
        main.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (new.mediaItemIndex == 1 && handoffAt < 0) handoffAt = old.positionMs
            }
        })
        playQueue("a", "b", "c")

        // The tail is loaded ahead of time, paused at the fade start.
        run(main).untilPositionAtLeast(SONG_MS - FADE_MS - 1_000)
        val tail = tails.single()
        assertEquals("a", tail.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_READY, tail.playbackState)
        assertFalse(tail.isPlaying)

        // At the fade start the main player moves on and the tail takes over song "a".
        run(main).untilMediaItemIndex(1)
        assertEquals((SONG_MS - FADE_MS).toDouble(), handoffAt.toDouble(), 50.0)
        assertTrue(crossfader.isFading)
        assertTrue(tail.isPlaying)
        assertEquals((SONG_MS - FADE_MS).toDouble(), tail.currentPosition.toDouble(), 100.0)
        assertTrue("incoming starts silent, was ${main.volume}", main.volume < 0.1f)
        assertTrue("outgoing starts full, was ${tail.volume}", tail.volume > 0.99f)

        // Equal-power curves: incoming = sin, outgoing = cos, so the combined power stays at 1.
        run(main).untilPositionAtLeast(FADE_MS / 4)
        assertEquals(0.383f, main.volume, 0.05f) // sin 22.5°
        assertEquals(0.924f, tail.volume, 0.05f) // cos 22.5°
        run(main).untilPositionAtLeast(FADE_MS / 2)
        assertEquals(0.707f, main.volume, 0.05f)
        assertEquals(0.707f, tail.volume, 0.05f)
        run(main).untilPositionAtLeast(FADE_MS * 3 / 4)
        assertEquals(0.924f, main.volume, 0.05f)
        assertEquals(0.383f, tail.volume, 0.05f)
        assertEquals(1f, main.volume * main.volume + tail.volume * tail.volume, 0.02f)

        // Then the tail stops and the new song plays on at full volume.
        runMainLooperUntil { !crossfader.isFading }
        assertEquals(1f, main.volume)
        assertEquals(Player.STATE_IDLE, tail.playbackState)
        assertEquals(1, main.currentMediaItemIndex)
        assertTrue(main.isPlaying)
        assertEquals(FADE_MS.toDouble(), main.currentPosition.toDouble(), 150.0)
    }

    @Test
    fun everySongInTheQueueIsCrossfaded() {
        playQueue("a", "b", "c")
        run(main).untilMediaItemIndex(1)
        runMainLooperUntil { !crossfader.isFading }
        run(main).untilMediaItemIndex(2)
        assertTrue(crossfader.isFading)
        assertEquals("b", tails.single().currentMediaItem?.mediaId)
    }

    @Test
    fun skippingCancelsAPreparedCrossfade() {
        playQueue("a", "b")
        run(main).untilPositionAtLeast(SONG_MS - FADE_MS - 1_000)
        val tail = tails.single()
        assertEquals(Player.STATE_READY, tail.playbackState)

        main.seekToNextMediaItem()
        assertFalse(crossfader.isFading)
        assertEquals(Player.STATE_IDLE, tail.playbackState)
        assertEquals(1f, main.volume)
    }

    @Test
    fun seekingMidFadeEndsItImmediately() {
        playQueue("a", "b")
        run(main).untilMediaItemIndex(1)
        assertTrue(crossfader.isFading)

        main.seekTo(10_000)
        assertFalse(crossfader.isFading)
        assertEquals(1f, main.volume)
        assertEquals(Player.STATE_IDLE, tails.single().playbackState)
    }

    @Test
    fun pausingMidFadeStopsTheOutgoingSong() {
        playQueue("a", "b")
        run(main).untilMediaItemIndex(1)
        assertTrue(crossfader.isFading)

        main.pause()
        assertFalse(crossfader.isFading)
        assertEquals(1f, main.volume)
        assertEquals(Player.STATE_IDLE, tails.single().playbackState)
    }

    @Test
    fun turnedOffMeansAPlainTransition() {
        fadeMs = 0
        playQueue("a", "b")
        run(main).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertTrue(tails.isEmpty())
        assertEquals(1, main.currentMediaItemIndex)
        assertEquals(1f, main.volume)
    }

    @Test
    fun repeatOneLoopsWithoutFading() {
        main.repeatMode = Player.REPEAT_MODE_ONE
        playQueue("a", "b")
        run(main).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertTrue(tails.isEmpty())
        assertEquals(0, main.currentMediaItemIndex)
    }

    @Test
    fun lastSongEndsNormally() {
        playQueue("a")
        run(main).untilState(Player.STATE_ENDED)
        assertTrue(tails.isEmpty())
        assertEquals(1f, main.volume)
    }

    private companion object {
        const val SONG_MS = 20_000L
        const val FADE_MS = 4_000L
    }
}
