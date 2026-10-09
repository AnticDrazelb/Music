package app.tempo.music.playback.karaoke

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the lead vocal down in a stereo mix, like Apple Music Sing's vocal slider.
 *
 * Lead vocals are almost always mixed dead centre: the same sound, at the same level and in
 * phase, in both channels. Instruments are spread across the stereo field and reverb differs
 * between the channels. So the audio is cut into short overlapping frames (a short-time
 * Fourier transform), and in every frequency bin the left and right channels are compared.
 * Where they match, that part of the sound is centred and is turned down; where they differ it
 * is left alone. Unlike the classic "subtract the channels" trick this keeps panned
 * instruments where they were, and it leaves the bass and kick drum (also centred, but below
 * the voice) and the cymbals' top end untouched.
 *
 * It can't separate a voice from a centred instrument in the same bins, so it reduces vocals
 * rather than removing them perfectly; mono recordings are unaffected.
 *
 * Audio comes out exactly [latency] samples late, whether or not vocals are being reduced, so
 * moving the slider never makes the music jump. At full vocals the transform is skipped and
 * the output is the input, delayed.
 */
class VocalRemover(sampleRate: Int, private val state: KaraokeState) {

    /** Frame length: about 43 ms, fine enough for pitch, short enough for consonants. */
    private val size = if (sampleRate > 50_000) 4096 else 2048
    private val hop = size / 4
    private val bins = size / 2
    val latency: Int get() = size

    /** Square-root Hann, used both before and after the transform. */
    private val window = DoubleArray(size) { sqrt(0.5 - 0.5 * cos(2 * PI * it / size)) }
    /** Squared windows at 75% overlap add up to 2. */
    private val norm = 0.5

    /** How much of each bin may be removed: none for bass, all for the voice's range. */
    private val bandWeight = DoubleArray(bins + 1) { k ->
        val hz = k.toDouble() * sampleRate / size
        when {
            hz < LOW_CUT -> 0.0
            hz < LOW_FULL -> (hz - LOW_CUT) / (LOW_FULL - LOW_CUT)
            hz < HIGH_FULL -> 1.0
            hz < HIGH_CUT -> (HIGH_CUT - hz) / (HIGH_CUT - HIGH_FULL)
            else -> 0.0
        }
    }

    private val inL = DoubleArray(size)
    private val inR = DoubleArray(size)
    private val accL = DoubleArray(size)
    private val accR = DoubleArray(size)
    private val readyL = DoubleArray(hop)
    private val readyR = DoubleArray(hop)
    private var filled = size - hop
    private var readPos = 0

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val previousMask = DoubleArray(bins + 1)
    private val fft = Fft(size)

    /** Current vocal level, eased towards the setting a frame at a time. */
    private var level = 1.0

    /** Pushes one stereo sample in and writes the sample [latency] earlier to [out]. */
    fun process(left: Double, right: Double, out: DoubleArray) {
        out[0] = readyL[readPos]
        out[1] = readyR[readPos]
        readPos++
        inL[filled] = left
        inR[filled] = right
        filled++
        if (filled == size) {
            frame()
            System.arraycopy(inL, hop, inL, 0, size - hop)
            System.arraycopy(inR, hop, inR, 0, size - hop)
            filled = size - hop
            readPos = 0
        }
    }

    private fun frame() {
        val target = state.vocals.toDouble().coerceIn(0.0, 1.0)
        level += (target - level).coerceIn(-LEVEL_STEP, LEVEL_STEP)

        if (level >= 1.0) {
            // Overlap-adding the doubly windowed input is exactly what the transform would
            // return with nothing removed, so the two paths join seamlessly.
            for (i in 0 until size) {
                val w = window[i] * window[i] * norm
                accL[i] += inL[i] * w
                accR[i] += inR[i] * w
            }
            previousMask.fill(0.0)
        } else {
            // Left in the real part and right in the imaginary part: one transform for both.
            for (i in 0 until size) {
                re[i] = inL[i] * window[i]
                im[i] = inR[i] * window[i]
            }
            fft.transform(re, im, inverse = false)
            reduceCentre(1.0 - level)
            fft.transform(re, im, inverse = true)
            for (i in 0 until size) {
                val w = window[i] * norm / size
                accL[i] += re[i] * w
                accR[i] += im[i] * w
            }
        }

        System.arraycopy(accL, 0, readyL, 0, hop)
        System.arraycopy(accR, 0, readyR, 0, hop)
        System.arraycopy(accL, hop, accL, 0, size - hop)
        System.arraycopy(accR, hop, accR, 0, size - hop)
        accL.fill(0.0, size - hop, size)
        accR.fill(0.0, size - hop, size)
    }

    /** Turns down the centred part of every bin by up to [amount]. */
    private fun reduceCentre(amount: Double) {
        for (k in 0..bins) {
            val m = (size - k) % size
            // Unpack the two channels' spectra: L = (Z[k] + conj Z[N-k]) / 2, R = (Z[k] - conj Z[N-k]) / 2i.
            val lRe = (re[k] + re[m]) / 2
            val lIm = (im[k] - im[m]) / 2
            val rRe = (im[k] + im[m]) / 2
            val rIm = (re[m] - re[k]) / 2

            // 1 when both channels carry the same sound in phase, 0 or less when they don't.
            val energy = lRe * lRe + lIm * lIm + rRe * rRe + rIm * rIm
            val similarity = if (energy < 1e-18) 0.0 else 2 * (lRe * rRe + lIm * rIm) / energy
            var mask = if (similarity <= 0) 0.0 else similarity.pow8()
            // Smoothing over time stops single bins flickering in and out ("musical noise").
            mask = 0.5 * mask + 0.5 * previousMask[k]
            previousMask[k] = mask

            val cut = amount * mask * bandWeight[k]
            if (cut == 0.0) continue
            val midRe = (lRe + rRe) / 2 * cut
            val midIm = (lIm + rIm) / 2 * cut
            val nlRe = lRe - midRe
            val nlIm = lIm - midIm
            val nrRe = rRe - midRe
            val nrIm = rIm - midIm

            // Pack back: Z[k] = L + iR, Z[N-k] = conj(L) + i conj(R).
            re[k] = nlRe - nrIm
            im[k] = nlIm + nrRe
            if (m != k) {
                re[m] = nlRe + nrIm
                im[m] = nrRe - nlIm
            }
        }
    }

    private fun Double.pow8(): Double {
        val x2 = this * this
        val x4 = x2 * x2
        return x4 * x4
    }

    private companion object {
        const val LOW_CUT = 100.0
        const val LOW_FULL = 200.0
        const val HIGH_FULL = 8_000.0
        const val HIGH_CUT = 14_000.0
        /** About 80 ms from full vocals to none. */
        const val LEVEL_STEP = 0.12
    }
}

/** In-place radix-2 complex FFT. */
internal class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val levels = Integer.numberOfTrailingZeros(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    /** Unscaled in both directions. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - levels)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var t = 0
                for (j in start until start + half) {
                    val wr = cosTable[t]
                    val wi = sign * sinTable[t]
                    val k = j + half
                    val xr = re[k] * wr - im[k] * wi
                    val xi = re[k] * wi + im[k] * wr
                    re[k] = re[j] - xr
                    im[k] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    t += step
                }
                start += len
            }
            len *= 2
        }
    }
}
