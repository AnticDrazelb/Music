import SwiftUI

/// The iPod's LCD: a title bar over whichever page is on top, sliding between pages.
struct ScreenView: View {
    @ObservedObject var navigator: Navigator
    @EnvironmentObject var player: AudioPlayer

    var body: some View {
        GeometryReader { geo in
            let unit = geo.size.height / 9.5
            VStack(spacing: 0) {
                TitleBar(title: navigator.top.title, unit: unit)
                ZStack {
                    ForEach(navigator.stack.suffix(1)) { page in
                        PageView(page: page, navigator: navigator, unit: unit)
                            .transition(.asymmetric(
                                insertion: .move(edge: navigator.forward ? .trailing : .leading),
                                removal: .move(edge: navigator.forward ? .leading : .trailing)))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
            }
            .background(LCD.background)
        }
    }
}

struct PageView: View {
    @ObservedObject var page: Page
    let navigator: Navigator
    let unit: CGFloat

    var body: some View {
        switch page.kind {
        case .menu: MenuView(page: page, navigator: navigator, unit: unit)
        case .nowPlaying: NowPlayingView(page: page, unit: unit)
        case .search: SearchView(page: page, navigator: navigator, unit: unit)
        }
    }
}

// MARK: - Title bar

struct TitleBar: View {
    let title: String
    let unit: CGFloat
    @EnvironmentObject var player: AudioPlayer
    @StateObject private var battery = BatteryMonitor()

    var body: some View {
        // The classic's title bar: title on the left, play state and battery on the right.
        HStack(spacing: unit * 0.2) {
            Text(title)
                .font(LCD.font(unit * 0.46, .bold))
                .foregroundStyle(LCD.text)
                .lineLimit(1)
            Spacer(minLength: unit * 0.3)
            if player.current != nil {
                Image(systemName: player.isPlaying ? "play.fill" : "pause.fill")
                    .font(.system(size: unit * 0.32, weight: .bold))
                    .foregroundStyle(Color(hex: 0x2E7BD6))
            }
            BatteryIcon(level: battery.level, charging: battery.charging)
                .frame(width: unit * 0.85, height: unit * 0.4)
        }
        .padding(.horizontal, unit * 0.25)
        .frame(height: unit)
        .background(LCD.titleBar)
        .overlay(alignment: .bottom) { Rectangle().fill(LCD.titleBarLine).frame(height: 1) }
    }
}

@MainActor
final class BatteryMonitor: ObservableObject {
    @Published var level: Float = 1
    @Published var charging = false
    private var observers: [NSObjectProtocol] = []

    init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        update()
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.update() }
            })
        }
    }

    private func update() {
        let raw = UIDevice.current.batteryLevel
        level = raw < 0 ? 1 : raw
        charging = [.charging, .full].contains(UIDevice.current.batteryState)
    }
}

/// The iPod's little green battery.
struct BatteryIcon: View {
    let level: Float
    let charging: Bool

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            HStack(spacing: 0) {
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: h * 0.15)
                        .fill(LinearGradient(colors: [Color(hex: 0xF4F4F4), Color(hex: 0xBDBDBD)], startPoint: .top, endPoint: .bottom))
                    RoundedRectangle(cornerRadius: h * 0.1)
                        .fill(LinearGradient(colors: level < 0.2 ? [Color(hex: 0xF7A08F), Color(hex: 0xD3301A)]
                                                                  : [Color(hex: 0xC6F59A), Color(hex: 0x4FB31F)],
                                             startPoint: .top, endPoint: .bottom))
                        .frame(width: max(2, (w * 0.9 - 4) * CGFloat(level)))
                        .padding(2)
                    if charging {
                        Image(systemName: "bolt.fill")
                            .font(.system(size: h * 0.6))
                            .foregroundStyle(.black.opacity(0.6))
                            .frame(maxWidth: .infinity)
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: h * 0.15).stroke(Color(hex: 0x6B6B6B), lineWidth: 1))
                .frame(width: w * 0.9)
                Rectangle().fill(Color(hex: 0x6B6B6B)).frame(width: w * 0.08, height: h * 0.45)
            }
        }
    }
}

