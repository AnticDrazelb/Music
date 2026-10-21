import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking // URLSession on Linux, for the stream check
#endif

// What YouTube Music's catalogue is made of, beyond songs: albums, artists, playlists, and the
// shelves (rows) its pages are built from.

struct AlbumRef: Hashable {
    /// The album page (MPREb_…).
    let browseId: String
    var title: String
    /// "Artist • 2019" or "Single • Artist".
    var subtitle: String?
    var artworkURL: URL?
}

struct ArtistRef: Hashable {
    /// The artist's channel (UC…).
    let channelId: String
    var name: String
    var subtitle: String?
    var artworkURL: URL?
}

struct PlaylistRef: Hashable {
    /// Without the "VL" prefix browse IDs carry.
    let playlistId: String
    var title: String
    var subtitle: String?
    var artworkURL: URL?
}

/// A page YouTube Music links to with a browse ID (and sometimes params), like "See all".
struct BrowseTarget: Hashable {
    let browseId: String
    let params: String?
}

enum CatalogItem: Hashable {
    case song(Track)
    case album(AlbumRef)
    case artist(ArtistRef)
    case playlist(PlaylistRef)
    /// A Moods & Genres category or other page.
    case page(title: String, target: BrowseTarget)

    var title: String {
        switch self {
        case .song(let t): return t.title
        case .album(let a): return a.title
        case .artist(let a): return a.name
        case .playlist(let p): return p.title
        case .page(let title, _): return title
        }
    }

    var key: String {
        switch self {
        case .song(let t): return "s:" + t.id
        case .album(let a): return "a:" + a.browseId
        case .artist(let a): return "r:" + a.channelId
        case .playlist(let p): return "p:" + p.playlistId
        case .page(_, let target): return "g:" + target.browseId + (target.params ?? "")
        }
    }
}

/// A titled row of items, with where its "See all" goes.
struct Shelf {
    let title: String
    let items: [CatalogItem]
    let more: BrowseTarget?
}

struct AlbumPage {
    let album: AlbumRef
    let artist: String?
    let artistId: String?
    let tracks: [Track]
}

struct PlaylistPage {
    let title: String?
    let subtitle: String?
    let artworkURL: URL?
    let tracks: [Track]
    let continuation: String?
}

struct ArtistPage {
    let name: String
    let about: String?
    let artworkURL: URL?
    let topSongs: [Track]
    /// The playlist of all the artist's songs, behind "See all" on Top Songs.
    let allSongsPlaylistId: String?
    let shelves: [Shelf]
}

struct ChartEntry: Hashable {
    let rank: Int
    let previousRank: Int?
    let track: Track
}

/// Search filters YouTube Music offers.
enum SearchFilter: String, CaseIterable {
    case songs = "Songs", albums = "Albums", artists = "Artists", playlists = "Playlists", videos = "Videos"

    /// The search "params" for each filter.
    var params: String {
        switch self {
        case .songs: return "EgWKAQIIAWoMEA4QChADEAQQCRAF"
        case .videos: return "EgWKAQIQAWoMEA4QChADEAQQCRAF"
        case .albums: return "EgWKAQIYAWoMEA4QChADEAQQCRAF"
        case .artists: return "EgWKAQIgAWoMEA4QChADEAQQCRAF"
        case .playlists: return "EgWKAQIoAWoMEA4QChADEAQQCRAF"
        }
    }
}

/// Reads YouTube Music's pages (WEB_REMIX browse, search and next responses).
enum CatalogParser {
    private static let separator = " • "
    private static let artistPage = "MUSIC_PAGE_TYPE_ARTIST"

    // MARK: Items

    /// Any row or tile: a song, album, artist, playlist or category, from whichever renderer it is.
    static func item(_ element: Any?) -> CatalogItem? {
        guard let object = element as? JSONTree.Object else { return nil }
        if let row = object["musicResponsiveListItemRenderer"] as? JSONTree.Object { return listItem(row) }
        if let tile = object["musicTwoRowItemRenderer"] as? JSONTree.Object { return tileItem(tile) }
        if let button = object["musicNavigationButtonRenderer"] as? JSONTree.Object { return navigationButton(button) }
        if object["flexColumns"] != nil { return listItem(object) }
        if object["title"] != nil, object["navigationEndpoint"] != nil || object["thumbnailRenderer"] != nil { return tileItem(object) }
        return nil
    }

