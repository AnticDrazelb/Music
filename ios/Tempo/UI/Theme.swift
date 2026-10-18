import SwiftUI

/// User preferences, kept in UserDefaults.
@MainActor
final class Preferences: ObservableObject {
    static let shared = Preferences()

    enum Body: String, CaseIterable { case white = "White", black = "Black" }

    @Published var body: Body { didSet { defaults.set(body.rawValue, forKey: "body") } }
    @Published var clicker: Bool { didSet { defaults.set(clicker, forKey: "clicker") } }
    @Published var haptics: Bool { didSet { defaults.set(haptics, forKey: "haptics") } }

    private let defaults = UserDefaults.standard

    init() {
        body = Body(rawValue: defaults.string(forKey: "body") ?? "") ?? .white
        clicker = defaults.object(forKey: "clicker") as? Bool ?? true
        haptics = defaults.object(forKey: "haptics") as? Bool ?? true
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// The colours of an iPod video (5th generation), white or black.
struct PodStyle {
    let shell: [Color]
    let shellEdge: Color
    let wheel: [Color]
    let wheelLabel: Color
    let wheelPressed: Color
    let center: [Color]
    let centerEdge: Color

    static func of(_ body: Preferences.Body) -> PodStyle {
        switch body {
        case .white:
            return PodStyle(
                shell: [Color(hex: 0xFDFDFD), Color(hex: 0xF1F1F3), Color(hex: 0xE2E3E6)],
                shellEdge: Color(hex: 0xC9CACD),
                wheel: [Color(hex: 0xE9EAEC), Color(hex: 0xD8DADD)],
                wheelLabel: Color(hex: 0x9C9EA3),
                wheelPressed: Color(hex: 0xC4C6CA),
                center: [Color(hex: 0xFFFFFF), Color(hex: 0xEDEEF0)],
                centerEdge: Color(hex: 0xB4B6BB)
            )
        case .black:
            return PodStyle(
                shell: [Color(hex: 0x3A3A3D), Color(hex: 0x1B1B1D), Color(hex: 0x0B0B0C)],
                shellEdge: Color(hex: 0x050505),
                wheel: [Color(hex: 0x2E2F31), Color(hex: 0x1D1E20)],
                wheelLabel: Color(hex: 0xB8B9BC),
                wheelPressed: Color(hex: 0x3D3E41),
                center: [Color(hex: 0x1A1A1C), Color(hex: 0x0D0D0E)],
                centerEdge: Color(hex: 0x3A3B3E)
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
