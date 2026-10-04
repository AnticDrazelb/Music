package app.tempo.music.data.youtube

import app.tempo.music.data.model.ArtistDetail
import app.tempo.music.data.model.ArtistRef
import app.tempo.music.data.model.BrowseRef
import app.tempo.music.data.model.ArtistSection
import app.tempo.music.data.model.CollectionDetail
import app.tempo.music.data.model.CollectionRef
import app.tempo.music.data.model.CollectionType
import app.tempo.music.data.model.MusicItem
import app.tempo.music.data.model.Page
import app.tempo.music.data.model.SearchFilter
import app.tempo.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import org.schabi.newpipe.extractor.Page as NewPipePage

/**
 * All YouTube / YouTube Music access goes through NewPipeExtractor, which is maintained
 * against YouTube's frequently changing internal APIs.
 */
class YouTubeRepository(private val client: OkHttpClient) {

    companion object {
        /** Longer "songs" are almost always mixes or compilations. */
        const val MAX_SONG_SECONDS = 15 * 60L
        private const val ALBUMS = "Albums"
        private const val PLAYLISTS = "Playlists"
    }

    private val service: StreamingService get() = ServiceList.YouTube
    private val music = YouTubeMusicApi(client)

    fun init(countryCode: String) {
        NewPipe.init(
            NewPipeDownloader(client),
            Localization.fromLocale(Locale.getDefault()),
            ContentCountry(countryCode.uppercase()),
        )
        music.country = countryCode.uppercase()
        // Mixes ("radio") need the consent cookie in some regions.
        YoutubeParsingHelper.setConsentAccepted(true)
    }

    fun setCountry(countryCode: String) {
        music.country = countryCode.uppercase()
        NewPipe.setupLocalization(
            NewPipe.getPreferredLocalization(),
            ContentCountry(countryCode.uppercase()),
        )
    }

    // ---------------------------------------------------------------- search

    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MusicItem> =
        withContext(Dispatchers.IO) {
            val contentFilter = when (filter) {
                SearchFilter.SONGS -> YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
                SearchFilter.ALBUMS -> YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
                SearchFilter.ARTISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
                SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
            }
            val (raw, next) = rawSearch(query, contentFilter, page)
            val type = if (filter == SearchFilter.ALBUMS) CollectionType.ALBUM else CollectionType.PLAYLIST
            val items = raw.mapNotNull { it.toMusicItem(isMusic = true, collectionType = type) }
                .let { list -> if (filter == SearchFilter.SONGS) list.filterIsInstance<Song>() else list }
                .distinctBy { it.key }

            if (page == null && items.isEmpty() && filter == SearchFilter.SONGS) {
                // The YouTube Music catalogue isn't offered in every region; fall back to
                // regular YouTube results so search still finds something playable.
                val (videos, videosNext) = rawSearch(query, YoutubeSearchQueryHandlerFactory.VIDEOS, null)
                val streams = videos.filterIsInstance<StreamInfoItem>()
                val songs = streams.filter { YouTubeUtils.looksLikeMusic(it.name, it.uploaderName) }
                    .ifEmpty { streams } // keep something rather than nothing
                    .mapNotNull { it.toSong(isMusic = false) }
                    .filter { it.durationSec <= MAX_SONG_SECONDS } // skip hour-long compilations
                    .distinctBy { it.id }
                return@withContext Page(songs, videosNext?.takeIf { NewPipePage.isValid(it) })
            }
            Page(items, next?.takeIf { NewPipePage.isValid(it) })
        }

