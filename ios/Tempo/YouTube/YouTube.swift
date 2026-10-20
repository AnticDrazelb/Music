import Foundation

enum YouTubeError: LocalizedError {
    case unexpectedResponse
    case http(Int)
    case unplayable(String)
    case noAudio
    case allFailed([String])

    var errorDescription: String? {
        switch self {
        case .unexpectedResponse: return "YouTube sent something unexpected"
        case .http(let code): return "YouTube refused the request (HTTP \(code))"
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

    /// The same direct stream limited to a byte range, the way YouTube's apps fetch it.
    func rangeURL(_ start: Int64, _ end: Int64) -> URL {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        components.queryItems = (components.queryItems ?? []) + [URLQueryItem(name: "range", value: "\(start)-\(end)")]
        return components.url!
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

    /// Live suggestions while typing.
    func suggestions(_ query: String) async throws -> [String] {
        let root = try await musicPost("music/get_search_suggestions", ["input": query])
        return JSONTree.findAll(root, key: "searchSuggestionRenderer").map { JSONTree.text($0["suggestion"]) }
            .filter { !$0.isEmpty }
    }

    private func musicPost(_ endpoint: String, _ body: [String: Any]) async throws -> JSONTree.Object {
        var payload = body
        payload["context"] = ["client": [
            "clientName": "WEB_REMIX",
            "clientVersion": Self.musicClientVersion,
            "hl": language,
            "gl": region,
        ]]
        var request = URLRequest(url: URL(string: "https://music.youtube.com/youtubei/v1/\(endpoint)?prettyPrint=false")!)
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

    /// A YouTube app the player request identifies as. Each gets different links: some stop
    /// after the first megabyte for apps that don't send a token, so several are tried.
    struct Client {
        let name: String
        /// The InnerTube client name sent to YouTube.
        let clientName: String
        let id: String
        let version: String
        let userAgent: String
        let extra: [String: Any]
        /// Its links are scrambled and need YouTube's player script to unlock (see PlayerScript).
        var needsPlayerScript = false
        /// A web player: says it's embedded on youtube.com, and its links want the site's headers.
        var web = false

        /// Headers googlevideo expects when fetching this client's links.
        var streamHeaders: [String: String] {
            var headers = ["User-Agent": userAgent]
            if web {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
            return headers
        }
    }

    private static let webUserAgentChrome =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /// Tried in this order. The TV and embedded players need no token but scramble their links;
    /// the phone apps' links are plain but are now cut off without a token.
    static let clients: [Client] = [
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
            name: "WEB_EMBEDDED", clientName: "WEB_EMBEDDED_PLAYER", id: "56", version: "1.20250923.21.00",
            userAgent: webUserAgentChrome, extra: [:], needsPlayerScript: true, web: true
        ),
        Client(
            name: "ANDROID_VR", clientName: "ANDROID_VR", id: "28", version: "1.65.10",
            userAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra: ["deviceMake": "Oculus", "deviceModel": "Quest 3", "androidSdkVersion": 32, "osName": "Android", "osVersion": "12L"]
        ),
        // The Android app without an SDK version.
        Client(
            name: "ANDROID_SDKLESS", clientName: "ANDROID", id: "3", version: "20.10.38",
            userAgent: "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip",
            extra: ["osName": "Android", "osVersion": "11"]
        ),
        // Mostly for its HLS stream, the last resort.
        Client(
            name: "IOS", clientName: "IOS", id: "5", version: "20.10.4",
            userAgent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra: ["deviceMake": "Apple", "deviceModel": "iPhone16,2", "osName": "iPhone", "osVersion": "18.3.2.22D82"]
        ),
    ]

    /// Every way YouTube offers this song's audio, best first: each client's direct AAC (m4a)
    /// stream, then the iOS client's HLS stream. AVPlayer can't play YouTube's Opus/WebM, so
    /// only AAC is used. Direct links are sometimes refused when fetched (HTTP 403) even
    /// though the lookup worked, so callers try them in turn. `notes` says what each client
    /// that gave nothing usable answered, for the error shown when everything fails.
    func candidates(for videoId: String) async throws -> (streams: [ResolvedStream], notes: [String]) {
        if visitorData == nil { _ = try? await musicPost("search", ["query": "music"]) }
        var direct: [ResolvedStream] = []
        var hls: [ResolvedStream] = []
        var notes: [String] = []
        var unplayable: String?
        for client in Self.clients {
            do {
                let root = try await player(videoId, client: client)
                let status = JSONTree.string(root, "playabilityStatus", "status") ?? "ERROR"
                guard status == "OK" else {
                    let reason = JSONTree.string(root, "playabilityStatus", "reason") ?? "can't be played"
                    if status != "LOGIN_REQUIRED" { unplayable = unplayable ?? friendly(reason) }
                    let short = reason.localizedCaseInsensitiveContains("bot") ? "bot check" : reason
                    notes.append("\(client.name): \(short) (\(status))")
                    continue
                }
                var audio: ResolvedStream?
                if client.needsPlayerScript, let format = Self.bestAudioFormat(in: root) {
                    do {
                        audio = Self.stream(format, url: try await PlayerScript.shared.url(for: format), client: client)
                    } catch {
                        notes.append("\(client.name): \(error.localizedDescription)")
                    }
                } else {
                    audio = Self.bestAudio(in: root, client: client)
                }
                if let audio { direct.append(audio) }
                let manifest = JSONTree.string(root, "streamingData", "hlsManifestUrl").flatMap(URL.init(string:))
                if let manifest {
                    hls.append(ResolvedStream(url: manifest, mimeType: "application/x-mpegURL", contentLength: nil,
                                              headers: client.streamHeaders, isHLS: true, client: client.name))
                }
                if audio == nil && manifest == nil && !notes.contains(where: { $0.hasPrefix(client.name + ":") }) {
                    notes.append("\(client.name): \(Self.whyNoAudio(root))")
                }
            } catch {
                notes.append("\(client.name): \(error.localizedDescription)")
            }
        }
        let all = direct + hls
        if all.isEmpty {
            if let unplayable { throw YouTubeError.unplayable(unplayable) }
            throw YouTubeError.allFailed(notes)
        }
        return (all, notes)
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

    /// A stream to play now: the first direct link that answers, else HLS (AVPlayer plays it natively).
    func playableStream(for videoId: String) async throws -> ResolvedStream {
        let (all, found) = try await candidates(for: videoId)
        var notes = found
        for stream in all {
            if stream.isHLS { return stream }
            let status = await probe(stream)
            if (200..<300).contains(status) { return stream }
            notes.append("\(stream.client): audio refused (HTTP \(status))")
        }
        throw YouTubeError.allFailed(notes)
    }

    /// Fetches the last kilobyte of a direct stream; returns the HTTP status (0 if unreachable).
    /// Links YouTube means to cut off still answer for the first megabyte, so the end is checked.
    func probe(_ stream: ResolvedStream) async -> Int {
        let end = stream.contentLength ?? 1024
        var request = URLRequest(url: stream.rangeURL(max(0, end - 1024), end - 1))
        stream.headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        request.timeoutInterval = 12
        guard let (_, response) = try? await session.data(for: request) else { return 0 }
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }

    private func player(_ videoId: String, client: Client) async throws -> JSONTree.Object {
        var context: [String: Any] = ["clientName": client.clientName, "clientVersion": client.version, "hl": language, "gl": region]
        context.merge(client.extra) { a, _ in a }
        if let visitorData { context["visitorData"] = visitorData }
        var payload: [String: Any] = [
            "context": ["client": context],
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
        ]
        if client.web {
            payload["context"] = ["client": context, "thirdParty": ["embedUrl": "https://www.youtube.com/"]]
        }
        if client.needsPlayerScript {
            let timestamp = try await PlayerScript.shared.signatureTimestamp()
            payload["playbackContext"] = ["contentPlaybackContext": ["signatureTimestamp": timestamp]]
        }
        var request = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(client.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(client.id, forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(client.version, forHTTPHeaderField: "X-YouTube-Client-Version")
        if let visitorData { request.setValue(visitorData, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw YouTubeError.http(http.statusCode)
        }
        return try JSONTree.parse(data)
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

    private func friendly(_ reason: String) -> String {
        if reason.localizedCaseInsensitiveContains("bot") {
            return "YouTube wants to check you're not a bot. Try again later or on another network."
        }
        return reason
    }
}
