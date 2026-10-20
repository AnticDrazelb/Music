import Foundation
import UIKit

/// Downloads songs into the library, one or two at a time.
@MainActor
final class DownloadManager: ObservableObject {
    static let shared = DownloadManager()

    enum State: Equatable {
        case queued
        case downloading(Double)
        case failed(String)
    }

    /// Songs still in progress or failed; finished ones move into the library.
    @Published private(set) var states: [String: State] = [:]
    @Published private(set) var queue: [Track] = []

    private let library: LibraryStore
    private var running: [String: Task<Void, Never>] = [:]
    private let maxConcurrent = 2
    /// googlevideo throttles single big requests, so files come down in ranges like YouTube's apps do.
    private let chunkSize: Int64 = 4 * 1024 * 1024

    init(library: LibraryStore = .shared) {
        self.library = library
    }

    func state(of id: String) -> State? { states[id] }

    var activeCount: Int { states.values.filter { if case .failed = $0 { return false } else { return true } }.count }

    func download(_ track: Track) {
        guard !library.contains(track.id) else { return }
        switch states[track.id] {
        case .queued?, .downloading?: return
        case .failed?: queue.removeAll { $0.id == track.id }
        case nil: break
        }
        states[track.id] = .queued
        queue.append(track)
        pump()
    }

    func cancel(_ id: String) {
        running[id]?.cancel()
        running[id] = nil
        states[id] = nil
        queue.removeAll { $0.id == id }
        pump()
    }

    func clearFailed() {
        for (id, state) in states { if case .failed = state { states[id] = nil; queue.removeAll { $0.id == id } } }
    }

    private func pump() {
        while running.count < maxConcurrent,
              let next = queue.first(where: { states[$0.id] == .queued && running[$0.id] == nil }) {
            let id = next.id
            running[id] = Task { [weak self] in
                await self?.run(next)
                self?.running[id] = nil
                self?.pump()
            }
        }
    }

    private func run(_ track: Track) async {
        let background = UIApplication.shared.beginBackgroundTask(withName: "download \(track.id)")
        defer { UIApplication.shared.endBackgroundTask(background) }
        states[track.id] = .downloading(0)
        do {
            let fileName = try await saveAudio(of: track)
            try Task.checkCancellation()

            var saved = track
            saved.fileName = fileName
            saved.addedAt = Date()
            saved.artworkFileName = await saveArtwork(for: track)
            library.add(saved)
            states[track.id] = nil
            queue.removeAll { $0.id == track.id }
        } catch is CancellationError {
            states[track.id] = nil
        } catch {
            if Task.isCancelled { states[track.id] = nil; return }
            states[track.id] = .failed(error.localizedDescription)
        }
    }

    /// Tries each way YouTube offers the song until one downloads: a direct AAC file (checked
    /// first, since YouTube sometimes refuses them with HTTP 403), then the HLS stream.
    private func saveAudio(of track: Track) async throws -> String {
        let progress: @MainActor (Double) -> Void = { [weak self] fraction in
            self?.states[track.id] = .downloading(fraction)
        }
        let (streams, found) = try await YouTube.shared.candidates(for: track.id)
        var notes = found
        for stream in streams {
            try Task.checkCancellation()
            do {
                if stream.isHLS {
                    return try await HLSDownloader.download(stream, name: track.id, to: library.musicFolder, progress: progress)
                }
                let status = await YouTube.shared.probe(stream)
                guard (200..<300).contains(status) else {
                    notes.append("\(stream.client): audio refused (HTTP \(status))")
                    continue
                }
                let fileName = "\(track.id).m4a"
                let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
                try await fetch(stream, to: temp, progress: progress)
                let destination = library.musicFolder.appendingPathComponent(fileName)
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: temp, to: destination)
                return fileName
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                notes.append("\(stream.client)\(stream.isHLS ? " HLS" : ""): \(error.localizedDescription)")
            }
        }
        throw YouTubeError.allFailed(notes)
    }

    private func fetch(_ stream: ResolvedStream, to file: URL, progress: @escaping @MainActor (Double) -> Void) async throws {
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        var offset: Int64 = 0
        let total = stream.contentLength
        while true {
            try Task.checkCancellation()
            let end = offset + chunkSize - 1
            var request = URLRequest(url: stream.rangeURL(offset, end))
            stream.headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw YouTubeError.http(http.statusCode)
            }
            try handle.write(contentsOf: data)
            offset += Int64(data.count)
            if let total, total > 0 { progress(min(1, Double(offset) / Double(total))) }
            if data.count < chunkSize || (total.map { offset >= $0 } ?? false) || data.isEmpty { break }
        }
        guard offset > 0 else { throw YouTubeError.noAudio }
    }

    private func saveArtwork(for track: Track) async -> String? {
        guard let url = track.artworkURL,
              let result = try? await URLSession.shared.data(from: url),
              let image = UIImage(data: result.0) else { return nil }
        // Video stills are 16:9; the iPod shows square art, so crop the middle.
        let side = min(image.size.width, image.size.height)
        let crop = CGRect(x: (image.size.width - side) / 2, y: (image.size.height - side) / 2, width: side, height: side)
        let square = UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { _ in
            image.draw(at: CGPoint(x: -crop.minX, y: -crop.minY))
        }
        guard let jpeg = square.jpegData(compressionQuality: 0.85) else { return nil }
        let name = "\(track.id).jpg"
        try? jpeg.write(to: library.artworkFolder.appendingPathComponent(name), options: .atomic)
        return name
    }
}
