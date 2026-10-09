package app.tempo.music.playback.karaoke

import app.tempo.music.data.lyrics.LyricEntry
import app.tempo.music.data.lyrics.LyricsAligner
import app.tempo.music.data.lyrics.LyricsRepository
import app.tempo.music.data.lyrics.LyricsTiming
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic band plays a song whose singer follows the lyrics, but [shiftMs] later than the
 * lyrics say (like a music video with a longer intro). The aligner must find the shift from
 * the audio alone.
 */
class LyricsAlignmentTest {

    private val rate = 22_050

    /** Album-timed lyrics: a 6.5 s intro, two verses, an instrumental break, a chorus. */
    private val lrc = """
        [00:06.54]Comin' out of my cage and I've been doin' just fine
        [00:10.09]Gotta, gotta be down because I want it all
        [00:13.60]It started out with a kiss, how did it end up like this?
        [00:17.40]It was only a kiss, it was only a kiss
        [00:21.30]Now I'm falling asleep and she's calling a cab
        [00:24.80]While he's having a smoke and she's taking a drag
        [00:28.40]Now they're going to bed and my stomach is sick
        [00:31.90]And it's all in my head, but she's touching his chest now
        [00:36.50]
        [00:44.00]Jealousy, turning saints into the sea
        [00:48.10]Swimming through sick lullabies
        [00:51.50]Choking on your alibis
        [00:55.00]But it's just the price I pay
        [00:58.60]Destiny is calling me
        [01:02.00]Open up my eager eyes
        [01:05.40]'Cause I'm Mr. Brightside
        [01:09.00]
        [01:18.00]I'm coming out of my cage and I've been doing just fine
        [01:21.50]Gotta, gotta be down because I want it all
    """.trimIndent()

    private val entries: List<LyricEntry> = LyricsTiming.entries(LyricsRepository.parseLrc(lrc), 95_000)

    private fun activityFor(shiftMs: Long, centredGuitar: Boolean = false, singer: Boolean = true): DoubleArray {
        val random = Random(11)
        val seconds = 95
        val words = entries.filterIsInstance<LyricEntry.Line>().flatMap { it.words }
            .map { (it.startMs + shiftMs) to (it.endMs + shiftMs) }
        val notes = words.map { 196.0 * Math.pow(2.0, random.nextInt(0, 8) / 12.0) }
        val activity = VocalActivity(rate)
        var wordIndex = 0
        var phase = 0.0
        val chords = listOf(130.81, 164.81, 196.0, 110.0)
        for (n in 0 until rate * seconds) {
            val t = n.toDouble() / rate
            val ms = (t * 1000).toLong()
            // Lead vocal: centred, harmonic, with vibrato, only while a word is sung.
            while (wordIndex < words.size && words[wordIndex].second < ms) wordIndex++
            var voice = 0.0
            if (singer && wordIndex < words.size && ms >= words[wordIndex].first) {
                val f = notes[wordIndex] * (1 + 0.01 * sin(2 * PI * 5.5 * t))
                phase += 2 * PI * f / rate
                for (h in 1..5) voice += sin(h * phase) / h
                voice *= 0.12
            }
            // Strummed guitar, mostly on the left (or centred), playing all the way through.
            val chord = chords[(t / 2).toInt() % chords.size]
            val strum = exp(-((t % 0.5) * 6))
            var guitar = 0.0
            for (m in listOf(1.0, 1.26, 1.5, 2.0)) guitar += sin(2 * PI * chord * m * t)
            guitar *= 0.05 * strum
            // Centred bass and drums: kick on the beat, a noisy snare on the off-beat.
            val bass = 0.12 * sin(2 * PI * chord / 2 * t)
            val beat = t % 0.5
            val kick = if (beat < 0.08) 0.3 * sin(2 * PI * 60 * beat) * exp(-beat * 40) else 0.0
            val snare = if (abs(beat - 0.25) < 0.06) 0.08 * (random.nextDouble() * 2 - 1) * exp(-(beat - 0.25).coerceAtLeast(0.0) * 30) else 0.0
            val centre = voice + bass + kick + snare
            val (gl, gr) = if (centredGuitar) 0.5 to 0.5 else 0.85 to 0.25
            activity.add(centre + guitar * gl, centre + guitar * gr)
        }
        return activity.activity()
    }

    private fun aligned(shiftMs: Long, hint: Long? = null, centredGuitar: Boolean = false, singer: Boolean = true) =
        LyricsAligner.offsetMs(entries, activityFor(shiftMs, centredGuitar, singer), VocalActivity.STEP_MS, hint)

    @Test
    fun findsALongerIntro() = assertEquals(3_500.0, aligned(3_500).toDouble(), 200.0)

    @Test
    fun findsAShorterIntro() = assertEquals(-2_000.0, aligned(-2_000).toDouble(), 200.0)

    @Test
    fun leavesWellTimedLyricsAlone() = assertEquals(0L, aligned(0))

    @Test
    fun copesWithACentredGuitar() = assertEquals(3_500.0, aligned(3_500, centredGuitar = true).toDouble(), 300.0)

    @Test
    fun usesTheLengthDifferenceAsAHint() = assertEquals(3_500.0, aligned(3_500, hint = 3_000).toDouble(), 600.0)

    @Test
    fun doesNothingWithoutASinger() = assertEquals(0L, aligned(3_500, singer = false))
}
