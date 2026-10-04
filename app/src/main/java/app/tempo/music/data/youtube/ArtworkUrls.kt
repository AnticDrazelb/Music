package app.tempo.music.data.youtube

/**
 * Picks artwork URLs sized for where they're shown. Stored URLs are whatever YouTube handed
 * out (often a 336x188 video still), so this runs at load time and also fixes songs already in
 * the library.
 */
object ArtworkUrls {

    private val googleSize = Regex("""=(?:w\d+-h\d+|s\d+)[^/]*$""")
    private val videoThumb = Regex("""^https?://(?:i\d?\.ytimg\.com|img\.youtube\.com)/vi(?:_webp)?/([A-Za-z0-9_-]{11})/""")

    /**
     * URLs to try in order for an image drawn [px] pixels wide (0 if unknown). The last one
     * always exists; earlier ones are sharper but may be missing for some videos.
     */
    fun candidates(url: String, px: Int): List<String> {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        if ("googleusercontent.com" in fixed || "ggpht.com" in fixed) {
            // Google's image CDN resizes on request: ask for (at least) the size we draw.
            val size = when {
                px <= 0 -> 1200
                px <= 300 -> 300
                px <= 600 -> 600
                else -> 1200
            }
            val sized = when {
                googleSize.containsMatchIn(fixed) -> googleSize.replace(fixed, "=w$size-h$size-l90-rj")
                '=' !in fixed -> "$fixed=w$size-h$size-l90-rj"
                else -> fixed
            }
            return listOf(sized)
        }
        val id = videoThumb.find(fixed)?.groupValues?.get(1) ?: return listOf(fixed)
        // Video stills are 16:9 and cropped to a square: 320x180 is plenty for list rows; larger
        // art uses the 1280x720 still. hqdefault/sddefault are skipped because they're letterboxed
        // with black bars that would show in the square crop.
        val base = "https://i.ytimg.com/vi/$id"
        return if (px in 1..200) listOf("$base/mqdefault.jpg")
        else listOf("$base/hq720.jpg", "$base/maxresdefault.jpg", "$base/mqdefault.jpg")
    }
}
