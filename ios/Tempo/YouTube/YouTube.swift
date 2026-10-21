import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking // URLSession on Linux, for the stream check
#endif

enum YouTubeError: LocalizedError {
    case unexpectedResponse
    case http(Int)
    case unplayable(String)
    case noAudio
    case allFailed([String])
    case audioRefused(Int)
    /// The download stopped partway (at this percentage): a link that needs a token.
    case cutOff(Int, Int)

    var errorDescription: String? {
        switch self {
        case .unexpectedResponse: return "YouTube sent something unexpected"
        case .http(let code): return "YouTube refused the request (HTTP \(code))"
        case .audioRefused(let code): return "audio refused (HTTP \(code))"
        case .cutOff(let code, let percent): return "cut off at \(percent)% (HTTP \(code))"
        case .unplayable(let reason): return reason
        case .noAudio: return "No downloadable audio for this song"
        case .allFailed(let notes): return notes.isEmpty ? "No playable audio for this song" : notes.joined(separator: "\n")
        }
    }
}

/// A playable audio stream for one video, with the headers googlevideo expects.
struct ResolvedStream {
    let url: URL
    let mimeType: String
    let contentLength: Int64?
    let headers: [String: String]
    /// True for an HLS manifest (a playlist of short segments).
    let isHLS: Bool
    /// Which YouTube client handed it out, for error messages.
    let client: String

    /// A request for some of the file, made the way YouTube's own apps (and NewPipe) make
    /// them: a POST with a two-byte body, a request number, and a Range header.
    func request(bytes start: Int64, through end: Int64, number: Int) -> URLRequest {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.queryItems = (components.queryItems ?? []) + [URLQueryItem(name: "rn", value: String(number))]
        var request = URLRequest(url: components.url ?? url)
        request.httpMethod = "POST"
        request.httpBody = Data([0x78, 0x00])
        request.timeoutInterval = 30
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
        return request
    }

    /// The file's full size from a ranged response ("bytes 0-1023/4567890").
    static func totalLength(_ response: HTTPURLResponse) -> Int64? {
        (response.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap { Int64($0) }
    }
}

/// YouTube Music's own web API (InnerTube): search through the WEB_REMIX client, and audio
/// streams through the app clients that hand out direct URLs.
actor YouTube {
    static let shared = YouTube()

    private let session: URLSession
    private var visitorData: String?

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        session = URLSession(configuration: config)
    }

    private static let musicClientVersion = "1.20250929.03.00"
    private static let webUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    /// YouTube Music's "Songs" search filter.
    private static let songsFilter = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

    private var region: String { Locale.current.region?.identifier ?? "US" }
    private var language: String { Locale.current.language.languageCode?.identifier ?? "en" }

    // MARK: Search

    /// Songs matching `query`: YouTube Music's catalogue first, then any music uploads.
    func searchSongs(_ query: String) async throws -> [Track] {
        let songs = SearchParser.songs(in: try await musicPost("search", ["query": query, "params": Self.songsFilter]))
        if !songs.isEmpty { return songs }
        return SearchParser.songs(in: try await musicPost("search", ["query": query]))
    }

    /// The other upload of the same song: for a music video, its catalogue "art track" (just the
    /// album audio, with the cover art); for an art track, any upload of it. YouTube often serves
    /// one when it refuses the other.
    func alternative(to track: Track) async -> Track? {
        guard let results = try? await searchSongs("\(track.title) \(track.artist)") else { return nil }
        func normalized(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }
        let title = normalized(track.title), artist = normalized(track.artist)
        let matches = results.filter { other in
            guard other.id != track.id else { return false }
            let otherTitle = normalized(other.title), otherArtist = normalized(other.artist)
            let sameTitle = !title.isEmpty && (otherTitle == title || otherTitle.hasPrefix(title) || title.hasPrefix(otherTitle))
            let sameArtist = artist.isEmpty || otherArtist.contains(artist) || artist.contains(otherArtist)
            // Within 15 seconds when both lengths are known, so a live or extended version isn't picked.
            let sameLength = track.duration == nil || other.duration == nil || abs(track.duration! - other.duration!) <= 15
            return sameTitle && sameArtist && sameLength
        }
        if track.isVideo != false, let artTrack = matches.first(where: { $0.isVideo == false }) { return artTrack }
        return matches.first
    }

