import SwiftUI

/// The click wheel: slide a finger around the ring to scroll, press MENU, ⏮, ⏭, ⏯ on the ring,
/// or the center button. Holding MENU goes back to the main menu; holding the center button
/// opens extra options.
struct ClickWheel: View {
    let diameter: CGFloat
    let style: PodStyle
    let onEvent: (WheelEvent) -> Void

    private enum Zone { case menu, next, playPause, previous }

    /// Ticks per full turn of the wheel.
    private let ticksPerTurn = 24.0
    private let holdDelay: UInt64 = 600_000_000

    @State private var pressed: Zone?
    @State private var lastAngle: Double?
    @State private var accumulated = 0.0
    @State private var travelled = 0.0
    @State private var holdTask: Task<Void, Never>?
    @State private var holdFired = false
    @State private var centerPressed = false

    private var centerDiameter: CGFloat { diameter * 0.39 }

    var body: some View {
        ZStack {
            ring
            labels
            centerButton
        }
        .frame(width: diameter, height: diameter)
    }

    private var ring: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(colors: style.wheel, startPoint: .top, endPoint: .bottom))
                .overlay(Circle().stroke(style.centerEdge.opacity(0.8), lineWidth: 1))
                .shadow(color: .black.opacity(0.18), radius: 1.5, y: 1)
            if let pressed {
                Circle()
                    .trim(from: 0, to: 0.25)
                    .stroke(style.wheelPressed, lineWidth: (diameter - centerDiameter) / 2)
                    .frame(width: (diameter + centerDiameter) / 2, height: (diameter + centerDiameter) / 2)
                    .rotationEffect(.degrees(rotation(of: pressed)))
                    .opacity(0.8)
            }
        }
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged(dragChanged)
                .onEnded(dragEnded)
        )
    }

    private var labels: some View {
        let inset = (diameter - centerDiameter) / 4 + centerDiameter / 2
        let size = diameter * 0.075
        return ZStack {
            Text("MENU")
                .font(.system(size: size * 0.82, weight: .bold, design: .rounded))
                .kerning(0.5)
                .offset(y: -inset)
            Image(systemName: "forward.end.alt.fill").font(.system(size: size * 0.9)).offset(x: inset)
            Image(systemName: "backward.end.alt.fill").font(.system(size: size * 0.9)).offset(x: -inset)
            Image(systemName: "playpause.fill").font(.system(size: size * 0.9)).offset(y: inset)
        }
        .foregroundStyle(style.wheelLabel)
        .allowsHitTesting(false)
    }

    private var centerButton: some View {
        Circle()
            .fill(LinearGradient(colors: centerPressed ? style.center.reversed() : style.center,
                                 startPoint: .top, endPoint: .bottom))
            .overlay(Circle().stroke(style.centerEdge, lineWidth: 1))
            .shadow(color: .black.opacity(centerPressed ? 0.05 : 0.18), radius: centerPressed ? 0.5 : 2, y: centerPressed ? 0 : 1)
            .frame(width: centerDiameter, height: centerDiameter)
            .scaleEffect(centerPressed ? 0.98 : 1)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        guard !centerPressed else { return }
                        centerPressed = true
                        holdFired = false
                        startHold { onEvent(.selectHold) }
                    }
                    .onEnded { _ in
                        centerPressed = false
                        holdTask?.cancel()
                        if !holdFired {
                            Clicker.shared.buttonPress()
                            onEvent(.select)
                        }
                    }
            )
            .accessibilityLabel("Select")
            .accessibilityAddTraits(.isButton)
    }

    // MARK: Gestures

    private func angle(of point: CGPoint) -> Double {
        atan2(Double(point.y - diameter / 2), Double(point.x - diameter / 2))
    }

    private func zone(at point: CGPoint) -> Zone {
        let a = angle(of: point)
        switch a {
        case (-3 * .pi / 4)..<(-.pi / 4): return .menu
        case (-.pi / 4)..<(.pi / 4): return .next
        case (.pi / 4)..<(3 * .pi / 4): return .playPause
        default: return .previous
        }
    }

    private func rotation(of zone: Zone) -> Double {
        switch zone {
        case .next: return -45
        case .playPause: return 45
        case .previous: return 135
        case .menu: return 225
        }
    }

    private func dragChanged(_ value: DragGesture.Value) {
        let current = angle(of: value.location)
        guard let last = lastAngle else {
            // Finger down: maybe a button press, until it moves around the ring.
            lastAngle = current
            accumulated = 0
            travelled = 0
            holdFired = false
            let zone = zone(at: value.startLocation)
            pressed = zone
            if zone == .menu {
                startHold { onEvent(.menuHold) }
            }
            return
        }
        var delta = current - last
        if delta > .pi { delta -= 2 * .pi }
        if delta < -.pi { delta += 2 * .pi }
        lastAngle = current
        accumulated += delta
        travelled += abs(delta)
        if travelled > 0.2 {
            pressed = nil
            holdTask?.cancel()
        }
        let step = 2 * .pi / ticksPerTurn
        while abs(accumulated) >= step {
            let direction = accumulated > 0 ? 1 : -1
            accumulated -= Double(direction) * step
            onEvent(.scroll(direction))
        }
    }

    private func dragEnded(_ value: DragGesture.Value) {
        holdTask?.cancel()
        if travelled <= 0.2, !holdFired, let zone = pressed {
            Clicker.shared.buttonPress()
            switch zone {
            case .menu: onEvent(.menu)
            case .next: onEvent(.next)
            case .previous: onEvent(.previous)
            case .playPause: onEvent(.playPause)
            }
        }
        pressed = nil
        lastAngle = nil
    }

    private func startHold(_ action: @escaping () -> Void) {
        holdTask?.cancel()
        holdTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: holdDelay)
            guard !Task.isCancelled else { return }
            holdFired = true
            Clicker.shared.buttonPress()
            action()
        }
    }
}
