import UIKit
import WebKit

/// Makes the "proof of origin" tokens YouTube now wants on stream links, the way a browser
/// does: Google's BotGuard check runs in a hidden web view on a youtube.com page, YouTube
/// turns its answer into an integrity token, and the page uses that to mint a token for a
/// visitor or video ID. This follows the published protocol used by NewPipe and BgUtils.
@MainActor
final class PoToken: NSObject, WKNavigationDelegate {
    static let shared = PoToken()

    enum Failure: LocalizedError {
        case setup(String)
        var errorDescription: String? {
            if case .setup(let step) = self { return "couldn't make a token (\(step))" }
            return nil
        }
    }

    private static let requestKey = "O43z0dpjhgX20SCx4KAo"
    private static let apiKey = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    private var webView: WKWebView?
    private var expires: Date?
    private var tokens: [String: String] = [:]
    private var setUpTask: Task<Void, Error>?
    private var pageLoaded: CheckedContinuation<Void, Error>?

    /// A token bound to `identifier` (a visitor ID for stream links, a video ID for the player).
    func token(for identifier: String) async throws -> String {
        if let expires, expires.timeIntervalSinceNow < 120 { reset() }
        if let token = tokens[identifier] { return token }
        try await ready()
        guard let webView else { throw Failure.setup("no web view") }
        let result = try await webView.callAsyncJavaScript(
            "return await tempoMint(identifier)", arguments: ["identifier": identifier], contentWorld: .page)
        guard let token = result as? String, !token.isEmpty else { throw Failure.setup("mint") }
        tokens[identifier] = token
        return token
    }

    private func reset() {
        webView?.removeFromSuperview()
        webView = nil
        expires = nil
        tokens = [:]
        setUpTask = nil
    }

    private func ready() async throws {
        if expires != nil, webView != nil { return }
        if let setUpTask { return try await setUpTask.value }
        let task = Task { try await setUp() }
        setUpTask = task
        do {
            try await task.value
        } catch {
            reset()
            throw error
        }
    }

    private func setUp() async throws {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 2, height: 2), configuration: configuration)
        webView.navigationDelegate = self
        webView.alpha = 0.01
        webView.isUserInteractionEnabled = false
        // Kept in the window so the page isn't suspended while BotGuard runs.
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        window?.addSubview(webView)
        self.webView = webView

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pageLoaded = continuation
            webView.loadHTMLString("<!DOCTYPE html><html><head></head><body></body></html>",
                                   baseURL: URL(string: "https://www.youtube.com/"))
        }
        _ = try await webView.evaluateJavaScript(Self.script)
        let userAgent = (try? await webView.evaluateJavaScript("navigator.userAgent")) as? String

        let challenge = try await jnn("Create", body: [Self.requestKey], userAgent: userAgent)
        let response = try await webView.callAsyncJavaScript(
            "return await tempoRunBotGuard(challenge)", arguments: ["challenge": challenge], contentWorld: .page)
        guard let botguard = response as? String, !botguard.isEmpty else { throw Failure.setup("BotGuard") }

        let integrity = try await jnn("GenerateIT", body: [Self.requestKey, botguard], userAgent: userAgent)
        guard let parts = integrity as? [Any], let integrityToken = parts.first as? String else {
            throw Failure.setup("integrity token")
        }
        let lifetime = (parts.count > 1 ? parts[1] as? Double : nil) ?? 3600
        _ = try await webView.callAsyncJavaScript(
            "return await tempoSetIntegrityToken(token)", arguments: ["token": integrityToken], contentWorld: .page)
        expires = Date().addingTimeInterval(lifetime)
    }

    private func jnn(_ method: String, body: [Any], userAgent: String?) async throws -> Any {
        var request = URLRequest(url: URL(string: "https://www.youtube.com/api/jnn/v1/\(method)")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json+protobuf", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("grpc-web-javascript/0.1", forHTTPHeaderField: "x-user-agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("https://www.youtube.com/", forHTTPHeaderField: "Referer")
        if let userAgent { request.setValue(userAgent, forHTTPHeaderField: "User-Agent") }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw Failure.setup("\(method) HTTP \(http.statusCode)")
        }
        return try JSONSerialization.jsonObject(with: data)
    }

    // MARK: WKNavigationDelegate

    nonisolated func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        MainActor.assumeIsolated {
            pageLoaded?.resume()
            pageLoaded = nil
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        MainActor.assumeIsolated {
            pageLoaded?.resume(throwing: error)
            pageLoaded = nil
        }
    }

    nonisolated func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        MainActor.assumeIsolated {
            pageLoaded?.resume(throwing: error)
            pageLoaded = nil
        }
    }

    // MARK: Page script

    /// Runs the BotGuard challenge and mints tokens. The challenge comes from YouTube's
    /// Create call: either plain, or base64 with every byte shifted by 97.
    private static let script = #"""
    window.tempo = { signal: [] };
    function tempoB64ToU8(s) {
        s = s.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '=');
        const bin = atob(s);
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }
    function tempoU8ToB64Url(u8) {
        let s = '';
        for (let i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
        return btoa(s).replace(/\+/g, '-').replace(/\//g, '_');
    }
    window.tempoRunBotGuard = async function (raw) {
        let data;
        if (raw.length > 1 && typeof raw[1] === 'string') {
            const bytes = tempoB64ToU8(raw[1]).map(b => b + 97);
            data = JSON.parse(new TextDecoder().decode(bytes));
        } else {
            data = raw[0];
        }
        const wrappedScript = data[1], wrappedUrl = data[2], program = data[4], globalName = data[5];
        const script = Array.isArray(wrappedScript) ? wrappedScript.find(v => v && typeof v === 'string') : null;
        const url = Array.isArray(wrappedUrl) ? wrappedUrl.find(v => v && typeof v === 'string') : null;
        if (script) {
            new Function(script)();
        } else if (url) {
            await new Promise((resolve, reject) => {
                const el = document.createElement('script');
                el.src = url.startsWith('//') ? 'https:' + url : url;
                el.onload = resolve;
                el.onerror = () => reject('interpreter script');
                document.head.appendChild(el);
            });
        } else {
            throw 'no interpreter';
        }
        const vm = window[globalName];
        if (!vm || typeof vm.a !== 'function') throw 'no BotGuard VM';
        let snapshot = null;
        await vm.a(program, (asyncSnapshot) => { snapshot = asyncSnapshot; }, true, undefined, () => {}, [[], []]);
        for (let i = 0; i < 50 && !snapshot; i++) await new Promise(r => setTimeout(r, 100));
        if (!snapshot) throw 'no snapshot function';
        window.tempo.signal = [];
        return await new Promise((resolve) => {
            snapshot((response) => resolve(response), [undefined, undefined, window.tempo.signal, undefined]);
        });
    };
    window.tempoSetIntegrityToken = async function (token) {
        const getMinter = window.tempo.signal[0];
        if (typeof getMinter !== 'function') throw 'no minter';
        window.tempo.minter = await getMinter(tempoB64ToU8(token));
        return true;
    };
    window.tempoMint = async function (identifier) {
        const out = await window.tempo.minter(new TextEncoder().encode(identifier));
        return tempoU8ToB64Url(out);
    };
    true;
    """#
}
