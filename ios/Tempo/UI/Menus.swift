import SwiftUI

/// The iPod's menus, built the way the original's were laid out.
extension Navigator {
    func rootPage() -> Page {
        Page(title: "iPod", isRoot: true) { [unowned self] in
            var rows = [
                MenuItem(id: "music", title: "Music", action: { [unowned self] in push(musicPage()) }),
                MenuItem(id: "search", title: "Search", action: { [unowned self] in push(searchPage()) }),
                MenuItem(id: "downloads", title: "Downloads",
                         detail: downloads.activeCount > 0 ? "\(downloads.activeCount)" : nil,
                         action: { [unowned self] in push(downloadsPage()) }),
                MenuItem(id: "shuffle", title: "Shuffle Songs", chevron: false, action: { [unowned self] in shuffleSongs() }),
                MenuItem(id: "settings", title: "Settings", action: { [unowned self] in push(settingsPage()) }),
            ]
            if player.current != nil {
                rows.append(MenuItem(id: "nowplaying", title: "Now Playing", action: { [unowned self] in push(nowPlayingPage()) }))
            }
            return rows
        }
    }

    func musicPage() -> Page {
        Page(title: "Music") { [unowned self] in
            [
                MenuItem(id: "artists", title: "Artists", action: { [unowned self] in push(artistsPage()) }),
                MenuItem(id: "albums", title: "Albums", action: { [unowned self] in push(albumsPage(artist: nil)) }),
                MenuItem(id: "songs", title: "Songs", action: { [unowned self] in push(songsPage(title: "Songs") { [unowned self] in library.songs }) }),
                MenuItem(id: "search", title: "Search", action: { [unowned self] in push(searchPage()) }),
            ]
        }
    }

    private static let emptyLibrary = "No songs yet.\nUse Search to find music and download it."

    func artistsPage() -> Page {
        Page(title: "Artists", message: Self.emptyLibrary) { [unowned self] in
            library.artists.map { artist in
                MenuItem(id: artist, title: artist, action: { [unowned self] in push(artistPage(artist)) })
            }
        }
    }

    func artistPage(_ artist: String) -> Page {
        Page(title: artist) { [unowned self] in
            let all = MenuItem(id: "all", title: "All Songs", action: { [unowned self] in
                push(songsPage(title: artist) { [unowned self] in library.songs(by: artist) })
            })
            return [all] + library.albums(by: artist).map { album in
                MenuItem(id: album.title, title: album.title, action: { [unowned self] in push(albumPage(album)) })
            }
        }
    }

    func albumsPage(artist: String?) -> Page {
        Page(title: "Albums", message: Self.emptyLibrary) { [unowned self] in
            library.albums(by: artist).map { album in
                MenuItem(id: album.title + "\u{1}" + album.artist, title: album.title, action: { [unowned self] in push(albumPage(album)) })
            }
        }
    }

    func albumPage(_ album: LibraryStore.Album) -> Page {
        songsPage(title: album.title) { [unowned self] in library.songs(in: album) }
    }

    /// A list of library songs: select plays from there, hold opens the song's page.
    func songsPage(title: String, songs: @escaping () -> [Track]) -> Page {
        Page(title: title, message: Self.emptyLibrary) { [unowned self] in
            let list = songs()
            return list.enumerated().map { i, track in
                MenuItem(id: track.id, title: track.title, chevron: false,
                         action: { [unowned self] in
                             player.play(list, startAt: i)
                             push(nowPlayingPage())
                         },
                         holdAction: { [unowned self] in push(songPage(track, context: list)) })
            }
        }
    }

    func shuffleSongs() {
        let songs = library.songs
        guard !songs.isEmpty else {
            push(Page(title: "Shuffle Songs", message: Self.emptyLibrary))
            return
        }
        player.setShuffle(true)
        player.play(songs, startAt: Int.random(in: songs.indices))
        push(nowPlayingPage())
    }

    func nowPlayingPage() -> Page {
        Page(title: "Now Playing", kind: .nowPlaying)
    }

    func searchPage() -> Page {
        Page(title: "Search", kind: .search)
    }

