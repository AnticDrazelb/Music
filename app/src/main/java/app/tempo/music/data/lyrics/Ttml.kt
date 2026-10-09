package app.tempo.music.data.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Reads TTML lyrics, the format Apple Music uses (and Unison stores): each line is a `<p>`,
 * and with word timing each word or syllable is a `<span>` with its own begin and end.
 * Syllables of one word sit next to each other with no space between them. A `<span
 * ttm:role="x-bg">` holds background vocals, and `ttm:agent` says who sings the line.
 */
object Ttml {

    fun parse(ttml: String): List<LyricLine> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(ttml))

        val lines = mutableListOf<LyricLine>()
        val agents = mutableListOf<String>()
        var line: LineBuilder? = null
        // Inside a background-vocals span, and inside a timed word span.
        var backgroundDepth = 0
        var spanDepth = 0
        var word: WordBuilder? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> {
                        val agent = parser.attr("agent")
                        if (agent != null && agent !in agents) agents += agent
                        line = LineBuilder(
                            start = parseTime(parser.attr("begin")) ?: 0,
                            end = parseTime(parser.attr("end")),
                            // The second singer of a duet sings from the right, like Apple Music.
                            alignEnd = agent != null && agents.indexOf(agent) == 1,
                        )
                    }
                    "span" -> {
                        spanDepth++
                        if (parser.attr("role") == "x-bg") {
                            backgroundDepth = spanDepth
                        } else {
                            val begin = parseTime(parser.attr("begin"))
                            if (begin != null) word = WordBuilder(begin, parseTime(parser.attr("end")) ?: begin)
                        }
                    }
                    "br" -> line?.space(backgroundDepth > 0)
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    val current = line
                    when {
                        current == null -> Unit
                        word != null -> word!!.text.append(text)
                        text.isBlank() -> current.space(backgroundDepth > 0)
                        else -> current.untimed(text, backgroundDepth > 0)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "span" -> {
                        word?.let { w -> line?.add(w, backgroundDepth > 0) }
                        word = null
                        if (spanDepth == backgroundDepth) backgroundDepth = 0
                        spanDepth--
                    }
                    "p" -> {
                        line?.build()?.let(lines::add)
                        line = null
                    }
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it) == name }?.let(::getAttributeValue)

    /** "8.835", "2:55.459", "1:02:03.5" or "12.3s", in milliseconds. */
    internal fun parseTime(value: String?): Long? {
        val v = value?.trim()?.removeSuffix("s") ?: return null
        val parts = v.split(':')
        var seconds = 0.0
        for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        return Math.round(seconds * 1000)
    }

    private class WordBuilder(val start: Long, val end: Long) {
        val text = StringBuilder()
    }

    private class LineBuilder(val start: Long, val end: Long?, val alignEnd: Boolean) {
        private val main = mutableListOf<LyricWord>()
        private val background = mutableListOf<LyricWord>()
        private val plain = StringBuilder()

        private fun words(bg: Boolean) = if (bg) background else main

        fun add(w: WordBuilder, bg: Boolean) {
            val text = w.text.toString().replace(Regex("\\s+"), " ")
            if (text.isBlank()) return
            words(bg) += LyricWord(w.start, maxOf(w.end, w.start + 1), text.trimStart())
        }

        /** A word break: the space goes on the end of the previous word. */
        fun space(bg: Boolean) {
            val list = words(bg)
            if (list.isNotEmpty() && !list.last().text.endsWith(" ")) {
                list[list.lastIndex] = list.last().let { it.copy(text = it.text + " ") }
            }
            if (!bg && main.isEmpty()) plain.append(' ')
        }

        fun untimed(text: String, bg: Boolean) {
            if (!bg) plain.append(text)
        }

        fun build(): LyricLine? {
            if (main.isEmpty()) {
                val text = plain.toString().replace(Regex("\\s+"), " ").trim()
                return if (text.isEmpty() && background.isEmpty()) null else LyricLine(start, text, background = trimmed(background), alignEnd = alignEnd)
            }
            val words = trimmed(main)
            return LyricLine(
                timeMs = minOf(start, words.first().startMs),
                text = words.joinToString("") { it.text },
                words = words,
                background = trimmed(background),
                alignEnd = alignEnd,
            )
        }

        private fun trimmed(list: List<LyricWord>): List<LyricWord> =
            if (list.isEmpty()) list else list.dropLast(1) + list.last().copy(text = list.last().text.trimEnd())
    }
}
