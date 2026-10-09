@file:OptIn(UnstableApi::class)

package app.tempo.music.playback.karaoke

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.tempo.music.playback.MediaItems
import java.nio.ByteOrder

/** Decodes a song's audio off the player, for analysis. */
object SongAudio {

    /**
     * Measures the singing in the first [maxMs] of [videoId] (from its download, the audio cache
     * or YouTube, like the player). Blocking; returns null if the audio can't be decoded.
     * [keepGoing] is checked often so the work stops when it's no longer wanted.
     */
    fun vocalActivity(
        sources: DataSource.Factory,
        videoId: String,
        maxMs: Long = 150_000,
        keepGoing: () -> Boolean = { true },
    ): VocalActivity? {
        val media = DataSourceMedia(sources, videoId)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(media)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var activity: VocalActivity? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (keepGoing()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > maxMs * 1000) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (out >= 0) {
                    val pcm = decoder.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    pcm.position(info.offset)
                    pcm.limit(info.offset + info.size)
                    val analysis = activity ?: VocalActivity(rate).also { activity = it }
                    val bytes = if (float) 4 else 2
                    while (pcm.remaining() >= bytes * channels) {
                        val l = if (float) pcm.getFloat().toDouble() else pcm.getShort() / 32768.0
                        var r = l
                        if (channels > 1) r = if (float) pcm.getFloat().toDouble() else pcm.getShort() / 32768.0
                        repeat(channels - 2) { if (float) pcm.getFloat() else pcm.getShort() }
                        analysis.add(l, r)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return activity
        } catch (e: Exception) {
            android.util.Log.w("SongAudio", "Couldn't analyse $videoId", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { media.close() }
        }
    }
}

/** Lets Android's MediaExtractor read through the player's [DataSource]. */
private class DataSourceMedia(private val sources: DataSource.Factory, private val videoId: String) : MediaDataSource() {
    private var source: DataSource? = null
    private var position = -1L
    private var length = C.LENGTH_UNSET.toLong()

    private fun openAt(at: Long) {
        runCatching { source?.close() }
        val s = sources.createDataSource()
        val remaining = s.open(DataSpec.Builder().setUri(MediaItems.uriFor(videoId)).setPosition(at).setKey(videoId).build())
        if (remaining != C.LENGTH_UNSET.toLong()) length = at + remaining
        source = s
        position = at
    }

    override fun readAt(at: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length != C.LENGTH_UNSET.toLong() && at >= length) return -1
        if (source == null || at != position) openAt(at)
        var read = 0
        while (read < size) {
            val n = source!!.read(buffer, offset + read, size - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        position += read
        return if (read == 0) -1 else read
    }

    override fun getSize(): Long {
        if (length == C.LENGTH_UNSET.toLong() && source == null) openAt(0)
        return length
    }

    override fun close() {
        runCatching { source?.close() }
        source = null
        position = -1
    }
}