// MARK: - Menus

struct MenuView: View {
    @ObservedObject var page: Page
    let navigator: Navigator
    let unit: CGFloat
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var downloads: DownloadManager
    @EnvironmentObject var player: AudioPlayer
    @EnvironmentObject var prefs: Preferences

    var body: some View {
        let rows = page.rows()
        GeometryReader { geo in
            let rowHeight = unit
            let header = page.track == nil ? 0 : unit * 2.6
            let visible = max(1, Int((geo.size.height - header) / rowHeight))
            HStack(spacing: 0) {
                VStack(spacing: 0) {
                    if let track = page.track {
                        SongHeader(track: track, unit: unit).frame(height: header)
                    }
                    if rows.isEmpty {
                        EmptyMessage(text: page.loading ? nil : page.message, loading: page.loading, loadingText: page.loadingText, unit: unit)
                    } else {
                        list(rows, visible: visible, rowHeight: rowHeight)
                    }
                    Spacer(minLength: 0)
                }
                .frame(width: page.isRoot ? geo.size.width * 0.5 : geo.size.width)
                if page.isRoot {
                    RootArtwork(unit: unit)
                        .frame(width: geo.size.width * 0.5, height: geo.size.height)
                        .clipped()
                }
            }
            .onAppear { navigator.visibleRows = visible; clamp(rows.count) }
            .onChange(of: rows.count) { _, count in clamp(count) }
        }
    }

    private func list(_ rows: [MenuItem], visible: Int, rowHeight: CGFloat) -> some View {
        let top = min(page.scrollTop, max(0, rows.count - visible))
        let shown = Array(rows.enumerated()).dropFirst(top).prefix(visible)
        return HStack(spacing: 0) {
            VStack(spacing: 0) {
                ForEach(Array(shown), id: \.element.id) { index, row in
                    MenuRow(item: row, selected: index == page.selection, unit: unit)
                        .frame(height: rowHeight)
                        .contentShape(Rectangle())
                        .onTapGesture { navigator.tapRow(index, on: page) }
                        .onLongPressGesture { page.selection = index; (row.holdAction ?? row.action)?() }
                }
            }
            if rows.count > visible {
                ScrollIndicator(total: rows.count, visible: visible, top: top)
                    .frame(width: unit * 0.32, height: rowHeight * CGFloat(visible))
            }
        }
    }

    private func clamp(_ count: Int) {
        if page.selection >= count { page.selection = max(0, count - 1) }
        navigator.keepVisible(page)
    }
}

struct MenuRow: View {
    let item: MenuItem
    let selected: Bool
    let unit: CGFloat

    var body: some View {
        HStack(spacing: unit * 0.2) {
            Text(item.title)
                .font(LCD.font(unit * 0.46))
                .lineLimit(1)
            Spacer(minLength: 4)
            if let detail = item.detail {
                Text(detail)
                    .font(LCD.font(unit * 0.4, .medium))
                    .lineLimit(1)
                    .foregroundStyle(selected ? .white : LCD.secondaryText)
            }
            if item.chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: unit * 0.34, weight: .heavy))
                    .foregroundStyle(selected ? .white : LCD.secondaryText)
            }
        }
        .foregroundStyle(selected ? .white : LCD.text)
        .padding(.horizontal, unit * 0.25)
        .frame(maxHeight: .infinity)
        .background {
            if selected {
                Rectangle().fill(LCD.selection)
                    .overlay(alignment: .top) { Rectangle().fill(.white.opacity(0.35)).frame(height: 1) }
            }
        }
    }
}

