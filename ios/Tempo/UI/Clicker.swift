import AVFoundation
import UIKit

/// The click wheel's tick: a tiny synthesized click through the speaker, plus a light haptic.
@MainActor
final class Clicker {
    static let shared = Clicker()

    private var players: [AVAudioPlayer] = []
    private var next = 0
    private let tick = UISelectionFeedbackGenerator()
    private let press = UIImpactFeedbackGenerator(style: .medium)

    init() {
        let data = Self.clickWAV()
        players = (0..<4).compactMap { _ in
            let player = try? AVAudioPlayer(data: data)
            player?.volume = 0.35
            player?.prepareToPlay()
            return player
        }
    }

    func scrollTick() {
        if Preferences.shared.clicker { playClick() }
        if Preferences.shared.haptics { tick.selectionChanged() }
    }

    func buttonPress() {
        if Preferences.shared.clicker { playClick() }
        if Preferences.shared.haptics { press.impactOccurred(intensity: 0.7) }
    }

    private func playClick() {
        guard !players.isEmpty else { return }
        let player = players[next]
        next = (next + 1) % players.count
        player.currentTime = 0
        player.play()
    }

    /// A 6 ms decaying click as a 16-bit mono WAV.
    private static func clickWAV() -> Data {
        let rate = 44_100
        let count = rate * 6 / 1000
        var samples = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let t = Double(i) / Double(count)
            let envelope = exp(-t * 7)
            let tone = sin(Double(i) * 2 * .pi * 3_200 / Double(rate)) * 0.6 + Double.random(in: -0.4...0.4)
            samples[i] = Int16(max(-1, min(1, tone * envelope)) * Double(Int16.max))
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