    private fun rawSearch(query: String, contentFilter: String, page: Any?): Pair<List<InfoItem>, NewPipePage?> {
        val handler = service.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            info.relatedItems to info.nextPage
        } else {
            val more = SearchInfo.getMoreItems(service, handler, page as NewPipePage)
            more.items to more.nextPage
        }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else service.suggestionExtractor.suggestionList(query)
    }

    /** Songs for a free-text query, e.g. a genre or mood. */
    suspend fun songsFor(query: String): List<Song> =
        search(query, SearchFilter.SONGS).items.filterIsInstance<Song>()

    suspend fun playlistsFor(query: String): List<CollectionRef> =
        search(query, SearchFilter.PLAYLISTS).items.filterIsInstance<CollectionRef>()

    // ---------------------------------------------------------------- streams

    fun streamInfoBlocking(videoId: String): StreamInfo =
        StreamInfo.getInfo(service, YouTubeUtils.watchUrl(videoId))

    suspend fun song(videoId: String): Song = withContext(Dispatchers.IO) {
        val info = streamInfoBlocking(videoId)
        val (title, artist) = YouTubeUtils.splitTitle(info.name, info.uploaderName)
        Song(
            id = videoId,
            title = title,
            artist = artist,
            artistUrl = info.uploaderUrl,
            thumbnailUrl = YouTubeUtils.bestThumbnail(info.thumbnails),
            durationSec = info.duration,
            isVideo = true,
        )
    }

    /** Songs YouTube considers similar to [videoId] ("watch next"). */
    suspend fun similar(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        streamInfoBlocking(videoId).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toSong(isMusic = false) }
            .filter { it.id != videoId }
            .distinctBy { it.id }
    }

    /**
     * A radio station seeded from [videoId]: YouTube Music's own radio first (it also works for
     * the art-track ids the charts use), then YouTube's mix, then "watch next" suggestions.
     */
    suspend fun radio(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        val songs = runCatching { music.radio(videoId) }.getOrDefault(emptyList()).filter { it.id != videoId }
            .ifEmpty {
                runCatching {
                    PlaylistInfo.getInfo(service, YouTubeUtils.radioUrl(videoId)).relatedItems
                        .mapNotNull { it.toSong(isMusic = false) }
                }.getOrDefault(emptyList())
            }
            .ifEmpty { runCatching { similar(videoId) }.getOrDefault(emptyList()) }
        songs.filter { it.id != videoId }.distinctBy { it.id }
    }

    // ---------------------------------------------------------------- collections

    suspend fun collection(url: String): CollectionDetail = withContext(Dispatchers.IO) {
        musicCollection(url)?.let { return@withContext it }
        val info = PlaylistInfo.getInfo(service, url)
        val isMusic = isMusicCollection(url)
        val type = collectionType(url)
        val ref = CollectionRef(
            url = url,
            title = info.name,
            subtitle = info.uploaderName?.let { YouTubeUtils.cleanArtist(it) },
            thumbnailUrl = YouTubeUtils.bestThumbnail(info.thumbnails)
                ?: info.relatedItems.firstOrNull()?.let { YouTubeUtils.bestThumbnail(it.thumbnails) },
            type = type,
            itemCount = info.streamCount,
        )
        CollectionDetail(
            ref = ref,
            songs = info.relatedItems.mapNotNull { it.toSong(isMusic) }.distinctBy { it.id },
            nextPage = info.nextPage?.takeIf { NewPipePage.isValid(it) },
        )
    }

    /**
     * Playlists read through YouTube Music, which lists each track under its catalogue title and
     * artists instead of the upload's "Artist - Song (Official 4K Video)". Album pages (MPREb_)
     * only exist there.
     */
    private fun musicCollection(url: String): CollectionDetail? {
        val page = YouTubeUtils.albumBrowseId(url)?.let { music.album(it) }
            ?: run {
                val id = YouTubeUtils.playlistId(url) ?: return null
                if (id.startsWith("RD") && !id.startsWith("RDCLAK")) return null // mixes stay on YouTube
                runCatching { music.playlist(id) }.getOrNull()
            }
        if (page == null || page.songs.isEmpty()) return null
        return CollectionDetail(
            ref = CollectionRef(
                url = url,
                title = page.title.orEmpty(),
                subtitle = page.subtitle,
                thumbnailUrl = page.thumbnailUrl ?: page.songs.first().thumbnailUrl,
                type = collectionType(url),
            ),
            songs = page.songs,
            nextPage = page.continuation?.let(::MusicContinuation),
        )
    }

    private data class MusicContinuation(val token: String)

    suspend fun moreCollectionSongs(url: String, page: Any): Page<Song> = withContext(Dispatchers.IO) {
        if (page is MusicContinuation) {
            val more = music.playlistContinuation(page.token)
            return@withContext Page(more.songs, more.continuation?.let(::MusicContinuation))
        }
        val more = PlaylistInfo.getMoreItems(service, url, page as NewPipePage)
        Page(
            more.items.mapNotNull { it.toSong(isMusicCollection(url)) },
            more.nextPage?.takeIf { NewPipePage.isValid(it) },
        )
    }

    // ---------------------------------------------------------------- artists

    /**
     * An artist page from YouTube Music (top songs, albums, singles, similar artists...). Anything
     * it doesn't provide comes from the YouTube channel instead.
     */
    suspend fun artist(url: String): ArtistDetail = withContext(Dispatchers.IO) {
        val page = YouTubeUtils.channelId(url)?.let { id -> runCatching { music.artist(id) }.getOrNull() }
        val sections = page?.sections.orEmpty().map { section ->
            ArtistSection(section.title, section.items, section.more?.let { BrowseRef(it.browseId, it.params, section.title) })
        }
        val allSongs = page?.songsPlaylistId?.let { id ->
            CollectionRef(YouTubeUtils.musicPlaylistUrl(id), title = "${page.name.orEmpty()} Songs".trim())
        }
        if (page?.name != null && page.songs.isNotEmpty() && sections.isNotEmpty()) {
            return@withContext ArtistDetail(
                ref = ArtistRef(url, page.name, page.thumbnailUrl, page.subscribers),
                bannerUrl = null,
                description = page.description,
                topSongs = page.songs,
                sections = sections,
                allSongs = allSongs,
            )
        }

        val channel = channelArtist(url)
        val hasAlbums = sections.any { s -> s.items.any { (it as? CollectionRef)?.type == CollectionType.ALBUM } }
        val hasPlaylists = sections.any { s -> s.items.any { (it as? CollectionRef)?.type == CollectionType.PLAYLIST } }
        ArtistDetail(
            ref = ArtistRef(
                url = url,
                name = page?.name ?: channel.ref.name,
                thumbnailUrl = page?.thumbnailUrl ?: channel.ref.thumbnailUrl,
                subscribers = page?.subscribers?.takeIf { it > 0 } ?: channel.ref.subscribers,
            ),
            bannerUrl = channel.bannerUrl,
            description = page?.description ?: channel.description,
            topSongs = page?.songs?.takeIf { it.isNotEmpty() } ?: channel.topSongs,
            sections = buildList {
                channel.sections.firstOrNull { it.title == ALBUMS }?.takeIf { !hasAlbums }?.let(::add)
                addAll(sections)
                channel.sections.firstOrNull { it.title == PLAYLISTS }?.takeIf { !hasPlaylists }?.let(::add)
            },
            allSongs = allSongs,
        )
    }

    /** Everything behind a section's "See All", e.g. an artist's full discography. */
    suspend fun browse(ref: BrowseRef): List<MusicItem> = withContext(Dispatchers.IO) {
        music.browseItems(YouTubeMusicApi.BrowseTarget(ref.browseId, ref.params))
    }

    /** The artist built from their YouTube channel: name, uploads, releases and playlists. */
    private suspend fun channelArtist(url: String): ArtistDetail = coroutineScope {
        val info = withContext(Dispatchers.IO) { ChannelInfo.getInfo(service, url) }
        val name = YouTubeUtils.cleanArtist(info.name)

        fun tab(filter: String) = info.tabs.firstOrNull { filter in it.contentFilters }
        val videosTab = tab(ChannelTabs.VIDEOS)
        val albumsTab = tab(ChannelTabs.ALBUMS)
        val playlistsTab = tab(ChannelTabs.PLAYLISTS)

        val albums = async(Dispatchers.IO) {
            albumsTab?.let { t ->
                runCatching { ChannelTabInfo.getInfo(service, t).relatedItems }.getOrNull()
            }.orEmpty().mapNotNull { it.toMusicItem(true, CollectionType.ALBUM) as? CollectionRef }
        }
        val playlists = async(Dispatchers.IO) {
            playlistsTab?.let { t ->
                runCatching { ChannelTabInfo.getInfo(service, t).relatedItems }.getOrNull()
            }.orEmpty().mapNotNull { it.toMusicItem(true, CollectionType.PLAYLIST) as? CollectionRef }
        }
        val top = async {
            val songs = runCatching { songsFor(name) }.getOrDefault(emptyList())
                .filter { YouTubeUtils.credits(it.artist, name) }
                .take(10)
            // Fall back to the channel's uploads when the music catalogue has nothing.
            songs.ifEmpty {
                withContext(Dispatchers.IO) {
                    videosTab?.let { t ->
                        runCatching { ChannelTabInfo.getInfo(service, t).relatedItems }.getOrNull()
                    }.orEmpty().filterIsInstance<StreamInfoItem>()
                        .mapNotNull { it.toSong(isMusic = false) }
                        .filter { it.durationSec <= MAX_SONG_SECONDS }
                        .take(10)
                }
            }
        }

        ArtistDetail(
            ref = ArtistRef(
                url = url,
                name = name,
                thumbnailUrl = YouTubeUtils.bestThumbnail(info.avatars),
                subscribers = info.subscriberCount,
            ),
            bannerUrl = info.banners.maxByOrNull { it.width }?.url,
            description = info.description,
            topSongs = top.await(),
            sections = listOf(ArtistSection(ALBUMS, albums.await()), ArtistSection(PLAYLISTS, playlists.await()))
                .filter { it.items.isNotEmpty() },
        )
    }

    // ---------------------------------------------------------------- mapping

    private fun isMusicCollection(url: String) =
        "music.youtube.com" in url || YouTubeUtils.playlistId(url)?.startsWith("OLAK5uy_") == true

    private fun collectionType(url: String): CollectionType {
        if (YouTubeUtils.albumBrowseId(url) != null) return CollectionType.ALBUM
        val id = YouTubeUtils.playlistId(url).orEmpty()
        return when {
            id.startsWith("OLAK5uy_") || id.startsWith("MPREb_") -> CollectionType.ALBUM
            id.startsWith("RD") -> CollectionType.MIX
            else -> CollectionType.PLAYLIST
        }
    }

    private fun InfoItem.toMusicItem(isMusic: Boolean, collectionType: CollectionType): MusicItem? =
        when (this) {
            is StreamInfoItem -> toSong(isMusic)
            is PlaylistInfoItem -> {
                val type = when (playlistType) {
                    PlaylistInfo.PlaylistType.NORMAL, null -> collectionType(url).let {
                        if (it == CollectionType.PLAYLIST) collectionType else it
                    }
                    else -> CollectionType.MIX
                }
                CollectionRef(
                    url = url,
                    title = name,
                    subtitle = uploaderName?.let { YouTubeUtils.cleanArtist(it) },
                    thumbnailUrl = YouTubeUtils.bestThumbnail(thumbnails),
                    type = type,
                    itemCount = streamCount,
                )
            }
            is ChannelInfoItem -> ArtistRef(
                url = url,
                name = YouTubeUtils.cleanArtist(name),
                thumbnailUrl = YouTubeUtils.bestThumbnail(thumbnails),
                subscribers = subscriberCount,
            )
            else -> null
        }

    private fun InfoItem.toSong(isMusic: Boolean): Song? {
        val item = this as? StreamInfoItem ?: return null
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) {
            return null
        }
        if (item.isShortFormContent) return null
        val id = YouTubeUtils.videoId(item.url) ?: return null
        val (title, artist) = YouTubeUtils.splitTitle(item.name, item.uploaderName, artistIsReliable = isMusic)
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistUrl = item.uploaderUrl,
            thumbnailUrl = YouTubeUtils.bestThumbnail(item.thumbnails),
            durationSec = item.duration,
            isVideo = !isMusic,
        )
    }
}
