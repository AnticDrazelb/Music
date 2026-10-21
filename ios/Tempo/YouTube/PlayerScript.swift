import Foundation
import JavaScriptCore

/// Unlocks stream URLs from clients that hand them out scrambled (the TV client). YouTube's
/// own player script knows how: its "n" function un-throttles a URL, and its signature
/// function decodes a ciphered one. yt-dlp's solver (bundled, public domain) finds those
/// functions in the player script; it runs here in JavaScriptCore.
actor PlayerScript {
    static let shared = PlayerScript()

    private struct Player {
        let id: String
        let signatureTimestamp: Int
        let context: JSContext
        /// The player script after the solver's first pass, so later calls skip the slow parse.
        var preprocessed: String?
        let source: String
    }

    private var player: Player?
    private var lastCheck: Date?

    enum Failure: LocalizedError {
        case noPlayer, solver(String)
        var errorDescription: String? {
            switch self {
            case .noPlayer: return "couldn't load YouTube's player"
            case .solver(let message): return "couldn't unlock the link (\(message))"
            }
        }
    }

    /// The signature timestamp the player request must send for the TV client.
    func signatureTimestamp() async throws -> Int {
        try await loadPlayer().signatureTimestamp
    }

    /// A playable URL for a format from a TV client response: deciphers `signatureCipher`
    /// and solves the `n` parameter.
    func url(for format: [String: Any]) async throws -> URL {
        var urlString: String
        var sig: (value: String, param: String)?
        if let direct = format["url"] as? String {
            urlString = direct
        } else if let cipher = format["signatureCipher"] as? String ?? format["cipher"] as? String {
            let items = URLComponents(string: "?" + cipher)?.queryItems ?? []
            func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
            guard let base = value("url"), let s = value("s") else { throw Failure.solver("no cipher") }
            urlString = base
            sig = (s, value("sp") ?? "signature")
        } else {
            throw Failure.solver("no url")
        }
        guard var components = URLComponents(string: urlString) else { throw Failure.solver("bad url") }
        let n = components.queryItems?.first { $0.name == "n" }?.value

        let solved = try await solve(n: n.map { [$0] } ?? [], sig: sig.map { [$0.value] } ?? [])
        var items = components.queryItems ?? []
        if let n {
            guard let newN = solved.n[n] else { throw Failure.solver("n") }
            items = items.map { $0.name == "n" ? URLQueryItem(name: "n", value: newN) : $0 }
        }
        if let sig {
            guard let decoded = solved.sig[sig.value] else { throw Failure.solver("signature") }
            items.append(URLQueryItem(name: sig.param, value: decoded))
        }
        components.queryItems = items
        // Signatures can hold "+" and "/", which must stay escaped in the query.
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let url = components.url else { throw Failure.solver("bad url") }
        return url
    }

    /// Runs the solver on challenges; returns challenge -> answer for each kind.
    func solve(n: [String], sig: [String]) async throws -> (n: [String: String], sig: [String: String]) {
        var player = try await loadPlayer()
        var requests: [[String: Any]] = []
        if !n.isEmpty { requests.append(["type": "n", "challenges": n]) }
        if !sig.isEmpty { requests.append(["type": "sig", "challenges": sig]) }
        guard !requests.isEmpty else { return ([:], [:]) }

        let context = player.context
        var input: [String: Any] = ["requests": requests]
        if let preprocessed = player.preprocessed {
            input["type"] = "preprocessed"
            input["preprocessed_player"] = preprocessed
        } else {
            input["type"] = "player"
            input["player"] = player.source
            input["output_preprocessed"] = true
        }
        context.setObject(input, forKeyedSubscript: "solverInput" as NSString)
        guard let output = context.evaluateScript("JSON.stringify(jsc(solverInput))")?.toString(),
              let data = output.data(using: .utf8),
              let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let message = context.exception?.toString() ?? "no result"
            context.exception = nil
            throw Failure.solver(message)
        }
        if player.preprocessed == nil, let preprocessed = result["preprocessed_player"] as? String {
            player.preprocessed = preprocessed
            self.player = player
        }
        var answers: (n: [String: String], sig: [String: String]) = ([:], [:])
        let responses = result["responses"] as? [[String: Any]] ?? []
        for (request, response) in zip(requests, responses) {
            guard response["type"] as? String == "result", let data = response["data"] as? [String: String] else {
                throw Failure.solver(response["error"] as? String ?? "failed")
            }
            if request["type"] as? String == "n" { answers.n = data } else { answers.sig = data }
        }
        return answers
    }

    // MARK: Loading

    /// The current player script, checked against YouTube at most every 30 minutes.
    private func loadPlayer() async throws -> Player {
        if let player, let lastCheck, Date().timeIntervalSince(lastCheck) < 1800 { return player }
        let iframe = try await text(URL(string: "https://www.youtube.com/iframe_api")!)
        guard let range = iframe.range(of: #"player\\?/([0-9a-fA-F]{8})\\?/"#, options: .regularExpression) else {
            if let player { return player }
            throw Failure.noPlayer
        }
        let id = String(iframe[range].filter(\.isHexDigit).suffix(8))
        lastCheck = Date()
        if let player, player.id == id { return player }

        let source = try await text(URL(string: "https://www.youtube.com/s/player/\(id)/player_ias.vflset/en_US/base.js")!)
        guard let stsRange = source.range(of: #"(signatureTimestamp|sts):\d+"#, options: .regularExpression),
              let sts = Int(source[stsRange].split(separator: ":").last ?? "") else { throw Failure.noPlayer }

        guard let context = JSContext() else { throw Failure.noPlayer }
        for name in ["lib.min", "core.min"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "js"),
                  let script = try? String(contentsOf: url, encoding: .utf8) else { throw Failure.solver("solver missing") }
            context.evaluateScript(script)
            // The library bundle exports one `lib` object; the solver expects its parts as globals.
            if name == "lib.min" { context.evaluateScript("var meriyah = lib.meriyah; var astring = lib.astring;") }
        }
        guard context.objectForKeyedSubscript("jsc")?.isUndefined == false else {
            throw Failure.solver(context.exception?.toString() ?? "solver didn't load")
        }
        let loaded = Player(id: id, signatureTimestamp: sts, context: context, preprocessed: nil, source: source)
        player = loaded
        return loaded
    }

    private func text(_ url: URL) async throws -> String {
        var request = URLRequest(url: url)
        request.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { throw Failure.noPlayer }
        return String(decoding: data, as: UTF8.self)
    }
}