struct ScrollIndicator: View {
    let total: Int
    let visible: Int
    let top: Int

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let thumb = max(h * CGFloat(visible) / CGFloat(total), 12)
            let offset = (h - thumb) * CGFloat(top) / CGFloat(max(1, total - visible))
            ZStack(alignment: .top) {
                Rectangle().fill(LCD.progressTrack)
                    .overlay(Rectangle().stroke(Color(hex: 0x9A9DA2), lineWidth: 1))
                Rectangle().fill(LCD.progressFill)
                    .frame(height: thumb)
                    .overlay(Rectangle().stroke(Color(hex: 0x2B5E9E), lineWidth: 0.5))
                    .offset(y: offset)
            }
        }
    }
}

struct EmptyMessage: View {
    let text: String?
    let loading: Bool
    var loadingText = "Searching…"
    let unit: CGFloat

    var body: some View {
        VStack(spacing: unit * 0.3) {
            if loading {
                ProgressView().controlSize(.regular).tint(LCD.secondaryText)
                Text(loadingText).font(LCD.font(unit * 0.42)).foregroundStyle(LCD.secondaryText)
            } else if let text {
                Text(text)
                    .font(LCD.font(unit * 0.42))
                    .foregroundStyle(LCD.secondaryText)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.6)
            }
        }
        .padding(unit * 0.4)
        .frame(maxWidth: .infinity, minHeight: unit * 4)
    }
}

/// On the main menu, the right half shows artwork from the library, slowly changing.
struct RootArtwork: View {
    let unit: CGFloat
    @EnvironmentObject var library: LibraryStore
    @State private var index = 0
    private let timer = Timer.publish(every: 6, on: .main, in: .common).autoconnect()

    var body: some View {
        let art = library.tracks.filter { $0.artworkFileName != nil }
        ZStack {
            if art.isEmpty {
                LinearGradient(colors: [Color(hex: 0xDDE6F3), Color(hex: 0x9FB6D6)], startPoint: .top, endPoint: .bottom)
                Image(systemName: "music.note")
                    .font(.system(size: unit * 1.6, weight: .light))
                    .foregroundStyle(.white.opacity(0.9))
            } else {
                let track = art[index % art.count]
                ArtworkView(track: track)
                    .id(track.id)
                    .transition(.opacity)
            }
        }
        .overlay(alignment: .leading) { Rectangle().fill(Color.black.opacity(0.25)).frame(width: 1) }
        .onReceive(timer) { _ in
            guard art.count > 1 else { return }
            withAnimation(.easeInOut(duration: 1)) { index = Int.random(in: 0..<art.count * 7) }
        }
    }
}

/// Library artwork from disk, or the remote art for search results.
struct ArtworkView: View {
    let track: Track
    @EnvironmentObject var library: LibraryStore

    var body: some View {
        if let image = library.artwork(for: track) {
            Image(uiImage: image).resizable().scaledToFill()
        } else if let url = track.artworkURL {
            AsyncImage(url: url) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                placeholder
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0xE9EBEF), Color(hex: 0xBFC5CE)], startPoint: .top, endPoint: .bottom)
            Image(systemName: "music.note").foregroundStyle(.white)
        }
    }
}

/// Artwork and details at the top of a song's page.
struct SongHeader: View {
    let track: Track
    let unit: CGFloat

    var body: some View {
        HStack(spacing: unit * 0.35) {
            ArtworkView(track: track)
                .frame(width: unit * 2.1, height: unit * 2.1)
                .clipped()
                .overlay(Rectangle().stroke(Color.black.opacity(0.2), lineWidth: 0.5))
            VStack(alignment: .leading, spacing: unit * 0.06) {
                Text(track.title).font(LCD.font(unit * 0.46, .bold)).lineLimit(2)
                Text(track.artist).font(LCD.font(unit * 0.38, .medium)).lineLimit(1)
                if let album = track.album {
                    Text(album).font(LCD.font(unit * 0.36, .regular)).lineLimit(1)
                }
                if let d = track.duration {
                    Text(TimeText.string(Double(d))).font(LCD.font(unit * 0.34, .regular)).foregroundStyle(LCD.secondaryText)
                }
            }
            .foregroundStyle(LCD.text)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, unit * 0.3)
        .frame(maxHeight: .infinity)
        .overlay(alignment: .bottom) { Rectangle().fill(Color(hex: 0xC8CBD0)).frame(height: 1) }
    }
}

