import SwiftUI

/// The click wheel: slide a finger around the ring to scroll, press MENU, ⏮, ⏭, ⏯ on the ring,
/// or the center button. Holding MENU goes back to the main menu; holding the center button
/// opens extra options.
///
/// Like the real one, the ring is a single plate that rocks toward the edge you press, sitting
/// in a shallow well in the case, and the center button is a separate raised key. Buttons click
/// as they go down and again as they come up.
struct ClickWheel: View {
    let diameter: CGFloat
    let style: PodStyle
    let onEvent: (WheelEvent) -> Void

    private enum Zone { case menu, next, playPause, previous }

    /// Ticks per full turn of the wheel.
    private let ticksPerTurn = 24.0
    /// How far (radians) a finger can drift before a press becomes a scroll.
    private let pressSlop = 0.18
    private let holdDelay: UInt64 = 600_000_000

    @State private var pressed: Zone?
    @State private var lastAngle: Double?
    @State private var accumulated = 0.0
    @State private var travelled = 0.0
    @State private var holdTask: Task<Void, Never>?
    @State private var holdFired = false
    @State private var centerPressed = false
    /// Where the finger is on the ring, for the faint sheen that follows it.
    @State private var touch: CGPoint?

    private var centerDiameter: CGFloat { diameter * 0.32 }
    private var isDark: Bool { style.dark }

    var body: some View {
        ZStack {
            well
            ZStack {
                ring
                labels
            }
            .rotation3DEffect(tilt.angle, axis: tilt.axis, perspective: 0.6)
            .animation(.spring(response: 0.12, dampingFraction: 0.55), value: pressed)
            centerButton
        }
        .frame(width: diameter, height: diameter)
    }

    // MARK: Drawing

    /// The shallow dish in the case the wheel sits in: shadowed at the top, catching light at the bottom.
    private var well: some View {
        Circle()
            .fill(LinearGradient(colors: [.black.opacity(isDark ? 0.55 : 0.16), .white.opacity(isDark ? 0.08 : 0.9)],
                                 startPoint: .top, endPoint: .bottom))
            .frame(width: diameter + 6, height: diameter + 6)
            .blur(radius: 1.5)
    }

