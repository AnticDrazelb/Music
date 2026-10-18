import SwiftUI

/// What the click wheel can do.
enum WheelEvent {
    case scroll(Int)
    case menu
    case menuHold
    case select
    case selectHold
    case playPause
    case next
    case previous
}

struct MenuItem: Identifiable {
    let id: String
    var title: String
    /// Right-aligned text, like a song's length or "42%".
    var detail: String? = nil
    var chevron = true
    var action: (() -> Void)? = nil
    /// Holding the center button on the row.
    var holdAction: (() -> Void)? = nil
}

/// One screen on the iPod. Menus build their rows on demand so they follow the library.
@MainActor
final class Page: ObservableObject, Identifiable {
    enum Kind { case menu, nowPlaying, search }
    enum NowPlayingMode { case progress, volume, scrub }

    let id = UUID()
    let title: String
    let kind: Kind
    var rows: () -> [MenuItem]
    /// A song shown above the rows (a song's own page).
    let track: Track?
    /// The first screen: shows artwork beside the menu.
    let isRoot: Bool

    @Published var selection = 0
    @Published var scrollTop = 0
    @Published var loading = false
    @Published var message: String?
    @Published var results: [Track] = []

    // Search
    @Published var query = ""
    @Published var letterIndex = 0

    // Now Playing
    @Published var mode: NowPlayingMode = .progress
    var modeReset: Task<Void, Never>?

    init(title: String, kind: Kind = .menu, track: Track? = nil, isRoot: Bool = false,
         message: String? = nil, rows: @escaping () -> [MenuItem] = { [] }) {
        self.title = title
        self.kind = kind
        self.track = track
        self.isRoot = isRoot
        self.message = message
        self.rows = rows
    }
}

@MainActor
final class Navigator: ObservableObject {
    @Published private(set) var stack: [Page] = []
    /// Which way the screens slide: forward into a menu, or back out.
    @Published private(set) var forward = true
    /// Rows that fit on the screen, set by the menu view.
    var visibleRows = 7

    let library: LibraryStore
    let downloads: DownloadManager
    let player: AudioPlayer
    let prefs: Preferences

    static let searchKeys: [String] = (65...90).map { String(UnicodeScalar($0)!) } + (0...9).map(String.init) + ["space", "delete", "search"]

    init(library: LibraryStore = .shared, downloads: DownloadManager = .shared,
         player: AudioPlayer = .shared, prefs: Preferences = .shared) {
        self.library = library
        self.downloads = downloads
        self.player = player
        self.prefs = prefs
        stack = [rootPage()]
    }

    var top: Page { stack[stack.count - 1] }

