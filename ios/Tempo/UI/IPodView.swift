import SwiftUI

/// The whole iPod classic: an aluminium face, the screen up top and the click wheel below.
struct IPodView: View {
    @StateObject private var navigator = Navigator()
    @EnvironmentObject var prefs: Preferences
    @Environment(\.scenePhase) private var scenePhase
    /// The backlight: goes off after a while with nothing touched, like the iPod's.
    @State private var lit = true
    @State private var backlightTimer: Task<Void, Never>?

    var body: some View {
        let style = PodStyle.of(prefs.body)
        GeometryReader { geo in
            let width = geo.size.width
            let height = geo.size.height
            // Proportions taken from a real iPod-style layout on an iPhone: the screen nearly
            // fills the width, the wheel is 80% of it, and the screen takes whatever height
            // is left (between 4:3 and square).
            let margin = width * 0.05
            let screenWidth = width - margin * 2
            let topGap = width * 0.02
            let gap = width * 0.17
            let bottomGap = width * 0.06
            let wheel = min(width * 0.8, height - topGap - gap - bottomGap - screenWidth * 0.75)
            let screenHeight = min(screenWidth * 1.0, max(screenWidth * 0.75, height - topGap - gap - bottomGap - wheel))
            let spare = max(0, height - topGap - screenHeight - gap - wheel - bottomGap)

            VStack(spacing: 0) {
                Color.clear.frame(height: topGap + spare * 0.2)
                bezel(width: screenWidth, height: screenHeight)
                Color.clear.frame(height: gap + spare * 0.4)
                ClickWheel(diameter: wheel, style: style) { event in
                    wake()
                    navigator.handle(event)
                }
                Spacer(minLength: 0)
            }
            .frame(width: width, height: geo.size.height)
        }
        .background(shell(style).ignoresSafeArea())
        .overlay(CaseEdges(dark: style.dark).ignoresSafeArea())
        .ignoresSafeArea(.keyboard)
        // Any touch turns the backlight back on.
        .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in wake() })
        .onAppear {
            openScreenForScreenshots()
            Tilt.shared.start()
            wake()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Tilt.shared.start(); wake() } else { Tilt.shared.stop() }
        }
        .onChange(of: prefs.backlight) { _, _ in wake() }
        .fileImporter(isPresented: $navigator.showImporter, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { navigator.importFiles(urls) }
        }
        // "Open in Tempo" from the share sheet.
        .onOpenURL { url in navigator.importFiles([url]) }
        .task(id: scenePhase) {
            // Pick up songs copied into Tempo's folder with the Files app or Finder.
            if scenePhase == .active { _ = await LibraryImporter.shared.importDroppedFiles() }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
    }

    /// CI screenshots set TEMPO_SCREEN to open a page at launch.
    private func openScreenForScreenshots() {
        switch ProcessInfo.processInfo.environment["TEMPO_SCREEN"] {
        case "music": navigator.push(navigator.musicPage())
        case "search":
            let page = navigator.searchPage()
            page.query = "Daft p"
            page.letterIndex = 20
            navigator.push(page)
        case "settings": navigator.push(navigator.settingsPage())
        case "black": prefs.body = .black
        case "results": navigator.runSearch("Bohemian Rhapsody")
        case "nowplaying":
            Task {
                guard let first = try? await YouTube.shared.searchSongs("Daft Punk One More Time").first else { return }
                navigator.player.play([first])
                navigator.push(navigator.nowPlayingPage())
            }
        default: break
        }
    }

    private func bezel(width: CGFloat, height: CGFloat) -> some View {
        let border = width * 0.022
        return ScreenView(navigator: navigator)
            .frame(width: width - border * 2, height: height - border * 2)
            .overlay(ScreenGlass(dimmed: !lit))
            .clipShape(RoundedRectangle(cornerRadius: width * 0.025))
            .padding(border)
            .background(
                RoundedRectangle(cornerRadius: width * 0.045)
                    .fill(LinearGradient(colors: [Color(hex: 0x2A2B2E), Color(hex: 0x0E0E10)], startPoint: .top, endPoint: .bottom))
            )
            .overlay(RoundedRectangle(cornerRadius: width * 0.045).stroke(.black.opacity(0.6), lineWidth: 1))
            .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
    }

    private func shell(_ style: PodStyle) -> some View {
        ZStack {
            LinearGradient(colors: style.shell, startPoint: .top, endPoint: .bottom)
            AluminiumGrain(dark: style.dark)
            BodyGloss(dark: style.dark)
        }
    }

    private func wake() {
        if !lit { withAnimation(.easeOut(duration: 0.15)) { lit = true } }
        backlightTimer?.cancel()
        let seconds = prefs.backlight
        guard seconds > 0 else { return }
        backlightTimer = Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.8)) { lit = false }
        }
    }
}
