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

    private fun listItem(
        id: String,
        title: String,
        artistRuns: String,
        type: String,
        duration: String = "3:33",
        thumbnail: Boolean = true,
        moreColumns: String = "",
    ) = """
        {"musicResponsiveListItemRenderer": {
          ${if (thumbnail) """"thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=abc", "width": 400, "height": 225}]}}},""" else ""}
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title",
              "navigationEndpoint": {"watchEndpoint": {"videoId": "$id", "watchEndpointMusicSupportedConfigs":
                {"watchEndpointMusicConfig": {"musicVideoType": "$type"}}}}}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [$artistRuns]}}}$moreColumns
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

    private fun column(text: String, browseId: String? = null) =
        """,{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [${if (browseId == null) run(text) else """{"text": "$text", "navigationEndpoint": {"browseEndpoint": {"browseId": "$browseId"}}}"""}]}}}"""

    private fun tile(title: String, subtitle: String, endpoint: String) = """
        {"musicTwoRowItemRenderer": {"title": {"runs": [{"text": "$title"}]}, "subtitle": {"runs": [{"text": "$subtitle"}]},
          "thumbnailRenderer": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://lh3.googleusercontent.com/t=w226-h226-l90-rj", "width": 226, "height": 226}]}}},
          "navigationEndpoint": $endpoint}}"""

    private fun browse(id: String) = """{"browseEndpoint": {"browseId": "$id"}}"""

    private fun watch(id: String, type: String? = null) = """{"watchEndpoint": {"videoId": "$id"${
        if (type == null) "" else """, "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "$type"}}"""
    }}}"""

    private fun carousel(title: String, more: String?, tiles: String) = """
        {"musicCarouselShelfRenderer": {"header": {"musicCarouselShelfBasicHeaderRenderer": {"title": {"runs": [{"text": "$title"
          ${if (more != null) """, "navigationEndpoint": {"browseEndpoint": {"browseId": "UCkooks", "params": "$more"}}""" else ""}}]}}},
          "contents": [$tiles]}}"""

    @Test
    fun artistPagesHaveTopSongsAndYouTubeMusicRows() {
        val page = YouTubeMusicApi.parseArtist(
            parse(
                """{"header": {"musicImmersiveHeaderRenderer": {
                    "title": {"runs": [{"text": "The Kooks"}]}, "description": {"runs": [{"text": "British indie band."}]},
                    "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                      {"url": "https://lh3.googleusercontent.com/kooks=w1440-h600-l90-rj", "width": 1440, "height": 600}]}}},
                    "subscriptionButton": {"subscribeButtonRenderer": {"subscriberCountText": {"runs": [{"text": "1.2M"}]}}}}},
                   "contents": {"sectionListRenderer": {"contents": [
                     {"musicShelfRenderer": {"title": {"runs": [{"text": "Top songs", "navigationEndpoint": {"browseEndpoint": {"browseId": "VLOLAK5uy_songs"}}}]},
                       "contents": [${listItem("naive", "Naive", run("The Kooks", "MUSIC_PAGE_TYPE_ARTIST", "UCkooks"), "MUSIC_VIDEO_TYPE_ATV",
                           moreColumns = column("612M plays") + column("Inside In / Inside Out", "MPREb_inside"))}]}},
                     ${carousel("Albums", "ggMIegYIARoCAQI%3D", tile("Inside In / Inside Out", "Album • 2006", browse("MPREb_inside")))},
                     ${carousel("Videos", null, tile("The Kooks - Naive (Official Video)", "The Kooks • 98M views", watch("jkaMiaRLgvY", "MUSIC_VIDEO_TYPE_OMV")) + "," + tile("Naive (Lyrics)", "The Kooks • 2M views", watch("lyrics1")))},
                     ${carousel("Live performances", null, tile("Naive (Live at Glastonbury)", "The Kooks • 3M views", watch("glasto1", "MUSIC_VIDEO_TYPE_UGC")) + "," + tile("Naive (Live)", "The Kooks", watch("liveatv", "MUSIC_VIDEO_TYPE_ATV")))},
                     ${carousel("Music videos", null, tile("Shine On", "The Kooks", watch("shineatv", "MUSIC_VIDEO_TYPE_ATV")))},
                     ${carousel("Fans might also like", null, tile("Arctic Monkeys", "8.1M subscribers", browse("UCarctic")) + "," + tile("Empty", "", "{}"))},
                     ${carousel("Featured on", null, tile("Indie Anthems", "Playlist", browse("VLRDCLAK5uy_indie")))}
                   ]}}}""",
            ),
        )
        assertEquals("The Kooks", page.name)
        assertEquals("British indie band.", page.description)
        assertEquals(1_200_000L, page.subscribers)
        assertEquals("https://lh3.googleusercontent.com/kooks=w544-h544-l90-rj", page.thumbnailUrl)
        assertEquals("OLAK5uy_songs", page.songsPlaylistId)
        assertEquals(listOf("Naive"), page.songs.map { it.title })
        // The album column is the one linking to an album, not the play count before it.
        assertEquals("Inside In / Inside Out", page.songs.single().album)

        // No music videos (Tempo leaves them out): video rows go, and video tiles are dropped
        // from other rows, while audio tracks (ATV) stay.
        assertEquals(listOf("Albums", "Live performances", "Fans might also like", "Featured on"), page.sections.map { it.title })
        val album = page.sections[0].items.single() as app.tempo.music.data.model.CollectionRef
        assertEquals("https://music.youtube.com/browse/MPREb_inside", album.url)
        assertEquals(app.tempo.music.data.model.CollectionType.ALBUM, album.type)
        assertEquals(YouTubeMusicApi.BrowseTarget("UCkooks", "ggMIegYIARoCAQI%3D"), page.sections[0].more)
        val live = page.sections[1].items.single() as app.tempo.music.data.model.Song
        assertEquals("liveatv", live.id)
        assertEquals(false, live.isVideo)
        val similar = page.sections[2].items.single() as app.tempo.music.data.model.ArtistRef
        assertEquals("https://www.youtube.com/channel/UCarctic", similar.url)
        assertEquals(8_100_000L, similar.subscribers)
        val playlist = page.sections[3].items.single() as app.tempo.music.data.model.CollectionRef
        assertEquals("https://music.youtube.com/playlist?list=RDCLAK5uy_indie", playlist.url)
    }

    @Test
    fun albumTracksTakeTheAlbumsArtistAndArt() {
        val page = YouTubeMusicApi.parseAlbum(
            parse(
                """{"header": {"musicResponsiveHeaderRenderer": {
                    "title": {"runs": [{"text": "Inside In / Inside Out"}]},
                    "straplineTextOne": {"runs": [{"text": "The Kooks", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCkooks"}}}]},
                    "subtitle": {"runs": [{"text": "Album"}, {"text": " • "}, {"text": "2006"}]},
                    "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                      {"url": "https://lh3.googleusercontent.com/album=w544-h544-l90-rj", "width": 544, "height": 544}]}}}}},
                   "contents": {"musicShelfRenderer": {"contents": [
                     ${listItem("seaside", "Seaside", "", "MUSIC_VIDEO_TYPE_ATV", "1:39", thumbnail = false)},
                     ${listItem("naive", "Naive", "", "MUSIC_VIDEO_TYPE_ATV", "3:23", thumbnail = false)},
                     ${listItem("feat", "Ooh La (feat. Someone)", run("The Kooks", "MUSIC_PAGE_TYPE_ARTIST", "UCkooks") + "," + run(" & ") + "," + run("Someone"), "MUSIC_VIDEO_TYPE_ATV", thumbnail = false)}
                   ]}}}""",
            ),
        )
        assertEquals("Inside In / Inside Out", page.title)
        assertEquals("The Kooks", page.subtitle)
        assertEquals(listOf("Seaside", "Naive", "Ooh La (feat. Someone)"), page.songs.map { it.title })
        assertEquals(listOf("The Kooks", "The Kooks", "The Kooks & Someone"), page.songs.map { it.artist })
        assertTrue(page.songs.all { it.album == "Inside In / Inside Out" })
        assertTrue(page.songs.all { it.thumbnailUrl == "https://lh3.googleusercontent.com/album=w544-h544-l90-rj" })
        assertTrue(page.songs.all { it.artistUrl == "https://www.youtube.com/channel/UCkooks" })
        assertEquals(listOf(99L, 203L, 213L), page.songs.map { it.durationSec })
    }

    @Test
    fun seeAllListsEveryTile() {
        val items = YouTubeMusicApi.parseItems(
            parse(
                """{"contents": {"gridRenderer": {"items": [
                    ${tile("Inside In / Inside Out", "2006", browse("MPREb_inside"))},
                    ${tile("Konk", "2008", browse("MPREb_konk"))},
                    ${tile("Konk", "2008", browse("MPREb_konk"))},
                    ${tile("Naive (Official Video)", "The Kooks • 98M views", watch("jkaMiaRLgvY", "MUSIC_VIDEO_TYPE_OMV"))}
                  ]}}}""",
            ),
        )
        // Videos are left out of See All pages too.
        assertEquals(listOf("Inside In / Inside Out", "Konk"), items.map { (it as app.tempo.music.data.model.CollectionRef).title })
    }

    @Test
    fun parsesCounts() {
        assertEquals(4_550_000L, YouTubeMusicApi.parseCount("4.55M"))
        assertEquals(932_000L, YouTubeMusicApi.parseCount("932K subscribers"))
        assertEquals(1_500L, YouTubeMusicApi.parseCount("1,500"))
        assertEquals(-1L, YouTubeMusicApi.parseCount("no subscribers"))
    }

    @Test
    fun parsesDurations() {
        assertEquals(213L, YouTubeMusicApi.parseDuration("3:33"))
        assertEquals(3723L, YouTubeMusicApi.parseDuration("1:02:03"))
        assertEquals(-1L, YouTubeMusicApi.parseDuration(""))
        assertEquals(-1L, YouTubeMusicApi.parseDuration("LIVE"))
    }
}
