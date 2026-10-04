@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Feeds test tones through the processor and measures how loud they come out. */
class EqualizerAudioProcessorTest {

    private val rate = 48_000

    private fun custom(vararg bands: Pair<Int, Float>) = EqSettings(
        enabled = true,
        preset = null,
        customGains = Eq.FLAT.toMutableList().apply { bands.forEach { (i, g) -> this[i] = g } },
    )

    private fun processor(settings: EqSettings, channels: Int = 2, encoding: Int = C.ENCODING_PCM_16BIT) =
        EqualizerAudioProcessor(EqualizerState(settings)).apply {
            configure(AudioFormat(rate, channels, encoding))
            flush()
        }

    /** Interleaved 16-bit frames; [right] defaults to the same tone as [left]. */
    private fun tone(left: Double, amplitude: Double, seconds: Double = 1.0, right: Double? = left): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val f = if (i % 2 == 0) left else right
            if (f == null) 0 else (amplitude * 32767 * sin(2 * PI * f * (i / 2) / rate)).roundToInt().toShort()
        }
    }

    private fun EqualizerAudioProcessor.run(input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        var written = 0
        input.toList().chunked(4096).forEach { chunk ->
            val buffer = ByteBuffer.allocateDirect(chunk.size * 2).order(ByteOrder.nativeOrder())
            chunk.forEach(buffer::putShort)
            buffer.flip()
            queueInput(buffer)
            val output = getOutput()
            while (output.hasRemaining()) out[written++] = output.getShort()
        }
        assertEquals(input.size, written)
        return out
    }

    /** RMS level of one channel in dB, skipping the filters' start-up transient. */
    private fun levelDb(samples: ShortArray, channel: Int = 0, from: Double = 0.3): Double {
        val values = samples.filterIndexed { i, _ -> i % 2 == channel && i / 2 >= rate * from }.map { it / 32768.0 }
        return 20 * log10(sqrt(values.sumOf { it * it } / values.size))
    }

    private fun gainDb(settings: EqSettings, frequency: Double): Double {
        val input = tone(frequency, 0.1)
        return levelDb(processor(settings).run(input)) - levelDb(input)
    }

    @Test
    fun flatIsBitExact() {
        val random = Random(7)
        val input = ShortArray(rate) { random.nextInt(-32768, 32768).toShort() }
        assertArrayEquals(input, processor(EqSettings(preset = EqPreset.FLAT)).run(input))
    }

    @Test
    fun turnedOffIsBitExactEvenWithBoosts() {
        val input = tone(62.0, 0.5)
        val off = EqSettings(enabled = false, preset = EqPreset.BASS_BOOSTER)
        assertArrayEquals(input, processor(off).run(input))
    }

    @Test
    fun boostingABandMakesItLouderThanTheRest() {
        val bass = custom(1 to 12f) // +12 dB at 62 Hz
        val atBand = gainDb(bass, 62.0)
        val elsewhere = gainDb(bass, 4_000.0)
        assertEquals(12.0, atBand - elsewhere, 0.5)

        // Absolute levels match the curve drawn on screen, including the anti-clipping headroom.
        val curve = EqCurve(bass.gains, rate.toDouble())
        assertEquals(curve.responseDb(62.0) + curve.preampDb, atBand, 0.3)
        assertEquals(curve.responseDb(4_000.0) + curve.preampDb, elsewhere, 0.3)
    }

    @Test
    fun cuttingABandMakesItQuieterAndLeavesTheRestAlone() {
        val cut = custom(5 to -12f) // -12 dB at 1 kHz
        assertEquals(-12.0, gainDb(cut, 1_000.0), 0.5)
        assertEquals(0.0, gainDb(cut, 62.0), 0.3)
        assertEquals(0.0, gainDb(cut, 16_000.0), 0.3)
    }

    @Test
    fun everyBandWorks() {
        for (band in 0 until Eq.BAND_COUNT) {
            val frequency = Eq.FREQUENCIES[band].toDouble()
            assertEquals("band ${Eq.label(band)}", -9.0, gainDb(custom(band to -9f), frequency), 0.6)
        }
    }

    @Test
    fun presetsChangeTheSound() {
        assertTrue(gainDb(EqSettings(preset = EqPreset.BASS_BOOSTER), 62.0) - gainDb(EqSettings(preset = EqPreset.BASS_BOOSTER), 4_000.0) > 4)
        assertTrue(gainDb(EqSettings(preset = EqPreset.TREBLE_REDUCER), 8_000.0) < -4)
    }

    @Test
    fun fullScaleBoostNeverClips() {
        val input = tone(62.0, 1.0) // already at full scale
        val out = processor(custom(0 to 12f, 1 to 12f, 2 to 12f)).run(input)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= (0.98 * 32767).roundToInt() + 1)
        // Still a clean tone, not crushed: level within a dB of the input.
        assertEquals(levelDb(input), levelDb(out), 1.0)
    }

    @Test
    fun channelsAreFilteredIndependently() {
        val input = tone(left = 1_000.0, amplitude = 0.3, right = null)
        val out = processor(custom(5 to 6f, 1 to -6f)).run(input)
        assertTrue(out.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 0.toShort() })
    }

    @Test
    fun changesApplyWhileAudioIsPlaying() {
        val state = EqualizerState(EqSettings(preset = EqPreset.FLAT))
        val processor = EqualizerAudioProcessor(state).apply {
            configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
        val input = tone(1_000.0, 0.2)
        assertEquals(levelDb(input), levelDb(processor.run(input)), 0.01)
        state.settings = custom(5 to -12f)
        assertEquals(levelDb(input) - 12, levelDb(processor.run(input)), 0.5)
        state.settings = EqSettings(preset = EqPreset.FLAT)
        assertEquals(levelDb(input), levelDb(processor.run(input)), 0.1)
    }

    @Test
    fun floatOutputIsEqualisedToo() {
        val processor = processor(custom(5 to -12f), encoding = C.ENCODING_PCM_FLOAT)
        val frames = rate
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { n ->
            val s = (0.2 * sin(2 * PI * 1_000 * n / rate)).toFloat()
            buffer.putFloat(s).putFloat(s)
        }
        buffer.flip()
        processor.queueInput(buffer)
        val out = processor.getOutput()
        val left = FloatArray(frames) { out.getFloat().also { out.getFloat() } }
        val tail = left.drop(rate / 3)
        val rms = sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
        assertEquals(20 * log10(0.2 / sqrt(2.0)) - 12, 20 * log10(rms), 0.5)
    }

    @Test
    fun presetsAreWellFormed() {
        EqPreset.entries.forEach { preset ->
            assertEquals(preset.title, Eq.BAND_COUNT, preset.gains.size)
            assertTrue(preset.title, preset.gains.all { abs(it) <= Eq.MAX_GAIN_DB })
        }
    }
}
