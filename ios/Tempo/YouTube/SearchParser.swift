import Foundation

/// Reads songs out of a YouTube Music search response.
enum SearchParser {
    private static let separator = " • "
    /// Leading labels on unfiltered results ("Song • Artist • Album • 3:45").
    private static let typeLabels: Set<String> = ["Song", "Video"]
    private static let skippedTypes: Set<String> = ["MUSIC_VIDEO_TYPE_PODCAST_EPISODE"]
    private static let catalogue = "MUSIC_VIDEO_TYPE_ATV"

    static func songs(in root: JSONTree.Object) -> [Track] {
        var seen = Set<String>()
        var result: [Track] = []
        // The "Top result" card comes first on unfiltered searches.
        for card in JSONTree.findAll(root, key: "musicCardShelfRenderer") {
            if let track = parseCard(card), seen.insert(track.id).inserted { result.append(track) }
        }
        for item in JSONTree.findAll(root, key: "musicResponsiveListItemRenderer") {
            if let track = parseListItem(item), seen.insert(track.id).inserted { result.append(track) }
        }
        return result
    }

    /// A result row: title | "Artist • Album • 3:45" (each a column or a run).
    static func parseListItem(_ item: JSONTree.Object) -> Track? {
        let columns = JSONTree.array(item["flexColumns"]).map {
            JSONTree.path($0, "musicResponsiveListItemFlexColumnRenderer", "text")
        }
        guard let first = columns.first else { return nil }
        let titleRuns = JSONTree.array(JSONTree.path(first, "runs"))
        let videoId = JSONTree.string(item, "playlistItemData", "videoId")
            ?? titleRuns.lazy.compactMap { JSONTree.string($0, "navigationEndpoint", "watchEndpoint", "videoId") }.first
        guard let videoId else { return nil }
        let type = JSONTree.findString(item, key: "musicVideoType")
        if let type, skippedTypes.contains(type) { return nil }
        let title = JSONTree.text(first)
        guard !title.isEmpty else { return nil }

        let bylineRuns = columns.dropFirst().flatMap { column -> [Any] in
            let runs = JSONTree.array(JSONTree.path(column, "runs"))
            return runs.isEmpty ? [] : runs + [["text": separator]]
        }
        var song = track(videoId: videoId, rawTitle: title, bylineRuns: bylineRuns, item: item, type: type)
        // Album and playlist rows put the length in a column on the right.
        if song.duration == nil, let fixed = JSONTree.array(item["fixedColumns"]).first {
            song.duration = TitleCleaner.parseDuration(JSONTree.text(JSONTree.path(fixed, "musicResponsiveListItemFixedColumnRenderer", "text")))
        }
        return song
    }

    /// The big "Top result" card on unfiltered searches, when it's a song or video.
    static func parseCard(_ card: JSONTree.Object) -> Track? {
        let titleRuns = JSONTree.array(JSONTree.path(card, "title", "runs"))
        guard let videoId = titleRuns.lazy.compactMap({ JSONTree.string($0, "navigationEndpoint", "watchEndpoint", "videoId") }).first
        else { return nil }
        let type = JSONTree.findString(card, key: "musicVideoType")
        if let type, skippedTypes.contains(type) { return nil }
        let runs = JSONTree.array(JSONTree.path(card, "subtitle", "runs"))
        return track(videoId: videoId, rawTitle: JSONTree.text(card["title"]), bylineRuns: runs, item: card, type: type)
    }

    static func track(videoId: String, rawTitle: String, bylineRuns: [Any], item: JSONTree.Object, type: String?) -> Track {
        // Split the byline into its " • " separated parts, keeping each part's runs.
        var parts: [[Any]] = [[]]
        for run in bylineRuns {
            if JSONTree.string(run, "text") == separator { parts.append([]) } else { parts[parts.count - 1].append(run) }
        }
        // Play and view counts ("114 plays", "3.2M views") aren't artists.
        parts = parts.filter { !$0.isEmpty && !isCount(joined($0)) }
        if let label = parts.first.map(joined), typeLabels.contains(label) { parts.removeFirst() }

        let artistText = parts.first.map(joined) ?? ""
        let albumPart = parts.dropFirst().first { part in
            part.contains { (JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseId") ?? "").hasPrefix("MPREb_") }
        }
        let albumId = albumPart?.lazy.compactMap { JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseId") }.first
        let artistId = parts.first?.lazy.compactMap { JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseId") }
            .first { $0.hasPrefix("UC") }
        let duration = parts.lazy.map(joined).compactMap(TitleCleaner.parseDuration).first
        let isArtistPage = parts.first?.contains {
            JSONTree.string($0, "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                            "browseEndpointContextMusicConfig", "pageType") == "MUSIC_PAGE_TYPE_ARTIST"
        } ?? false

        let title: String, artist: String
        if type == catalogue {
            title = TitleCleaner.stripNoise(rawTitle)
            artist = TitleCleaner.cleanArtist(artistText)
        } else {
            let split = TitleCleaner.split(title: rawTitle, uploader: artistText.isEmpty ? nil : artistText,
                                           artistIsReliable: isArtistPage)
            title = split.title
            artist = split.artist
        }
        return Track(
            id: videoId,
            title: title,
            artist: artist,
            album: albumPart.map(joined),
            duration: duration,
            artworkURL: TitleCleaner.artworkURL(largestThumbnail(item)),
            isVideo: type.map { $0 != catalogue },
            albumId: albumId,
            artistId: artistId
        )
    }

    private static func isCount(_ text: String) -> Bool {
        text.range(of: #"^[\d.,]+[KMB]? (plays?|views?|listeners?)$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    static func joined(_ runs: [Any]) -> String {
        runs.compactMap { JSONTree.string($0, "text") }.joined().trimmingCharacters(in: .whitespaces)
    }

    static func largestThumbnail(_ item: Any?) -> String? {
        let lists = [
            JSONTree.path(item, "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"),
            JSONTree.path(item, "thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails"),
            JSONTree.path(item, "thumbnail", "thumbnails"),
            JSONTree.path(item, "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"),
            JSONTree.path(item, "foregroundThumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"),
        ]
        let list = lists.lazy.compactMap { $0 as? [Any] }.first ?? []
        return list.compactMap { $0 as? JSONTree.Object }
            .max { ($0["width"] as? Int ?? 0) < ($1["width"] as? Int ?? 0) }?["url"] as? String
    }
}
