import AudioToolbox
import CoreHaptics
import UIKit

/// The iPod's clicker and the feel of its buttons. Scrolling makes a short, high tick. Pressing
/// a button is a firmer "click" as the dome switch goes down and a lighter "clack" as it comes
/// back up. Sounds are system sounds, so they play straight away, mix with music and respect the
/// silent switch. Haptics use Core Haptics where the phone has it.
@MainActor
final class Clicker {
    static let shared = Clicker()

    private var tickSound: SystemSoundID = 0
    private var downSound: SystemSoundID = 0
    private var upSound: SystemSoundID = 0

    private var engine: CHHapticEngine?
    private var tickPattern: CHHapticPattern?
    private var downPattern: CHHapticPattern?
    private var upPattern: CHHapticPattern?
    private var holdPattern: CHHapticPattern?
    private let fallbackTick = UISelectionFeedbackGenerator()
    private let fallbackPress = UIImpactFeedbackGenerator(style: .rigid)

    init() {
        tickSound = Self.makeSound("tick", Self.clickWAV(milliseconds: 4, pitch: 4_200, decay: 11, noise: 0.35))
        downSound = Self.makeSound("down", Self.clickWAV(milliseconds: 9, pitch: 2_400, decay: 6, noise: 0.5))
        upSound = Self.makeSound("up", Self.clickWAV(milliseconds: 5, pitch: 3_300, decay: 9, noise: 0.3, gain: 0.55))
        setUpHaptics()
    }

    /// One step of the wheel.
    func scrollTick() {
        if Preferences.shared.clicker { AudioServicesPlaySystemSound(tickSound) }
        if Preferences.shared.haptics { play(tickPattern) { fallbackTick.selectionChanged() } }
    }

    /// A button going down under the finger.
    func buttonDown() {
        if Preferences.shared.clicker { AudioServicesPlaySystemSound(downSound) }
        if Preferences.shared.haptics { play(downPattern) { fallbackPress.impactOccurred(intensity: 0.9) } }
    }

    /// The button springing back.
    func buttonUp() {
        if Preferences.shared.clicker { AudioServicesPlaySystemSound(upSound) }
        if Preferences.shared.haptics { play(upPattern) { fallbackPress.impactOccurred(intensity: 0.4) } }
    }

    /// A full press, for taps on the screen.
    func buttonPress() {
        buttonDown()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.07) { self.buttonUp() }
    }

    /// Held long enough: a deeper double pulse.
    func hold() {
        if Preferences.shared.haptics { play(holdPattern) { fallbackPress.impactOccurred(intensity: 1) } }
    }

    // MARK: Haptics

    private func setUpHaptics() {
        guard CHHapticEngine.capabilitiesForHardware().supportsHaptics,
              let engine = try? CHHapticEngine() else { return }
        engine.playsHapticsOnly = true
        engine.isAutoShutdownEnabled = true
        engine.resetHandler = { [weak engine] in try? engine?.start() }
        self.engine = engine
        try? engine.start()

        func transient(_ intensity: Float, _ sharpness: Float, at time: TimeInterval = 0) -> CHHapticEvent {
            CHHapticEvent(eventType: .hapticTransient, parameters: [
                CHHapticEventParameter(parameterID: .hapticIntensity, value: intensity),
                CHHapticEventParameter(parameterID: .hapticSharpness, value: sharpness),
            ], relativeTime: time)
        }
        tickPattern = try? CHHapticPattern(events: [transient(0.42, 0.95)], parameters: [])
        downPattern = try? CHHapticPattern(events: [transient(0.95, 0.55)], parameters: [])
        upPattern = try? CHHapticPattern(events: [transient(0.38, 0.85)], parameters: [])
        holdPattern = try? CHHapticPattern(events: [transient(0.8, 0.3), transient(1, 0.4, at: 0.09)], parameters: [])
    }

    private func play(_ pattern: CHHapticPattern?, fallback: () -> Void) {
        guard let engine, let pattern, let player = try? engine.makePlayer(with: pattern) else {
            fallback()
            return
        }
        do {
            try player.start(atTime: CHHapticTimeImmediate)
        } catch {
            // The engine stops when the app is in the background; start it again.
            try? engine.start()
            try? player.start(atTime: CHHapticTimeImmediate)
        }
    }

    // MARK: Sounds

    private static func makeSound(_ name: String, _ data: Data) -> SystemSoundID {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("clicker-\(name).wav")
        try? data.write(to: url, options: .atomic)
        var id: SystemSoundID = 0
        AudioServicesCreateSystemSoundID(url as CFURL, &id)
        return id
    }

    /// A short decaying click, like the iPod's piezo, as a 16-bit mono WAV.
    static func clickWAV(milliseconds: Int, pitch: Double, decay: Double, noise: Double, gain: Double = 1) -> Data {
        let rate = 44_100
        let count = rate * milliseconds / 1000
        var generator = SystemRandomNumberGenerator()
        let samples: [Int16] = (0..<count).map { i in
            let t = Double(i) / Double(count)
            let envelope = exp(-t * decay) * min(1, Double(i) / 12)
            let tone = sin(Double(i) * 2 * .pi * pitch / Double(rate)) * (1 - noise)
            let hiss = Double.random(in: -1...1, using: &generator) * noise
            return Int16(max(-1, min(1, (tone + hiss) * envelope * gain * 0.8)) * Double(Int16.max))
        }
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let byteCount = count * 2
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + byteCount))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(rate)); append(UInt32(rate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(byteCount))
        samples.forEach { append($0) }
        return data
    }
}
