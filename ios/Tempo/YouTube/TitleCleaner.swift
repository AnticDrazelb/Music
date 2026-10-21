import Foundation

/// Turns YouTube upload titles into clean song titles and artists, like the Android app's
/// YouTubeUtils: "Haddaway - What Is Love (Official 4K Video)" -> ("What Is Love", "Haddaway").
enum TitleCleaner {
    static let unknownArtist = "Unknown Artist"

    /// Words that describe the upload rather than the song; a bracket made only of these goes.
    private static let noiseWords: Set<String> = [
        "official", "oficial", "officiel", "music", "video", "videos", "videoclip", "clip", "mv", "m", "v",
        "audio", "only", "lyric", "lyrics", "letra", "paroles", "visualizer", "visualiser", "hd", "hq",
        "4k", "8k", "uhd", "remaster", "remastered", "explicit", "clean", "full", "new", "color", "coded",
        "animated", "performance", "premiere",
    ]

    private static func regex(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    private static let bracket = regex(#"[(\[【]([^()\[\]【】]*)[)\]】]"#)
    private static let trailingPipe = regex(#"\s*(?:[|｜]|\s•\s).*$"#)
    private static let dash = regex(#"\s+[-–—~]\s*|\s*[-–—~]\s+"#)
    private static let noiseNumber = regex(#"^(?:\d{4}|\d{3,4}p|\d{2,3}fps)$"#)
    private static let versionSuffix = regex(
        #"^(?:\d{4}\s+)?(?:re-?master|live\b|mono\b|demo\b|edit\b|radio\b|version\b|remix\b|mix\b|acoustic\b|instrumental\b|from\b|extended\b|feat\.|ft\.|with\b)"#
    )
    private static let vevoHump = try! NSRegularExpression(pattern: #"(?<=[a-z])(?=[A-Z])"#)

    private static func isNoise(_ content: String) -> Bool {
        let tokens = content.lowercased()
            .components(separatedBy: CharacterSet(charactersIn: " /,+&|:._!-–—"))
            .filter { !$0.isEmpty }
        return !tokens.isEmpty && tokens.allSatisfy { token in
            noiseWords.contains(token) || matches(noiseNumber, token)
        }
    }

    private static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /// Removes upload decorations: "(Official 4K Video)", "[HD]", "| Official Audio".
    static func stripNoise(_ title: String) -> String {
        var t = title
        let ns = t as NSString
        for match in bracket.matches(in: t, range: NSRange(location: 0, length: ns.length)).reversed() {
            let inner = ns.substring(with: match.range(at: 1))
            if isNoise(inner) {
                t = (t as NSString).replacingCharacters(in: match.range, with: " ")
            }
        }
        let piped = trailingPipe.stringByReplacingMatches(in: t, range: NSRange(location: 0, length: (t as NSString).length), withTemplate: "")
        if !piped.trimmingCharacters(in: .whitespaces).isEmpty { t = piped }
        t = t.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        t = t.trimmingCharacters(in: CharacterSet(charactersIn: " -–—|:,"))
        return t.isEmpty ? title.trimmingCharacters(in: .whitespaces) : t
    }

    /// "Rick Astley - Topic" -> "Rick Astley", "TaylorSwiftVEVO" -> "Taylor Swift".
    static func cleanArtist(_ name: String?) -> String {
        guard var artist = name?.trimmingCharacters(in: .whitespaces), !artist.isEmpty else { return unknownArtist }
        if artist.hasPrefix("@") { artist.removeFirst() }
        if artist.hasSuffix(" - Topic") { artist = String(artist.dropLast(8)) }
        if artist.count > 4, artist.uppercased().hasSuffix("VEVO") {
            artist = String(artist.dropLast(4)).trimmingCharacters(in: .whitespaces)
            if !artist.contains(" ") {
                artist = vevoHump.stringByReplacingMatches(
                    in: artist, range: NSRange(location: 0, length: (artist as NSString).length), withTemplate: " ")
            }
        }
        return artist.isEmpty ? unknownArtist : artist
    }

    private static func normalized(_ s: String) -> String {
        s.lowercased().filter { $0.isLetter || $0.isNumber }
    }

    private static func sameArtist(_ a: String, _ b: String) -> Bool {
        let x = normalized(a), y = normalized(b)
        guard !x.isEmpty, !y.isEmpty else { return false }
        return x == y || (min(x.count, y.count) >= 3 && (x.contains(y) || y.contains(x)))
    }

    private static func isPart(_ name: String, of channel: String) -> Bool {
        let x = normalized(name)
        return x.count >= 2 && normalized(channel).contains(x)
    }

    /// (title, artist) for an upload. With `artistIsReliable` (catalogue songs) the title is
    /// only split when one side names the uploader.
    static func split(title rawTitle: String, uploader: String?, artistIsReliable: Bool = false) -> (title: String, artist: String) {
        let channel = uploader.map(cleanArtist)
        let title = stripNoise(rawTitle)
        if uploader?.hasSuffix(" - Topic") == true { return (title, channel ?? unknownArtist) }

        let ns = title as NSString
        if let m = dash.firstMatch(in: title, range: NSRange(location: 0, length: ns.length)) {
            let left = ns.substring(to: m.range.location).trimmingCharacters(in: .whitespaces)
            let right = ns.substring(from: m.range.location + m.range.length).trimmingCharacters(in: .whitespaces)
            let names = channel.map { sameArtist(left, $0) || sameArtist(right, $0) } ?? false
            let splittable = !left.isEmpty && !right.isEmpty && left.count <= 80 &&
                !matches(versionSuffix, right) && (names || !artistIsReliable)
            if splittable {
                // "Title - Artist": only when the right side is the channel's name, not merely in it.
                let swapped = channel.map { isPart(right, of: $0) && !sameArtist(left, $0) } ?? false
                return swapped ? (left, cleanArtist(right)) : (right, cleanArtist(left))
            }
        }
        return (title, channel ?? unknownArtist)
    }

    /// "3:45" -> 225, "1:02:03" -> 3723.
    static func parseDuration(_ text: String) -> Int? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ":").map { Int($0) }
        guard (2...3).contains(parts.count), parts.allSatisfy({ $0 != nil }) else { return nil }
        return parts.reduce(0) { $0 * 60 + $1! }
    }

    /// Asks Google's image CDN for a crisp square; video stills get YouTube's larger frame.
    static func artworkURL(_ raw: String?, size: Int = 544) -> URL? {
        guard var url = raw, !url.isEmpty else { return nil }
        if url.hasPrefix("//") { url = "https:" + url }
        if url.contains("googleusercontent.com") || url.contains("ggpht.com") {
            if let range = url.range(of: #"=(w\d+-h\d+|s\d+)[^/]*$"#, options: .regularExpression) {
                url.replaceSubrange(range, with: "=w\(size)-h\(size)-l90-rj")
            } else if !url.contains("=") {
                url += "=w\(size)-h\(size)-l90-rj"
            }
        }
        return URL(string: url)
    }
}
