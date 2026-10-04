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

    /** "Rick Astley - Topic" -> "Rick Astley", "TaylorSwiftVEVO" -> "TaylorSwift". */
    fun cleanArtist(name: String?): String {
        if (name.isNullOrBlank()) return "Unknown Artist"
        return name.trim()
            .removeSuffix(" - Topic")
            .replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { name.trim() }
    }

    private val noise = Regex(
        """\s*[(\[](?:\s*(?:official|music|lyrics?|audio|hd|hq|4k|visuali[sz]er|video|vídeo|oficial|officiel|clip|remaster(?:ed)?|\d{4}|full|version)\s*)+[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPipe = Regex("""\s*[|｜].*$""")
    private val dashSplit = Regex("""\s+[-–—]\s+""")

    fun stripNoise(title: String): String {
        val cleaned = noise.replace(title, "").let { t ->
            val withoutPipe = trailingPipe.replace(t, "")
            withoutPipe.ifBlank { t }
        }
        return cleaned.trim().ifBlank { title.trim() }
    }

    /**
     * Turns a regular YouTube video title like "Artist - Song (Official Video)" into
     * (title = "Song", artist = "Artist"). YouTube Music items are already clean and
     * should not be passed through this.
     */
    fun splitTitle(rawTitle: String, uploader: String?): Pair<String, String> {
        val uploaderClean = cleanArtist(uploader)
        if (uploader?.endsWith(" - Topic") == true) {
            return stripNoise(rawTitle) to uploaderClean
        }
        val stripped = stripNoise(rawTitle)
        val parts = dashSplit.split(stripped, limit = 2)
        if (parts.size == 2) {
            val artist = parts[0].trim()
            val title = parts[1].trim()
            if (artist.isNotEmpty() && title.isNotEmpty() && artist.length <= 60) {
                return title to artist
            }
        }
        return stripped to uploaderClean
    }
}
