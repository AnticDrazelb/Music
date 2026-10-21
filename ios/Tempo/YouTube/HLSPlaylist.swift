import Foundation

/// Reading YouTube's HLS playlists.
enum HLSPlaylist {
    /// The audio-only rendition if the master playlist has one, else its smallest variant.
    static func mediaPlaylist(in master: String, base: URL) throws -> URL {
        let lines = master.components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }
        guard lines.contains(where: { $0.hasPrefix("#EXT-X-STREAM-INF") || $0.hasPrefix("#EXT-X-MEDIA") }) else {
            return base // already a media playlist
        }
        let audio = lines.filter { $0.hasPrefix("#EXT-X-MEDIA") && $0.contains("TYPE=AUDIO") }
            .compactMap { attribute("URI", in: $0) }
            .compactMap { URL(string: $0, relativeTo: base)?.absoluteURL }
        // Prefer YouTube's higher-quality AAC rendition (itag 234 over 233).
        if let best = audio.max(by: { itag($0) < itag($1) }) { return best }

        var variants: [(bandwidth: Int, url: URL)] = []
        for (i, line) in lines.enumerated() where line.hasPrefix("#EXT-X-STREAM-INF") {
            guard let uri = lines.dropFirst(i + 1).first(where: { !$0.isEmpty && !$0.hasPrefix("#") }),
                  let url = URL(string: uri, relativeTo: base)?.absoluteURL else { continue }
            variants.append((Int(attribute("BANDWIDTH", in: line) ?? "") ?? .max, url))
        }
        guard let smallest = variants.min(by: { $0.bandwidth < $1.bandwidth }) else { throw YouTubeError.noAudio }
        return smallest.url
    }

    static func parseMediaPlaylist(_ playlist: String, base: URL) -> (initSegment: URL?, segments: [URL]) {
        var initSegment: URL?
        var segments: [URL] = []
        for raw in playlist.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("#EXT-X-MAP"), let uri = attribute("URI", in: line) {
                initSegment = URL(string: uri, relativeTo: base)?.absoluteURL
            } else if !line.isEmpty, !line.hasPrefix("#"), let url = URL(string: line, relativeTo: base)?.absoluteURL {
                segments.append(url)
            }
        }
        return (initSegment, segments)
    }

    /// `KEY=value` or `KEY="value"` from an HLS tag line.
    static func attribute(_ key: String, in line: String) -> String? {
        guard let range = line.range(of: key + "=") else { return nil }
        let rest = line[range.upperBound...]
        if rest.hasPrefix("\"") {
            return rest.dropFirst().split(separator: "\"", maxSplits: 1).first.map(String.init)
        }
        return rest.split(separator: ",", maxSplits: 1).first.map(String.init)
    }

    private static func itag(_ url: URL) -> Int {
        guard let range = url.absoluteString.range(of: #"/itag/(\d+)"#, options: .regularExpression) else { return 0 }
        return Int(url.absoluteString[range].dropFirst(6)) ?? 0
    }
}
