package app.tempo.music.playback.karaoke

import kotlin.math.PI
import kotlin.math.cos

/**
 * Measures, every [STEP_MS], how much of the sound is a lead vocal: the share of energy in the
 * voice's range that is mixed dead centre (the same test [VocalRemover] uses). It rises when
 * the singer comes in and falls in instrumental parts, which is what lyrics are lined up with.
 *
 * Feed it decoded stereo audio with [add]; read the result with [activity].
 */
class VocalActivity(private val sampleRate: Int) {

    private val size = if (sampleRate > 50_000) 4096 else 2048
    private val hop = size / 2
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
    private val fft = Fft(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val left = DoubleArray(size)
    private val right = DoubleArray(size)
    private var filled = 0
    private var framesSeen = 0L

    private val voiceLow = binOf(VOICE_LOW_HZ)
    private val voiceHigh = binOf(VOICE_HIGH_HZ)
    private val allLow = binOf(60.0)
    private val allHigh = minOf(binOf(10_000.0), size / 2)

    private val centre = ArrayList<Double>()
    private val total = ArrayList<Double>()

    private fun binOf(hz: Double) = (hz * size / sampleRate).toInt().coerceIn(1, size / 2)

    /** Duration of audio taken in so far, in milliseconds. */
    val durationMs: Long get() = framesSeen * 1000 / sampleRate

    fun add(l: Double, r: Double) {
        left[filled] = l
        right[filled] = r
        filled++
        framesSeen++
        if (filled == size) {
            analyse(framesSeen - size)
            System.arraycopy(left, hop, left, 0, size - hop)
            System.arraycopy(right, hop, right, 0, size - hop)
            filled = size - hop
        }
    }

    private fun analyse(startFrame: Long) {
        for (i in 0 until size) {
            re[i] = left[i] * window[i]
            im[i] = right[i] * window[i]
        }
        fft.transform(re, im, inverse = false)
        var centred = 0.0
        var all = 0.0
        for (k in allLow..allHigh) {
            val m = (size - k) % size
            val lRe = (re[k] + re[m]) / 2
            val lIm = (im[k] - im[m]) / 2
            val rRe = (im[k] + im[m]) / 2
            val rIm = (re[m] - re[k]) / 2
            val energy = lRe * lRe + lIm * lIm + rRe * rRe + rIm * rIm
            all += energy
            if (k in voiceLow..voiceHigh && energy > 1e-18) {
                val similarity = 2 * (lRe * rRe + lIm * rIm) / energy
                if (similarity > 0) {
                    val s2 = similarity * similarity
                    centred += energy * s2 * s2
                }
            }
        }
        // The frame's centre time decides which step it counts towards.
        val step = ((startFrame + size / 2) * 1000 / sampleRate / STEP_MS).toInt()
        while (centre.size <= step) {
            centre += 0.0
            total += 0.0
        }
        centre[step] = centre[step] + centred
        total[step] = total[step] + all
    }

    /**
     * Vocal share for each [STEP_MS] step from the start: 0 (no centred voice-range sound, or
     * silence) to 1.
     */
    fun activity(): DoubleArray {
        val loudest = total.maxOrNull() ?: 0.0
        return DoubleArray(centre.size) { i ->
            // Near-silence says nothing about singing.
            if (total[i] < loudest * 1e-4) 0.0 else centre[i] / total[i]
        }
    }

    companion object {
        const val STEP_MS = 100L
        private const val VOICE_LOW_HZ = 200.0
        private const val VOICE_HIGH_HZ = 3_500.0
    }
}
