import AVFoundation
import SwiftUI

@main
struct TempoApp: App {
    @StateObject private var library = LibraryStore.shared
    @StateObject private var downloads = DownloadManager.shared
    @StateObject private var player = AudioPlayer.shared
    @StateObject private var prefs = Preferences.shared

    init() {
        // Wheel clicks mix with other apps' audio until Tempo plays something itself.
        try? AVAudioSession.sharedInstance().setCategory(.ambient)
    }

    var body: some Scene {
        WindowGroup {
            IPodView()
                .environmentObject(library)
                .environmentObject(downloads)
                .environmentObject(player)
                .environmentObject(prefs)
                .preferredColorScheme(.light)
        }
    }
}
