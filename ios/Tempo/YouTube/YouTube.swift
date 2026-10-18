import Foundation

enum YouTubeError: LocalizedError {
    case unexpectedResponse
    case http(Int)
    case unplayable(String)
    case noAudio

    var errorDescription: String? {
        switch self {
        case .unexpectedResponse: return "YouTube sent something unexpected"
        case .http(let code): return "YouTube is unavailable (HTTP \(code))"
        case .unplayable(let reason): return reason
        case .noAudio: return "No downloadable audio for this song"
        }
    }
}

/// A playable audio stream for one video, with the headers googlevideo expects.
struct ResolvedStream {
    let url: URL
    let mimeType: String
    let contentLength: Int64?
    let headers: [String: String]
    /// True for an HLS manifest: it plays but can't be saved as one file.
    let isHLS: Bool
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

    /// An app client YouTube serves direct (unciphered) stream URLs to.
    struct Client {
        let name: String
        let id: String
        let version: String
        let userAgent: String
        let extra: [String: Any]
    }

    /// Tried in order: when YouTube refuses one ("confirm you're not a bot"), the next often works.
    static let clients: [Client] = [
        Client(
            name: "ANDROID_VR", id: "28", version: "1.62.27",
            userAgent: "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra: ["deviceMake": "Oculus", "deviceModel": "Quest 3", "androidSdkVersion": 32, "osName": "Android", "osVersion": "12L"]
        ),
        Client(
            name: "IOS", id: "5", version: "20.10.4",
            userAgent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra: ["deviceMake": "Apple", "deviceModel": "iPhone16,2", "osName": "iPhone", "osVersion": "18.3.2.22D82"]
        ),
        Client(
            name: "ANDROID", id: "3", version: "20.10.38",
            userAgent: "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            extra: ["androidSdkVersion": 34, "osName": "Android", "osVersion": "14"]
        ),
    ]

    /// The best AAC (m4a) audio stream for a video. AVPlayer can't play YouTube's Opus/WebM, so
    /// only MP4 audio is used; an HLS manifest is the last resort, for playback only.
    func stream(for videoId: String, allowHLS: Bool) async throws -> ResolvedStream {
        if visitorData == nil { _ = try? await musicPost("search", ["query": "music"]) }
        var lastError: Error = YouTubeError.noAudio
        var hlsFallback: ResolvedStream?
        for client in Self.clients {
            do {
                let root = try await player(videoId, client: client)
                let status = JSONTree.string(root, "playabilityStatus", "status") ?? "ERROR"
                guard status == "OK" else {
                    let reason = JSONTree.string(root, "playabilityStatus", "reason") ?? "This song can't be played"
                    lastError = YouTubeError.unplayable(friendly(reason))
                    continue
                }
                if let stream = Self.bestAudio(in: root, userAgent: client.userAgent) { return stream }
                if hlsFallback == nil,
                   let hls = JSONTree.string(root, "streamingData", "hlsManifestUrl"),
                   let url = URL(string: hls) {
                    hlsFallback = ResolvedStream(url: url, mimeType: "application/x-mpegURL", contentLength: nil,
                                                 headers: ["User-Agent": client.userAgent], isHLS: true)
                }
            } catch {
                lastError = error
            }
        }
        if allowHLS, let hlsFallback { return hlsFallback }
        throw lastError
    }

    private func player(_ videoId: String, client: Client) async throws -> JSONTree.Object {
        var context: [String: Any] = ["clientName": client.name, "clientVersion": client.version, "hl": language, "gl": region]
        context.merge(client.extra) { a, _ in a }
        if let visitorData { context["visitorData"] = visitorData }
        let payload: [String: Any] = [
            "context": ["client": context],
            "videoId": videoId,
            "contentCheckOk": true,
            "racyCheckOk": true,
        ]
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

    static func bestAudio(in root: JSONTree.Object, userAgent: String) -> ResolvedStream? {
        let formats = JSONTree.array(JSONTree.path(root, "streamingData", "adaptiveFormats")).compactMap { $0 as? JSONTree.Object }
        let candidates = formats.filter { format in
            guard let mime = format["mimeType"] as? String, mime.hasPrefix("audio/mp4"), format["url"] is String else { return false }
            // Prefer the original track when YouTube offers dubbed ones.
            if let track = JSONTree.path(format, "audioTrack") as? JSONTree.Object,
               let isDefault = track["audioIsDefault"] as? Bool, !isDefault { return false }
            return true
        }
        guard let best = candidates.max(by: { ($0["bitrate"] as? Int ?? 0) < ($1["bitrate"] as? Int ?? 0) }),
              let urlString = best["url"] as? String, let url = URL(string: urlString) else { return nil }
        let length = (best["contentLength"] as? String).flatMap { Int64($0) }
        return ResolvedStream(url: url, mimeType: best["mimeType"] as? String ?? "audio/mp4", contentLength: length,
                              headers: ["User-Agent": userAgent], isHLS: false)
    }

    private func friendly(_ reason: String) -> String {
        if reason.localizedCaseInsensitiveContains("bot") {
            return "YouTube wants to check you're not a bot. Try again later or on another network."
        }
        return reason
    }
}
