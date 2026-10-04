package app.tempo.music.data

import app.tempo.music.data.youtube.YouTubeUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Real titles and channels from YouTube radio and playlists. */
    @Test
    fun cleansRealWorldVideoTitles() {
        val cases = listOf(
            Triple("Rick Astley - Together Forever (Official Video) [4K Remaster]", "Rick Astley", "Together Forever" to "Rick Astley"),
            Triple("a-ha - Take On Me (Official Video) [4K]", "a-ha", "Take On Me" to "a-ha"),
            Triple("a-ha - Take On Me (Official Video) [Remastered in 4K]", "a-ha", "Take On Me" to "a-ha"),
            Triple("Tina Turner - The Best (Official Music Video) [HD Upgrade]", "Tina Turner", "The Best" to "Tina Turner"),
            Triple("Roxette - Listen to Your Heart (Official Video Remastered)", "Roxette", "Listen to Your Heart" to "Roxette"),
            Triple("Alphaville - Forever Young (Official Video HD)", "Alphaville (official)", "Forever Young" to "Alphaville"),
            Triple("Haddaway - What Is Love (Official 4K Video)", "DECADR", "What Is Love" to "Haddaway"),
            Triple("Journey - Don't Stop Believin' (Visualizer)", "Lost Elephant", "Don't Stop Believin'" to "Journey"),
            Triple("R.E.M. - Losing My Religion (Official HD Music Video)", "remhq", "Losing My Religion" to "R.E.M."),
            Triple("Mr.President - Coco Jamboo (1996) [Official Video]", "MELOMAN DANCE", "Coco Jamboo" to "Mr.President"),
            Triple("SNAP! - Rhythm Is A Dancer (Official 4K Music Video)", "SNAP!", "Rhythm Is A Dancer" to "SNAP!"),
            Triple("Starship - Nothing's Gonna Stop Us Now (Official Music Video) [HD]", "RHINO and Starship", "Nothing's Gonna Stop Us Now" to "Starship"),
            Triple("Laura Branigan - Self Control (Official Music Video)", "RHINO", "Self Control" to "Laura Branigan"),
            Triple("One Dance (feat. WizKid & Kyla) - Drake", "Drake", "One Dance (feat. WizKid & Kyla)" to "Drake"),
            Triple("Stella Lefty - Boston (Lyric Video)", "Stella Lefty", "Boston" to "Stella Lefty"),
            Triple("PinkPantheress - Stateside + Zara Larsson (Official Video)", "Pinkpantheress and Zara Larsson", "Stateside + Zara Larsson" to "PinkPantheress"),
            Triple("Ella Langley & Morgan Wallen - I Can’t Love You Anymore (Official Lyric Video)", "Ella Langley", "I Can’t Love You Anymore" to "Ella Langley & Morgan Wallen"),
            Triple("Bruno Mars - I Just Might [Official Music Video]", "Bruno Mars", "I Just Might" to "Bruno Mars"),
            Triple("sombr - Homewrecker (official video)", "sombr", "Homewrecker" to "sombr"),
            Triple("BTS (방탄소년단) ‘SWIM’ Official MV", "HYBE LABELS", "SWIM" to "BTS"),
            Triple("M.G.L. x Erika Isac - \"BACCARAT\"", "M.G.L. Official and Erika Isac", "BACCARAT" to "M.G.L. x Erika Isac"),
            Triple("Toosie Slide (Official Explicit Audio)", "Drake", "Toosie Slide" to "Drake"),
            Triple("DtMF (Letra)", "Bad Bunny", "DtMF" to "Bad Bunny"),
            Triple("Tití Me Preguntó (La Letra / Lyrics)", "Bad Bunny", "Tití Me Preguntó" to "Bad Bunny"),
            Triple("Perfect (Lyrics/ Lyrics video)", "Ed Sheeran", "Perfect" to "Ed Sheeran"),
            Triple("Queen - Bohemian Rhapsody (Live Aid 1985)", "Live Aid", "Bohemian Rhapsody (Live Aid 1985)" to "Queen"),
            Triple("Song Title | Official Music Video", "Artist", "Song Title" to "Artist"),
            Triple("Song Title Official Video HD", "Artist", "Song Title" to "Artist"),
            Triple("【MV】Song Title", "Artist", "Song Title" to "Artist"),
            Triple("Artist - Song (Official Audio)", null, "Song" to "Artist"),
            Triple("VICTONY -Still Standing (official lyrics video)", "Afrobeat Groove official", "Still Standing" to "VICTONY"),
            Triple("Wizkid & Asake - “Soft Life Mafia”  -(Official Lyrics Music Video 2026", "Vibe Africa", "Soft Life Mafia" to "Wizkid & Asake"),
            Triple("Jay-Z - Empire State of Mind", "JAYZVEVO", "Empire State of Mind" to "Jay-Z"),
            Triple("Taylor Swift - Patient Zero Official Music Video 4K High Definition", "Swiftie", "Patient Zero" to "Taylor Swift"),
            Triple("Adele\u00A0-\u00A0Hello", "Adele", "Hello" to "Adele"),
        )
        cases.forEach { (raw, uploader, expected) ->
            assertEquals(raw, expected, YouTubeUtils.splitTitle(raw, uploader))
        }
    }

    @Test
    fun leavesRealSongTitlesAlone() {
        val titles = listOf(
            "Thank You for the Music", "Video Killed the Radio Star", "Don't Stop Believin'", "Rock 'n' Roll",
            "Blinding Lights (Live On The 2020 MTV VMAs)", "blinding lights (slowed + reverb)", "Hello (Live at the NRJ Awards)",
            "I Knew It, I Knew You (from Toy Story 5)", "Perfect Duet (with Beyoncé)", "Bohemian Rhapsody - Remastered 2011",
            "Song - Live at Wembley", "Song (Acoustic Version)", "Everybody (Backstreet's Back)", "Spider-Man Theme",
            "Song -Remix", "Love Story (Taylor's Version)", "Something High", "Higher Love",
        )
        titles.forEach { title ->
            assertEquals(title, title to "Artist", YouTubeUtils.splitTitle(title, "Artist"))
            assertEquals(title, title, YouTubeUtils.stripNoise(title))
        }
    }

    @Test
    fun catalogueTitlesAreOnlySplitWhenTheyNameTheArtist() {
        assertEquals("I Just Might" to "Bruno Mars", YouTubeUtils.splitTitle("Bruno Mars - I Just Might", "Bruno Mars", artistIsReliable = true))
        assertEquals("Love - Hate" to "Artist", YouTubeUtils.splitTitle("Love - Hate", "Artist", artistIsReliable = true))
        assertEquals("Love - Hate" to "Artist", YouTubeUtils.splitTitle("Love - Hate [Official Video]", "Artist", artistIsReliable = true))
    }

    @Test
    fun recleansSongsSavedByOlderVersions() {
        // What 1.4.0 saved from YouTube Music playlists and radio.
        assertEquals("I Knew It, I Knew You (from Toy Story 5)" to "Taylor Swift",
            YouTubeUtils.recleanStored("Taylor Swift - I Knew It, I Knew You (from Toy Story 5)", "Taylor Swift"))
        assertEquals("Stateside + Zara Larsson" to "PinkPantheress",
            YouTubeUtils.recleanStored("PinkPantheress - Stateside + Zara Larsson", "Pinkpantheress and Zara Larsson"))
        assertEquals("Take On Me" to "a-ha", YouTubeUtils.recleanStored("Take On Me [Remastered in 4K]", "a-ha"))
        assertEquals("The Best" to "Tina Turner", YouTubeUtils.recleanStored("The Best [HD Upgrade]", "Tina Turner"))
        assertEquals("Forever Young" to "Alphaville", YouTubeUtils.recleanStored("Forever Young", "Alphaville (official)"))
        assertEquals("Song" to "Artist", YouTubeUtils.recleanStored("Artist - Song", "Unknown Artist"))
        // Already clean, or a real title with a dash: unchanged.
        assertEquals("Love - Hate" to "Someone", YouTubeUtils.recleanStored("Love - Hate", "Someone"))
        assertEquals("Bohemian Rhapsody - Remastered 2011" to "Queen", YouTubeUtils.recleanStored("Bohemian Rhapsody - Remastered 2011", "Queen"))
        assertEquals("Blinding Lights" to "The Weeknd", YouTubeUtils.recleanStored("Blinding Lights", "The Weeknd"))
        // Running it twice changes nothing more.
        val once = YouTubeUtils.recleanStored("Taylor Swift - I Knew It, I Knew You (from Toy Story 5)", "Taylor Swift")
        assertEquals(once, YouTubeUtils.recleanStored(once.first, once.second))
    }

    @Test
    fun tellsSongsFromClipsInVideoSearch() {
        listOf(
            "Taylor Swift - Patient Zero (Official Video)" to "TaylorSwiftVEVO",
            "Blank Space" to "Taylor Swift - Topic",
            "Perfect (Lyrics)" to "7clouds",
            "Song Title [Official Visualizer]" to "Artist",
        ).forEach { (title, channel) -> assertTrue(title, YouTubeUtils.looksLikeMusic(title, channel)) }
        listOf(
            "Drake Maye delivers a strike to Doubs for the TD" to "NFL",
            "Taylor Swift crashes Dakota Johnson's SNL monologue, Shocks Fans" to "NatFL",
            "The most INSANE Bohemian Rhapsody Flashmob you will ever see!!" to "Julien Cohen",
            "Bad Bunny's Apple Music Super Bowl Halftime Show" to "NFL and 2 more",
        ).forEach { (title, channel) -> assertFalse(title, YouTubeUtils.looksLikeMusic(title, channel)) }
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
        assertEquals("Taylor Swift", YouTubeUtils.cleanArtist("TaylorSwiftVEVO"))
        assertEquals("Alphaville", YouTubeUtils.cleanArtist("Alphaville (official)"))
        assertEquals("CANDLEBOX", YouTubeUtils.cleanArtist("CANDLEBOX OFFICIAL"))
        assertEquals("M.G.L.", YouTubeUtils.cleanArtist("M.G.L. Official"))
        assertEquals("NFL", YouTubeUtils.cleanArtist("NFL and 2 more"))
        assertEquals("BTS", YouTubeUtils.cleanArtist("BTS (방탄소년단)"))
        assertEquals("Unknown Artist", YouTubeUtils.cleanArtist(null))
    }
}
