import Foundation

// Loads every kind of YouTube Music page the app shows (search with each filter and its next
// page, album, artist, playlist, home, moods & genres, new releases, radio, charts) and prints
// what was read from each, so the parsers are checked against the live service.

private func describe(_ item: CatalogItem) -> String {
    switch item {
    case .song(let t): return "song \(t.title) — \(t.artist)\(t.album.map { " [\($0)]" } ?? "")\(t.albumId != nil ? " +albumId" : "")\(t.artistId != nil ? " +artistId" : "")"
    case .album(let a): return "album \(a.title) (\(a.subtitle ?? "")) \(a.browseId)"
    case .artist(let a): return "artist \(a.name) \(a.channelId)"
    case .playlist(let p): return "playlist \(p.title) (\(p.subtitle ?? "")) \(p.playlistId)"
    case .page(let title, let target): return "page \(title) \(target.browseId)"
    }
}

private func line(_ ok: Bool, _ text: String) {
    print("\(ok ? "✓" : "✗") \(text)")
}

func catalogCheck() async -> Bool {
    var failures = 0
    func check(_ ok: Bool, _ text: String) { line(ok, text); if !ok { failures += 1 } }

    var firstAlbum: AlbumRef?, firstArtist: ArtistRef?, firstPlaylist: PlaylistRef?
    for filter in SearchFilter.allCases {
        do {
            let page = try await YouTube.shared.search("green day", filter: filter)
            check(!page.items.isEmpty, "search \(filter.rawValue): \(page.items.count) items, more: \(page.continuation != nil)")
            page.items.prefix(3).forEach { print("    \(describe($0))") }
            for item in page.items {
                if case .album(let a) = item, firstAlbum == nil { firstAlbum = a }
                if case .artist(let a) = item, firstArtist == nil { firstArtist = a }
                if case .playlist(let p) = item, firstPlaylist == nil { firstPlaylist = p }
            }
            if let token = page.continuation {
                let next = try await YouTube.shared.search("green day", filter: filter, continuation: token)
                check(!next.items.isEmpty, "search \(filter.rawValue) page 2: \(next.items.count) items")
                next.items.prefix(2).forEach { print("    \(describe($0))") }
            }
        } catch {
            check(false, "search \(filter.rawValue): \(error.localizedDescription)")
        }
    }

    if let album = firstAlbum {
        do {
            let page = try await YouTube.shared.album(album.browseId)
            check(!page.tracks.isEmpty, "album \(page.album.title) by \(page.artist ?? "?"): \(page.tracks.count) tracks, art: \(page.album.artworkURL != nil)")
            page.tracks.prefix(3).forEach { print("    \(describe(.song($0))) \($0.duration ?? -1)s") }
        } catch { check(false, "album: \(error.localizedDescription)") }
    } else { check(false, "no album to open") }

    if let artist = firstArtist {
        do {
            let page = try await YouTube.shared.artist(artist.channelId)
            check(!page.topSongs.isEmpty, "artist \(page.name): \(page.topSongs.count) top songs, all songs: \(page.allSongsPlaylistId ?? "none"), \(page.shelves.count) rows")
            page.shelves.forEach { print("    row \($0.title): \($0.items.count) items, see all: \($0.more?.browseId ?? "none")") }
            if let more = page.shelves.first(where: { $0.more != nil })?.more {
                let all = try await YouTube.shared.shelves(more)
                check(!(all.first?.items.isEmpty ?? true), "see all: \(all.first?.items.count ?? 0) items")
            }
            if let id = page.allSongsPlaylistId {
                let songs = try await YouTube.shared.playlist(id)
                check(!songs.tracks.isEmpty, "all songs: \(songs.tracks.count), more: \(songs.continuation != nil)")
            }
        } catch { check(false, "artist: \(error.localizedDescription)") }
    } else { check(false, "no artist to open") }

    if let playlist = firstPlaylist {
        do {
            let page = try await YouTube.shared.playlist(playlist.playlistId)
            check(!page.tracks.isEmpty, "playlist \(page.title ?? "?"): \(page.tracks.count) tracks, more: \(page.continuation != nil)")
            if let token = page.continuation {
                let next = try await YouTube.shared.playlist(playlist.playlistId, continuation: token)
                check(!next.tracks.isEmpty, "playlist page 2: \(next.tracks.count) tracks")
            }
        } catch { check(false, "playlist: \(error.localizedDescription)") }
    } else { check(false, "no playlist to open") }

    do {
        let home = try await YouTube.shared.home()
        check(!home.isEmpty, "home: \(home.count) rows")
        home.prefix(8).forEach { print("    row \($0.title): \($0.items.count) — \($0.items.prefix(2).map(describe).joined(separator: "; "))") }
    } catch { check(false, "home: \(error.localizedDescription)") }

    do {
        let moods = try await YouTube.shared.shelves(BrowseTarget(browseId: "FEmusic_moods_and_genres", params: nil))
        check(!moods.isEmpty, "moods & genres: \(moods.map { "\($0.title) (\($0.items.count))" }.joined(separator: ", "))")
        if case .page(let title, let target)? = moods.last?.items.first {
            let page = try await YouTube.shared.shelves(target)
            check(!page.isEmpty, "category \(title): \(page.map { "\($0.title) (\($0.items.count))" }.joined(separator: ", "))")
            page.first?.items.prefix(2).forEach { print("    \(describe($0))") }
        }
    } catch { check(false, "moods: \(error.localizedDescription)") }

    do {
        let releases = try await YouTube.shared.shelves(BrowseTarget(browseId: "FEmusic_new_releases_albums", params: nil))
        check(!(releases.first?.items.isEmpty ?? true), "new releases: \(releases.first?.items.count ?? 0)")
        releases.first?.items.prefix(2).forEach { print("    \(describe($0))") }
    } catch { check(false, "new releases: \(error.localizedDescription)") }

    do {
        let radio = try await YouTube.shared.radio("vlOuYdPVlak")
        check(radio.count > 10, "radio: \(radio.count) songs")
        radio.prefix(3).forEach { print("    \(describe(.song($0)))") }
    } catch { check(false, "radio: \(error.localizedDescription)") }

    do {
        let chart = try await YouTube.shared.topSongs(country: "us")
        check(chart.entries.count >= 100, "top 100 (US, week of \(chart.week ?? "?")): \(chart.entries.count)")
        chart.entries.prefix(3).forEach { print("    #\($0.rank) \(describe(.song($0.track)))") }
        let artists = try await YouTube.shared.topArtists(country: "us")
        check(!artists.isEmpty, "top artists: \(artists.count), first \(artists.first?.name ?? "?")")
    } catch { check(false, "charts: \(error.localizedDescription)") }

    print("\n\(failures == 0 ? "All catalogue checks passed" : "\(failures) catalogue checks failed")")
    return failures == 0
}
