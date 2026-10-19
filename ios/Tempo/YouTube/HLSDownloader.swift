import AVFoundation
import Foundation

/// Saves an HLS stream's audio as one file. YouTube's iOS client streams audio as HLS: a
/// playlist of short MPEG-TS (or fragmented MP4) segments. The segments are fetched, the AAC
/// is pulled out of the transport stream, and the result is repackaged as an .m4a.
enum HLSDownloader {
    /// Downloads to `destinationFolder/<name>.m4a` (or .aac if repackaging fails) and returns the file name.
    static func download(_ stream: ResolvedStream, name: String, to destinationFolder: URL,
                         progress: @escaping @MainActor (Double) -> Void) async throws -> String {
        let master = try await text(stream.url, headers: stream.headers)
        let playlistURL = try HLSPlaylist.mediaPlaylist(in: master, base: stream.url)
        let playlist = playlistURL == stream.url ? master : try await text(playlistURL, headers: stream.headers)
        let (initSegment, segments) = HLSPlaylist.parseMediaPlaylist(playlist, base: playlistURL)
        guard !segments.isEmpty else { throw YouTubeError.noAudio }

        // Fetch a few segments at a time, keeping them in order.
        var parts = [Data?](repeating: nil, count: segments.count)
        var done = 0
        try await withThrowingTaskGroup(of: (Int, Data).self) { group in
            var next = 0
            func addNext() {
                guard next < segments.count else { return }
                let index = next
                next += 1
                group.addTask { (index, try await fetch(segments[index], headers: stream.headers)) }
            }
            for _ in 0..<4 { addNext() }
            while let (index, data) = try await group.next() {
                parts[index] = data
                done += 1
                await progress(Double(done) / Double(segments.count))
                addNext()
            }
        }
        let body = parts.reduce(into: Data()) { $0.append($1 ?? Data()) }

        if let initSegment {
            // Fragmented MP4: the init segment plus the fragments is already a playable file.
            var file = try await fetch(initSegment, headers: stream.headers)
            file.append(body)
            let fileName = name + ".m4a"
            try file.write(to: destinationFolder.appendingPathComponent(fileName), options: .atomic)
            return fileName
        }

        let adts = TransportStream.extractAAC(from: body)
        guard !adts.isEmpty else { throw YouTubeError.noAudio }
        let aac = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".aac")
        try adts.write(to: aac)
        defer { try? FileManager.default.removeItem(at: aac) }

        // Rewrap the raw AAC in an M4A container (no re-encoding) for exact seeking and length.
        let m4a = destinationFolder.appendingPathComponent(name + ".m4a")
        try? FileManager.default.removeItem(at: m4a)
        if await rewrap(aac, to: m4a) { return name + ".m4a" }
        let fallback = destinationFolder.appendingPathComponent(name + ".aac")
        try? FileManager.default.removeItem(at: fallback)
        try FileManager.default.copyItem(at: aac, to: fallback)
        return name + ".aac"
    }

    // MARK: Network

    private static func text(_ url: URL, headers: [String: String]) async throws -> String {
        String(decoding: try await fetch(url, headers: headers), as: UTF8.self)
    }

    private static func fetch(_ url: URL, headers: [String: String]) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw YouTubeError.http(http.statusCode)
        }
        return data
    }

    private static func rewrap(_ source: URL, to destination: URL) async -> Bool {
        let asset = AVURLAsset(url: source)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else { return false }
        export.outputURL = destination
        export.outputFileType = .m4a
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            export.exportAsynchronously { done.resume() }
        }
        return export.status == .completed
    }
}
