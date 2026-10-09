@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.eq

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import app.tempo.music.playback.karaoke.KaraokeState
import app.tempo.music.playback.karaoke.VocalRemover
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * End to end: a real ExoPlayer built with [EqualizerRenderersFactory] (as the playback service
 * builds it) plays a WAV file, and the PCM handed to the AudioTrack is captured and measured.
 * The file has a 62 Hz tone on the left channel and a 4 kHz tone on the right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EqualizerPlaybackTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val rate = 44_100
    private val captured = ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
        synchronized(captured) { captured.write(data) }
    }
    private lateinit var song: File
    private lateinit var songSamples: ShortArray

    @Before
    fun setUp() {
        ShadowAudioTrack.addAudioDataListener(listener)
        val frames = rate * 3
        songSamples = ShortArray(frames * 2) { i ->
            val f = if (i % 2 == 0) 62.0 else 4_000.0
            (0.25 * 32767 * sin(2 * PI * f * (i / 2) / rate)).roundToInt().toShort()
        }
        song = File(context.cacheDir, "tones.wav").apply { writeBytes(wav(songSamples)) }
    }

    @After
    fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(listener)
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate)
            .putInt(rate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(data)
        samples.forEach(buffer::putShort)
        return buffer.array()
    }

    /** Plays the file to the end and returns what the AudioTrack received. */
    private fun play(settings: EqSettings, karaoke: KaraokeState = KaraokeState()): ShortArray {
        synchronized(captured) { captured.reset() }
        val player = ExoPlayer.Builder(context, EqualizerRenderersFactory(context, EqualizerState(settings), karaoke))
            .setClock(FakeClock(/* isAutoAdvancing = */ true))
            .build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(song)))
        player.prepare()
        player.play()
        runMainLooperUntil(60_000) { player.playbackState == Player.STATE_ENDED || player.playerError != null }
        player.playerError?.let { throw it }
        player.release()
        val bytes = synchronized(captured) { captured.toByteArray() }
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
        return shorts
    }

    private fun levelDb(samples: ShortArray, channel: Int): Double {
        val values = samples.filterIndexed { i, _ -> i % 2 == channel && i / 2 >= rate / 2 }.map { it / 32768.0 }
        return 20 * log10(sqrt(values.sumOf { it * it } / values.size))
    }

    @Test
    fun flatPlaysTheSongUnchanged() {
        val out = play(EqSettings(preset = EqPreset.FLAT))
        // The karaoke stage delays everything by a fixed amount, so turning it on mid-song
        // doesn't make the music jump; the song itself arrives complete and unchanged.
        val delay = VocalRemover(rate, KaraokeState()).latency * 2
        assertEquals(songSamples.size + delay, out.size)
        // Media3's audio sink fades the first 20 ms in to avoid a pop; after that every sample
        // reaches the AudioTrack exactly as it is in the file.
        val afterFadeIn = rate / 20 * 2
        assertTrue((afterFadeIn until songSamples.size).all { out[delay + it] == songSamples[it] })
    }

    @Test
    fun karaokeLeavesInstrumentsOnOneSideAlone() {
        // The tones are on separate channels, like panned instruments, so there is no centred
        // vocal for karaoke to remove.
        val flat = play(EqSettings(preset = EqPreset.FLAT))
        val karaoke = play(EqSettings(preset = EqPreset.FLAT), KaraokeState().apply { vocals = 0f })
        assertEquals("62 Hz", 0.0, levelDb(karaoke, 0) - levelDb(flat, 0), 0.3)
        assertEquals("4 kHz", 0.0, levelDb(karaoke, 1) - levelDb(flat, 1), 0.3)
    }

    @Test
    fun bassBoostChangesWhatReachesTheSpeaker() {
        val flat = play(EqSettings(preset = EqPreset.FLAT))
        val boosted = play(EqSettings(preset = null, customGains = Eq.FLAT.toMutableList().apply { this[1] = 12f }))
        val bass = levelDb(boosted, 0) - levelDb(flat, 0)
        val treble = levelDb(boosted, 1) - levelDb(flat, 1)
        assertEquals("62 Hz vs 4 kHz", 12.0, bass - treble, 0.6)
    }

    @Test
    fun trebleCutQuietensOnlyTheTreble() {
        val flat = play(EqSettings(preset = EqPreset.FLAT))
        val cut = play(EqSettings(preset = null, customGains = Eq.FLAT.toMutableList().apply { this[7] = -12f }))
        assertEquals("4 kHz", -12.0, levelDb(cut, 1) - levelDb(flat, 1), 0.6)
        assertEquals("62 Hz", 0.0, levelDb(cut, 0) - levelDb(flat, 0), 0.3)
    }
}
