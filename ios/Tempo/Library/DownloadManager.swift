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
    private let chunkSize: Int64 = 2 * 1024 * 1024

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
            let (fileName, source) = try await saveAudio(of: track)
            try Task.checkCancellation()

            var saved = track
            saved.fileName = fileName
            saved.addedAt = Date()
            // An art track brings the album's square cover and name.
            saved.album = saved.album ?? source.album
            var artwork = await saveArtwork(for: source)
            if artwork == nil, source.id != track.id { artwork = await saveArtwork(for: track) }
            saved.artworkFileName = artwork
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

    /// Saves the song's audio, never its video. For a music video, its catalogue art track (the
    /// album audio) is tried first; whichever of the two fails, the other is tried. Returns the
    /// file name and the upload the audio came from.
    private func saveAudio(of track: Track) async throws -> (String, Track) {
        var order = [track]
        var alternative: Track?
        if track.isVideo != false {
            alternative = await YouTube.shared.alternative(to: track)
            if let artTrack = alternative, artTrack.isVideo == false { order.insert(artTrack, at: 0) }
        }
        var notes: [String] = []
        var index = 0
        while index < order.count {
            let source = order[index]
            index += 1
            do {
                return (try await saveAudio(videoId: source.id, as: track.id), source)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                let label = source.id == track.id ? "" : "Art track · "
                if case YouTubeError.allFailed(let found) = error {
                    notes += found.map { label + $0 }
                } else {
                    notes.append(label + error.localizedDescription)
                }
            }
            if index == order.count, alternative == nil {
                alternative = await YouTube.shared.alternative(to: track)
                if let alternative, !order.contains(where: { $0.id == alternative.id }) { order.append(alternative) }
            }
        }
        throw YouTubeError.allFailed(notes)
    }

    /// Asks each YouTube client in turn (the visionOS app first) and downloads the first of its
    /// streams that comes down whole: a direct AAC file, else its HLS stream. A refused link
    /// fails on its first chunk, so the next one is tried straight away.
    private func saveAudio(videoId: String, as name: String) async throws -> String {
        let progress: @MainActor (Double) -> Void = { [weak self] fraction in
            self?.states[name] = .downloading(fraction)
        }
        var notes: [String] = []
        for client in YouTube.clients {
            try Task.checkCancellation()
            let streams: [ResolvedStream]
            do {
                streams = try await YouTube.shared.streams(for: videoId, client: client)
            } catch YouTubeError.allFailed(let more) {
                notes += more
                continue
            } catch {
                notes.append("\(client.name): \(error.localizedDescription)")
                continue
            }
            for stream in streams {
                try Task.checkCancellation()
                do {
                    if stream.isHLS {
                        return try await HLSDownloader.download(stream, name: name, to: library.musicFolder, progress: progress)
                    }
                    let fileName = "\(name).m4a"
                    let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
                    defer { try? FileManager.default.removeItem(at: temp) }
                    try await fetch(stream, to: temp, progress: progress)
                    let destination = library.musicFolder.appendingPathComponent(fileName)
                    try? FileManager.default.removeItem(at: destination)
                    try FileManager.default.moveItem(at: temp, to: destination)
                    return fileName
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    progress(0)
                    notes.append("\(stream.client)\(stream.isHLS ? " HLS" : ""): \(error.localizedDescription)")
                }
            }
        }
        throw YouTubeError.allFailed(notes)
    }

    /// Downloads a direct stream in chunks, each requested the way YouTube's apps do.
    private func fetch(_ stream: ResolvedStream, to file: URL, progress: @escaping @MainActor (Double) -> Void) async throws {
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        var offset: Int64 = 0
        var total = stream.contentLength
        var number = 1
        while total.map({ offset < $0 }) ?? true {
            try Task.checkCancellation()
            let request = stream.request(bytes: offset, through: offset + chunkSize - 1, number: number)
            number += 1
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw YouTubeError.unexpectedResponse }
            guard (200..<300).contains(http.statusCode) else {
                throw offset == 0 ? YouTubeError.audioRefused(http.statusCode)
                                  : YouTubeError.cutOff(http.statusCode, Int(Double(offset) / Double(total ?? offset) * 100))
            }
            if http.statusCode == 200 {
                // The server ignored the range and sent the whole file.
                try handle.truncate(atOffset: 0)
                try handle.write(contentsOf: data)
                offset = Int64(data.count)
                break
            }
            total = ResolvedStream.totalLength(http) ?? total
            try handle.write(contentsOf: data)
            offset += Int64(data.count)
            if let total, total > 0 { progress(min(1, Double(offset) / Double(total))) }
            if data.isEmpty || (total == nil && data.count < chunkSize) { break }
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
