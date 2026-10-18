import SwiftUI

/// The whole iPod video: a glossy shell, the 4:3 screen up top and the click wheel below.
struct IPodView: View {
    @StateObject private var navigator = Navigator()
    @EnvironmentObject var prefs: Preferences

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
                ClickWheel(diameter: wheel, style: style) { navigator.handle($0) }
                Spacer(minLength: margin)
            }
            .frame(width: width)
        }
        .background(shell(style).ignoresSafeArea())
        .ignoresSafeArea(.keyboard)
        .onAppear(perform: openScreenForScreenshots)
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
            .clipShape(RoundedRectangle(cornerRadius: 3))
            .overlay {
                // A faint sheen across the plastic over the LCD.
                LinearGradient(colors: [.white.opacity(0.18), .clear, .clear], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .allowsHitTesting(false)
            }
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
            // The polished front catches the light from above.
            LinearGradient(colors: [.white.opacity(0.35), .clear], startPoint: .top, endPoint: .center)
                .blendMode(.softLight)
        }
    }
}