    /// A list row: a song when it plays something, otherwise whatever its link opens.
    static func listItem(_ row: JSONTree.Object) -> CatalogItem? {
        if let song = SearchParser.parseListItem(row) { return .song(song) }
        let columns = JSONTree.array(row["flexColumns"]).map { JSONTree.path($0, "musicResponsiveListItemFlexColumnRenderer", "text") }
        let title = JSONTree.text(columns.first)
        guard !title.isEmpty else { return nil }
        let subtitle = columns.dropFirst().map { JSONTree.text($0) }.filter { !$0.isEmpty }.joined(separator: separator)
        let endpoint: Any? = row["navigationEndpoint"]
            ?? JSONTree.path(JSONTree.array(JSONTree.path(columns.first, "runs")).first, "navigationEndpoint")
        return linked(title: title, subtitle: subtitle.isEmpty ? nil : subtitle, endpoint: endpoint,
                      artwork: SearchParser.largestThumbnail(row))
    }

    /// A tile: title over subtitle, opening an album, playlist or artist, or playing a song.
    static func tileItem(_ tile: JSONTree.Object) -> CatalogItem? {
        let title = JSONTree.text(tile["title"])
        guard !title.isEmpty else { return nil }
        let subtitle = JSONTree.text(tile["subtitle"])
        let endpoint: Any? = tile["navigationEndpoint"]
            ?? JSONTree.path(JSONTree.array(JSONTree.path(tile, "title", "runs")).first, "navigationEndpoint")
        if let videoId = JSONTree.string(endpoint, "watchEndpoint", "videoId") {
            let type = JSONTree.findString(tile, key: "musicVideoType")
            let runs = JSONTree.array(JSONTree.path(tile, "subtitle", "runs"))
            return .song(SearchParser.track(videoId: videoId, rawTitle: title, bylineRuns: runs, item: tile, type: type))
        }
        return linked(title: title, subtitle: subtitle.isEmpty ? nil : subtitle, endpoint: endpoint,
                      artwork: SearchParser.largestThumbnail(tile))
    }

    private static func navigationButton(_ button: JSONTree.Object) -> CatalogItem? {
        let title = JSONTree.text(button["buttonText"])
        guard !title.isEmpty, let target = browseTarget(JSONTree.path(button, "clickCommand")) else { return nil }
        return .page(title: title, target: target)
    }

    private static func linked(title: String, subtitle: String?, endpoint: Any?, artwork: String?) -> CatalogItem? {
        guard let browseId = JSONTree.string(endpoint, "browseEndpoint", "browseId") else {
            // Some playlists only link through a watch endpoint with a playlist ID.
            if let playlistId = JSONTree.string(endpoint, "watchPlaylistEndpoint", "playlistId")
                ?? JSONTree.string(endpoint, "watchEndpoint", "playlistId") {
                return .playlist(PlaylistRef(playlistId: playlistId, title: title, subtitle: subtitle,
                                             artworkURL: TitleCleaner.artworkURL(artwork)))
            }
            return nil
        }
        let pageType = JSONTree.string(endpoint, "browseEndpoint", "browseEndpointContextSupportedConfigs",
                                       "browseEndpointContextMusicConfig", "pageType")
        let art = TitleCleaner.artworkURL(artwork)
        if browseId.hasPrefix("MPREb_") || pageType == "MUSIC_PAGE_TYPE_ALBUM" {
            return .album(AlbumRef(browseId: browseId, title: title, subtitle: subtitle, artworkURL: art))
        }
        if browseId.hasPrefix("UC") || pageType == artistPage {
            return .artist(ArtistRef(channelId: browseId, name: title, subtitle: subtitle, artworkURL: art))
        }
        if browseId.hasPrefix("VL") || pageType == "MUSIC_PAGE_TYPE_PLAYLIST" {
            let id = browseId.hasPrefix("VL") ? String(browseId.dropFirst(2)) : browseId
            return .playlist(PlaylistRef(playlistId: id, title: title, subtitle: subtitle, artworkURL: art))
        }
        // Podcasts, episodes and other pages aren't music.
        if pageType?.contains("PODCAST") == true || browseId.hasPrefix("MPSP") { return nil }
        return .page(title: title, target: BrowseTarget(browseId: browseId, params: JSONTree.string(endpoint, "browseEndpoint", "params")))
    }

