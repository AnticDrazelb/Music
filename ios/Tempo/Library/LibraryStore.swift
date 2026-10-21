import Foundation
import UIKit

/// The downloaded songs: audio and artwork files in Documents, indexed in library.json.
/// Files are stored by name, not path, because the app's container moves between launches.
@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()

    @Published private(set) var tracks: [Track] = []

    let musicFolder: URL
    let artworkFolder: URL
    private let indexFile: URL

    init(root: URL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]) {
        musicFolder = root.appendingPathComponent("Music", isDirectory: true)
        artworkFolder = root.appendingPathComponent("Artwork", isDirectory: true)
        indexFile = root.appendingPathComponent("library.json")
        for folder in [musicFolder, artworkFolder] {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        }
        load()
    }

    // MARK: Queries

    func contains(_ id: String) -> Bool { tracks.contains { $0.id == id } }

    func track(_ id: String) -> Track? { tracks.first { $0.id == id } }

    var songs: [Track] {
        tracks.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    var artists: [String] {
        Set(tracks.map(\.artist)).sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    struct Album: Hashable {
        let title: String
        let artist: String
    }

    func albums(by artist: String? = nil) -> [Album] {
        let source = artist.map { a in tracks.filter { $0.artist == a } } ?? tracks
        var seen = Set<Album>()
        for track in source { seen.insert(Album(title: track.albumTitle, artist: track.artist)) }
        return seen.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    func songs(in album: Album) -> [Track] {
        tracks.filter { $0.albumTitle == album.title && $0.artist == album.artist }
            .sorted { ($0.addedAt ?? .distantPast) < ($1.addedAt ?? .distantPast) }
    }

    func songs(by artist: String) -> [Track] {
        songs.filter { $0.artist == artist }
    }

    func fileURL(for track: Track) -> URL? {
        guard let name = (self.track(track.id) ?? track).fileName else { return nil }
        let url = musicFolder.appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func artworkFile(for track: Track) -> URL? {
        guard let name = (self.track(track.id) ?? track).artworkFileName else { return nil }
        return artworkFolder.appendingPathComponent(name)
    }

    func artwork(for track: Track) -> UIImage? {
        artworkFile(for: track).flatMap { UIImage(contentsOfFile: $0.path) }
    }

    var bytesUsed: Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: musicFolder, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    // MARK: Changes

    func add(_ track: Track) {
        tracks.removeAll { $0.id == track.id }
        tracks.append(track)
        save()
    }

    func remove(_ track: Track) {
        guard let stored = self.track(track.id) else { return }
        if let name = stored.fileName { try? FileManager.default.removeItem(at: musicFolder.appendingPathComponent(name)) }
        if let name = stored.artworkFileName { try? FileManager.default.removeItem(at: artworkFolder.appendingPathComponent(name)) }
        tracks.removeAll { $0.id == track.id }
        save()
    }

    func removeAll() {
        for track in tracks { remove(track) }
    }

    private func load() {
        guard let data = try? Data(contentsOf: indexFile),
              let stored = try? JSONDecoder().decode([Track].self, from: data) else { return }
        // Drop entries whose audio went missing (e.g. deleted in the Files app).
        tracks = stored.filter { track in
            track.fileName.map { FileManager.default.fileExists(atPath: musicFolder.appendingPathComponent($0).path) } ?? false
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(tracks) else { return }
        try? data.write(to: indexFile, options: .atomic)
    }
}
