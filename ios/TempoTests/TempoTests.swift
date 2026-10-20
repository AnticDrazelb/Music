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
        let client = YouTube.clients[0]
        let stream = try XCTUnwrap(YouTube.bestAudio(in: root, client: client))
        XCTAssertEqual(stream.url.absoluteString, "https://a/140")
        XCTAssertEqual(stream.contentLength, 3_456_789)
        XCTAssertEqual(stream.headers["User-Agent"], client.userAgent)
        XCTAssertEqual(stream.client, client.name)
        XCTAssertFalse(stream.isHLS)
    }

    func testFindsScrambledFormat() throws {
        let root: JSONTree.Object = ["streamingData": ["adaptiveFormats": [
            ["itag": 140, "mimeType": "audio/mp4", "bitrate": 130_000, "signatureCipher": "s=abc&sp=sig&url=https%3A%2F%2Fa%2F140"],
        ]]]
        XCTAssertEqual(YouTube.bestAudioFormat(in: root)?["itag"] as? Int, 140)
        // Without a plain URL it needs the player script, so no direct stream.
        XCTAssertNil(YouTube.bestAudio(in: root, client: YouTube.clients[0]))
    }
}

/// Runs the bundled solver on YouTube's current player script in JavaScriptCore. Needs the
/// network; skipped when YouTube's player script can't be loaded.
final class PlayerScriptTests: XCTestCase {
    private let n = "ZdZIqFPQK-Ty8wId"
    private let sig = "gN7a-hudCuAuPH6fByOk1_GNXN0yNMHShjZXS2VOgsEItAJz0tipeavEOmNdYN-wUtcEqD3bCXjc0iyKfAyZxCBGgIARwsSdQfJ2CJtt"

    private func timestamp() async throws -> Int {
        do {
            return try await PlayerScript.shared.signatureTimestamp()
        } catch PlayerScript.Failure.noPlayer {
            throw XCTSkip("YouTube's player script couldn't be loaded")
        }
    }

    func testSolvesLivePlayer() async throws {
        let sts = try await timestamp()
        XCTAssertGreaterThan(sts, 10_000)
        let answers = try await PlayerScript.shared.solve(n: [n], sig: [sig])
        let solvedN = try XCTUnwrap(answers.n[n])
        XCTAssertFalse(solvedN.isEmpty)
        XCTAssertNotEqual(solvedN, n)
        let solvedSig = try XCTUnwrap(answers.sig[sig])
        XCTAssertNotEqual(solvedSig, sig)
    }

    func testUnlocksScrambledFormat() async throws {
        _ = try await timestamp()
        let base = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=140&n=\(n)&mime=audio%2Fmp4"
        let cipher = "s=\(sig)&sp=sig&url=\(base.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!)"
        let url = try await PlayerScript.shared.url(for: ["signatureCipher": cipher, "mimeType": "audio/mp4"])
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(items.first { $0.name == "itag" }?.value, "140")
        XCTAssertNotEqual(items.first { $0.name == "n" }?.value, n)
        XCTAssertNotNil(items.first { $0.name == "sig" }?.value)
    }
}