// MARK: - Now Playing

struct NowPlayingView: View {
    @ObservedObject var page: Page
    let unit: CGFloat
    @EnvironmentObject var player: AudioPlayer

    var body: some View {
        VStack(spacing: 0) {
            if let track = player.current {
                HStack {
                    Text("\(player.index + 1) of \(player.queue.count)")
                    Spacer()
                    if player.shuffle { Image(systemName: "shuffle") }
                    if player.repeatMode != .off { Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat") }
                }
                .font(LCD.font(unit * 0.36, .medium))
                .foregroundStyle(LCD.secondaryText)
                .padding(.horizontal, unit * 0.3)
                .padding(.top, unit * 0.15)

                HStack(alignment: .center, spacing: unit * 0.35) {
                    ReflectedArtwork(track: track, size: unit * 3.6)
                    VStack(alignment: .leading, spacing: unit * 0.1) {
                        Text(track.title).font(LCD.font(unit * 0.48, .bold)).lineLimit(2)
                        Text(track.artist).font(LCD.font(unit * 0.42, .medium)).lineLimit(1)
                        Text(track.albumTitle).font(LCD.font(unit * 0.4, .regular)).lineLimit(1)
                        if player.isLoading {
                            HStack(spacing: 4) {
                                ProgressView().controlSize(.mini)
                                Text("Loading…")
                            }
                            .font(LCD.font(unit * 0.36, .regular))
                            .foregroundStyle(LCD.secondaryText)
                        } else if let error = player.error {
                            Text(error).font(LCD.font(unit * 0.3, .regular)).foregroundStyle(Color(hex: 0xB3261E))
                                .lineLimit(8).minimumScaleFactor(0.6)
                        }
                    }
                    .foregroundStyle(LCD.text)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, unit * 0.35)
                .frame(maxHeight: .infinity)

                bottomBar
                    .padding(.horizontal, unit * 0.35)
                    .padding(.bottom, unit * 0.3)
            } else {
                EmptyMessage(text: "Nothing playing", loading: false, unit: unit)
                Spacer()
            }
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        switch page.mode {
        case .volume:
            HStack(spacing: unit * 0.25) {
                Image(systemName: "speaker.fill")
                ProgressBar(fraction: Double(player.volume), knob: false).frame(height: unit * 0.4)
                Image(systemName: "speaker.wave.3.fill")
            }
            .font(.system(size: unit * 0.36))
            .foregroundStyle(LCD.secondaryText)
            .frame(height: unit * 0.95)
        case .progress, .scrub:
            VStack(spacing: unit * 0.08) {
                ProgressBar(fraction: player.duration > 0 ? player.currentTime / player.duration : 0, knob: page.mode == .scrub)
                    .frame(height: unit * 0.4)
                HStack {
                    Text(TimeText.string(player.currentTime))
                    Spacer()
                    Text("-" + TimeText.string(max(0, player.duration - player.currentTime)))
                }
                .font(LCD.font(unit * 0.36, .medium))
                .foregroundStyle(LCD.text)
            }
            .frame(height: unit * 0.95)
        }
    }
}

struct ProgressBar: View {
    let fraction: Double
    let knob: Bool

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let f = CGFloat(max(0, min(1, fraction.isFinite ? fraction : 0)))
            ZStack(alignment: .leading) {
                Rectangle().fill(LCD.progressTrack)
                Rectangle().fill(LCD.progressFill).frame(width: w * f)
                if knob {
                    // The scrubber's diamond.
                    Rectangle()
                        .fill(LinearGradient(colors: [.white, Color(hex: 0xB9BDC3)], startPoint: .top, endPoint: .bottom))
                        .overlay(Rectangle().stroke(Color(hex: 0x3D3F43), lineWidth: 1))
                        .frame(width: h * 0.9, height: h * 0.9)
                        .rotationEffect(.degrees(45))
                        .offset(x: w * f - h * 0.45)
                }
            }
            .overlay(Rectangle().stroke(Color(hex: 0x7E8287), lineWidth: 1))
        }
    }
}