    private var ring: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(colors: style.wheel, startPoint: .top, endPoint: .bottom))
            // A soft highlight from above, darker toward the rim.
            Circle()
                .fill(RadialGradient(colors: [.white.opacity(isDark ? 0.06 : 0.35), .clear],
                                     center: UnitPoint(x: 0.5, y: 0.25), startRadius: 0, endRadius: diameter * 0.6))
            Circle()
                .strokeBorder(LinearGradient(colors: [.black.opacity(isDark ? 0.5 : 0.18), .white.opacity(isDark ? 0.12 : 0.7)],
                                             startPoint: .top, endPoint: .bottom), lineWidth: 1.5)
            if let pressed {
                // The pressed edge goes down into shadow.
                Circle()
                    .trim(from: 0, to: 0.25)
                    .stroke(style.wheelPressed, lineWidth: (diameter - centerDiameter) / 2)
                    .frame(width: (diameter + centerDiameter) / 2, height: (diameter + centerDiameter) / 2)
                    .rotationEffect(.degrees(rotation(of: pressed)))
                    .opacity(0.55)
                    .blur(radius: 4)
            }
            if let touch {
                // Skin on matte plastic: a faint warm patch under the finger.
                Circle()
                    .fill(RadialGradient(colors: [.white.opacity(isDark ? 0.07 : 0.28), .clear],
                                         center: .center, startRadius: 0, endRadius: diameter * 0.12))
                    .frame(width: diameter * 0.24, height: diameter * 0.24)
                    .position(touch)
                    .allowsHitTesting(false)
            }
            // The gap between the wheel and the center button.
            Circle()
                .fill(RadialGradient(colors: [.black.opacity(isDark ? 0.6 : 0.22), .clear],
                                     center: .center, startRadius: centerDiameter * 0.5, endRadius: centerDiameter * 0.58))
                .frame(width: centerDiameter * 1.2, height: centerDiameter * 1.2)
        }
        .clipShape(Circle())
        .shadow(color: .black.opacity(isDark ? 0.5 : 0.14), radius: 1, y: 0.5)
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
        // Printed into the plastic: a hairline highlight below each mark.
        let emboss = Color.white.opacity(isDark ? 0.08 : 0.8)
        return ZStack {
            Group {
                Text("MENU")
                    .font(.system(size: size * 0.82, weight: .bold, design: .rounded))
                    .kerning(0.5)
                    .offset(y: -inset)
                Image(systemName: "forward.end.alt.fill").font(.system(size: size * 0.9)).offset(x: inset)
                Image(systemName: "backward.end.alt.fill").font(.system(size: size * 0.9)).offset(x: -inset)
                Image(systemName: "playpause.fill").font(.system(size: size * 0.9)).offset(y: inset)
            }
            .foregroundStyle(style.wheelLabel)
            .shadow(color: emboss, radius: 0, y: 0.8)
        }
        .allowsHitTesting(false)
    }

    private var centerButton: some View {
        let d = centerDiameter
        return ZStack {
            Circle()
                .fill(LinearGradient(colors: centerPressed ? style.center.reversed() : style.center,
                                     startPoint: .top, endPoint: .bottom))
            // A soft sheen on the metal.
            Circle()
                .fill(RadialGradient(colors: [.white.opacity(centerPressed ? 0 : (isDark ? 0.08 : 0.4)), .clear],
                                     center: UnitPoint(x: 0.5, y: 0.2), startRadius: 0, endRadius: d * 0.55))
            // Bead-blasted aluminium, like the face.
            AluminiumGrain(dark: isDark).clipShape(Circle())
            Circle()
                .strokeBorder(LinearGradient(colors: [.white.opacity(isDark ? 0.15 : 0.95), style.centerEdge],
                                             startPoint: .top, endPoint: .bottom), lineWidth: 1)
        }
        .frame(width: d, height: d)
        .shadow(color: .black.opacity(centerPressed ? 0.12 : (isDark ? 0.6 : 0.22)),
                radius: centerPressed ? 0.5 : 2.5, y: centerPressed ? 0.3 : 1.8)
        .scaleEffect(centerPressed ? 0.975 : 1)
        .animation(.spring(response: 0.1, dampingFraction: 0.6), value: centerPressed)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !centerPressed else { return }
                    centerPressed = true
                    holdFired = false
                    Clicker.shared.buttonDown()
                    startHold { onEvent(.selectHold) }
                }
                .onEnded { _ in
                    centerPressed = false
                    holdTask?.cancel()
                    Clicker.shared.buttonUp()
                    if !holdFired { onEvent(.select) }
                }
        )
        .accessibilityLabel("Select")
        .accessibilityAddTraits(.isButton)
    }

    /// The plate rocks a few degrees toward the pressed edge.
    private var tilt: (angle: Angle, axis: (x: CGFloat, y: CGFloat, z: CGFloat)) {
        switch pressed {
        case .menu?: return (.degrees(5), (1, 0, 0))
        case .playPause?: return (.degrees(-5), (1, 0, 0))
        case .next?: return (.degrees(5), (0, 1, 0))
        case .previous?: return (.degrees(-5), (0, 1, 0))
        case nil: return (.zero, (1, 0, 0))
        }
    }

    // MARK: Gestures

    private func angle(of point: CGPoint) -> Double {
        atan2(Double(point.y - diameter / 2), Double(point.x - diameter / 2))
    }

    private func zone(at point: CGPoint) -> Zone {
        switch angle(of: point) {
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
        touch = value.location
        let current = angle(of: value.location)
        guard let last = lastAngle else {
            // Finger down: a button press, unless it starts moving around the ring.
            lastAngle = current
            accumulated = 0
            travelled = 0
            holdFired = false
            let zone = zone(at: value.startLocation)
            pressed = zone
            Clicker.shared.buttonDown()
            if zone == .menu { startHold { onEvent(.menuHold) } }
            return
        }
        var delta = current - last
        if delta > .pi { delta -= 2 * .pi }
        if delta < -.pi { delta += 2 * .pi }
        lastAngle = current
        accumulated += delta
        travelled += abs(delta)
        if travelled > pressSlop, pressed != nil {
            // It's a scroll after all: let the plate settle without a press.
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
        if travelled <= pressSlop, let zone = pressed {
            Clicker.shared.buttonUp()
            if !holdFired {
                switch zone {
                case .menu: onEvent(.menu)
                case .next: onEvent(.next)
                case .previous: onEvent(.previous)
                case .playPause: onEvent(.playPause)
                }
            }
        }
        pressed = nil
        lastAngle = nil
        withAnimation(.easeOut(duration: 0.25)) { touch = nil }
    }

    private func startHold(_ action: @escaping () -> Void) {
        holdTask?.cancel()
        holdTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: holdDelay)
            guard !Task.isCancelled else { return }
            holdFired = true
            Clicker.shared.hold()
            action()
        }
    }
}
