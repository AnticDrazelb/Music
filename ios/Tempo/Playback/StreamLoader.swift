import AVFoundation

/// Feeds AVPlayer a YouTube audio stream. AVPlayer can only make plain GET requests, but
/// googlevideo wants the requests YouTube's apps make (see `ResolvedStream.request`), so the
/// asset gets a made-up URL scheme and this loader fetches the bytes AVPlayer asks for.
final class StreamLoader: NSObject, AVAssetResourceLoaderDelegate {
    private static let scheme = "tempo-stream"
    private let stream: ResolvedStream
    private let queue = DispatchQueue(label: "app.tempo.stream-loader")
    private let session = URLSession(configuration: .default)
    private var requestNumber = 1
    private var total: Int64?
    private var tasks: [AVAssetResourceLoadingRequest: Task<Void, Never>] = [:]

    init(stream: ResolvedStream) {
        self.stream = stream
        total = stream.contentLength
    }

    /// An asset that plays the stream through this loader. Keep the loader alive while it plays.
    func makeAsset() -> AVURLAsset {
        var components = URLComponents(url: stream.url, resolvingAgainstBaseURL: false)!
        components.scheme = Self.scheme
        let asset = AVURLAsset(url: components.url!)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    func cancelAll() {
        queue.async {
            self.tasks.values.forEach { $0.cancel() }
            self.tasks = [:]
        }
    }

    // MARK: AVAssetResourceLoaderDelegate (called on `queue`)

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        tasks[loadingRequest] = Task { [weak self] in
            await self?.serve(loadingRequest)
        }
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        tasks.removeValue(forKey: loadingRequest)?.cancel()
    }

    // MARK: Fetching

    private func serve(_ loadingRequest: AVAssetResourceLoadingRequest) async {
        do {
            if let info = loadingRequest.contentInformationRequest {
                let length = try await totalLength()
                queue.sync {
                    info.contentType = AVFileType.m4a.rawValue
                    info.contentLength = length
                    info.isByteRangeAccessSupported = true
                }
            }
            if let dataRequest = loadingRequest.dataRequest {
                let length = try await totalLength()
                var offset = dataRequest.currentOffset != 0 ? dataRequest.currentOffset : dataRequest.requestedOffset
                let end = dataRequest.requestsAllDataToEndOfResource
                    ? length
                    : min(length, dataRequest.requestedOffset + Int64(dataRequest.requestedLength))
                // Smaller pieces at first so playback starts quickly, then bigger ones.
                var piece: Int64 = 256 * 1024
                while offset < end {
                    try Task.checkCancellation()
                    let last = min(end, offset + piece) - 1
                    let data = try await fetch(offset, last)
                    guard !data.isEmpty else { break }
                    queue.sync { dataRequest.respond(with: data) }
                    offset += Int64(data.count)
                    piece = min(piece * 2, 2 * 1024 * 1024)
                }
            }
            queue.sync {
                loadingRequest.finishLoading()
                tasks[loadingRequest] = nil
            }
        } catch {
            queue.sync {
                if !loadingRequest.isFinished && !loadingRequest.isCancelled { loadingRequest.finishLoading(with: error) }
                tasks[loadingRequest] = nil
            }
        }
    }

    private func totalLength() async throws -> Int64 {
        if let total = queue.sync(execute: { self.total }) { return total }
        _ = try await fetch(0, 1)
        guard let total = queue.sync(execute: { self.total }) else { throw YouTubeError.unexpectedResponse }
        return total
    }

    private func fetch(_ start: Int64, _ end: Int64) async throws -> Data {
        let number = queue.sync { () -> Int in
            defer { requestNumber += 1 }
            return requestNumber
        }
        let (data, response) = try await session.data(for: stream.request(bytes: start, through: end, number: number))
        guard let http = response as? HTTPURLResponse else { throw YouTubeError.unexpectedResponse }
        guard (200..<300).contains(http.statusCode) else { throw YouTubeError.audioRefused(http.statusCode) }
        if let length = ResolvedStream.totalLength(http) { queue.sync { total = length } }
        if http.statusCode == 200 {
            // The whole file came back: hand over just the part asked for.
            queue.sync { total = Int64(data.count) }
            let lower = Int(min(start, Int64(data.count)))
            let upper = Int(min(end + 1, Int64(data.count)))
            return data.subdata(in: lower..<upper)
        }
        return data
    }
}
