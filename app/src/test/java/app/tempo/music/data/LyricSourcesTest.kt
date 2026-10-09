package app.tempo.music.data

import app.tempo.music.data.lyrics.CaptionTrack
import app.tempo.music.data.lyrics.Captions
import app.tempo.music.data.lyrics.LyricEntry
import app.tempo.music.data.lyrics.Lyrics
import app.tempo.music.data.lyrics.LyricsRepository
import app.tempo.music.data.lyrics.LyricsTiming
import app.tempo.music.data.lyrics.Ttml
import app.tempo.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Word-timed lyrics from Unison (Apple Music TTML), YouTube captions, and the order sources are tried in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LyricSourcesTest {

    /** A made-up duet in the layout Apple Music and Unison use. */
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word" xml:lang="en"><head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/></metadata></head><body dur="1:05.000"><div begin="8.835" end="40.000" itunes:songPart="Verse"><p begin="8.835" end="12.339" itunes:key="L1" ttm:agent="v1"><span begin="8.835" end="9.155">Morning</span> <span begin="9.155" end="9.587">comes</span> <span begin="10.056" end="10.328">so</span> <span begin="10.328" end="11.222">slow</span><span begin="11.222" end="11.475">ly</span> <span ttm:role="x-bg"><span begin="11.500" end="11.800">(so</span> <span begin="11.800" end="12.300">slowly)</span></span></p><p begin="12.339" end="14.476" itunes:key="L2" ttm:agent="v2"><span begin="12.339" end="12.518">I</span> <span begin="12.518" end="12.915">know,</span> <span begin="13.203" end="14.476">I know</span></p><p begin="1:02.000" end="1:04.500" itunes:key="L3" ttm:agent="v1"><span begin="1:02.000" end="1:02.500">Good</span><span begin="1:02.500" end="1:03.000">night</span></p></div></body></tt>
    """.trimIndent()

    @Test
    fun readsAppleStyleWordTiming() {
        val lines = Ttml.parse(ttml)
        assertEquals(listOf("Morning comes so slowly", "I know, I know", "Goodnight"), lines.map { it.text })
        val first = lines[0]
        assertEquals(listOf("Morning ", "comes ", "so ", "slow", "ly"), first.words.map { it.text })
        assertEquals(8_835L, first.timeMs)
        assertEquals(11_475L, first.words.last().endMs)
        assertEquals("(so slowly)", first.background.joinToString("") { it.text })
        assertFalse(first.alignEnd)
        assertTrue("second singer on the right", lines[1].alignEnd)
        assertEquals(62_000L, lines[2].timeMs)
        assertEquals(1_000L, Ttml.parseTime("1s"))
        assertEquals(3_723_500L, Ttml.parseTime("1:02:03.5"))
    }

    @Test
    fun wordTimedLinesKeepTheirBackingVocals() {
        val entries = LyricsTiming.entries(Ttml.parse(ttml), 65_000).filterIsInstance<LyricEntry.Line>()
        assertTrue(entries.all { it.exact })
        assertEquals(12_300L, entries[0].endMs) // the backing vocals end last
        assertEquals("(so slowly)", entries[0].background.joinToString("") { it.text })
        assertTrue(entries[1].alignEnd)
    }

    @Test
    fun readsLyricCaptions() {
        val json3 = """
            {"events":[
              {"tStartMs":0,"dDurationMs":4000,"segs":[{"utf8":"[Music]"}]},
              {"tStartMs":10200,"dDurationMs":3000,"segs":[{"utf8":"♪ COMING DOWN "},{"utf8":"THE ROAD ♪"}]},
              {"tStartMs":13500,"dDurationMs":3000,"segs":[{"utf8":"ON A\nSUNDAY"}]},
              {"tStartMs":17000,"dDurationMs":1000}
            ]}
        """.trimIndent()
        val lines = Captions.parseJson3(json3)
        assertEquals(listOf(10_200L, 13_500L), lines.map { it.timeMs })
        assertEquals(listOf("Coming down the road", "On a sunday"), lines.map { it.text })
        assertEquals(1.0, Captions.overlap(lines, "Coming down the road on a Sunday morning"), 0.01)
        assertEquals("https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=json3", Captions.json3Url("https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=vtt"))
    }

    // ---------------------------------------------------------------- source order

    private val lrclibJson = Json.encodeToString(
        kotlinx.serialization.json.JsonArray.serializer(),
        buildJsonArray {
            add(buildJsonObject {
                put("trackName", "Road Song")
                put("artistName", "The Band")
                put("duration", 200.0)
                put("syncedLyrics", (1..8).joinToString("\n") { "[00:${10 + it * 3}.00]Coming down the road number $it" })
            })
        },
    )

    private fun unisonJson(videoId: String) = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("success", true)
            put("data", buildJsonObject {
                put("videoId", videoId)
                put("format", "ttml")
                put("syncType", "richsync")
                put("duration", 65)
                put("lyrics", ttml.replace("</div>", (4..8).joinToString("") { "<p begin=\"${20 + it}.0\" end=\"${20 + it}.5\"><span begin=\"${20 + it}.0\" end=\"${20 + it}.5\">Line$it</span></p>" } + "</div>"))
            })
        },
    )

    private fun client(unisonByVideo: Boolean, unisonBySong: Boolean, captions: String? = null, calls: MutableList<String> = mutableListOf()) =
        OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            calls += url.host + url.encodedPath
            val (code, body) = when {
                url.host == "unison.boidu.dev" && url.queryParameter("v") != null ->
                    if (unisonByVideo) 200 to unisonJson(url.queryParameter("v")!!) else 404 to "{\"success\":false}"
                url.host == "unison.boidu.dev" -> if (unisonBySong) 200 to unisonJson("someOtherVideo") else 404 to "{\"success\":false}"
                url.host == "lrclib.net" -> 200 to lrclibJson
                url.host == "captions.test" -> if (captions != null) 200 to captions else 404 to ""
                else -> 404 to ""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private val song = Song("abcdefghijk", "Road Song", "The Band", durationSec = 200)

    private fun repo(client: OkHttpClient, tracks: List<CaptionTrack> = emptyList()) =
        LyricsRepository(client, kotlin.io.path.createTempDirectory("lyrics").toFile(), "Tempo/test") { tracks }

    @Test
    fun prefersUnisonForThisVideo() = runBlocking<Unit> {
        val lyrics = repo(client(unisonByVideo = true, unisonBySong = true)).lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.UNISON, lyrics.source)
        assertTrue(lyrics.matchesRecording)
        assertTrue(lyrics.lines.first().words.isNotEmpty())
    }

    @Test
    fun usesUnisonWordTimingForTheSongButLinesItUp() = runBlocking<Unit> {
        val lyrics = repo(client(unisonByVideo = false, unisonBySong = true)).lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.UNISON, lyrics.source)
        assertFalse("another recording, so the audio decides the timing", lyrics.matchesRecording)
        assertEquals(65.0, lyrics.durationSec!!, 0.0)
    }

    @Test
    fun usesTheVideosOwnCaptionsWhenTheyMatchTheLyrics() = runBlocking<Unit> {
        val captions = """{"events":[""" + (1..9).joinToString(",") { """{"tStartMs":${it * 2000},"segs":[{"utf8":"Coming down the road number $it"}]}""" } + "]}"
        val tracks = listOf(
            CaptionTrack("https://captions.test/auto?fmt=vtt", "en", autoGenerated = true),
            CaptionTrack("https://captions.test/manual?fmt=vtt", "en", autoGenerated = false),
        )
        val calls = mutableListOf<String>()
        val lyrics = repo(client(false, false, captions, calls), tracks).lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.YOUTUBE, lyrics.source)
        assertTrue(lyrics.matchesRecording)
        assertEquals(2_000L, lyrics.lines.first().timeMs)
        assertFalse("automatic captions are never used", calls.any { it.endsWith("/auto") })
    }

    @Test
    fun skipsCaptionsThatAreNotTheLyrics() = runBlocking<Unit> {
        val captions = """{"events":[""" + (1..9).joinToString(",") { """{"tStartMs":${it * 2000},"segs":[{"utf8":"Bajando por el camino $it"}]}""" } + "]}"
        val tracks = listOf(CaptionTrack("https://captions.test/es?fmt=vtt", "es", autoGenerated = false))
        val lyrics = repo(client(false, false, captions), tracks).lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.LRCLIB, lyrics.source)
    }

    @Test
    fun fallsBackToLrclib() = runBlocking<Unit> {
        val lyrics = repo(client(unisonByVideo = false, unisonBySong = false)).lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.LRCLIB, lyrics.source)
        assertEquals(8, lyrics.lines.size)
    }

    @Test
    fun looksAgainForLyricsSavedByOlderVersions() = runBlocking<Unit> {
        val dir = kotlin.io.path.createTempDirectory("lyrics").toFile()
        java.io.File(dir, "${song.id}.json").writeText("""{"synced":"[00:01.00]Old line"}""")
        val fresh = LyricsRepository(client(true, false), dir, "Tempo/test").lyrics(song, 200) as Lyrics.Synced
        assertEquals(Lyrics.UNISON, fresh.source)

        // Offline, the old copy is still shown.
        java.io.File(dir, "${song.id}.json").writeText("""{"synced":"[00:01.00]Old line"}""")
        val offline = OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val old = LyricsRepository(offline, dir, "Tempo/test").lyrics(song, 200) as Lyrics.Synced
        assertEquals("Old line", old.lines.single().text)
    }
}
