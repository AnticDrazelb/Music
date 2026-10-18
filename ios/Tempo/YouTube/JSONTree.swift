import Foundation

/// Small helpers for walking InnerTube's deeply nested JSON without a schema.
enum JSONTree {
    typealias Object = [String: Any]

    static func parse(_ data: Data) throws -> Object {
        guard let object = try JSONSerialization.jsonObject(with: data) as? Object else {
            throw YouTubeError.unexpectedResponse
        }
        return object
    }

    /// The value at a key path, e.g. `path(item, "thumbnail", "thumbnails")`.
    static func path(_ value: Any?, _ keys: String...) -> Any? {
        keys.reduce(value) { ($0 as? Object)?[$1] }
    }

    static func string(_ value: Any?, _ keys: String...) -> String? {
        keys.reduce(value) { ($0 as? Object)?[$1] } as? String
    }

    static func array(_ value: Any?) -> [Any] { value as? [Any] ?? [] }

    /// Every object stored under `key` anywhere in the tree.
    static func findAll(_ value: Any?, key: String) -> [Object] {
        var found: [Object] = []
        func walk(_ v: Any?) {
            if let object = v as? Object {
                for (k, child) in object {
                    if k == key, let match = child as? Object { found.append(match) }
                    walk(child)
                }
            } else if let list = v as? [Any] {
                list.forEach(walk)
            }
        }
        walk(value)
        return found
    }

    /// The first string stored under `key` anywhere in the tree.
    static func findString(_ value: Any?, key: String) -> String? {
        if let object = value as? Object {
            if let s = object[key] as? String { return s }
            for child in object.values {
                if let s = findString(child, key: key) { return s }
            }
        } else if let list = value as? [Any] {
            for child in list {
                if let s = findString(child, key: key) { return s }
            }
        }
        return nil
    }

    /// Text from InnerTube's `{runs: [{text}]}` or `{simpleText}`.
    static func text(_ value: Any?) -> String {
        let runs = array(path(value, "runs")).compactMap { string($0, "text") }.joined()
        return runs.isEmpty ? (string(value, "simpleText") ?? "") : runs
    }
}
