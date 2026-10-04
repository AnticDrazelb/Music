package app.tempo.music.data.youtube

import org.schabi.newpipe.extractor.Image

object YouTubeUtils {

    private val videoIdPatterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})"""),
    )
    private val playlistIdPattern = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
    private val bareIdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")

    fun videoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (bareIdPattern.matches(url)) return url
        return videoIdPatterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    fun playlistId(url: String?): String? =
        url?.let { playlistIdPattern.find(it)?.groupValues?.get(1) }

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    fun channelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"
    fun playlistUrl(playlistId: String) = "https://www.youtube.com/playlist?list=$playlistId"
    fun musicPlaylistUrl(playlistId: String) = "https://music.youtube.com/playlist?list=$playlistId"
    private val channelIdPattern = Regex("""/channel/(UC[A-Za-z0-9_-]{10,})""")
    fun channelId(url: String?): String? = url?.let { channelIdPattern.find(it)?.groupValues?.get(1) }
    private val albumIdPattern = Regex("""/browse/(MPREb_[A-Za-z0-9_-]+)""")
    fun albumBrowseId(url: String?): String? = url?.let { albumIdPattern.find(it)?.groupValues?.get(1) }
    fun radioUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"

    /** Picks the largest thumbnail and asks Google's image CDN for a crisp square-ish size. */
    fun bestThumbnail(images: List<Image>?): String? {
        if (images.isNullOrEmpty()) return null
        val best = images.maxByOrNull { if (it.height > 0) it.height else it.width } ?: return null
        return upscale(best.url)
    }

    private val sizeParam = Regex("""=(w\d+-h\d+|s\d+)[^/]*$""")

    fun upscale(url: String?, size: Int = 544): String? {
        if (url.isNullOrBlank()) return url
        val fixed = if (url.startsWith("//")) "https:$url" else url
        val isGoogleCdn = "googleusercontent.com" in fixed || "ggpht.com" in fixed
        if (!isGoogleCdn) return fixed
        return if (sizeParam.containsMatchIn(fixed)) {
            sizeParam.replace(fixed, "=w$size-h$size-l90-rj")
        } else if (!fixed.contains('=')) {
            "$fixed=w$size-h$size-l90-rj"
        } else {
            fixed
        }
    }

    // ------------------------------------------------------------ titles & artists

    const val UNKNOWN_ARTIST = "Unknown Artist"

    /** "Rick Astley - Topic" -> "Rick Astley", "TaylorSwiftVEVO" -> "Taylor Swift", null -> "Unknown Artist". */
    fun cleanArtist(name: String?): String = cleanArtistOrNull(name) ?: UNKNOWN_ARTIST

    fun cleanArtistOrNull(name: String?): String? {
        if (name.isNullOrBlank()) return null
        var artist = name.trim().removePrefix("@")
            .replace(andMore, "")
            .removeSuffix(" - Topic")
        if (artist.length > 4 && artist.endsWith("VEVO", ignoreCase = true)) {
            artist = artist.dropLast(4).trim()
            // "TaylorSwiftVEVO" -> "Taylor Swift"
            if (' ' !in artist) artist = artist.replace(camelHump, " ")
        }
        artist = artist.replace(officialSuffix, "").replace(foreignAlias, "")
        return artist.trim().ifBlank { name.trim() }
    }

    private val andMore = Regex("""\s+and \d+ more$""")
    private val camelHump = Regex("""(?<=[a-z])(?=[A-Z])""")
    private val officialSuffix = Regex(
        """\s*(?:[(\[]\s*official\s*[)\]]|-?\s+official(?:\s+(?:artist\s+)?channel)?)$""",
        RegexOption.IGNORE_CASE,
    )
    /** "BTS (방탄소년단)" -> "BTS". */
    private val foreignAlias = Regex("""\s*\([^)]*[\u1100-\u11FF\u3040-\u30FF\u3130-\u318F\u4E00-\u9FFF\uAC00-\uD7AF][^)]*\)""")

    /** Words that describe the upload rather than the song; a bracket made only of these goes. */
    private val noiseWords = setOf(
        "official", "officiel", "officielle", "oficial", "ufficiale", "offizielles", "offizielle",
        "music", "musik", "musica", "música", "video", "videos", "vídeo", "videoclip", "clip", "mv", "m", "v",
        "audio", "áudio", "only", "lyric", "lyrics", "letra", "letras", "paroles", "testo", "la", "con", "de",
        "visualizer", "visualiser", "visualization", "hd", "hq", "sd", "uhd", "fhd", "2k", "4k", "8k",
        "remaster", "remastered", "remasterizado", "remasterizada", "restored", "upgrade", "upgraded", "in",
        "explicit", "clean", "performance", "animated", "color", "colour", "coded", "slide", "slideshow",
        "premiere", "full", "vinyl", "rip", "new",
    )
    private val noiseNumber = Regex("""\d{4}|\d{3,4}p|\d{2,3}fps""")
    private val bracket = Regex("""[(\[【「『〔{]([^()\[\]【】「」『』〔〕{}]*)[)\]】」』〕}]""")
    private val tokenSplit = Regex("""[\s/,+&|:._!\-–—]+""")

    /** Loose words at the end of a title ("Song Official Video", "Song HD"). */
    private val trailingNoise = setOf(
        "official", "oficial", "music", "video", "audio", "lyrics", "lyric", "hd", "hq", "4k", "8k", "uhd", "mv",
        "visualizer", "visualiser", "high", "definition", "quality", "ultra", "remastered", "remaster", "upgrade",
    )
    /** ...but only removed when one of these is among them, so "Thank You for the Music" stays. */
    private val strongTrailing = setOf("official", "oficial", "lyrics", "lyric", "hd", "hq", "4k", "8k", "uhd", "mv", "visualizer", "visualiser")

    private val trailingPipe = Regex("""\s*[|｜].*$""")
    /** A dash with a space on at least one side: "A - B", "A -B", "A- B" (but not "Jay-Z"). */
    private val dashSplit = Regex("""\s+[-–—~]\s*|\s*[-–—~]\s+""")
    /** An unclosed bracket at the end: "Song (Official Lyric Video". */
    private val openBracket = Regex("""\s*[(\[]([^()\[\]]*)$""")
    private val whitespace = Regex("""\s+""")
    private val unicodeSpace = Regex("""[\u00A0\u2000-\u200B\u202F\u205F\u3000]""")
    private val quotes = listOf("\"" to "\"", "“" to "”", "‘" to "’", "«" to "»", "'" to "'")

    /** A right-hand side like this is part of the title ("Song - Remastered 2009"), not "Artist - Title". */
    private val versionSuffix = Regex(
        """^(?:\d{4}\s+)?(?:re-?master|live\b|mono\b|stereo\b|demo\b|edit\b|radio\b|single\b|album\b|version\b|""" +
            """remix\b|mix\b|acoustic\b|instrumental\b|bonus\b|from\b|recorded\b|unplugged\b|extended\b|original\b|""" +
            """deluxe\b|feat\.|ft\.|with\b|interlude\b|reprise\b|sped\b|slowed\b)""",
        RegexOption.IGNORE_CASE,
    )
    /** K-pop style: "BTS (방탄소년단) 'SWIM' Official MV". */
    private val quotedTitle = Regex("""^(.+?)\s+[‘'"“«]([^’'"”»]+)[’'"”»]$""")
    private val videoMarker = Regex("""\b(?:m/?v|official)\b""", RegexOption.IGNORE_CASE)

    private fun isNoise(content: String): Boolean {
        val tokens = content.lowercase().split(tokenSplit).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { it in noiseWords || noiseNumber.matches(it) }
    }

    private fun unquote(text: String): String {
        val t = text.trim()
        quotes.forEach { (open, close) ->
            if (t.length > 2 && t.startsWith(open) && t.endsWith(close)) return t.substring(open.length, t.length - close.length).trim()
        }
        return t
    }

    /** Removes upload decorations: "(Official 4K Video)", "[HD Upgrade]", "| Official Audio", trailing "Official MV". */
    fun stripNoise(title: String): String {
        // No-break and thin spaces would hide "Artist - Title" from the patterns below.
        var t = unicodeSpace.replace(title, " ")
        t = bracket.replace(t) { m -> if (isNoise(m.groupValues[1])) " " else m.value }
        t = openBracket.replace(t) { m -> if (m.groupValues[1].isBlank() || isNoise(m.groupValues[1])) "" else m.value }
        t = trailingPipe.replace(t, "").ifBlank { t }
        val words = t.trim().split(whitespace)
        var keep = words.size
        fun isTrailingNoise(word: String) = word.lowercase().trim(',', '-', '–', ':').let { it in trailingNoise || noiseNumber.matches(it) }
        while (keep > 1 && isTrailingNoise(words[keep - 1])) keep--
        if (words.drop(keep).any { it.lowercase().trim(',', '-', '–', ':') in strongTrailing }) {
            t = words.take(keep).joinToString(" ")
        }
        t = whitespace.replace(t, " ").trim().trim('-', '–', '—', '|', ':', ',').trim()
        return unquote(t).ifBlank { title.trim() }
    }

    private fun normalized(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun isPartOf(name: String, channel: String): Boolean {
        val x = normalized(name)
        return x.length >= 2 && x in normalized(channel)
    }

    private fun sameArtist(a: String, b: String): Boolean {
        val x = normalized(a)
        val y = normalized(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 3 && (x in y || y in x))
    }

    /**
     * Turns a YouTube upload's title and channel into (title, artist):
     * "Haddaway - What Is Love (Official 4K Video)" by "DECADR" -> ("What Is Love", "Haddaway").
     * The channel is used to tell "Artist - Title" from "Title - Artist", and as the artist when
     * the title doesn't name one. Auto-generated "- Topic" uploads are already clean.
     *
     * [artistIsReliable] is for YouTube Music catalogue items, whose uploader is the real artist:
     * their titles are only split when one side names that artist.
     */
    fun splitTitle(rawTitle: String, uploader: String?, artistIsReliable: Boolean = false): Pair<String, String> {
        val channel = cleanArtistOrNull(uploader)
        val title = stripNoise(rawTitle)
        if (uploader?.trim()?.endsWith(" - Topic") == true) return title to (channel ?: UNKNOWN_ARTIST)

        val parts = dashSplit.split(title, limit = 2)
        if (parts.size == 2) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            val names = channel != null && (sameArtist(left, channel) || sameArtist(right, channel))
            val splittable = left.isNotEmpty() && right.isNotEmpty() && left.length <= 80 &&
                !versionSuffix.containsMatchIn(right) && (names || !artistIsReliable)
            if (splittable) {
                // "Title - Artist": only when the right side is the channel's name, not merely in it.
                val swapped = channel != null && isPartOf(right, channel) && !sameArtist(left, channel)
                val (artist, song) = if (swapped) right to left else left to right
                return unquote(song) to cleanArtist(artist)
            }
            return title to (channel ?: UNKNOWN_ARTIST)
        }
        if (!artistIsReliable && videoMarker.containsMatchIn(rawTitle)) {
            quotedTitle.find(title)?.let { m ->
                val artist = m.groupValues[1].trim()
                if (artist.length <= 60) return m.groupValues[2].trim() to cleanArtist(artist)
            }
        }
        return title to (channel ?: UNKNOWN_ARTIST)
    }

    /** Re-cleans a title and artist saved by an older version, which were already split once. */
    fun recleanStored(title: String, artist: String): Pair<String, String> =
        if (artist.isBlank() || artist == UNKNOWN_ARTIST) splitTitle(title, null)
        else splitTitle(title, artist, artistIsReliable = true)

    private val musicMarker = Regex(
        """\b(?:official\s+(?:music\s+)?(?:video|audio|visuali[sz]er|lyric)|lyrics?|visuali[sz]er|m/?v|audio|music\s+video)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** For plain YouTube search results: is this upload probably a song, rather than a clip about one? */
    fun looksLikeMusic(rawTitle: String, uploader: String?): Boolean {
        val channel = uploader.orEmpty()
        return channel.endsWith(" - Topic") || channel.contains("VEVO", ignoreCase = true) ||
            musicMarker.containsMatchIn(rawTitle) || dashSplit.containsMatchIn(rawTitle)
    }

    private val creditSplit = Regex("""\s*(?:,|&|\+|/|\bx\b|\bfeat\.?|\bft\.?|\bwith\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

    /** True when [artistName] is one of the artists credited in [credits] ("Drake & Sexyy Red", "DRAKE FT. X"). */
    fun credits(credits: String, artistName: String): Boolean {
        val wanted = normalized(artistName)
        return wanted.isNotEmpty() && credits.split(creditSplit).any { normalized(it) == wanted }
    }
}
