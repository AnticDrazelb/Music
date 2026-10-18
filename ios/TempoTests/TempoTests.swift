import XCTest
@testable import Tempo

final class SearchParserTests: XCTestCase {
    private func fixture(_ name: String) throws -> JSONTree.Object {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: name, withExtension: "json"))
        return try JSONTree.parse(Data(contentsOf: url))
    }

    func testUnfilteredSearchKeepsVideosAndDropsPodcasts() throws {
        let tracks = SearchParser.songs(in: try fixture("search_unfiltered"))
        let ids = tracks.map(\.id)
        XCTAssertTrue(ids.contains("vbvyNnw8Qjg"))
        XCTAssertFalse(ids.contains("-wCH-lOv2y0"), "podcast episodes aren't songs")
        XCTAssertEqual(Set(ids).count, ids.count)

        let liveAid = try XCTUnwrap(tracks.first { $0.id == "vbvyNnw8Qjg" })
        XCTAssertEqual(liveAid.artist, "Queen")
        XCTAssertEqual(liveAid.title, "Bohemian Rhapsody (Live Aid 1985)")
        XCTAssertNotNil(liveAid.artworkURL)
    }

    func testCatalogueSongRow() throws {
        let row: JSONTree.Object = [
            "playlistItemData": ["videoId": "abcdefghijk"],
            "thumbnail": ["musicThumbnailRenderer": ["thumbnail": ["thumbnails": [
                ["url": "https://lh3.googleusercontent.com/x=w60-h60-l90-rj", "width": 60],
                ["url": "https://lh3.googleusercontent.com/x=w120-h120-l90-rj", "width": 120],
            ]]]],
            "flexColumns": [
                ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [[
                    "text": "One More Time",
                    "navigationEndpoint": ["watchEndpoint": ["videoId": "abcdefghijk",
                        "watchEndpointMusicSupportedConfigs": ["watchEndpointMusicConfig": ["musicVideoType": "MUSIC_VIDEO_TYPE_ATV"]]]],
                ]]]]],
                ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [
                    ["text": "Daft Punk", "navigationEndpoint": ["browseEndpoint": ["browseId": "UC123"]]],
                    ["text": " • "],
                    ["text": "Discovery", "navigationEndpoint": ["browseEndpoint": ["browseId": "MPREb_abc"]]],
                    ["text": " • "],
                    ["text": "5:21"],
                ]]]],
            ],
        ]
        let track = try XCTUnwrap(SearchParser.parseListItem(row))
        XCTAssertEqual(track.id, "abcdefghijk")
        XCTAssertEqual(track.title, "One More Time")
        XCTAssertEqual(track.artist, "Daft Punk")
        XCTAssertEqual(track.album, "Discovery")
        XCTAssertEqual(track.duration, 321)
        XCTAssertEqual(track.artworkURL?.absoluteString, "https://lh3.googleusercontent.com/x=w544-h544-l90-rj")
    }
}

final class TitleCleanerTests: XCTestCase {
    func testSplitsArtistAndTitle() {
        let r = TitleCleaner.split(title: "Haddaway - What Is Love (Official 4K Video)", uploader: "DECADR")
        XCTAssertEqual(r.title, "What Is Love")
        XCTAssertEqual(r.artist, "Haddaway")
    }

    func testTopicAndVevoChannels() {
        XCTAssertEqual(TitleCleaner.cleanArtist("Rick Astley - Topic"), "Rick Astley")
        XCTAssertEqual(TitleCleaner.cleanArtist("TaylorSwiftVEVO"), "Taylor Swift")
    }

    func testKeepsVersionSuffix() {
        let r = TitleCleaner.split(title: "Heroes - 2017 Remaster", uploader: "David Bowie - Topic")
        XCTAssertEqual(r.title, "Heroes - 2017 Remaster")
        XCTAssertEqual(r.artist, "David Bowie")
    }

    func testDurations() {
        XCTAssertEqual(TitleCleaner.parseDuration("3:45"), 225)
        XCTAssertEqual(TitleCleaner.parseDuration("1:02:03"), 3723)
        XCTAssertNil(TitleCleaner.parseDuration("12M views"))
    }
}

final class StreamPickingTests: XCTestCase {
    func testPicksBestOriginalAAC() throws {
        let root: JSONTree.Object = ["streamingData": ["adaptiveFormats": [
            ["itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160_000, "url": "https://a/251"],
            ["itag": 139, "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"", "bitrate": 50_000, "url": "https://a/139"],
            ["itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130_000, "url": "https://a/140", "contentLength": "3456789"],
            ["itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 140_000, "url": "https://a/dub",
             "audioTrack": ["audioIsDefault": false]],
            ["itag": 137, "mimeType": "video/mp4", "bitrate": 4_000_000, "url": "https://a/137"],
        ]]]
        let stream = try XCTUnwrap(YouTube.bestAudio(in: root, userAgent: "UA"))
        XCTAssertEqual(stream.url.absoluteString, "https://a/140")
        XCTAssertEqual(stream.contentLength, 3_456_789)
        XCTAssertEqual(stream.headers["User-Agent"], "UA")
        XCTAssertFalse(stream.isHLS)
    }
}
