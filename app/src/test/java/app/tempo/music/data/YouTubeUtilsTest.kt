package app.tempo.music.data

import app.tempo.music.data.youtube.YouTubeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUtilsTest {

    @Test
    fun extractsVideoIds() {
        assertEquals("dQw4w9WgXcQ", YouTubeUtils.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals("dQw4w9WgXcQ", YouTubeUtils.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD"))
        assertEquals("dQw4w9WgXcQ", YouTubeUtils.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", YouTubeUtils.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeUtils.videoId("dQw4w9WgXcQ"))
        assertNull(YouTubeUtils.videoId("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun splitsArtistAndTitle() {
        assertEquals(
            "Choosin' Texas" to "Ella Langley",
            YouTubeUtils.splitTitle("Ella Langley - Choosin' Texas (Official Video)", "EllaLangleyVEVO"),
        )
        assertEquals(
            "Never Gonna Give You Up" to "Rick Astley",
            YouTubeUtils.splitTitle("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", "Rick Astley"),
        )
        assertEquals(
            "Here Comes The Sun - Remastered 2009" to "The Beatles",
            YouTubeUtils.splitTitle("Here Comes The Sun - Remastered 2009", "The Beatles - Topic"),
        )
        assertEquals("Song (feat. Someone)" to "Artist", YouTubeUtils.splitTitle("Song (feat. Someone) [Official Lyric Video]", "Artist"))
    }

    @Test
    fun upscalesGoogleImages() {
        assertEquals(
            "https://yt3.googleusercontent.com/abc=w544-h544-l90-rj",
            YouTubeUtils.upscale("https://yt3.googleusercontent.com/abc=w180-h180-l90-rj"),
        )
        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", YouTubeUtils.upscale("https://i.ytimg.com/vi/x/hqdefault.jpg"))
    }

    @Test
    fun cleansArtistNames() {
        assertEquals("Rick Astley", YouTubeUtils.cleanArtist("Rick Astley - Topic"))
        assertEquals("shakira", YouTubeUtils.cleanArtist("shakiraVEVO"))
    }
}
