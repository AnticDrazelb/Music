package app.tempo.music.data.lyrics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lines lyrics up with the recording actually playing.
 *
 * Synced lyrics are timed to one release of a song, usually the album version. The audio
 * playing may be another (a music video with a longer intro, a radio edit), so every line can be
 * seconds early or late. Given how much singing there is in each [stepMs] slice of the audio
 * (from [app.tempo.music.playback.karaoke.VocalActivity]), this finds the shift that best
 * matches where the lyrics say singing happens.
 */
object LyricsAligner {
    const val MAX_SHIFT_MS = 20_000L
    private const val MIN_CORRELATION = 0.15
    private const val MIN_GAIN = 0.03
    /** Shifts smaller than this aren't worth correcting. */
    private const val MIN_SHIFT_MS = 300L

    /**
     * Milliseconds to add to every lyric time so it matches the audio (positive when the audio's
     * singing comes later), or 0 when there's no clear answer.
     *
     * [lengthHintMs] is how much longer the audio is than the recording the lyrics were timed to,
     * when both are known. Extra length is most often an intro, so a shift close to it is
     * preferred when it fits nearly as well as the best one.
     */
    fun offsetMs(entries: List<LyricEntry>, activity: DoubleArray, stepMs: Long, lengthHintMs: Long? = null): Long {
        val lines = entries.filterIsInstance<LyricEntry.Line>()
        if (lines.size < 4 || activity.size < 200) return 0
        val audio = smooth(activity, 5)
        val step = stepMs.toInt()
        val shifts = (-MAX_SHIFT_MS..MAX_SHIFT_MS step stepMs).toList()
        val scores = shifts.map { shift -> correlation(audio, singing(lines, audio.size, step, shift)) }

        val best = scores.indices.maxBy { scores[it] }
        val none = scores[shifts.indexOf(0L)]
        var chosen = best
        if (lengthHintMs != null && abs(lengthHintMs) in 500..MAX_SHIFT_MS) {
            val near = scores.indices.filter { abs(shifts[it] - lengthHintMs) <= 1_500 }.maxByOrNull { scores[it] }
            if (near != null && scores[near] >= scores[best] - MIN_GAIN) chosen = near
        }
        val shift = shifts[chosen]
        return if (scores[chosen] >= MIN_CORRELATION && scores[chosen] - none >= MIN_GAIN && abs(shift) >= MIN_SHIFT_MS) shift else 0
    }

    /** 1 where the lyrics (moved by [shiftMs]) say a word is being sung, else 0. */
    private fun singing(lines: List<LyricEntry.Line>, steps: Int, stepMs: Int, shiftMs: Long): DoubleArray {
        val out = DoubleArray(steps)
        for (line in lines) for (word in line.words) {
            val from = ((word.startMs + shiftMs) / stepMs).toInt().coerceAtLeast(0)
            val to = ((word.endMs + shiftMs) / stepMs).toInt().coerceAtMost(steps - 1)
            for (i in from..to) out[i] = 1.0
        }
        return smooth(out, 5)
    }

    private fun smooth(values: DoubleArray, width: Int): DoubleArray {
        val half = width / 2
        return DoubleArray(values.size) { i ->
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - half)..minOf(values.lastIndex, i + half)) {
                sum += values[j]
                n++
            }
            sum / n
        }
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        val meanA = a.average()
        val meanB = b.average()
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        return if (varA == 0.0 || varB == 0.0) 0.0 else cov / sqrt(varA * varB)
    }
}
