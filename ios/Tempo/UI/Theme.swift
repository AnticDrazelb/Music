import SwiftUI

/// User preferences, kept in UserDefaults.
@MainActor
final class Preferences: ObservableObject {
    static let shared = Preferences()

    enum Body: String, CaseIterable { case silver = "Silver", black = "Black" }

    @Published var body: Body { didSet { defaults.set(body.rawValue, forKey: "body") } }
    @Published var clicker: Bool { didSet { defaults.set(clicker, forKey: "clicker") } }
    @Published var haptics: Bool { didSet { defaults.set(haptics, forKey: "haptics") } }
    /// Seconds before the backlight goes off; 0 keeps it on.
    @Published var backlight: Int { didSet { defaults.set(backlight, forKey: "backlight") } }
    static let backlightChoices = [10, 20, 30, 60, 0]
    /// When the queue runs out, keep going with similar songs.
    @Published var autoplay: Bool { didSet { defaults.set(autoplay, forKey: "autoplay") } }
    /// Charts for the whole world ("global") or one country (a two-letter code).
    @Published var chartRegion: String { didSet { defaults.set(chartRegion, forKey: "chartRegion") } }

    private let defaults = UserDefaults.standard

    init() {
        body = Body(rawValue: defaults.string(forKey: "body") ?? "") ?? .silver
        clicker = defaults.object(forKey: "clicker") as? Bool ?? true
        haptics = defaults.object(forKey: "haptics") as? Bool ?? true
        backlight = defaults.object(forKey: "backlight") as? Int ?? 20
        autoplay = defaults.object(forKey: "autoplay") as? Bool ?? true
        chartRegion = defaults.string(forKey: "chartRegion") ?? (Locale.current.region?.identifier.lowercased() ?? "global")
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The colours of an iPod classic: anodized aluminium in silver or black.
struct PodStyle {
    let dark: Bool
    /// The aluminium face, top to bottom.
    let shell: [Color]
    let shellEdge: Color
    let wheel: [Color]
    let wheelLabel: Color
    let wheelPressed: Color
    /// The center button is aluminium too, matching the face.
    let center: [Color]
    let centerEdge: Color

    static func of(_ body: Preferences.Body) -> PodStyle {
        switch body {
        case .silver:
            return PodStyle(
                dark: false,
                shell: [Color(hex: 0xE2E3E5), Color(hex: 0xD3D4D7), Color(hex: 0xC4C6C9)],
                shellEdge: Color(hex: 0xA9ACB0),
                wheel: [Color(hex: 0xFCFCFC), Color(hex: 0xEEEEEF)],
                wheelLabel: Color(hex: 0xB3B5B9),
                wheelPressed: Color(hex: 0xD6D7D9),
                center: [Color(hex: 0xDADBDD), Color(hex: 0xC2C4C7)],
                centerEdge: Color(hex: 0xA7AAAE)
            )
        case .black:
            return PodStyle(
                dark: true,
                shell: [Color(hex: 0x38393C), Color(hex: 0x2A2B2E), Color(hex: 0x1D1E20)],
                shellEdge: Color(hex: 0x0C0C0D),
                wheel: [Color(hex: 0x1B1B1C), Color(hex: 0x0C0C0D)],
                wheelLabel: Color(hex: 0x66676B),
                wheelPressed: Color(hex: 0x2A2A2C),
                center: [Color(hex: 0x3A3B3E), Color(hex: 0x26272A)],
                centerEdge: Color(hex: 0x131314)
            )
        }
    }
}

/// The LCD's look: white lists, a silver title bar and the glossy blue selection bar.
enum LCD {
    static let background = Color.white
    static let text = Color.black
    static let secondaryText = Color(hex: 0x5A5A5E)
    static let titleBar = LinearGradient(
        colors: [Color(hex: 0xFFFFFF), Color(hex: 0xE9EBEE), Color(hex: 0xCFD3D8)],
        startPoint: .top, endPoint: .bottom)
    static let titleBarLine = Color(hex: 0x8E9196)
    static let selection = LinearGradient(
        stops: [
            .init(color: Color(hex: 0x8CC4F8), location: 0),
            .init(color: Color(hex: 0x4C9BEA), location: 0.48),
            .init(color: Color(hex: 0x2A7FDC), location: 0.52),
            .init(color: Color(hex: 0x1F6CCB), location: 1),
        ],
        startPoint: .top, endPoint: .bottom)
    static let progressFill = LinearGradient(
        stops: [
            .init(color: Color(hex: 0x9FD0FA), location: 0),
            .init(color: Color(hex: 0x4A97E6), location: 0.5),
            .init(color: Color(hex: 0x2B78CF), location: 1),
        ],
        startPoint: .top, endPoint: .bottom)
    static let progressTrack = LinearGradient(
        colors: [Color(hex: 0xE4E6E9), Color(hex: 0xFFFFFF)], startPoint: .top, endPoint: .bottom)

    static func font(_ size: CGFloat, _ weight: Font.Weight = .semibold) -> Font {
        .system(size: size, weight: weight, design: .default)
    }
}
