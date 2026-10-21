import Foundation

// Stand-ins for the parts of the app that need Apple's frameworks (JavaScriptCore, WebKit),
// so the stream code can be checked on Linux. Clients that need them report it and are skipped.
struct NeedsApplePlatform: LocalizedError {
    var errorDescription: String? { "needs iOS (player script or token)" }
}

actor PlayerScript {
    static let shared = PlayerScript()
    func signatureTimestamp() async throws -> Int { throw NeedsApplePlatform() }
    func url(for format: [String: Any]) async throws -> URL { throw NeedsApplePlatform() }
}

@MainActor
final class PoToken {
    static let shared = PoToken()
    func token(for identifier: String) async throws -> String { throw NeedsApplePlatform() }
}