    func push(_ page: Page) {
        forward = true
        // Let the direction settle before the slide starts.
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.22)) { self.stack.append(page) }
        }
    }

    func pop() {
        guard stack.count > 1 else { return }
        forward = false
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.22)) { _ = self.stack.popLast() }
        }
    }

    func popToRoot() {
        guard stack.count > 1 else { return }
        forward = false
        DispatchQueue.main.async {
            withAnimation(.easeInOut(duration: 0.22)) { self.stack = [self.stack[0]] }
        }
    }

    // MARK: Wheel

    func handle(_ event: WheelEvent) {
        let page = top
        switch event {
        case .menu:
            pop()
            return
        case .menuHold:
            popToRoot()
            return
        case .playPause:
            player.togglePlayPause()
            return
        case .next:
            player.next()
            return
        case .previous:
            player.previous()
            return
        default:
            break
        }
        switch page.kind {
        case .menu: handleMenu(event, page)
        case .nowPlaying: handleNowPlaying(event, page)
        case .search: handleSearch(event, page)
        }
    }

    private func handleMenu(_ event: WheelEvent, _ page: Page) {
        let rows = page.rows()
        switch event {
        case .scroll(let step):
            guard !rows.isEmpty else { return }
            let new = max(0, min(rows.count - 1, page.selection + step))
            if new != page.selection {
                page.selection = new
                Clicker.shared.scrollTick()
            }
            keepVisible(page)
        case .select:
            guard rows.indices.contains(page.selection) else { return }
            rows[page.selection].action?()
        case .selectHold:
            guard rows.indices.contains(page.selection) else { return }
            (rows[page.selection].holdAction ?? rows[page.selection].action)?()
        default:
            break
        }
    }

    func keepVisible(_ page: Page) {
        if page.selection < page.scrollTop { page.scrollTop = page.selection }
        if page.selection >= page.scrollTop + visibleRows { page.scrollTop = page.selection - visibleRows + 1 }
    }

    /// Tapping a row on the screen: select it, then open it.
    func tapRow(_ index: Int, on page: Page) {
        let rows = page.rows()
        guard rows.indices.contains(index) else { return }
        page.selection = index
        Clicker.shared.buttonPress()
        rows[index].action?()
    }

    private func handleNowPlaying(_ event: WheelEvent, _ page: Page) {
        switch event {
        case .scroll(let step):
            if page.mode == .scrub {
                let stride = max(2, player.duration / 120)
                let target = max(0, min(player.duration, player.currentTime + Double(step) * stride))
                if target != player.currentTime {
                    player.seek(to: target)
                    Clicker.shared.scrollTick()
                }
            } else {
                // Like the iPod, no click once the volume is all the way up or down.
                let new = max(0, min(1, player.volume + Float(step) * 0.04))
                if new != player.volume {
                    player.volume = new
                    Clicker.shared.scrollTick()
                }
                page.mode = .volume
            }
            resetModeLater(page)
        case .select:
            page.mode = page.mode == .scrub ? .progress : .scrub
            resetModeLater(page)
        case .selectHold:
            if let track = player.current { push(songPage(track, context: player.queue)) }
        default:
            break
        }
    }

    private func resetModeLater(_ page: Page) {
        page.modeReset?.cancel()
        page.modeReset = Task { [weak page] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) { page?.mode = .progress }
        }
    }

    private func handleSearch(_ event: WheelEvent, _ page: Page) {
        let keys = Self.searchKeys
        switch event {
        case .scroll(let step):
            page.letterIndex = (page.letterIndex + step + keys.count) % keys.count
            Clicker.shared.scrollTick()
        case .select:
            type(keys[page.letterIndex], on: page)
        case .selectHold:
            page.query = ""
        default:
            break
        }
    }

    func type(_ key: String, on page: Page) {
        switch key {
        case "space": if !page.query.isEmpty, !page.query.hasSuffix(" ") { page.query += " " }
        case "delete": if !page.query.isEmpty { page.query.removeLast() }
        case "search": runSearch(page.query)
        default: page.query += page.query.isEmpty || page.query.hasSuffix(" ") ? key : key.lowercased()
        }
    }

    func runSearch(_ text: String) {
        let query = text.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        let page = Page(title: "Search")
        page.rows = { [unowned self, weak page] in self.searchResultRows(for: page) }
        page.loading = true
        push(page)
        Task {
            do {
                let found = try await YouTube.shared.searchSongs(query)
                page.results = found
                page.message = found.isEmpty ? "No songs found for “\(query)”" : nil
            } catch {
                page.message = error.localizedDescription
            }
            page.loading = false
        }
    }

    private func searchResultRows(for page: Page?) -> [MenuItem] {
        guard let page else { return [] }
        let results = page.results
        return results.map { track in
            MenuItem(id: track.id, title: "\(track.title) — \(track.artist)", detail: status(of: track),
                     action: { [unowned self] in push(songPage(track, context: results)) },
                     holdAction: { [unowned self] in downloads.download(track) })
        }
    }

    /// "✓" when downloaded, a percentage while downloading.
    func status(of track: Track) -> String? {
        if library.contains(track.id) { return "✓" }
        switch downloads.state(of: track.id) {
        case .queued?: return "…"
        case .downloading(let f)?: return "\(Int(f * 100))%"
        case .failed?: return "!"
        case nil: return nil
        }
    }
}
