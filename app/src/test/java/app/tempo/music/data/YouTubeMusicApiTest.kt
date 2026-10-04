package app.tempo.music.data

import app.tempo.music.data.youtube.YouTubeMusicApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of YouTube Music responses, using trimmed-down copies of their real structure. */
class YouTubeMusicApiTest {

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun run(text: String, pageType: String? = null, browseId: String = "UC123") =
        if (pageType == null) """{"text": "$text"}"""
        else """{"text": "$text", "navigationEndpoint": {"browseEndpoint": {"browseId": "$browseId",
            "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "$pageType"}}}}}"""

    private fun listItem(id: String, title: String, artistRuns: String, type: String, duration: String = "3:33") = """
        {"musicResponsiveListItemRenderer": {
          "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=abc", "width": 400, "height": 225}]}}},
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title",
              "navigationEndpoint": {"watchEndpoint": {"videoId": "$id", "watchEndpointMusicSupportedConfigs":
                {"watchEndpointMusicConfig": {"musicVideoType": "$type"}}}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [$artistRuns]}}}
          ],
          "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "$duration"}]}}}],
          "playlistItemData": {"videoId": "$id"}
        }}"""

    @Test
    fun playlistTracksUseCatalogueTitlesAndArtists() {
        val page = YouTubeMusicApi.parsePlaylist(
            parse(
                """{"header": {"musicResponsiveHeaderRenderer": {
                    "title": {"runs": [{"text": "The Hit List"}]},
                    "straplineTextOne": {"runs": [{"text": "YouTube Music"}]},
                    "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                      {"url": "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", "width": 60, "height": 60},
                      {"url": "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", "width": 544, "height": 544}]}}}}},
                   "contents": {"musicPlaylistShelfRenderer": {"contents": [
                     ${listItem("mQezde_qeXw", "SWIM", run("BTS", "MUSIC_PAGE_TYPE_ARTIST"), "MUSIC_VIDEO_TYPE_OMV", "4:05")},
                     ${listItem("A3G_7XgK2B4", "APT.", run("ROSÉ", "MUSIC_PAGE_TYPE_ARTIST") + "," + run(" & ") + "," + run("Bruno Mars", "MUSIC_PAGE_TYPE_ARTIST", "UC456"), "MUSIC_VIDEO_TYPE_ATV", "2:54")},
                     ${listItem("x_dtKRzbCTU", "Haddaway - What Is Love (Official 4K Video)", run("DECADR", "MUSIC_PAGE_TYPE_USER_CHANNEL"), "MUSIC_VIDEO_TYPE_UGC")},
                     {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "NEXT123"}}}}
                   ]}}}""",
            ),
        )
        assertEquals("The Hit List", page.title)
        assertEquals("YouTube Music", page.subtitle)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", page.thumbnailUrl)
        assertEquals("NEXT123", page.continuation)
        assertEquals(listOf("SWIM", "APT.", "What Is Love"), page.songs.map { it.title })
        assertEquals(listOf("BTS", "ROSÉ & Bruno Mars", "Haddaway"), page.songs.map { it.artist })
        assertEquals(listOf(245L, 174L, 213L), page.songs.map { it.durationSec })
        assertEquals("https://www.youtube.com/channel/UC123", page.songs[0].artistUrl)
        // The uploading channel of a fan/label upload isn't the artist.
        assertNull(page.songs[2].artistUrl)
        assertFalse(page.songs[1].isVideo)
        assertTrue(page.songs[0].isVideo)
    }

    @Test
    fun radioEntriesAreCleaned() {
        fun panel(id: String, title: String, byline: String, type: String) = """
            {"playlistPanelVideoRenderer": {"videoId": "$id", "title": {"runs": [{"text": "$title"}]},
              "longBylineText": {"runs": [$byline]}, "lengthText": {"runs": [{"text": "4:01"}]},
              "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/$id/hq720.jpg?sqp=x", "width": 853, "height": 479}]},
              "navigationEndpoint": {"watchEndpoint": {"videoId": "$id", "watchEndpointMusicSupportedConfigs":
                {"watchEndpointMusicConfig": {"musicVideoType": "$type"}}}}}}"""
        val songs = YouTubeMusicApi.parseRadio(
            parse(
                """{"contents": {"playlistPanelRenderer": {"contents": [
                    ${panel("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", run("Rick Astley", "MUSIC_PAGE_TYPE_USER_CHANNEL") + "," + run(" • ") + "," + run("1.8B views"), "MUSIC_VIDEO_TYPE_UGC")},
                    ${panel("HEXWRTEbj1I", "Haddaway - What Is Love (Official 4K Video)", run("DECADR", "MUSIC_PAGE_TYPE_USER_CHANNEL") + "," + run(" • ") + "," + run("497M views"), "MUSIC_VIDEO_TYPE_UGC")},
                    ${panel("lFQdcPTTzSg", "Dai Dai", run("Shakira - Topic", "MUSIC_PAGE_TYPE_ARTIST") + "," + run(" • ") + "," + run("Dai Dai") + "," + run(" • ") + "," + run("2026"), "MUSIC_VIDEO_TYPE_ATV")},
                    ${panel("dQw4w9WgXcQ", "duplicate", run("x"), "MUSIC_VIDEO_TYPE_UGC")}
                  ]}}}""",
            ),
        )
        assertEquals(listOf("Never Gonna Give You Up", "What Is Love", "Dai Dai"), songs.map { it.title })
        assertEquals(listOf("Rick Astley", "Haddaway", "Shakira"), songs.map { it.artist })
        assertEquals("Dai Dai", songs[2].album)
        assertEquals(241L, songs[0].durationSec)
        // Rick Astley's own channel is his artist page; DECADR's isn't Haddaway's.
        assertEquals("https://www.youtube.com/channel/UC123", songs[0].artistUrl)
        assertNull(songs[1].artistUrl)
    }

    @Test
    fun parsesDurations() {
        assertEquals(213L, YouTubeMusicApi.parseDuration("3:33"))
        assertEquals(3723L, YouTubeMusicApi.parseDuration("1:02:03"))
        assertEquals(-1L, YouTubeMusicApi.parseDuration(""))
        assertEquals(-1L, YouTubeMusicApi.parseDuration("LIVE"))
    }
}
