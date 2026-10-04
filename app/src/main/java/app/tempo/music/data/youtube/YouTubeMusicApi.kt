package app.tempo.music.data.youtube

import app.tempo.music.data.charts.arr
import app.tempo.music.data.charts.int
import app.tempo.music.data.charts.jsonObj
import app.tempo.music.data.charts.path
import app.tempo.music.data.charts.str
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.MusicItem
import app.tempo.music.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/**
 * YouTube Music's own web API (InnerTube, the WEB_REMIX client), used where it gives cleaner
 * data than plain YouTube: playlist tracks come back with their catalogue title and artists
 * (even for music videos), and radio works for the "art track" ids the charts use.
 */
class YouTubeMusicApi(private val client: OkHttpClient) {

    @Volatile var country: String = "US"

    private val json = Json { ignoreUnknownKeys = true }

    data class PlaylistPage(
        val title: String?,
        val subtitle: String?,
        val thumbnailUrl: String?,
        val songs: List<Song>,
        val continuation: String?,
    )

    data class ArtistPage(
        val name: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val subscribers: Long,
        val songs: List<Song>,
        /** Playlist of all the artist's songs, behind "See All". */
        val songsPlaylistId: String?,
        val sections: List<Section>,
    )

    /** A row on an artist page ("Albums", "Singles & EPs", "Fans might also like"...), titled by YouTube Music. */
    data class Section(val title: String, val items: List<MusicItem>, val more: BrowseTarget?)

    data class BrowseTarget(val browseId: String, val params: String?)

    fun artist(channelId: String): ArtistPage = parseArtist(post("browse", buildJsonObject { put("browseId", channelId) }))

    /** An album page (MPREb_ id): tracks, with the album's artist and art filled in. */
    fun album(browseId: String): PlaylistPage = parseAlbum(post("browse", buildJsonObject { put("browseId", browseId) }))

    /** Everything behind a section's "See All" (all albums, all singles...). */
    fun browseItems(target: BrowseTarget): List<MusicItem> = parseItems(
        post(
            "browse",
            buildJsonObject {
                put("browseId", target.browseId)
                target.params?.let { put("params", it) }
            },
        ),
    )

    fun playlist(playlistId: String): PlaylistPage =
        parsePlaylist(post("browse", buildJsonObject { put("browseId", "VL$playlistId") }))

    fun playlistContinuation(token: String): PlaylistPage =
        parsePlaylist(post("browse", buildJsonObject { put("continuation", token) }))

    /** A YouTube Music radio station seeded from [videoId] (the seed itself comes first). */
    fun radio(videoId: String): List<Song> = parseRadio(
        post(
            "next",
            buildJsonObject {
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("params", "wAEB")
                put("isAudioOnly", true)
                put("enablePersistentPlaylistPanel", true)
                put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
            },
        ),
    )

