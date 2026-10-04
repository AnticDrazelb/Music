package app.tempo.music.data.charts

import app.tempo.music.data.model.ArtistChart
import app.tempo.music.data.model.ArtistChartEntry
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.Chart
import app.tempo.music.data.model.ChartEntry
import app.tempo.music.data.model.ChartKind
import app.tempo.music.data.model.Song
import app.tempo.music.data.youtube.YouTubeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Client for YouTube's public music charts (charts.youtube.com): weekly Top 100 songs,
 * music videos and artists for "global" and ~60 countries, plus daily trending videos.
 */
class ChartsRepository(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()

    suspend fun chart(kind: ChartKind, countryCode: String): Chart {
        require(kind != ChartKind.TOP_ARTISTS)
        val code = normalize(kind, countryCode)
        return cached("chart:$kind:$code") {
            val content = fetch(kind, code)
            parseSongChart(kind, code, content)
        }
    }

    suspend fun artists(countryCode: String): ArtistChart {
        val code = normalize(ChartKind.TOP_ARTISTS, countryCode)
        return cached("artists:$code") {
            val content = fetch(ChartKind.TOP_ARTISTS, code)
            parseArtistChart(code, content)
        }
    }

    private fun normalize(kind: ChartKind, countryCode: String): String {
        val code = countryCode.lowercase()
        return if (code == GLOBAL && !kind.supportsGlobal) "us" else code
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        cache[key]?.let { (time, value) -> if (now - time < CACHE_MS) return value as T }
        return load().also { cache[key] = now to it }
    }

    private suspend fun fetch(kind: ChartKind, code: String): JsonObject = withContext(Dispatchers.IO) {
        val period = if (kind == ChartKind.TRENDING) "" else "&chart_params_period_type=WEEKLY"
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_MUSIC_ANALYTICS")
                    put("clientVersion", "2.0")
                    put("hl", Locale.getDefault().language.ifBlank { "en" })
                    put("gl", if (code == GLOBAL) "US" else code.uppercase())
                }
            }
            put("browseId", "FEmusic_analytics_charts_home")
            put(
                "query",
                "perspective=CHART_DETAILS&chart_params_country_code=$code" +
                    "&chart_params_chart_type=${kind.apiType}$period",
            )
        }.toString()

        val request = Request.Builder()
            .url("https://charts.youtube.com/youtubei/v1/browse?alt=json&prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Origin", "https://charts.youtube.com")
            .header("Referer", "https://charts.youtube.com/")
            .header("X-YouTube-Client-Name", "31")
            .header("X-YouTube-Client-Version", "2.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Charts unavailable (HTTP ${response.code})")
            val root = json.parseToJsonElement(response.body.string()).jsonObj
            root.path("contents", "sectionListRenderer", "contents").arr?.firstOrNull()
                ?.path("musicAnalyticsSectionRenderer", "content")?.jsonObj
                ?: throw IOException("Unexpected charts response")
        }
    }

    internal fun parseSongChart(kind: ChartKind, code: String, content: JsonObject): Chart {
        val (list, items) = when (kind) {
            ChartKind.TOP_SONGS -> content["trackTypes"].arr?.firstOrNull()?.jsonObj
                .let { it to it?.get("trackViews").arr }
            else -> content["videos"].arr?.firstOrNull()?.jsonObj
                .let { it to it?.get("videoViews").arr }
        }
        val entries = items.orEmpty().mapIndexedNotNull { index, element ->
            val e = element.jsonObj ?: return@mapIndexedNotNull null
            val meta = e["chartEntryMetadata"]?.jsonObj
            val rank = meta?.get("currentPosition").int ?: (index + 1)
            val previous = meta?.get("previousPosition").int
            val weeks = meta?.get("periodsOnChart").int ?: 0
            val artists = e["artists"].arr.orEmpty().mapNotNull { it.jsonObj?.get("name").str }
            val song = when (kind) {
                ChartKind.TOP_SONGS -> {
                    // Prefer the "art track" (official audio with album art) over the music video.
                    val id = e["atvExternalVideoId"].str?.takeIf { it.isNotBlank() }
                        ?: e["encryptedVideoId"].str
                        ?: return@mapIndexedNotNull null
                    Song(
                        id = id,
                        title = e["name"].str ?: return@mapIndexedNotNull null,
                        artist = artists.joinToString(", ").ifBlank { "Unknown Artist" },
                        thumbnailUrl = YouTubeUtils.upscale(bestThumb(e)),
                    )
                }
                else -> {
                    val id = e["id"].str ?: return@mapIndexedNotNull null
                    Song(
                        id = id,
                        title = YouTubeUtils.stripNoise(e["title"].str ?: return@mapIndexedNotNull null),
                        artist = artists.joinToString(", ").ifBlank {
                            YouTubeUtils.cleanArtist(e["channelName"].str)
                        },
                        artistUrl = e["externalChannelId"].str?.let { YouTubeUtils.channelUrl(it) },
                        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        durationSec = e["videoDuration"].int?.toLong() ?: -1,
                        isVideo = true,
                    )
                }
            }
            ChartEntry(rank, previous?.takeIf { it > 0 }, weeks, song)
        }.sortedBy { it.rank }

        return Chart(
            kind = kind,
            countryCode = code,
            countryName = Countries.name(code),
            periodLabel = periodLabel(list),
            entries = entries,
        )
    }

    internal fun parseArtistChart(code: String, content: JsonObject): ArtistChart {
        val list = content["artists"].arr?.firstOrNull()?.jsonObj
        val entries = list?.get("artistViews").arr.orEmpty().mapIndexedNotNull { index, element ->
            val e = element.jsonObj ?: return@mapIndexedNotNull null
            val channelId = e["externalChannelId"].str ?: return@mapIndexedNotNull null
            val meta = e["chartEntryMetadata"]?.jsonObj
            ArtistChartEntry(
                rank = meta?.get("currentPosition").int ?: (index + 1),
                previousRank = meta?.get("previousPosition").int?.takeIf { it > 0 },
                artist = ArtistRef(
                    url = YouTubeUtils.channelUrl(channelId),
                    name = e["name"].str ?: return@mapIndexedNotNull null,
                    thumbnailUrl = YouTubeUtils.upscale(bestThumb(e)),
                ),
            )
        }.sortedBy { it.rank }
        return ArtistChart(code, Countries.name(code), periodLabel(list), entries)
    }

    private fun bestThumb(e: JsonObject): String? =
        e.path("thumbnail", "thumbnails").arr.orEmpty().mapNotNull { it.jsonObj }
            .maxByOrNull { it["width"].int ?: 0 }?.get("url").str

    private fun periodLabel(list: JsonObject?): String? {
        val end = list?.get("endDate").str ?: return null
        return runCatching {
            val date = LocalDate.parse(end)
            "Week ending " + date.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        }.getOrNull()
    }

    companion object {
        const val GLOBAL = "global"
        private const val CACHE_MS = 60 * 60 * 1000L
    }
}

// ---- tiny JSON navigation helpers ----

internal val JsonElement?.jsonObj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: List<JsonElement>? get() = this as? JsonArray
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
internal val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

internal fun JsonElement?.path(vararg keys: String): JsonElement? {
    var current: JsonElement? = this
    for (key in keys) current = (current as? JsonObject)?.get(key) ?: return null
    return current
}
