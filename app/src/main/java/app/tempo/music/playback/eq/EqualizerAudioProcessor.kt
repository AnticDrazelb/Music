@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.eq

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.tempo.music.playback.karaoke.KaraokeAudioProcessor
import app.tempo.music.playback.karaoke.KaraokeState
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** The settings the audio threads read; written from the main thread. */
class EqualizerState(initial: EqSettings = EqSettings()) {
    @Volatile var settings: EqSettings = initial
}

/** Renderers whose audio output runs through the karaoke vocal remover and the equaliser. */
class EqualizerRenderersFactory(
    context: Context,
    private val equalizer: EqualizerState,
    private val karaoke: KaraokeState = KaraokeState(),
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(KaraokeAudioProcessor(karaoke), EqualizerAudioProcessor(equalizer)))
        .build()
}

/**
 * Applies the equaliser to decoded PCM before it reaches the AudioTrack, so it works the
 * same on every phone (Android's own Equalizer effect is missing or ignored on some).
 *
 * Each band is a peaking biquad run in double precision (Direct Form I, which tolerates
 * coefficients changing while audio flows). Boosts are balanced by lowering the overall level
 * ([EqCurve.preampDb]), and a peak limiter catches anything left over, so the output never
 * clips. Level changes are smoothed to avoid clicks while sliders move. A flat curve is a
 * bit-exact pass-through.
 */
class EqualizerAudioProcessor(private val equalizer: EqualizerState) : BaseAudioProcessor() {

    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var sampleRate = 0

    private var applied: EqSettings? = null
    private var filtering = false
    private val coefficients = Array(Eq.BAND_COUNT) { DoubleArray(5) } // b0 b1 b2 a1 a2
    /** Filter memory per band and channel: x1 x2 y1 y2. */
    private var memory = Array(0) { DoubleArray(4) }
    private var frame = DoubleArray(0)

    private var preamp = 1.0
    private var targetPreamp = 1.0
    private var limiterGain = 1.0
    private var smoothing = 0.0
    private var release = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioFormat.NOT_SET // other formats pass through untouched
        }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        memory = Array(Eq.BAND_COUNT * channels) { DoubleArray(4) }
        frame = DoubleArray(channels)
        smoothing = 1 - exp(-1.0 / (SMOOTHING_SECONDS * sampleRate))
        release = 1 - exp(-1.0 / (LIMITER_RELEASE_SECONDS * sampleRate))
        applied = null
        filtering = false
        preamp = 1.0
        targetPreamp = 1.0
        limiterGain = 1.0
    }

    override fun onReset() {
        channels = 0
        encoding = C.ENCODING_INVALID
        sampleRate = 0
        applied = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        refresh()
        val output = replaceOutputBuffer(size)
        if (!filtering && preamp == 1.0 && limiterGain == 1.0) {
            output.put(inputBuffer)
        } else if (encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2 * channels) {
                for (ch in 0 until channels) frame[ch] = inputBuffer.getShort() / 32768.0
                processFrame()
                for (ch in 0 until channels) {
                    output.putShort((frame[ch] * 32767).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        } else {
            while (inputBuffer.remaining() >= 4 * channels) {
                for (ch in 0 until channels) frame[ch] = inputBuffer.getFloat().toDouble()
                processFrame()
                for (ch in 0 until channels) output.putFloat(frame[ch].toFloat())
            }
        }
        output.flip()
    }

    /** Picks up new settings; called once per buffer. */
    private fun refresh() {
        val settings = equalizer.settings
        if (settings === applied) return
        applied = settings
        if (settings.isFlat) {
            filtering = false
            targetPreamp = 1.0
            return
        }
        val curve = EqCurve(settings.gains, sampleRate.toDouble())
        curve.bands.forEachIndexed { i, b ->
            coefficients[i][0] = b.b0
            coefficients[i][1] = b.b1
            coefficients[i][2] = b.b2
            coefficients[i][3] = b.a1
            coefficients[i][4] = b.a2
        }
        if (!filtering) memory.forEach { it.fill(0.0) }
        filtering = true
        targetPreamp = 10.0.pow(curve.preampDb / 20)
    }

    private fun processFrame() {
        var peak = 0.0
        for (ch in 0 until channels) {
            var s = frame[ch] * preamp
            if (filtering) {
                for (band in 0 until Eq.BAND_COUNT) {
                    val c = coefficients[band]
                    val m = memory[band * channels + ch]
                    var y = c[0] * s + c[1] * m[0] + c[2] * m[1] - c[3] * m[2] - c[4] * m[3]
                    if (abs(y) < 1e-25) y = 0.0 // keep silence from decaying into slow denormals
                    m[1] = m[0]
                    m[0] = s
                    m[3] = m[2]
                    m[2] = y
                    s = y
                }
            }
            frame[ch] = s
            peak = maxOf(peak, abs(s))
        }

        // Peak limiter: instant attack, smooth release.
        val target = if (peak > CEILING) CEILING / peak else 1.0
        limiterGain = if (target < limiterGain) target else limiterGain + (target - limiterGain) * release
        if (limiterGain > 0.999999) limiterGain = 1.0
        if (limiterGain != 1.0) for (ch in 0 until channels) frame[ch] *= limiterGain

        preamp += (targetPreamp - preamp) * smoothing
        if (abs(preamp - targetPreamp) < 1e-6) preamp = targetPreamp
    }

    private companion object {
        /** Just under full scale. */
        const val CEILING = 0.98
        const val SMOOTHING_SECONDS = 0.02
        const val LIMITER_RELEASE_SECONDS = 0.08
    }
}