    /// Live suggestions while typing.
    func suggestions(_ query: String) async throws -> [String] {
        let root = try await musicPost("music/get_search_suggestions", ["input": query])
        return JSONTree.findAll(root, key: "searchSuggestionRenderer").map { JSONTree.text($0["suggestion"]) }
            .filter { !$0.isEmpty }
    }

    /// A YouTube Music (WEB_REMIX) request; `query` is added to the URL (continuation tokens).
    func musicPost(_ endpoint: String, _ body: [String: Any], query: String = "") async throws -> JSONTree.Object {
        var payload = body
        payload["context"] = ["client": [
            "clientName": "WEB_REMIX",
            "clientVersion": Self.musicClientVersion,
            "hl": language,
            "gl": region,
        ]]
        var request = URLRequest(url: URL(string: "https://music.youtube.com/youtubei/v1/\(endpoint)?prettyPrint=false\(query)")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.webUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://music.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("https://music.youtube.com/", forHTTPHeaderField: "Referer")
        request.setValue("67", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(Self.musicClientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        if let visitorData { request.setValue(visitorData, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw YouTubeError.http(http.statusCode)
        }
        let root = try JSONTree.parse(data)
        if visitorData == nil, let v = JSONTree.string(root, "responseContext", "visitorData") { visitorData = v }
        return root
    }

    // MARK: Streams

    /// A YouTube app the player request identifies as. Each gets different links, and YouTube
    /// refuses some apps' links without a token, so several are tried in turn.
    struct Client {
        let name: String
        /// The InnerTube client name sent to YouTube.
        let clientName: String
        let id: String
        let version: String
        let userAgent: String
        let extra: [String: Any]
        /// Sends the player script's signature timestamp (its links are scrambled).
        var needsPlayerScript = false
        /// A web player: its links want youtube.com's headers.
        var web = false
        /// Says it's embedded on youtube.com.
        var embedded = false
        /// Sends a PoToken with its player request and on its stream links.
        var needsPoToken = false
        /// Asks like YouTube's own apps do (and NewPipe for them): on youtubei.googleapis.com,
        /// with a visitor ID of its own and a playback nonce (cpn) also put on its links.
        var app = false

        /// Headers googlevideo expects when fetching this client's links.
        var streamHeaders: [String: String] {
            var headers = ["User-Agent": userAgent]
            if web {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }
            return headers
        }
    }

    private static let webUserAgentChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /// Tried in this order. The visionOS app comes first: it's what NewPipe (and so the Android
    /// app) gets its audio from without any token. The mobile site, whose token takes a few
    /// seconds to make, comes last.
    static let clients: [Client] = [
        Client(
            name: "VISIONOS", clientName: "VISIONOS", id: "101", version: "1.02",
            userAgent: "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)",
            extra: ["clientScreen": "WATCH", "platform": "MOBILE", "deviceMake": "Apple", "deviceModel": "RealityDevice14,1",
                    "osName": "visionOS", "osVersion": "25.6.0.23O471", "utcOffsetMinutes": 0],
            app: true
        ),
        Client(
            name: "ANDROID_VR", clientName: "ANDROID_VR", id: "28", version: "1.65.10",
            userAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra: ["deviceMake": "Oculus", "deviceModel": "Quest 3", "androidSdkVersion": 32, "osName": "Android", "osVersion": "12L"]
        ),
        Client(
            name: "TV", clientName: "TVHTML5", id: "7", version: "7.20250923.13.00",
            userAgent: "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            extra: ["userAgent": "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"], needsPlayerScript: true
        ),
        Client(
            name: "TV_SIMPLY", clientName: "TVHTML5_SIMPLY", id: "75", version: "1.0",
            userAgent: webUserAgentChrome, extra: [:], needsPlayerScript: true
        ),
        Client(
            name: "WEB_EMBEDDED", clientName: "WEB_EMBEDDED_PLAYER", id: "56", version: "1.20260122.01.00",
            userAgent: webUserAgentChrome, extra: [:], needsPlayerScript: true, web: true, embedded: true
        ),
        // Mostly for its HLS stream.
        Client(
            name: "IOS", clientName: "IOS", id: "5", version: "20.10.4",
            userAgent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra: ["deviceMake": "Apple", "deviceModel": "iPhone16,2", "osName": "iPhone", "osVersion": "18.3.2.22D82"]
        ),
        Client(
            name: "MWEB", clientName: "MWEB", id: "2", version: "2.20250925.01.00",
            userAgent: "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
            extra: [:], needsPlayerScript: true, web: true, needsPoToken: true
        ),
    ]

    /// Visitor IDs the app-style clients got for themselves, by client name.
    private var appVisitorData: [String: String] = [:]

    /// One client's ways to get this video's audio: its best AAC (m4a) stream, then its HLS
    /// stream if it has one. AVPlayer can't play YouTube's Opus/WebM, so only AAC is used, and
    /// no video is ever fetched. Throws `allFailed` with a one-line note when the client gives
    /// nothing usable, so callers can say what each one answered.
    func streams(for videoId: String, client: Client) async throws -> [ResolvedStream] {
        do {
            if visitorData == nil { _ = try? await musicPost("search", ["query": "music"]) }
            var (root, cpn) = try await player(videoId, client: client)
            var status = JSONTree.string(root, "playabilityStatus", "status") ?? "ERROR"
            if status == "UNPLAYABLE",
               JSONTree.string(root, "playabilityStatus", "reason")?.localizedCaseInsensitiveContains("reload") == true {
                // "The page needs to be reloaded": the TV client turns down the visitor ID from
                // YouTube Music, so ask again without one.
                (root, cpn) = try await player(videoId, client: client, sendVisitorData: false)
                status = JSONTree.string(root, "playabilityStatus", "status") ?? "ERROR"
            }
            guard status == "OK" else {
                let reason = JSONTree.string(root, "playabilityStatus", "reason") ?? "can't be played"
                let short = reason.localizedCaseInsensitiveContains("bot") ? "bot check" : reason
                throw YouTubeError.allFailed(["\(client.name): \(short) (\(status))"])
            }

            var found: [ResolvedStream] = []
            var problem: String?
            if let format = Self.bestAudioFormat(in: root) {
                do {
                    var url = try await unlockedURL(format)
                    if let cpn { url = Self.adding("cpn", cpn, to: url) }
                    let audio = Self.stream(format, url: url, client: client)
                    found += client.needsPoToken ? try await withPoTokens(audio, videoId: videoId) : [audio]
                } catch {
                    problem = error.localizedDescription
                }
            }
            if let manifest = JSONTree.string(root, "streamingData", "hlsManifestUrl").flatMap(URL.init(string:)) {
                found.append(ResolvedStream(url: manifest, mimeType: "application/x-mpegURL", contentLength: nil,
                                            headers: client.streamHeaders, isHLS: true, client: client.name))
            }
            if found.isEmpty { throw YouTubeError.allFailed(["\(client.name): \(problem ?? Self.whyNoAudio(root))"]) }
            return found
        } catch YouTubeError.allFailed(let notes) {
            throw YouTubeError.allFailed(notes)
        } catch {
            throw YouTubeError.allFailed(["\(client.name): \(error.localizedDescription)"])
        }
    }

    /// A format's URL, ready to fetch. Like NewPipe, the `n` parameter on every link is run
    /// through YouTube's player script (a link with an unsolved `n` is refused), and a
    /// scrambled signature is decoded the same way.
    private func unlockedURL(_ format: JSONTree.Object) async throws -> URL {
        if let plain = (format["url"] as? String).flatMap(URL.init(string:)),
           URLComponents(url: plain, resolvingAgainstBaseURL: false)?.queryItems?.contains(where: { $0.name == "n" }) != true {
            return plain
        }
        return try await PlayerScript.shared.url(for: format)
    }

    private static func adding(_ name: String, _ value: String, to url: URL) -> URL {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.queryItems = (components.queryItems ?? []) + [URLQueryItem(name: name, value: value)]
        return components.url ?? url
    }

    /// The stream with a PoToken on it. Logged out, YouTube binds the token to the visitor ID;
    /// which spelling of the ID (or the video ID instead) it checks isn't documented, so each
    /// is offered and the first that downloads wins.
    private func withPoTokens(_ stream: ResolvedStream, videoId: String) async throws -> [ResolvedStream] {
        var identifiers: [String] = []
        if let visitorData {
            identifiers.append(visitorData)
            if let decoded = visitorData.removingPercentEncoding, decoded != visitorData { identifiers.append(decoded) }
        }
        identifiers.append(videoId)
        var streams: [ResolvedStream] = []
        for identifier in identifiers {
            let token = try await PoToken.shared.token(for: identifier)
            streams.append(ResolvedStream(url: Self.adding("pot", token, to: stream.url), mimeType: stream.mimeType,
                                          contentLength: stream.contentLength, headers: stream.headers,
                                          isHLS: false, client: stream.client))
        }
        return streams
    }

    /// Why an OK player response had no usable audio, as far as it shows.
    private static func whyNoAudio(_ root: JSONTree.Object) -> String {
        let streaming = JSONTree.path(root, "streamingData") as? JSONTree.Object
        guard let streaming else { return "no streams" }
        let formats = JSONTree.array(streaming["adaptiveFormats"]).compactMap { $0 as? JSONTree.Object }
        if formats.isEmpty {
            return streaming["serverAbrStreamingUrl"] != nil ? "only SABR streams" : "no streams"
        }
        let audio = formats.filter { ($0["mimeType"] as? String)?.hasPrefix("audio/") == true }
        if audio.isEmpty { return "no audio streams" }
        if !audio.contains(where: { ($0["mimeType"] as? String)?.hasPrefix("audio/mp4") == true }) { return "no AAC audio" }
        return "audio has no link"
    }

    /// A stream to play `track` now. Like downloads, a music video's art track (its album audio)
    /// comes first, and whichever upload fails, the other is tried.
    func playableStream(for track: Track) async throws -> ResolvedStream {
        var order = [track.id]
        var alternative: Track?
        if track.isVideo != false {
            alternative = await self.alternative(to: track)
            if let artTrack = alternative, artTrack.isVideo == false { order.insert(artTrack.id, at: 0) }
        }
        var notes: [String] = []
        var index = 0
        while index < order.count {
            let id = order[index]
            index += 1
            do {
                return try await playableStream(for: id)
            } catch {
                let label = id == track.id ? "" : "Art track · "
                if case YouTubeError.allFailed(let found) = error { notes += found.map { label + $0 } } else { notes.append(label + error.localizedDescription) }
            }
            if index == order.count, alternative == nil, let other = await self.alternative(to: track), !order.contains(other.id) {
                alternative = other
                order.append(other.id)
            }
        }
        throw YouTubeError.allFailed(notes)
    }

    /// A stream to play one upload: the first client's direct link that answers all the way to
    /// its end, else the audio-only part of an HLS stream (AVPlayer plays HLS natively).
    func playableStream(for videoId: String) async throws -> ResolvedStream {
        var notes: [String] = []
        for client in Self.clients {
            let found: [ResolvedStream]
            do {
                found = try await streams(for: videoId, client: client)
            } catch {
                if case YouTubeError.allFailed(let more) = error { notes += more } else { notes.append(error.localizedDescription) }
                continue
            }
            for stream in found {
                if stream.isHLS {
                    var request = URLRequest(url: stream.url)
                    stream.headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
                    guard let (data, _) = try? await session.data(for: request),
                          let audio = try? HLSPlaylist.mediaPlaylist(in: String(decoding: data, as: UTF8.self), base: stream.url) else {
                        notes.append("\(stream.client) HLS: couldn't read the playlist")
                        continue
                    }
                    return ResolvedStream(url: audio, mimeType: stream.mimeType, contentLength: nil,
                                          headers: stream.headers, isHLS: true, client: stream.client)
                }
                let status = await probe(stream)
                if (200..<300).contains(status) { return stream }
                notes.append("\(stream.client): audio refused (HTTP \(status))")
            }
        }
        throw YouTubeError.allFailed(notes)
    }

    /// What every client and link does for one video, line by line, for Settings → Test
    /// Downloads: the player answer, the start and end of each direct link, and the HLS stream.
    func diagnose(_ videoId: String) async -> [String] {
        var lines: [String] = []
        for client in Self.clients {
            let found: [ResolvedStream]
            do {
                found = try await streams(for: videoId, client: client)
            } catch YouTubeError.allFailed(let notes) {
                lines += notes
                continue
            } catch {
                lines.append("\(client.name): \(error.localizedDescription)")
                continue
            }
            for stream in found {
                if stream.isHLS {
                    var request = URLRequest(url: stream.url)
                    stream.headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
                    guard let (data, response) = try? await session.data(for: request) else {
                        lines.append("\(client.name) HLS: unreachable")
                        continue
                    }
                    let master = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard let media = try? HLSPlaylist.mediaPlaylist(in: String(decoding: data, as: UTF8.self), base: stream.url) else {
                        lines.append("\(client.name) HLS: playlist \(master), unreadable")
                        continue
                    }
                    var mediaRequest = URLRequest(url: media)
                    stream.headers.forEach { mediaRequest.setValue($1, forHTTPHeaderField: $0) }
                    let playlist = (try? await session.data(for: mediaRequest)).map { String(decoding: $0.0, as: UTF8.self) } ?? ""
                    let segments = HLSPlaylist.parseMediaPlaylist(playlist, base: media).segments
                    var segment = 0
                    if let first = segments.first {
                        var segmentRequest = URLRequest(url: first)
                        stream.headers.forEach { segmentRequest.setValue($1, forHTTPHeaderField: $0) }
                        segment = ((try? await session.data(for: segmentRequest))?.1 as? HTTPURLResponse)?.statusCode ?? 0
                    }
                    lines.append("\(client.name) HLS: playlist \(master), \(segments.count) segments, first segment \(segment)")
                } else {
                    let start = ((try? await session.data(for: stream.request(bytes: 0, through: 65_535, number: 1)))?.1 as? HTTPURLResponse)?.statusCode ?? 0
                    let end = await probe(stream)
                    let token = stream.url.absoluteString.contains("pot=") ? " +token" : ""
                    lines.append("\(client.name)\(token): start \(start), end \(end)\(start == 206 && end == 206 ? " ✓" : "")")
                }
            }
        }
        return lines
    }

    /// Fetches the last kilobyte of a direct stream; returns the HTTP status (0 if unreachable).
    /// Links YouTube means to cut off still answer for the first megabyte, so the end is checked.
    func probe(_ stream: ResolvedStream) async -> Int {
        let end = stream.contentLength ?? 1024
        let request = stream.request(bytes: max(0, end - 1024), through: end - 1, number: 1)
        guard let (_, response) = try? await session.data(for: request) else { return 0 }
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }

    /// Asks YouTube for a video's player data as `client`. Returns it with the playback nonce
    /// (cpn) an app-style client sent, which goes on its stream links too.
    private func player(_ videoId: String, client: Client, sendVisitorData: Bool = true) async throws -> (JSONTree.Object, String?) {
        var context: [String: Any] = ["clientName": client.clientName, "clientVersion": client.version, "hl": language, "gl": region]
        context.merge(client.extra) { a, _ in a }
        let visitorData: String?
        if client.app {
            visitorData = try await appVisitor(for: client, context: context)
        } else {
            visitorData = sendVisitorData ? self.visitorData : nil
        }
        if let visitorData { context["visitorData"] = visitorData }

        var payload: [String: Any] = [
            "context": ["client": context],
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
        ]
        if client.embedded {
            payload["context"] = ["client": context, "thirdParty": ["embedUrl": "https://www.youtube.com/"]]
        }
        var cpn: String?
        if client.app {
            payload["context"] = Self.appContext(context)
            cpn = Self.nonce(16)
            payload["cpn"] = cpn
        }
        if client.needsPlayerScript {
            let timestamp = try await PlayerScript.shared.signatureTimestamp()
            payload["playbackContext"] = ["contentPlaybackContext": ["signatureTimestamp": timestamp]]
        }
        if client.needsPoToken {
            // The player request's token is bound to the video.
            payload["serviceIntegrityDimensions"] = ["poToken": try await PoToken.shared.token(for: videoId)]
        }

        let url = client.app
            ? "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=\(Self.nonce(12))&id=\(videoId)"
            : "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(client.userAgent, forHTTPHeaderField: "User-Agent")
        if client.app {
            request.setValue("2", forHTTPHeaderField: "X-Goog-Api-Format-Version")
        } else {
            request.setValue(client.id, forHTTPHeaderField: "X-YouTube-Client-Name")
            request.setValue(client.version, forHTTPHeaderField: "X-YouTube-Client-Version")
            if let visitorData { request.setValue(visitorData, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        }
        if client.web {
            request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
            request.setValue("https://www.youtube.com/", forHTTPHeaderField: "Referer")
        }
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw YouTubeError.http(http.statusCode)
        }
        return (try JSONTree.parse(data), cpn)
    }

    /// The context YouTube's apps send: the client, plus request and user settings.
    private static func appContext(_ client: [String: Any]) -> [String: Any] {
        [
            "client": client,
            "request": ["internalExperimentFlags": [Any](), "useSsl": true],
            "user": ["lockedSafetyMode": false],
        ]
    }

    /// An app-style client's own visitor ID, asked for once from YouTube's visitor_id endpoint.
    private func appVisitor(for client: Client, context: [String: Any]) async throws -> String {
        if let known = appVisitorData[client.name] { return known }
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: ["context": Self.appContext(context)])
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(client.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("2", forHTTPHeaderField: "X-Goog-Api-Format-Version")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw YouTubeError.http(http.statusCode)
        }
        guard let visitor = JSONTree.string(try JSONTree.parse(data), "responseContext", "visitorData") else {
            throw YouTubeError.unexpectedResponse
        }
        appVisitorData[client.name] = visitor
        return visitor
    }

    /// Random letters, digits, "-" and "_", like YouTube's playback nonces.
    private static func nonce(_ length: Int) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        return String((0..<length).map { _ in alphabet.randomElement()! })
    }

    /// The best original-language AAC format in a player response, plain or scrambled.
    static func bestAudioFormat(in root: JSONTree.Object) -> JSONTree.Object? {
        let formats = JSONTree.array(JSONTree.path(root, "streamingData", "adaptiveFormats")).compactMap { $0 as? JSONTree.Object }
        let candidates = formats.filter { format in
            guard let mime = format["mimeType"] as? String, mime.hasPrefix("audio/mp4"),
                  format["url"] is String || format["signatureCipher"] is String else { return false }
            // Prefer the original track when YouTube offers dubbed ones.
            if let track = JSONTree.path(format, "audioTrack") as? JSONTree.Object,
               let isDefault = track["audioIsDefault"] as? Bool, !isDefault { return false }
            return true
        }
        return candidates.max { ($0["bitrate"] as? Int ?? 0) < ($1["bitrate"] as? Int ?? 0) }
    }

    static func bestAudio(in root: JSONTree.Object, client: Client) -> ResolvedStream? {
        guard let best = bestAudioFormat(in: root), let urlString = best["url"] as? String,
              let url = URL(string: urlString) else { return nil }
        return stream(best, url: url, client: client)
    }

    static func stream(_ format: JSONTree.Object, url: URL, client: Client) -> ResolvedStream {
        ResolvedStream(url: url, mimeType: format["mimeType"] as? String ?? "audio/mp4",
                       contentLength: (format["contentLength"] as? String).flatMap { Int64($0) },
                       headers: client.streamHeaders, isHLS: false, client: client.name)
    }
}
