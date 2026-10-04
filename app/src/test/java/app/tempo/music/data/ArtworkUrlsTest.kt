package app.tempo.music.data

import app.tempo.music.data.youtube.ArtworkUrls
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkUrlsTest {

    @Test
    fun albumArtIsRequestedAtTheDrawnSize() {
        val url = "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"
        assertEquals(listOf("https://lh3.googleusercontent.com/abc=w300-h300-l90-rj"), ArtworkUrls.candidates(url, 150))
        assertEquals(listOf("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"), ArtworkUrls.candidates(url, 1050))
        assertEquals(listOf("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"), ArtworkUrls.candidates(url, 0))
        assertEquals(
            listOf("https://yt3.ggpht.com/avatar=w600-h600-l90-rj"),
            ArtworkUrls.candidates("https://yt3.ggpht.com/avatar=s88-c-k-c0x00ffffff-no-rj", 400),
        )
        assertEquals(
            listOf("https://yt3.googleusercontent.com/xyz=w600-h600-l90-rj"),
            ArtworkUrls.candidates("https://yt3.googleusercontent.com/xyz", 500),
        )
    }

    @Test
    fun videoStillsUseTheSharpestUnletterboxedVersion() {
        val small = "https://i.ytimg.com/vi/SjAgP7GwsGA/hqdefault.jpg?sqp=-oaymwEgCNACELwBSFXyq4qpAxIIARUAAIhCGAFwAcABBrgC8xg=&rs=A"
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/SjAgP7GwsGA/hq720.jpg",
                "https://i.ytimg.com/vi/SjAgP7GwsGA/maxresdefault.jpg",
                "https://i.ytimg.com/vi/SjAgP7GwsGA/mqdefault.jpg",
            ),
            ArtworkUrls.candidates(small, 1050),
        )
        assertEquals(listOf("https://i.ytimg.com/vi/SjAgP7GwsGA/mqdefault.jpg"), ArtworkUrls.candidates(small, 140))
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
            ArtworkUrls.candidates("https://i9.ytimg.com/vi_webp/dQw4w9WgXcQ/sddefault.webp", 600).first(),
        )
    }

    @Test
    fun otherImagesAreLeftAlone() {
        assertEquals(listOf("https://example.com/cover.jpg"), ArtworkUrls.candidates("https://example.com/cover.jpg", 500))
    }
}
