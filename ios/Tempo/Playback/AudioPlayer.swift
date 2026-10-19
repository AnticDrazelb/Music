import AVFoundation
import MediaPlayer
import UIKit

/// Plays a queue of songs: downloaded ones from disk, search results streamed from YouTube.
/// Keeps the lock screen and Control Center in sync.
@MainActor
final class AudioPlayer: ObservableObject {
    static let shared = AudioPlayer()

    enum RepeatMode: String, CaseIterable { case off = "Off", one = "One", all = "All" }

    @Published private(set) var queue: [Track] = []
    @Published private(set) var index = 0
    @Published private(set) var isPlaying = false
    @Published private(set) var isLoading = false
    @Published private(set) var currentTime: Double = 0
    @Published private(set) var duration: Double = 0
    @Published var error: String?
    @Published var volume: Float = 0.8 { didSet { player.volume = volume } }
    @Published var shuffle = false
    @Published var repeatMode: RepeatMode = .off

    var current: Track? { queue.indices.contains(index) ? queue[index] : nil }

    private let player = AVPlayer()
    private let library: LibraryStore
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loadTask: Task<Void, Never>?
    /// The queue in its original order, to restore when shuffle is switched off.
    private var unshuffled: [Track] = []

    init(library: LibraryStore = .shared) {
        self.library = library
        player.volume = volume
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time) }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, (note.object as? AVPlayerItem) === self.player.currentItem else { return }
                self.finished()
            }
        }
        setUpRemoteCommands()
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            MainActor.assumeIsolated {
                if type == .began { self?.isPlaying = false }
            }
        }
    }

    // MARK: Queue

    func play(_ tracks: [Track], startAt start: Int = 0) {
        guard tracks.indices.contains(start) else { return }
        unshuffled = tracks
        if shuffle {
            var rest = tracks
            let first = rest.remove(at: start)
            queue = [first] + rest.shuffled()
            index = 0
        } else {
            queue = tracks
            index = start
        }
        load(autoplay: true)
    }

    func playNext(_ track: Track) {
        if queue.isEmpty { play([track]); return }
        queue.insert(track, at: index + 1)
        unshuffled.append(track)
    }

    func setShuffle(_ on: Bool) {
        shuffle = on
        guard let current else { return }
        if on {
            unshuffled = queue
            queue = [current] + queue.enumerated().filter { $0.offset != index }.map(\.element).shuffled()
            index = 0
        } else if !unshuffled.isEmpty {
            queue = unshuffled
            index = unshuffled.firstIndex(of: current) ?? 0
        }
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { resume() }
    }

    func resume() {
        guard current != nil else { return }
        if player.currentItem == nil { load(autoplay: true); return }
        activateSession()
        player.play()
        isPlaying = true
        updateNowPlaying()
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func next() {
        guard !queue.isEmpty else { return }
        if index + 1 < queue.count {
            index += 1
        } else if repeatMode == .all {
            index = 0
        } else {
            pause()
            seek(to: 0)
            return
        }
        load(autoplay: true)
    }

    /// Like an iPod: back to the start of the song, or the previous song in the first seconds.
    func previous() {
        guard !queue.isEmpty else { return }
        if currentTime > 3 || index == 0 {
            seek(to: 0)
        } else {
            index -= 1
            load(autoplay: true)
        }
    }

    func seek(to seconds: Double) {
        let clamped = max(0, min(seconds, duration > 0 ? duration - 0.5 : seconds))
        currentTime = clamped
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        updateNowPlaying()
    }

    /// Removes a song that was deleted from the library.
    func forget(_ track: Track) {
        guard let position = queue.firstIndex(of: track) else { return }
        unshuffled.removeAll { $0.id == track.id }
        if position == index {
            queue.remove(at: position)
            if queue.isEmpty { stop() } else { index = min(index, queue.count - 1); load(autoplay: isPlaying) }
        } else {
            queue.remove(at: position)
            if position < index { index -= 1 }
        }
    }

    func stop() {
        loadTask?.cancel()
        player.replaceCurrentItem(with: nil)
        queue = []
        index = 0
        isPlaying = false
        currentTime = 0
        duration = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: Loading

    private func load(autoplay: Bool) {
        guard let track = current else { return }
        loadTask?.cancel()
        error = nil
        currentTime = 0
        duration = Double(track.duration ?? 0)
        player.replaceCurrentItem(with: nil)
        updateNowPlaying()

        if let file = library.fileURL(for: track) {
            start(AVPlayerItem(url: file), autoplay: autoplay)
            return
        }
        isLoading = true
        loadTask = Task { [weak self] in
            do {
                let stream = try await YouTube.shared.playableStream(for: track.id)
                guard !Task.isCancelled, let self, self.current?.id == track.id else { return }
                let asset = AVURLAsset(url: stream.url, options: ["AVURLAssetHTTPHeaderFieldsKey": stream.headers])
                self.isLoading = false
                self.start(AVPlayerItem(asset: asset), autoplay: autoplay)
            } catch {
                guard !Task.isCancelled, let self, self.current?.id == track.id else { return }
                self.isLoading = false
                self.isPlaying = false
                self.error = error.localizedDescription
            }
        }
    }

    private func start(_ item: AVPlayerItem, autoplay: Bool) {
        player.replaceCurrentItem(with: item)
        if autoplay {
            activateSession()
            player.play()
            isPlaying = true
        }
        updateNowPlaying()
    }

    private func finished() {
        if repeatMode == .one {
            seek(to: 0)
            player.play()
        } else {
            next()
        }
    }

    private func tick(_ time: CMTime) {
        let seconds = time.seconds
        if seconds.isFinite { currentTime = seconds }
        if let itemDuration = player.currentItem?.duration.seconds, itemDuration.isFinite, itemDuration > 0,
           abs(itemDuration - duration) > 0.5 {
            duration = itemDuration
            updateNowPlaying()
        }
    }

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }

    // MARK: Lock screen

    private func setUpRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in self?.resume(); return .success }
        center.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.togglePlayPause(); return .success }
        center.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        center.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(to: event.positionTime)
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let track = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.albumTitle,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let image = library.artwork(for: track) {
            info[MPMediaItemPropertyArtwork] = Self.artwork(image)
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Built outside the main actor: iOS asks for the image on a background queue.
    nonisolated private static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