/// Album art standing on a glossy floor, like the iPod video's Now Playing.
struct ReflectedArtwork: View {
    let track: Track
    let size: CGFloat

    var body: some View {
        VStack(spacing: 1) {
            art
            art
                .scaleEffect(x: 1, y: -1)
                .frame(height: size * 0.25, alignment: .top)
                .clipped()
                .mask(LinearGradient(colors: [.black.opacity(0.35), .clear], startPoint: .top, endPoint: .bottom))
        }
    }

    private var art: some View {
        ArtworkView(track: track)
            .frame(width: size, height: size)
            .clipped()
    }
}

// MARK: - Search

/// iPod-style search: turn the wheel through the letters and press the center button to type
/// each one, or tap the box to use the keyboard.
struct SearchView: View {
    @ObservedObject var page: Page
    let navigator: Navigator
    let unit: CGFloat
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: unit * 0.35) {
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: unit * 0.15)
                    .fill(Color.white)
                    .overlay(RoundedRectangle(cornerRadius: unit * 0.15).stroke(Color(hex: 0x7E8287), lineWidth: 1))
                HStack(spacing: unit * 0.15) {
                    Image(systemName: "magnifyingglass").foregroundStyle(LCD.secondaryText)
                    TextField("", text: $page.query)
                        .focused($typing)
                        .submitLabel(.search)
                        .autocorrectionDisabled()
                        .onSubmit { navigator.runSearch(page.query) }
                        .overlay(alignment: .leading) {
                            if page.query.isEmpty && !typing {
                                Text("Tap to type, or use the wheel").foregroundStyle(LCD.secondaryText.opacity(0.7))
                                    .allowsHitTesting(false)
                            }
                        }
                }
                .font(LCD.font(unit * 0.44, .medium))
                .foregroundStyle(LCD.text)
                .padding(.horizontal, unit * 0.2)
            }
            .frame(height: unit * 1.1)
            .padding(.horizontal, unit * 0.3)
            .padding(.top, unit * 0.35)

            LetterStrip(index: page.letterIndex, unit: unit) { i in
                page.letterIndex = i
                navigator.type(Navigator.searchKeys[i], on: page)
            }
            .frame(height: unit * 1.5)

            Text("Press ◉ to type the letter · Hold ◉ to clear")
                .font(LCD.font(unit * 0.32, .regular))
                .foregroundStyle(LCD.secondaryText)
            Spacer(minLength: 0)
        }
    }
}

struct LetterStrip: View {
    let index: Int
    let unit: CGFloat
    let onTap: (Int) -> Void

    var body: some View {
        let keys = Navigator.searchKeys
        let side = 4
        HStack(spacing: unit * 0.12) {
            ForEach(-side...side, id: \.self) { offset in
                let i = (index + offset + keys.count) % keys.count
                key(keys[i], selected: offset == 0)
                    .opacity(1 - Double(abs(offset)) * 0.16)
                    .onTapGesture { onTap(i) }
            }
        }
        .animation(.easeOut(duration: 0.12), value: index)
    }

    @ViewBuilder
    private func key(_ key: String, selected: Bool) -> some View {
        let label: AnyView = {
            switch key {
            case "space": return AnyView(Image(systemName: "space"))
            case "delete": return AnyView(Image(systemName: "delete.left"))
            case "search": return AnyView(Text("GO").font(LCD.font(unit * 0.42, .heavy)))
            default: return AnyView(Text(key))
            }
        }()
        label
            .font(LCD.font(selected ? unit * 0.7 : unit * 0.48, .bold))
            .foregroundStyle(selected ? .white : LCD.text)
            .frame(width: selected ? unit * 1.25 : unit * 0.78, height: selected ? unit * 1.25 : unit * 0.9)
            .background {
                if selected { RoundedRectangle(cornerRadius: unit * 0.15).fill(LCD.selection) }
            }
    }
}
