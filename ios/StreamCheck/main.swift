import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// Downloads real songs with the app's own stream code, from an ordinary public address, and
// reports what each YouTube client did. Run by the "iOS stream check" workflow.

let songs = [
    "green day longview", "lady gaga bruno mars die with a smile", "queen bohemian rhapsody",
    "taylor swift shake it off", "adele hello", "daft punk one more time",
]

func download(_ stream: ResolvedStream) async throws -> Int64 {
    var offset: Int64 = 0
    var total = stream.contentLength
    var number = 1
    while total.map({ offset < $0 }) ?? true {
        let request = stream.request(bytes: offset, through: offset + 2 * 1024 * 1024 - 1, number: number)
        number += 1
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as! HTTPURLResponse
        guard (200..<300).contains(http.statusCode) else {
            throw YouTubeError.cutOff(http.statusCode, Int(Double(offset) / Double(total ?? max(offset, 1)) * 100))
        }
        total = ResolvedStream.totalLength(http) ?? total
        offset += Int64(data.count)
        if http.statusCode == 200 || data.isEmpty { break }
    }
    return offset
}

func check() async -> Int {
    var worked = 0
    for query in songs {
        guard let track = try? await YouTube.shared.searchSongs(query).first else {
            print("✗ \(query): search failed")
            continue
        }
        print("• \(track.title) — \(track.artist) [\(track.id)] video=\(track.isVideo.map(String.init) ?? "?")")
        var done = false
        for client in YouTube.clients where !done {
            do {
                for stream in try await YouTube.shared.streams(for: track.id, client: client) where !stream.isHLS {
                    do {
                        let bytes = try await download(stream)
                        print("    ✓ \(client.name): downloaded \(bytes) bytes")
                        done = true
                        break
                    } catch {
                        print("    ✗ \(client.name): \(error.localizedDescription)")
                    }
                }
            } catch YouTubeError.allFailed(let notes) {
                notes.forEach { print("    ✗ \($0)") }
            } catch {
                print("    ✗ \(client.name): \(error.localizedDescription)")
            }
        }
        if done { worked += 1 }
    }
    print("\n\(worked) of \(songs.count) songs downloaded")
    return worked
}

let finished = DispatchSemaphore(value: 0)
var catalogPassed = true
Task {
    catalogPassed = await catalogCheck()
    print("")
    _ = await check()
    finished.signal()
}
finished.wait()
exit(catalogPassed ? 0 : 1)
