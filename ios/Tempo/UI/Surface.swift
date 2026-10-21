import CoreMotion
import SwiftUI

/// How the phone is tilted, smoothed, so reflections on the glossy front can move with it.
@MainActor
final class Tilt: ObservableObject {
    static let shared = Tilt()

    /// -1...1 left/right and toward/away, 0 when held at a normal reading angle.
    @Published private(set) var x: Double = 0
    @Published private(set) var y: Double = 0

    private let manager = CMMotionManager()

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive,
              !UIAccessibility.isReduceMotionEnabled else { return }
        manager.deviceMotionUpdateInterval = 1 / 30
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let gravity = motion?.gravity else { return }
            MainActor.assumeIsolated { self?.update(gravity) }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
    }

    private func update(_ gravity: CMAcceleration) {
        // People hold a phone tipped back about 35°, where gravity.y is around -0.6.
        let targetX = max(-1, min(1, gravity.x * 1.6))
        let targetY = max(-1, min(1, (gravity.y + 0.6) * 1.6))
        let newX = x + (targetX - x) * 0.18
        let newY = y + (targetY - y) * 0.18
        // Skip changes too small to see, so the views aren't redrawn for nothing.
        if abs(newX - x) > 0.002 || abs(newY - y) > 0.002 {
            x = newX
            y = newY
        }
    }
}

/// The anodized aluminium face: a broad, diffuse sheen that slides as the phone tilts.
struct BodyGloss: View {
    let dark: Bool
    @ObservedObject var tilt = Tilt.shared

    var body: some View {
        let shift = CGFloat(tilt.x) * 0.35
        let lift = CGFloat(tilt.y) * 0.3
        ZStack {
            LinearGradient(
                stops: [
                    .init(color: .white.opacity(0), location: 0),
                    .init(color: .white.opacity(dark ? 0.09 : 0.32), location: 0.42),
                    .init(color: .white.opacity(0), location: 0.62),
                ],
                startPoint: UnitPoint(x: -0.2 + shift, y: -0.1 + lift),
                endPoint: UnitPoint(x: 1.2 + shift, y: 0.9 + lift))
            .blendMode(dark ? .screen : .softLight)
            // Light from overhead on the top of the case.
            LinearGradient(colors: [.white.opacity(dark ? 0.08 : 0.25), .clear], startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.3))
        }
        .allowsHitTesting(false)
    }
}

/// The rounded edges of the case where the front curves away toward the chrome back.
struct CaseEdges: View {
    let dark: Bool

    var body: some View {
        ZStack {
            // Falloff as the face curves away.
            RoundedRectangle(cornerRadius: 48, style: .continuous)
                .strokeBorder(Color.black.opacity(dark ? 0.7 : 0.12), lineWidth: 14)
                .blur(radius: 10)
            // The chrome back peeking round the edge.
            RoundedRectangle(cornerRadius: 48, style: .continuous)
                .strokeBorder(
                    AngularGradient(colors: [
                        Color(hex: 0xF5F6F8), Color(hex: 0x9EA3AA), Color(hex: 0xFFFFFF),
                        Color(hex: 0x7D828A), Color(hex: 0xE8EAED), Color(hex: 0xA5AAB1), Color(hex: 0xF5F6F8),
                    ], center: .center),
                    lineWidth: 2.5)
                .opacity(0.9)
        }
        .allowsHitTesting(false)
    }
}

/// The LCD sits under the clear front, a little below it: shadowed along the top edge,
/// with a sharper reflection on the plastic over it that also follows the tilt.
struct ScreenGlass: View {
    let dimmed: Bool
    @ObservedObject var tilt = Tilt.shared

    var body: some View {
        let shift = CGFloat(tilt.x) * 0.5
        let lift = CGFloat(tilt.y) * 0.4
        ZStack {
            // Backlight off: the LCD goes grey and dark but stays readable, like the real one.
            Color.black.opacity(dimmed ? 0.58 : 0)
            // Inner shadow from the bezel above.
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.black.opacity(0.55), lineWidth: 8)
                .blur(radius: 5)
                .offset(y: 3)
                .mask(RoundedRectangle(cornerRadius: 8))
            // Reflection on the plastic.
            LinearGradient(
                stops: [
                    .init(color: .white.opacity(0.0), location: 0),
                    .init(color: .white.opacity(0.22), location: 0.3),
                    .init(color: .white.opacity(0.06), location: 0.31),
                    .init(color: .white.opacity(0.0), location: 0.55),
                ],
                startPoint: UnitPoint(x: 0 + shift, y: -0.2 + lift),
                endPoint: UnitPoint(x: 0.8 + shift, y: 1.0 + lift))
        }
        .animation(.easeInOut(duration: 0.5), value: dimmed)
        .allowsHitTesting(false)
    }
}

/// The fine speckle of bead-blasted, anodized aluminium.
struct AluminiumGrain: View {
    let dark: Bool

    private static let tile: UIImage = {
        let size = 128
        var generator = SystemRandomNumberGenerator()
        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { context in
            for y in 0..<size {
                for x in 0..<size {
                    let v = CGFloat.random(in: 0...1, using: &generator)
                    context.cgContext.setFillColor(UIColor(white: v, alpha: 1).cgColor)
                    context.cgContext.fill(CGRect(x: x, y: y, width: 1, height: 1))
                }
            }
        }
    }()

    var body: some View {
        Image(uiImage: Self.tile)
            .resizable(resizingMode: .tile)
            .blendMode(.overlay)
            .opacity(dark ? 0.06 : 0.09)
            .allowsHitTesting(false)
    }
}
