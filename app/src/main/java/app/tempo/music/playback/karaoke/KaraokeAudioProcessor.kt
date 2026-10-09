@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.karaoke

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Runs [VocalRemover] on decoded stereo PCM before the equaliser. It stays in the chain for
 * every stereo song so turning karaoke on or off mid-song is seamless; with vocals at full
 * level it only delays the audio by [VocalRemover.latency] samples (about 43 ms).
 * Mono and multichannel audio pass through untouched.
 */
class KaraokeAudioProcessor(private val state: KaraokeState) : BaseAudioProcessor() {

    private var remover: VocalRemover? = null
    private var encoding = C.ENCODING_INVALID
    private val frame = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        ) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    override fun onFlush() {
        encoding = inputAudioFormat.encoding
        remover = VocalRemover(inputAudioFormat.sampleRate, state)
    }

    override fun onReset() {
        remover = null
        encoding = C.ENCODING_INVALID
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remover = remover ?: return
        val bytesPerFrame = if (encoding == C.ENCODING_PCM_16BIT) 4 else 8
        val frames = inputBuffer.remaining() / bytesPerFrame
        if (frames == 0) return
        val output = replaceOutputBuffer(frames * bytesPerFrame)
        repeat(frames) {
            if (encoding == C.ENCODING_PCM_16BIT) {
                val l = inputBuffer.getShort() / 32768.0
                val r = inputBuffer.getShort() / 32768.0
                remover.process(l, r, frame)
                output.putShort(toShort(frame[0]))
                output.putShort(toShort(frame[1]))
            } else {
                remover.process(inputBuffer.getFloat().toDouble(), inputBuffer.getFloat().toDouble(), frame)
                output.putFloat(frame[0].toFloat())
                output.putFloat(frame[1].toFloat())
            }
        }
        output.flip()
    }

    /** Lets the last [VocalRemover.latency] samples out after the song's final buffer. */
    override fun onQueueEndOfStream() {
        val remover = remover ?: return
        val bytesPerFrame = if (encoding == C.ENCODING_PCM_16BIT) 4 else 8
        val output = replaceOutputBuffer(remover.latency * bytesPerFrame)
        repeat(remover.latency) {
            remover.process(0.0, 0.0, frame)
            if (encoding == C.ENCODING_PCM_16BIT) {
                output.putShort(toShort(frame[0]))
                output.putShort(toShort(frame[1]))
            } else {
                output.putFloat(frame[0].toFloat())
                output.putFloat(frame[1].toFloat())
            }
        }
        output.flip()
    }

    private fun toShort(sample: Double): Short = (sample * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
}