    /// A song's own page: play it, download it into the library, or delete it.
    func songPage(_ track: Track, context: [Track]) -> Page {
        Page(title: "Song", track: track) { [unowned self] in
            var rows: [MenuItem] = [
                MenuItem(id: "play", title: "Play", chevron: false, action: { [unowned self] in
                    let list = context.contains(track) ? context : [track]
                    player.play(list, startAt: list.firstIndex(of: track) ?? 0)
                    push(nowPlayingPage())
                }),
                MenuItem(id: "next", title: "Play Next", chevron: false, action: { [unowned self] in
                    player.playNext(track)
                    Clicker.shared.buttonPress()
                }),
            ]
            if library.contains(track.id) {
                rows.append(MenuItem(id: "inlib", title: "In Your Library", detail: "✓", chevron: false))
                rows.append(MenuItem(id: "delete", title: "Delete from Library", action: { [unowned self] in
                    push(confirmPage(title: "Delete Song", confirm: "Delete") { [unowned self] in
                        player.forget(track)
                        library.remove(track)
                        pop()
                    })
                }))
            } else {
                switch downloads.state(of: track.id) {
                case nil:
                    rows.insert(MenuItem(id: "download", title: "Download", chevron: false,
                                         action: { [unowned self] in downloads.download(track) }), at: 1)
                case .queued?:
                    rows.insert(MenuItem(id: "download", title: "Waiting… (Cancel)", chevron: false,
                                         action: { [unowned self] in downloads.cancel(track.id) }), at: 1)
                case .downloading(let f)?:
                    rows.insert(MenuItem(id: "download", title: "Downloading (Cancel)", detail: "\(Int(f * 100))%", chevron: false,
                                         action: { [unowned self] in downloads.cancel(track.id) }), at: 1)
                case .failed(let reason)?:
                    rows.insert(MenuItem(id: "download", title: "Retry Download", detail: "!", chevron: false,
                                         action: { [unowned self] in downloads.download(track) }), at: 1)
                    rows.append(MenuItem(id: "why", title: reason, chevron: false))
                }
            }
            return rows
        }
    }

    func downloadsPage() -> Page {
        Page(title: "Downloads", message: "Nothing downloading.\nFind songs in Search, then choose Download.") { [unowned self] in
            var rows = downloads.queue.map { track in
                MenuItem(id: track.id, title: track.title, detail: status(of: track),
                         action: { [unowned self] in push(songPage(track, context: [track])) })
            }
            if downloads.queue.contains(where: { if case .failed = downloads.state(of: $0.id) { return true } else { return false } }) {
                rows.append(MenuItem(id: "clear", title: "Clear Failed", chevron: false, action: { [unowned self] in downloads.clearFailed() }))
            }
            return rows
        }
    }

    func settingsPage() -> Page {
        Page(title: "Settings") { [unowned self] in
            [
                MenuItem(id: "about", title: "About", action: { [unowned self] in push(aboutPage()) }),
                MenuItem(id: "shuffle", title: "Shuffle", detail: player.shuffle ? "Songs" : "Off", chevron: false,
                         action: { [unowned self] in player.setShuffle(!player.shuffle) }),
                MenuItem(id: "repeat", title: "Repeat", detail: player.repeatMode.rawValue, chevron: false, action: { [unowned self] in
                    let all = AudioPlayer.RepeatMode.allCases
                    player.repeatMode = all[(all.firstIndex(of: player.repeatMode)! + 1) % all.count]
                }),
                MenuItem(id: "body", title: "Body", detail: prefs.body.rawValue, chevron: false, action: { [unowned self] in
                    withAnimation(.easeInOut(duration: 0.4)) { prefs.body = prefs.body == .silver ? .black : .silver }
                }),
                MenuItem(id: "backlight", title: "Backlight", detail: prefs.backlight == 0 ? "Always On" : "\(prefs.backlight) Seconds",
                         chevron: false, action: { [unowned self] in
                    let all = Preferences.backlightChoices
                    prefs.backlight = all[((all.firstIndex(of: prefs.backlight) ?? 0) + 1) % all.count]
                }),
                MenuItem(id: "clicker", title: "Clicker", detail: prefs.clicker ? "On" : "Off", chevron: false,
                         action: { [unowned self] in prefs.clicker.toggle() }),
                MenuItem(id: "haptics", title: "Haptics", detail: prefs.haptics ? "On" : "Off", chevron: false,
                         action: { [unowned self] in prefs.haptics.toggle() }),
                MenuItem(id: "reset", title: "Delete All Songs", action: { [unowned self] in
                    push(confirmPage(title: "Delete All", confirm: "Delete All Songs") { [unowned self] in
                        player.stop()
                        library.removeAll()
                        pop()
                    })
                }),
            ]
        }
    }

    func aboutPage() -> Page {
        Page(title: "About") { [unowned self] in
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
            let size = ByteCountFormatter.string(fromByteCount: library.bytesUsed, countStyle: .file)
            return [
                MenuItem(id: "name", title: "Tempo", detail: "iPod", chevron: false),
                MenuItem(id: "songs", title: "Songs", detail: "\(library.tracks.count)", chevron: false),
                MenuItem(id: "artists", title: "Artists", detail: "\(library.artists.count)", chevron: false),
                MenuItem(id: "used", title: "Used", detail: size, chevron: false),
                MenuItem(id: "version", title: "Version", detail: version, chevron: false),
            ]
        }
    }

    func confirmPage(title: String, confirm: String, action: @escaping () -> Void) -> Page {
        Page(title: title) { [unowned self] in
            [
                MenuItem(id: "cancel", title: "Cancel", chevron: false, action: { [unowned self] in pop() }),
                MenuItem(id: "confirm", title: confirm, chevron: false, action: action),
            ]
        }
    }
}
