import Foundation

/// A song: either a search result from YouTube Music or one downloaded into the library.
struct Track: Codable, Identifiable, Hashable {
    /// The YouTube video id.
    let id: String
    var title: String
    var artist: String
    var album: String?
    /// Seconds, or nil when unknown.
    var duration: Int?
    /// Remote artwork, as YouTube handed it out.
    var artworkURL: URL?
    /// Audio file name inside the library's Music folder, once downloaded.
    var fileName: String?
    /// Artwork file name inside the library's Artwork folder, once downloaded.
    var artworkFileName: String?
    var addedAt: Date?
    /// True for a music video or other upload, false for a catalogue "art track" (audio with
    /// the album cover), nil when unknown. Only the audio is ever fetched either way.
    var isVideo: Bool?
    /// YouTube Music's album page (an MPREb_ browse ID), for Go to Album.
    var albumId: String?
    /// The artist's channel (UC…), for Go to Artist.
    var artistId: String?

    var albumTitle: String { album?.isEmpty == false ? album! : "Unknown Album" }

    var isDownloaded: Bool { fileName != nil }
}

enum TimeText {
    /// 3:07, or 1:02:03 for long tracks.
    static func string(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded(.down))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
