package app.tempo.music.playback.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** A 10-band graphic equaliser: one peaking filter per octave, ±12 dB each. */
object Eq {
    val FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)
    val BAND_COUNT = FREQUENCIES.size
    const val MAX_GAIN_DB = 12f

    /** Bandwidth of one octave, so neighbouring bands meet at their half-gain points. */
    const val Q = 1.41

    val FLAT: List<Float> = List(BAND_COUNT) { 0f }

    fun label(index: Int): String {
        val f = FREQUENCIES[index]
        return if (f >= 1000) "${(f / 1000).toInt()}K" else f.toInt().toString()
    }
}

enum class EqPreset(val title: String, val gains: List<Float>) {
    FLAT("Flat", Eq.FLAT),
    BASS_BOOSTER("Bass Booster", listOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f)),
    BASS_REDUCER("Bass Reducer", listOf(-6f, -5f, -4f, -2.5f, -1f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE_BOOSTER("Treble Booster", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f)),
    TREBLE_REDUCER("Treble Reducer", listOf(0f, 0f, 0f, 0f, 0f, -1f, -2.5f, -4f, -5f, -6f)),
    VOCAL_BOOSTER("Vocal Booster", listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f)),
    LOUDNESS("Loudness", listOf(6f, 4f, 1f, 0f, 0f, 0f, 0f, 1f, 4f, 5f)),
    POP("Pop", listOf(-1f, 0f, 2f, 3f, 4f, 3f, 1f, 0f, -1f, -1f)),
    ROCK("Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    HIP_HOP("Hip-Hop", listOf(5f, 5f, 3f, 1f, -1f, -1f, 1f, 0f, 1f, 2f)),
    ELECTRONIC("Electronic", listOf(5f, 4f, 2f, 0f, -2f, 0f, 1f, 2f, 4f, 5f)),
    RNB("R&B", listOf(3f, 6f, 5f, 1f, -2f, -1f, 2f, 3f, 3f, 4f)),
    JAZZ("Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL("Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    ACOUSTIC("Acoustic", listOf(4f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f)),
    LATE_NIGHT("Late Night", listOf(-4f, -3f, -1f, 0f, 1f, 2f, 2f, 1f, -1f, -3f)),
    SMALL_SPEAKERS("Small Speakers", listOf(-4f, -3f, 0f, 3f, 3f, 2f, 1f, 0f, 1f, 2f)),
}

/**
 * What the equaliser should do. [preset] is null for the listener's own curve, kept in
 * [customGains] so it survives trying out presets.
 */
data class EqSettings(
    val enabled: Boolean = true,
    val preset: EqPreset? = EqPreset.FLAT,
    val customGains: List<Float> = Eq.FLAT,
) {
    /** The curve actually applied, in dB per band. */
    val gains: List<Float> get() = preset?.gains ?: customGains

    val isFlat: Boolean get() = !enabled || gains.all { it == 0f }

    val label: String get() = when {
        !enabled -> "Off"
        preset != null -> preset.title
        else -> "Custom"
    }
}

/** Biquad coefficients, normalised so a0 = 1. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Gain in dB at [frequency] Hz. */
    fun responseDb(frequency: Double, sampleRate: Double): Double {
        val w = 2 * PI * frequency / sampleRate
        // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
        val numRe = b0 + b1 * cos(w) + b2 * cos(2 * w)
        val numIm = -(b1 * sin(w) + b2 * sin(2 * w))
        val denRe = 1 + a1 * cos(w) + a2 * cos(2 * w)
        val denIm = -(a1 * sin(w) + a2 * sin(2 * w))
        return 20 * log10(hypot(numRe, numIm) / hypot(denRe, denIm))
    }

    companion object {
        val IDENTITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)

        /** Peaking EQ from the RBJ Audio EQ Cookbook. */
        fun peaking(frequency: Double, gainDb: Double, q: Double, sampleRate: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w0 = 2 * PI * frequency.coerceAtMost(sampleRate * 0.45) / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosW = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = (1 + alpha * a) / a0,
                b1 = -2 * cosW / a0,
                b2 = (1 - alpha * a) / a0,
                a1 = -2 * cosW / a0,
                a2 = (1 - alpha / a) / a0,
            )
        }
    }
}

/** The filters for one curve at one sample rate, plus the gain staging that keeps it clean. */
class EqCurve(val gainsDb: List<Float>, val sampleRate: Double) {
    /** One filter per band; a band at 0 dB is an exact identity. */
    val bands: List<Biquad> = gainsDb.mapIndexed { i, g ->
        if (g == 0f) Biquad.IDENTITY else Biquad.peaking(Eq.FREQUENCIES[i].toDouble(), g.toDouble(), Eq.Q, sampleRate)
    }

    fun responseDb(frequency: Double): Double = bands.sumOf { it.responseDb(frequency, sampleRate) }

    /** Highest point of the curve across the audible range. */
    val peakDb: Double by lazy {
        val top = minOf(20_000.0, sampleRate * 0.49)
        (0..240).maxOf { i -> responseDb(20.0 * (top / 20.0).pow(i / 240.0)) }
    }

    /**
     * Boosting a band makes loud passages exceed full scale, which would distort. The whole
     * signal is lowered by the curve's peak boost instead, so boosts reshape the sound without
     * clipping it. Cuts need no headroom.
     */
    val preampDb: Double get() = -peakDb.coerceAtLeast(0.0)
}
