import SwiftUI

/// The whole iPod video: a glossy shell, the 4:3 screen up top and the click wheel below.
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
            let margin = width * 0.065
            let screenWidth = width - margin * 2
            let screenHeight = min(screenWidth * 0.75, geo.size.height * 0.42)
            let wheelSpace = geo.size.height - screenHeight - margin * 2
            let wheel = min(width * 0.74, wheelSpace * 0.88)

            VStack(spacing: 0) {
                Spacer(minLength: margin * 0.6)
                bezel(width: screenWidth, height: screenHeight)
                Spacer(minLength: margin)
                ClickWheel(diameter: wheel, style: style) { event in
                    wake()
                    navigator.handle(event)
                }
                Spacer(minLength: margin)
            }
            .frame(width: width)
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
        let border = width * 0.035
        return ScreenView(navigator: navigator)
            .frame(width: width - border * 2, height: height - border * 2)
            .overlay(ScreenGlass(dimmed: !lit))
            .clipShape(RoundedRectangle(cornerRadius: 3))
            .padding(border)
            .background(
                RoundedRectangle(cornerRadius: width * 0.03)
                    .fill(LinearGradient(colors: [Color(hex: 0x2A2B2E), Color(hex: 0x0E0E10)], startPoint: .top, endPoint: .bottom))
            )
            .overlay(RoundedRectangle(cornerRadius: width * 0.03).stroke(.black.opacity(0.6), lineWidth: 1))
            .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
    }

    private func shell(_ style: PodStyle) -> some View {
        ZStack {
            LinearGradient(colors: style.shell, startPoint: .top, endPoint: .bottom)
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