    private fun post(endpoint: String, body: JsonObject): JsonObject {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", Locale.getDefault().language.ifBlank { "en" })
                    put("gl", country)
                }
            }
            body.forEach { (k, v) -> put(k, v) }
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music unavailable (HTTP ${response.code})")
            return json.parseToJsonElement(response.body.string()).jsonObj
                ?: throw IOException("Unexpected YouTube Music response")
        }
    }

    companion object {
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_VERSION = "1.20250929.03.00"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val ATV = "MUSIC_VIDEO_TYPE_ATV"
        private const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
        private const val SEPARATOR = " • "

        internal fun parsePlaylist(root: JsonObject): PlaylistPage {
            val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer")
                .firstNotNullOfOrNull { findAll(root, it).firstOrNull() }
            val subtitle = header?.let {
                text(it["straplineTextOne"]).ifBlank { text(it["subtitle"]).split(SEPARATOR).getOrNull(1).orEmpty() }
            }
            return PlaylistPage(
                title = header?.let { text(it["title"]) }?.ifBlank { null },
                subtitle = subtitle?.ifBlank { null },
                thumbnailUrl = header?.let { largestThumbnail(it) },
                songs = findAll(root, "musicResponsiveListItemRenderer").mapNotNull(::parseListItem).distinctBy { it.id },
                continuation = findAll(root, "continuationCommand").firstNotNullOfOrNull { it["token"].str },
            )
        }

        internal fun parseArtist(root: JsonObject): ArtistPage {
            val header = listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicResponsiveHeaderRenderer")
                .firstNotNullOfOrNull { findAll(root, it).firstOrNull() }
            val shelf = findAll(root, "musicShelfRenderer").firstOrNull()
            val sections = findAll(root, "musicCarouselShelfRenderer").mapNotNull { carousel ->
                val titleRun = carousel.path("header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs").arr?.firstOrNull()
                val title = titleRun.path("text").str?.trim().orEmpty()
                val items = carousel["contents"].arr.orEmpty().mapNotNull { parseCarouselItem(it.jsonObj) }
                    .distinctBy { it.key }
                if (title.isEmpty() || items.isEmpty()) null else Section(title, items, browseTarget(titleRun.path("navigationEndpoint")))
            }
            return ArtistPage(
                name = header?.let { text(it["title"]) }?.ifBlank { null },
                description = header?.let { text(it["description"]) }?.ifBlank { null },
                thumbnailUrl = header?.let { largestThumbnail(it) },
                subscribers = header?.let {
                    parseCount(text(it.path("subscriptionButton", "subscribeButtonRenderer", "subscriberCountText")))
                } ?: -1,
                songs = shelf?.get("contents").arr.orEmpty().mapNotNull { it.path("musicResponsiveListItemRenderer").jsonObj }
                    .mapNotNull(::parseListItem).distinctBy { it.id },
                songsPlaylistId = shelf.path("title", "runs").arr?.firstOrNull()
                    .path("navigationEndpoint", "browseEndpoint", "browseId").str
                    ?.takeIf { it.startsWith("VL") }?.removePrefix("VL"),
                sections = sections,
            )
        }

        internal fun parseAlbum(root: JsonObject): PlaylistPage {
            val page = parsePlaylist(root)
            val header = listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer")
                .firstNotNullOfOrNull { findAll(root, it).firstOrNull() }
            val artistRuns = header.path("straplineTextOne", "runs").arr
                ?: header.path("subtitle", "runs").arr.orEmpty().dropWhile { it.path("text").str != SEPARATOR }.drop(1)
                    .takeWhile { it.path("text").str != SEPARATOR }
            val artist = artistRuns.joinToString("") { it.path("text").str.orEmpty() }.trim().ifBlank { page.subtitle.orEmpty() }
            val artistUrl = artistRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "browseEndpoint", "browseId").str }
                ?.takeIf { it.startsWith("UC") }?.let(YouTubeUtils::channelUrl)
            // Album tracks usually leave out the artist and the art: they're the album's.
            return page.copy(
                subtitle = artist.ifBlank { null },
                songs = page.songs.map { song ->
                    song.copy(
                        artist = if (song.artist == YouTubeUtils.UNKNOWN_ARTIST && artist.isNotBlank()) artist else song.artist,
                        artistUrl = song.artistUrl ?: artistUrl,
                        album = song.album ?: page.title,
                        thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl,
                    )
                },
            )
        }

        internal fun parseItems(root: JsonObject): List<MusicItem> =
            (findAll(root, "musicTwoRowItemRenderer") + findAll(root, "musicResponsiveListItemRenderer"))
                .mapNotNull { parseCarouselItem(it, wrapped = false) }
                .distinctBy { it.key }

        /** A carousel tile: an album, single, playlist, artist or video. */
        private fun parseCarouselItem(element: JsonObject?, wrapped: Boolean = true): MusicItem? {
            val item = if (wrapped) {
                element?.get("musicTwoRowItemRenderer").jsonObj ?: element?.get("musicResponsiveListItemRenderer").jsonObj?.let {
                    return parseListItem(it)
                }
            } else {
                element
            } ?: return null
            if ("flexColumns" in item) return parseListItem(item)
            val title = text(item["title"]).ifBlank { return null }
            val subtitle = text(item["subtitle"]).ifBlank { null }
            val endpoint = item["navigationEndpoint"] ?: item.path("title", "runs").arr?.firstOrNull().path("navigationEndpoint")
            val browseId = endpoint.path("browseEndpoint", "browseId").str
            val pageType = endpoint.path(
                "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
            ).str
            val thumbnail = largestThumbnail(item)
            val videoId = endpoint.path("watchEndpoint", "videoId").str
            return when {
                browseId != null && (browseId.startsWith("MPREb_") || pageType == "MUSIC_PAGE_TYPE_ALBUM") -> CollectionRef(
                    url = albumUrl(browseId),
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnail,
                    type = CollectionType.ALBUM,
                )
                browseId != null && browseId.startsWith("UC") -> ArtistRef(
                    url = YouTubeUtils.channelUrl(browseId),
                    name = title,
                    thumbnailUrl = thumbnail,
                    subscribers = parseCount(subtitle.orEmpty()),
                )
                browseId != null && browseId.startsWith("VL") -> CollectionRef(
                    url = YouTubeUtils.musicPlaylistUrl(browseId.removePrefix("VL")),
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnail,
                )
                videoId != null -> {
                    // A video tile: "Never Gonna Give You Up" over "Rick Astley • 1.8B views".
                    val channel = subtitle?.substringBefore(SEPARATOR)?.trim()
                    val (songTitle, artist) = YouTubeUtils.splitTitle(title, channel, artistIsReliable = true)
                    Song(id = videoId, title = songTitle, artist = artist, thumbnailUrl = thumbnail, isVideo = true)
                }
                else -> null
            }
        }

        private fun browseTarget(endpoint: JsonElement?): BrowseTarget? {
            val id = endpoint.path("browseEndpoint", "browseId").str ?: return null
            return BrowseTarget(id, endpoint.path("browseEndpoint", "params").str)
        }

        fun albumUrl(browseId: String) = "https://music.youtube.com/browse/$browseId"

        /** "4.55M subscribers" -> 4550000. */
        internal fun parseCount(text: String): Long {
            val m = Regex("""([\d.,]+)\s*([KMB])?""", RegexOption.IGNORE_CASE).find(text) ?: return -1
            val number = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
            val scale = when (m.groupValues[2].uppercase()) {
                "K" -> 1e3
                "M" -> 1e6
                "B" -> 1e9
                else -> 1.0
            }
            return (number * scale).toLong()
        }

        internal fun parseRadio(root: JsonObject): List<Song> =
            findAll(root, "playlistPanelVideoRenderer").mapNotNull(::parsePanelItem).distinctBy { it.id }

        /** A track row in a playlist: title | artists | album, duration on the right. */
        private fun parseListItem(item: JsonObject): Song? {
            val columns = item["flexColumns"].arr.orEmpty().map { it.path("musicResponsiveListItemFlexColumnRenderer", "text") }
            val titleRuns = columns.getOrNull(0).path("runs").arr.orEmpty()
            val videoId = item.path("playlistItemData", "videoId").str
                ?: titleRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "watchEndpoint", "videoId").str }
                ?: return null
            val title = text(columns.getOrNull(0)).ifBlank { return null }
            val bylineRuns = columns.getOrNull(1).path("runs").arr.orEmpty()
            // The album is the column linking to an album page (artist pages put play counts in between).
            val album = columns.drop(1).firstOrNull { column ->
                column.path("runs").arr.orEmpty().any {
                    it.path("navigationEndpoint", "browseEndpoint", "browseId").str?.startsWith("MPREb_") == true
                }
            }?.let(::text) ?: text(columns.getOrNull(2)).takeIf { it.isNotBlank() && it.none(Char::isDigit) }
            val duration = item["fixedColumns"].arr?.firstOrNull()
                .path("musicResponsiveListItemFixedColumnRenderer", "text").let(::text)
            return song(videoId, title, bylineRuns, album, duration, largestThumbnail(item), videoType(item))
        }

        /** A queue entry from "next": title, "Artist • Album • Year" or "Channel • views". */
        private fun parsePanelItem(item: JsonObject): Song? {
            val videoId = item["videoId"].str ?: return null
            val title = text(item["title"]).ifBlank { return null }
            val bylineRuns = item.path("longBylineText", "runs").arr.orEmpty()
            val type = videoType(item)
            val parts = text(item["longBylineText"]).split(SEPARATOR)
            val album = if (type == ATV) parts.getOrNull(1)?.takeIf { it.isNotBlank() && !it.any(Char::isDigit) } else null
            return song(videoId, title, bylineRuns, album, text(item["lengthText"]), largestThumbnail(item), type)
        }

        private fun song(
            videoId: String,
            rawTitle: String,
            bylineRuns: List<JsonElement>,
            album: String?,
            duration: String,
            thumbnail: String?,
            type: String?,
        ): Song {
            // The artists are the byline runs before the first " • " ("A & B • Album • 2024").
            val artistRuns = bylineRuns.takeWhile { it.path("text").str != SEPARATOR }
            val byline = artistRuns.joinToString("") { it.path("text").str.orEmpty() }.trim()
            val link = artistRuns.firstNotNullOfOrNull { it.path("navigationEndpoint", "browseEndpoint") }
            // A real artist page, as opposed to whichever channel uploaded the video.
            val isArtistPage = link.path(
                "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
            ).str == ARTIST_PAGE
            val (title, artist) = if (type == ATV) {
                // Catalogue songs are clean; just drop decorations like "(Official Audio)".
                YouTubeUtils.stripNoise(rawTitle) to YouTubeUtils.cleanArtist(byline)
            } else {
                YouTubeUtils.splitTitle(rawTitle, byline, artistIsReliable = isArtistPage)
            }
            val artistUrl = link.path("browseId").str?.takeIf { it.startsWith("UC") }
                ?.takeIf { isArtistPage || type == ATV || artist.equals(YouTubeUtils.cleanArtist(byline), ignoreCase = true) }
                ?.let(YouTubeUtils::channelUrl)
            return Song(
                id = videoId,
                title = title,
                artist = artist,
                artistUrl = artistUrl,
                album = album,
                thumbnailUrl = thumbnail,
                durationSec = parseDuration(duration),
                isVideo = type != ATV,
            )
        }

        private fun videoType(item: JsonObject): String? = findString(item, "musicVideoType")

        private fun largestThumbnail(item: JsonObject): String? {
            val list = listOf(
                item.path("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"),
                item.path("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails"),
                item.path("thumbnail", "thumbnails"),
                item.path("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"),
            ).firstNotNullOfOrNull { it.arr } ?: findAll(item, "thumbnails", arrays = true).firstOrNull()?.get("thumbnails").arr
            return list.orEmpty().mapNotNull { it.jsonObj }
                .maxByOrNull { it["width"].int ?: 0 }?.get("url").str
                ?.let { YouTubeUtils.upscale(it) }
        }

        internal fun parseDuration(text: String): Long {
            val parts = text.trim().split(':').map { it.toLongOrNull() ?: return -1 }
            if (parts.isEmpty() || parts.size > 3) return -1
            return parts.fold(0L) { acc, p -> acc * 60 + p }
        }

        private fun text(element: JsonElement?): String =
            element.path("runs").arr.orEmpty().joinToString("") { it.path("text").str.orEmpty() }
                .ifEmpty { element.path("simpleText").str.orEmpty() }

        /** Every object found under [key] anywhere in the tree (or holding [key] as an array, with [arrays]). */
        private fun findAll(element: JsonElement?, key: String, arrays: Boolean = false): List<JsonObject> {
            val found = mutableListOf<JsonObject>()
            fun walk(e: JsonElement?) {
                when (e) {
                    is JsonObject -> e.forEach { (k, v) ->
                        if (k == key) {
                            if (arrays && v.arr != null) found += e else v.jsonObj?.let(found::add)
                        }
                        walk(v)
                    }
                    is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(element)
            return found
        }

        private fun findString(element: JsonElement?, key: String): String? {
            when (element) {
                is JsonObject -> {
                    element[key].str?.let { return it }
                    element.values.forEach { v -> findString(v, key)?.let { return it } }
                }
                is kotlinx.serialization.json.JsonArray -> element.forEach { v -> findString(v, key)?.let { return it } }
                else -> Unit
            }
            return null
        }
    }
}