    static func browseTarget(_ endpoint: Any?) -> BrowseTarget? {
        guard let id = JSONTree.string(endpoint, "browseEndpoint", "browseId") else { return nil }
        return BrowseTarget(browseId: id, params: JSONTree.string(endpoint, "browseEndpoint", "params"))
    }

    // MARK: Search

    /// Items of a filtered search page (or its continuation), plus the token for more.
    static func search(_ root: JSONTree.Object) -> (items: [CatalogItem], continuation: String?) {
        var items: [CatalogItem] = []
        var seen = Set<String>()
        for row in JSONTree.findAll(root, key: "musicResponsiveListItemRenderer") {
            if let item = listItem(row), seen.insert(item.key).inserted { items.append(item) }
        }
        return (items, listContinuation(root, in: ["musicShelfRenderer", "musicShelfContinuation"]))
    }

    /// The token for the next page of a list (search results, playlist songs). Only the list's
    /// own token counts: pages also carry one for the rows of suggestions below them.
    static func listContinuation(_ root: Any?, in containers: [String]) -> String? {
        let lists = containers.flatMap { JSONTree.findAll(root, key: $0) }
            + JSONTree.findAll(root, key: "appendContinuationItemsAction")
        return lists.lazy.compactMap { list in
            JSONTree.array(list["continuations"]).lazy.compactMap { JSONTree.string($0, "nextContinuationData", "continuation") }.first
                ?? JSONTree.array(list["contents"] ?? list["continuationItems"]).lazy.compactMap {
                    JSONTree.string($0, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")
                }.first
        }.first
    }

    static func continuation(_ root: Any?) -> String? {
        JSONTree.findAll(root, key: "nextContinuationData").lazy.compactMap { $0["continuation"] as? String }.first
            ?? JSONTree.findAll(root, key: "continuationCommand").lazy.compactMap { $0["token"] as? String }.first
    }

    // MARK: Shelves (home, moods, artist pages, new releases)

    /// Every titled row on a page: carousels, list shelves and grids.
    static func shelves(_ root: Any?) -> [Shelf] {
        var shelves: [Shelf] = []
        for carousel in JSONTree.findAll(root, key: "musicCarouselShelfRenderer") {
            let header = JSONTree.path(carousel, "header", "musicCarouselShelfBasicHeaderRenderer")
            let title = JSONTree.text(JSONTree.path(header, "title"))
            let items = unique(JSONTree.array(carousel["contents"]).compactMap(item))
            let more = browseTarget(JSONTree.path(header, "moreContentButton", "buttonRenderer", "navigationEndpoint"))
                ?? JSONTree.array(JSONTree.path(header, "title", "runs")).first.flatMap { browseTarget(JSONTree.path($0, "navigationEndpoint")) }
            if !title.isEmpty, !items.isEmpty { shelves.append(Shelf(title: title, items: items, more: more)) }
        }
        for grid in JSONTree.findAll(root, key: "gridRenderer") {
            let title = JSONTree.text(JSONTree.path(grid, "header", "gridHeaderRenderer", "title"))
            let items = unique(JSONTree.array(grid["items"]).compactMap(item))
            if !items.isEmpty { shelves.append(Shelf(title: title, items: items, more: nil)) }
        }
        for shelf in JSONTree.findAll(root, key: "musicShelfRenderer") {
            let title = JSONTree.text(shelf["title"])
            let items = unique(JSONTree.array(shelf["contents"]).compactMap(item))
            let more = browseTarget(JSONTree.path(shelf, "bottomEndpoint"))
                ?? JSONTree.array(JSONTree.path(shelf, "title", "runs")).first.flatMap { browseTarget(JSONTree.path($0, "navigationEndpoint")) }
            if !items.isEmpty { shelves.append(Shelf(title: title, items: items, more: more)) }
        }
        return shelves
    }

    private static func unique(_ items: [CatalogItem]) -> [CatalogItem] {
        var seen = Set<String>()
        return items.filter { seen.insert($0.key).inserted }
    }

    // MARK: Album, playlist, artist

    static func album(_ root: JSONTree.Object, browseId: String) -> AlbumPage {
        let header = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"].lazy
            .compactMap { JSONTree.findAll(root, key: $0).first }.first
        let title = JSONTree.text(header?["title"])
        let strapline = JSONTree.array(JSONTree.path(header, "straplineTextOne", "runs"))
        let artist = SearchParser.joined(strapline).nilIfEmpty
            ?? JSONTree.text(header?["subtitle"]).components(separatedBy: separator).dropFirst().first
        let artistId = strapline.lazy.compactMap { JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseId") }.first
        let subtitle = JSONTree.text(header?["subtitle"])
        let art = TitleCleaner.artworkURL(SearchParser.largestThumbnail(header))
        let album = AlbumRef(browseId: browseId, title: title.isEmpty ? "Album" : title,
                             subtitle: subtitle.isEmpty ? artist : subtitle, artworkURL: art)
        // Album tracks leave out the album's artist and art: fill them in.
        let tracks = JSONTree.findAll(root, key: "musicResponsiveListItemRenderer").compactMap(SearchParser.parseListItem).map { track -> Track in
            var t = track
            if t.artist == TitleCleaner.unknownArtist, let artist { t.artist = TitleCleaner.cleanArtist(artist) }
            t.album = t.album ?? album.title
            t.albumId = browseId
            t.artistId = t.artistId ?? artistId
            t.artworkURL = art ?? t.artworkURL
            t.isVideo = false
            return t
        }
        return AlbumPage(album: album, artist: artist, artistId: artistId, tracks: uniqueTracks(tracks))
    }

    static func playlist(_ root: JSONTree.Object) -> PlaylistPage {
        let header = ["musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer"].lazy
            .compactMap { JSONTree.findAll(root, key: $0).first }.first
        let title = JSONTree.text(header?["title"])
        let subtitle = [JSONTree.text(header?["straplineTextOne"]), JSONTree.text(header?["subtitle"])].first { !$0.isEmpty }
        let tracks = JSONTree.findAll(root, key: "musicResponsiveListItemRenderer").compactMap(SearchParser.parseListItem)
        return PlaylistPage(title: title.nilIfEmpty, subtitle: subtitle, artworkURL: TitleCleaner.artworkURL(SearchParser.largestThumbnail(header)),
                            tracks: uniqueTracks(tracks),
                            continuation: listContinuation(root, in: ["musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation"]))
    }

    static func artist(_ root: JSONTree.Object) -> ArtistPage {
        let header = ["musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicResponsiveHeaderRenderer"].lazy
            .compactMap { JSONTree.findAll(root, key: $0).first }.first
        let topShelf = JSONTree.findAll(root, key: "musicShelfRenderer").first
        let topSongs = JSONTree.array(topShelf?["contents"]).compactMap {
            (JSONTree.path($0, "musicResponsiveListItemRenderer") as? JSONTree.Object).flatMap(SearchParser.parseListItem)
        }
        let allSongs = JSONTree.array(JSONTree.path(topShelf, "title", "runs")).first
            .flatMap { JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseId") }
            .flatMap { $0.hasPrefix("VL") ? String($0.dropFirst(2)) : nil }
        // Rows of videos aren't for an audio player.
        let shelves = Self.shelves(root).filter { shelf in
            let lower = shelf.title.lowercased()
            return !lower.contains("video") && shelf.title != JSONTree.text(topShelf?["title"])
        }
        return ArtistPage(name: JSONTree.text(header?["title"]).nilIfEmpty ?? "Artist",
                          about: JSONTree.text(header?["description"]).nilIfEmpty,
                          artworkURL: TitleCleaner.artworkURL(SearchParser.largestThumbnail(header)),
                          topSongs: uniqueTracks(topSongs), allSongsPlaylistId: allSongs, shelves: shelves)
    }

    // MARK: Radio

    /// A radio station's queue from the "next" endpoint.
    static func radio(_ root: JSONTree.Object) -> [Track] {
        uniqueTracks(JSONTree.findAll(root, key: "playlistPanelVideoRenderer").compactMap { item -> Track? in
            guard let videoId = item["videoId"] as? String else { return nil }
            let title = JSONTree.text(item["title"])
            guard !title.isEmpty else { return nil }
            let runs = JSONTree.array(JSONTree.path(item, "longBylineText", "runs"))
            var track = SearchParser.track(videoId: videoId, rawTitle: title, bylineRuns: runs, item: item,
                                           type: JSONTree.findString(item, key: "musicVideoType"))
            track.duration = track.duration ?? TitleCleaner.parseDuration(JSONTree.text(item["lengthText"]))
            return track
        })
    }

    // MARK: Charts (charts.youtube.com)

    static func chart(_ root: JSONTree.Object) -> (entries: [ChartEntry], week: String?) {
        let content = JSONTree.path(JSONTree.array(JSONTree.path(root, "contents", "sectionListRenderer", "contents")).first,
                                    "musicAnalyticsSectionRenderer", "content")
        let list = JSONTree.array(JSONTree.path(content, "trackTypes")).first
        let entries = JSONTree.array(JSONTree.path(list, "trackViews")).enumerated().compactMap { index, element -> ChartEntry? in
            guard let e = element as? JSONTree.Object, let name = e["name"] as? String else { return nil }
            // The "art track" (official audio with album art) over the music video.
            guard let id = (e["atvExternalVideoId"] as? String).flatMap({ $0.isEmpty ? nil : $0 }) ?? e["encryptedVideoId"] as? String
            else { return nil }
            let artists = JSONTree.array(e["artists"]).compactMap { JSONTree.string($0, "name") }
            let meta = e["chartEntryMetadata"] as? JSONTree.Object
            let thumbs = JSONTree.array(JSONTree.path(e, "thumbnail", "thumbnails")).compactMap { $0 as? JSONTree.Object }
            let art = thumbs.max { ($0["width"] as? Int ?? 0) < ($1["width"] as? Int ?? 0) }?["url"] as? String
            let track = Track(id: id, title: name, artist: artists.isEmpty ? TitleCleaner.unknownArtist : artists.joined(separator: ", "),
                              artworkURL: TitleCleaner.artworkURL(art), isVideo: e["atvExternalVideoId"] == nil,
                              artistId: JSONTree.array(e["artists"]).first.flatMap { JSONTree.string($0, "externalChannelId") })
            let rank = (meta?["currentPosition"] as? Int) ?? index + 1
            let previous = (meta?["previousPosition"] as? Int).flatMap { $0 > 0 ? $0 : nil }
            return ChartEntry(rank: rank, previousRank: previous, track: track)
        }.sorted { $0.rank < $1.rank }
        return (entries, JSONTree.string(list, "endDate"))
    }

    static func artistChart(_ root: JSONTree.Object) -> [ArtistRef] {
        let content = JSONTree.path(JSONTree.array(JSONTree.path(root, "contents", "sectionListRenderer", "contents")).first,
                                    "musicAnalyticsSectionRenderer", "content")
        let list = JSONTree.array(JSONTree.path(content, "artists")).first
        return JSONTree.array(JSONTree.path(list, "artistViews")).compactMap { element -> ArtistRef? in
            guard let e = element as? JSONTree.Object, let id = e["externalChannelId"] as? String,
                  let name = e["name"] as? String else { return nil }
            let thumbs = JSONTree.array(JSONTree.path(e, "thumbnail", "thumbnails")).compactMap { $0 as? JSONTree.Object }
            let art = thumbs.max { ($0["width"] as? Int ?? 0) < ($1["width"] as? Int ?? 0) }?["url"] as? String
            let rank = JSONTree.path(e, "chartEntryMetadata", "currentPosition") as? Int
            return ArtistRef(channelId: id, name: name, subtitle: rank.map { "#\($0)" }, artworkURL: TitleCleaner.artworkURL(art))
        }
    }

    private static func uniqueTracks(_ tracks: [Track]) -> [Track] {
        var seen = Set<String>()
        return tracks.filter { seen.insert($0.id).inserted }
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Requests

extension YouTube {
    /// One page of a filtered search, or the next page with `continuation`.
    func search(_ query: String, filter: SearchFilter, continuation: String? = nil) async throws -> (items: [CatalogItem], continuation: String?) {
        let root: JSONTree.Object
        if let continuation {
            root = try await musicPost("search", [:], query: "&ctoken=\(continuation)&continuation=\(continuation)&type=next")
        } else {
            root = try await musicPost("search", ["query": query, "params": filter.params])
        }
        return CatalogParser.search(root)
    }

    func album(_ browseId: String) async throws -> AlbumPage {
        CatalogParser.album(try await musicPost("browse", ["browseId": browseId]), browseId: browseId)
    }

    /// A playlist's first page of songs, or the next page with `continuation`.
    func playlist(_ playlistId: String, continuation: String? = nil) async throws -> PlaylistPage {
        if let continuation {
            return CatalogParser.playlist(try await musicPost("browse", ["continuation": continuation]))
        }
        return CatalogParser.playlist(try await musicPost("browse", ["browseId": "VL" + playlistId]))
    }

    func artist(_ channelId: String) async throws -> ArtistPage {
        CatalogParser.artist(try await musicPost("browse", ["browseId": channelId]))
    }

    /// Any other page: "See all", a Moods & Genres category, New Releases, Home.
    func shelves(_ target: BrowseTarget) async throws -> [Shelf] {
        var body: [String: Any] = ["browseId": target.browseId]
        if let params = target.params { body["params"] = params }
        return CatalogParser.shelves(try await musicPost("browse", body))
    }

    /// YouTube Music's home: Quick picks and the other rows, plus the next batch of rows.
    func home() async throws -> [Shelf] {
        let root = try await musicPost("browse", ["browseId": "FEmusic_home"])
        var shelves = CatalogParser.shelves(root)
        if let more = CatalogParser.continuation(root),
           let next = try? await musicPost("browse", [:], query: "&ctoken=\(more)&continuation=\(more)&type=next") {
            shelves += CatalogParser.shelves(next)
        }
        return shelves.filter { shelf in shelf.items.contains { if case .song = $0 { return true } else { return false } } || !shelf.title.lowercased().contains("podcast") }
    }

    /// A radio station from a song: similar songs, the song itself first.
    func radio(_ videoId: String) async throws -> [Track] {
        CatalogParser.radio(try await musicPost("next", [
            "videoId": videoId,
            "playlistId": "RDAMVM" + videoId,
            "params": "wAEB",
            "isAudioOnly": true,
            "enablePersistentPlaylistPanel": true,
            "tunerSettingValue": "AUTOMIX_SETTING_NORMAL",
        ]))
    }

    /// The weekly Top 100 from YouTube Charts for a country ("global" or a two-letter code).
    func chart(country: String, artists: Bool = false) async throws -> JSONTree.Object {
        let code = country.lowercased()
        let body: [String: Any] = [
            "context": ["client": ["clientName": "WEB_MUSIC_ANALYTICS", "clientVersion": "2.0",
                                   "hl": "en", "gl": code == "global" ? "US" : code.uppercased()]],
            "browseId": "FEmusic_analytics_charts_home",
            "query": "perspective=CHART_DETAILS&chart_params_country_code=\(code)&chart_params_chart_type=\(artists ? "ARTISTS" : "TRACKS")&chart_params_period_type=WEEKLY",
        ]
        var request = URLRequest(url: URL(string: "https://charts.youtube.com/youtubei/v1/browse?alt=json&prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("https://charts.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("https://charts.youtube.com/", forHTTPHeaderField: "Referer")
        request.setValue("31", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue("2.0", forHTTPHeaderField: "X-YouTube-Client-Version")
        var attempt = 0
        while true {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            // Charts turns away bursts of requests: wait and ask again.
            if (status == 429 || status == 503), attempt < 2 {
                attempt += 1
                try await Task.sleep(nanoseconds: UInt64(2_000_000_000 * attempt))
                continue
            }
            guard (200..<300).contains(status) else { throw YouTubeError.http(status) }
            return try JSONTree.parse(data)
        }
    }

    func topSongs(country: String) async throws -> (entries: [ChartEntry], week: String?) {
        CatalogParser.chart(try await chart(country: country))
    }

    func topArtists(country: String) async throws -> [ArtistRef] {
        CatalogParser.artistChart(try await chart(country: country, artists: true))
    }
}
