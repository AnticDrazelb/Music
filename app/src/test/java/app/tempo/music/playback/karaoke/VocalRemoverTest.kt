@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.karaoke

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Feeds stereo test signals through the vocal remover and measures what comes out. */
class VocalRemoverTest {

    private val rate = 44_100

    private fun processor(vocals: Float) = KaraokeAudioProcessor(KaraokeState().apply { this.vocals = vocals }).apply {
        configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    /** Interleaved 16-bit stereo; each channel is a sum of (frequency, amplitude) tones. */
    private fun signal(left: List<Pair<Double, Double>>, right: List<Pair<Double, Double>>, seconds: Double = 1.5): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val tones = if (i % 2 == 0) left else right
            val t = (i / 2).toDouble() / rate
            (tones.sumOf { (f, a) -> a * sin(2 * PI * f * t) } * 32767).roundToInt().toShort()
        }
    }

    /** Runs [input] through in uneven chunks, then drains the end of the stream. */
    private fun KaraokeAudioProcessor.run(input: ShortArray, endOfStream: Boolean = false): ShortArray {
        val out = ArrayList<Short>(input.size + 8192)
        fun collect() {
            val output = getOutput()
            while (output.hasRemaining()) out += output.getShort()
        }
        var i = 0
        var chunk = 1000
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            val buffer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (j in i until i + n) buffer.putShort(input[j])
            buffer.flip()
            queueInput(buffer)
            collect()
            i += n
            chunk = if (chunk == 1000) 3002 else 1000
        }
        if (endOfStream) {
            queueEndOfStream()
            collect()
            assertTrue(isEnded)
        }
        return out.toShortArray()
    }

    /** Level of [frequency] in one channel, in dB relative to full scale, after the remover has settled. */
    private fun toneDb(samples: ShortArray, channel: Int, frequency: Double, from: Double = 0.5): Double {
        val start = (rate * from).toInt()
        val frames = samples.size / 2 - start
        var re = 0.0
        var im = 0.0
        for (n in 0 until frames) {
            val x = samples[(start + n) * 2 + channel] / 32768.0
            re += x * cos(2 * PI * frequency * n / rate)
            im -= x * sin(2 * PI * frequency * n / rate)
        }
        return 20 * log10(2 * sqrt(re * re + im * im) / frames + 1e-12)
    }

    private val latency = VocalRemover(44_100, KaraokeState()).latency

    @Test
    fun fullVocalsIsTheInputDelayedBitForBit() {
        val random = Random(3)
        val input = ShortArray(rate * 2) { random.nextInt(-32768, 32768).toShort() }
        val out = processor(1f).run(input, endOfStream = true)
        assertEquals(input.size + latency * 2, out.size)
        assertTrue(out.take(latency * 2).all { it == 0.toShort() })
        assertArrayEquals(input, out.copyOfRange(latency * 2, out.size))
    }

    @Test
    fun removesTheCentredVoiceRange() {
        val centred = listOf(440.0 to 0.2, 1_500.0 to 0.1)
        val out = processor(0f).run(signal(centred, centred))
        for ((f, _) in centred) {
            val before = 20 * log10(centred.first { it.first == f }.second)
            assertTrue("$f Hz should drop by 25 dB", toneDb(out, 0, f) < before - 25)
            assertTrue(toneDb(out, 1, f) < before - 25)
        }
    }

    @Test
    fun keepsBassAndPannedInstruments() {
        // Centred bass, a centred voice, and a guitar on the left only.
        val left = listOf(55.0 to 0.2, 800.0 to 0.2, 2_000.0 to 0.2)
        val right = listOf(55.0 to 0.2, 800.0 to 0.2)
        val out = processor(0f).run(signal(left, right))
        val full = 20 * log10(0.2)
        assertEquals("bass", full, toneDb(out, 0, 55.0), 1.0)
        assertEquals("bass", full, toneDb(out, 1, 55.0), 1.0)
        assertEquals("guitar", full, toneDb(out, 0, 2_000.0), 1.5)
        assertTrue("guitar stays on the left", toneDb(out, 1, 2_000.0) < full - 30)
        assertTrue("voice", toneDb(out, 0, 800.0) < full - 20)
    }

    @Test
    fun vocalLevelSetsHowMuchIsLeft() {
        val voice = listOf(1_000.0 to 0.25)
        val half = processor(0.5f).run(signal(voice, voice))
        assertEquals(20 * log10(0.25 * 0.5), toneDb(half, 0, 1_000.0), 1.0)
    }

    @Test
    fun turningKaraokeOnAndOffMidSongIsSmooth() {
        val state = KaraokeState()
        val processor = KaraokeAudioProcessor(state).apply {
            configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
        val voice = listOf(500.0 to 0.3)
        val input = signal(voice, voice, seconds = 3.0)
        val third = input.size / 3 / 2 * 2
        val out = processor.run(input.copyOfRange(0, third)) +
            processor.run(input.copyOfRange(third, 2 * third).also { state.vocals = 0f }) +
            processor.run(input.copyOfRange(2 * third, input.size).also { state.vocals = 1f })

        // No sample-to-sample jump bigger than the tone itself makes (a click would).
        val maxStep = 0.3 * 2 * PI * 500 / rate * 32768 * 1.1
        for (i in 2 until out.size step 2) {
            assertTrue("jump at frame ${i / 2}", abs(out[i] - out[i - 2]) <= maxStep)
        }
        // And once back at full vocals it's exactly the original again.
        val tail = out.copyOfRange(out.size - rate, out.size)
        val original = input.copyOfRange(input.size - rate - latency * 2, input.size - latency * 2)
        assertArrayEquals(original, tail)
    }

    @Test
    fun monoPassesThrough() {
        val processor = KaraokeAudioProcessor(KaraokeState().apply { vocals = 0f })
        processor.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        assertFalse(processor.isActive)
    }

    @Test
    fun fftRoundTrips() {
        val n = 256
        val fft = Fft(n)
        val random = Random(1)
        val re = DoubleArray(n) { random.nextDouble() }
        val im = DoubleArray(n) { random.nextDouble() }
        val (r0, i0) = re.copyOf() to im.copyOf()
        fft.transform(re, im, inverse = false)
        fft.transform(re, im, inverse = true)
        for (k in 0 until n) {
            assertEquals(r0[k], re[k] / n, 1e-9)
            assertEquals(i0[k], im[k] / n, 1e-9)
        }
    }
}
