import AVFoundation
import UIKit
import UniformTypeIdentifiers

/// Adds the user's own audio files to the library: picked in Files, shared to Tempo with
/// "Open in", or copied into Tempo's folder (Files app or Finder). Title, artist, album,
/// artwork and length come from the file's own tags; the file name is the fallback title.
@MainActor
final class LibraryImporter {
    static let shared = LibraryImporter()

    /// File types AVPlayer plays, for spotting audio dropped into the Documents folder.
    static let audioExtensions: Set<String> = ["mp3", "m4a", "m4b", "aac", "wav", "aif", "aiff", "aifc", "caf", "flac", "alac", "mp4"]

    private let library: LibraryStore

    init(library: LibraryStore = .shared) {
        self.library = library
    }

    struct Result {
        var imported: [Track] = []
        var failed: [String] = []
    }

    /// Copies (or, for files already in Tempo's folder, moves) each file into the library.
    func importFiles(_ urls: [URL], move: Bool = false) async -> Result {
        var result = Result()
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                result.imported.append(try await importFile(url, move: move))
            } catch {
                result.failed.append(url.lastPathComponent)
            }
        }
        return result
    }

    /// Audio files sitting loose in Documents (put there with the Files app or Finder) are
    /// moved into the library.
    func importDroppedFiles() async -> Result {
        let root = library.musicFolder.deletingLastPathComponent()
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isRegularFileKey])) ?? []
        let audio = files.filter { Self.audioExtensions.contains($0.pathExtension.lowercased()) }
        guard !audio.isEmpty else { return Result() }
        return await importFiles(audio, move: true)
    }

    private func importFile(_ source: URL, move: Bool) async throws -> Track {
        let id = "local-" + UUID().uuidString
        let ext = source.pathExtension.isEmpty ? "m4a" : source.pathExtension.lowercased()
        let fileName = "\(id).\(ext)"
        let destination = library.musicFolder.appendingPathComponent(fileName)

        // Files from iCloud Drive may need downloading first; a coordinated read takes care of it.
        var coordinatorError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: .withoutChanges, error: &coordinatorError) { readable in
            do {
                if move {
                    try FileManager.default.moveItem(at: readable, to: destination)
                } else {
                    try FileManager.default.copyItem(at: readable, to: destination)
                }
            } catch {
                copyError = error
            }
        }
        if let error = coordinatorError ?? copyError { throw error }

        let asset = AVURLAsset(url: destination)
        guard (try? await asset.load(.isPlayable)) == true else {
            try? FileManager.default.removeItem(at: destination)
            throw YouTubeError.noAudio
        }
        let metadata = (try? await asset.load(.commonMetadata)) ?? []
        func string(_ key: AVMetadataKey) async -> String? {
            guard let item = AVMetadataItem.metadataItems(from: metadata, withKey: key, keySpace: .common).first,
                  let value = try? await item.load(.stringValue) else { return nil }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let duration = (try? await asset.load(.duration)).map(\.seconds).flatMap { $0.isFinite ? Int($0.rounded()) : nil }

        let fallback = source.deletingPathExtension().lastPathComponent
        var title = await string(.commonKeyTitle)
        var artist = await string(.commonKeyArtist)
        if title == nil {
            // "Artist - Title.mp3" is a common way to name files.
            let split = TitleCleaner.split(title: fallback, uploader: nil)
            title = split.title
            if split.artist != TitleCleaner.unknownArtist { artist = artist ?? split.artist }
        }

        var track = Track(
            id: id,
            title: title ?? fallback,
            artist: artist ?? TitleCleaner.unknownArtist,
            album: await string(.commonKeyAlbumName),
            duration: duration,
            artworkURL: nil,
            fileName: fileName,
            addedAt: Date()
        )
        if let item = AVMetadataItem.metadataItems(from: metadata, withKey: AVMetadataKey.commonKeyArtwork, keySpace: .common).first,
           let data = try? await item.load(.dataValue), let image = UIImage(data: data),
           let jpeg = image.jpegData(compressionQuality: 0.85) {
            let artName = "\(id).jpg"
            if (try? jpeg.write(to: library.artworkFolder.appendingPathComponent(artName), options: .atomic)) != nil {
                track.artworkFileName = artName
            }
        }
        library.add(track)
        return track
    }
}
