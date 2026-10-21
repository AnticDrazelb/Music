import SwiftUI

/// Menus for YouTube Music's catalogue: search by category, albums, artists, playlists, charts,
/// new releases, genres & moods, and Discover (the home feed and radio).
extension Navigator {

    // MARK: Building blocks

    /// A page whose rows come from the network: shows "Loading…", then the rows, or why not.
    @discardableResult
    func remotePage(title: String, header: Track? = nil, empty: String = "Nothing here.",
                    load: @escaping (Page) async throws -> () -> [MenuItem]) -> Page {
        let page = Page(title: title, track: header)
        page.loading = true
        page.loadingText = "Loading…"
        push(page)
        Task {
            do {
                page.rows = try await load(page)
                page.message = empty
            } catch {
                page.message = error.localizedDescription
            }
            page.loading = false
        }
        return page
    }

    /// A row for anything in the catalogue. Songs open their page (hold to download).
    func row(_ item: CatalogItem, context: [Track] = []) -> MenuItem {
        switch item {
        case .song(let track):
            return MenuItem(id: item.key, title: "\(track.title) — \(track.artist)", detail: status(of: track), chevron: false,
                            action: { [unowned self] in push(songPage(track, context: context.isEmpty ? [track] : context)) },
                            holdAction: { [unowned self] in downloads.download(track) })
        case .album(let album):
            return MenuItem(id: item.key, title: album.title, action: { [unowned self] in openAlbum(album) })
        case .artist(let artist):
            return MenuItem(id: item.key, title: artist.name, action: { [unowned self] in openArtist(artist.channelId, name: artist.name, artwork: artist.artworkURL) })
        case .playlist(let playlist):
            return MenuItem(id: item.key, title: playlist.title, action: { [unowned self] in openPlaylist(playlist) })
        case .page(let title, let target):
            return MenuItem(id: item.key, title: title, action: { [unowned self] in openShelves(title: title, target: target) })
        }
    }

    /// Rows for a list of songs played like an album: Shuffle and Download All on top, then
    /// the songs; selecting one plays the list from there, holding opens its page.
    func songListRows(_ songs: [Track], numbered: Bool = false, details: [String?]? = nil) -> [MenuItem] {
        guard !songs.isEmpty else { return [] }
        let missing = songs.filter { !library.contains($0.id) }
        var rows = [MenuItem(id: "shuffle", title: "Shuffle", chevron: false, action: { [unowned self] in
            player.setShuffle(true)
            player.play(songs, startAt: Int.random(in: songs.indices))
            push(nowPlayingPage())
        })]
        if missing.isEmpty {
            rows.append(MenuItem(id: "downloadall", title: "All in Your Library", detail: "✓", chevron: false))
        } else {
            rows.append(MenuItem(id: "downloadall", title: "Download All", detail: "\(missing.count)", chevron: false,
                                 action: { [unowned self] in
                                     missing.forEach(downloads.download)
                                     Clicker.shared.buttonPress()
                                 }))
        }
        rows += songs.enumerated().map { i, track in
            MenuItem(id: "\(i)-" + track.id, title: numbered ? "\(i + 1). \(track.title)" : "\(track.title) — \(track.artist)",
                     detail: status(of: track) ?? details.flatMap { $0[i] } ?? track.duration.map { TimeText.string(Double($0)) }, chevron: false,
                     action: { [unowned self] in
                         player.play(songs, startAt: i)
                         push(nowPlayingPage())
                     },
                     holdAction: { [unowned self] in push(songPage(track, context: songs)) })
        }
        return rows
    }

    /// A stand-in song used to show an album's, artist's or playlist's art and name at the top.
    private func header(_ title: String, _ subtitle: String?, _ artwork: URL?, detail: String? = nil) -> Track {
        Track(id: "header-" + title, title: title, artist: subtitle ?? "", album: detail, artworkURL: artwork)
    }

    // MARK: Search

    /// Search results: a row for each kind of result, then the top songs to play straight away.
    func runSearch(_ text: String) {
        let query = text.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        remotePage(title: "Search", empty: "Nothing found for “\(query)”") { [unowned self] _ in
            let songs = (try? await YouTube.shared.search(query, filter: .songs).items) ?? []
            let tracks = songs.compactMap { if case .song(let t) = $0 { return t } else { return nil } }
            return { [unowned self] in
                let categories = SearchFilter.allCases.map { filter in
                    MenuItem(id: "filter-" + filter.rawValue, title: filter.rawValue,
                             action: { [unowned self] in openSearch(query, filter: filter) })
                }
                return categories + tracks.prefix(10).map { row(.song($0), context: tracks) }
            }
        }
    }

    /// One kind of search result, with More Results at the end while there are more.
    func openSearch(_ query: String, filter: SearchFilter) {
        remotePage(title: filter.rawValue, empty: "No \(filter.rawValue.lowercased()) found.") { [unowned self] page in
            let first = try await YouTube.shared.search(query, filter: filter)
            let list = MoreList(first.items, more: first.continuation)
            return { [unowned self, weak page] in
                let songs = list.items.compactMap { if case .song(let t) = $0 { return t } else { return nil } }
                var rows = list.items.map { row($0, context: songs) }
                if let more = list.more {
                    rows.append(MenuItem(id: "more", title: list.loading ? "Loading…" : "More Results…", chevron: false, action: {
                        list.load(page) {
                            let next = try await YouTube.shared.search(query, filter: filter, continuation: more)
                            return (next.items, next.continuation)
                        }
                    }))
                }
                return rows
            }
        }
    }

    // MARK: Albums, artists, playlists

    func openAlbum(_ album: AlbumRef) {
        remotePage(title: "Album", header: header(album.title, album.subtitle, album.artworkURL)) { [unowned self] page in
            let loaded = try await YouTube.shared.album(album.browseId)
            page.track = header(loaded.album.title, loaded.artist ?? album.subtitle, loaded.album.artworkURL ?? album.artworkURL,
                                detail: loaded.album.subtitle)
            return { [unowned self] in
                var rows = songListRows(loaded.tracks, numbered: true)
                if let artistId = loaded.artistId {
                    rows.insert(MenuItem(id: "artist", title: "Go to \(loaded.artist ?? "Artist")",
                                         action: { [unowned self] in openArtist(artistId, name: loaded.artist ?? "Artist", artwork: nil) }),
                                at: min(2, rows.count))
                }
                return rows
            }
        }
    }

    func openArtist(_ channelId: String, name: String, artwork: URL?) {
        remotePage(title: "Artist", header: header(name, nil, artwork)) { [unowned self] page in
            let artist = try await YouTube.shared.artist(channelId)
            page.track = header(artist.name, artist.about.map { String($0.prefix(80)) }, artist.artworkURL ?? artwork)
            return { [unowned self] in
                var rows: [MenuItem] = []
                if !artist.topSongs.isEmpty {
                    rows.append(MenuItem(id: "top", title: "Top Songs", action: { [unowned self] in
                        if let all = artist.allSongsPlaylistId {
                            openPlaylist(PlaylistRef(playlistId: all, title: "\(artist.name): All Songs", subtitle: nil, artworkURL: artist.artworkURL))
                        } else {
                            push(Page(title: "Top Songs") { [unowned self] in songListRows(artist.topSongs) })
                        }
                    }))
                    rows.append(MenuItem(id: "radio", title: "\(artist.name) Radio", chevron: false, action: { [unowned self] in
                        startRadio(from: artist.topSongs[0])
                    }))
                }
                rows += artist.shelves.map { shelf in
                    MenuItem(id: "shelf-" + shelf.title, title: shelf.title, action: { [unowned self] in openShelf(shelf) })
                }
                return rows
            }
        }
    }

    func openPlaylist(_ playlist: PlaylistRef) {
        remotePage(title: "Playlist", header: header(playlist.title, playlist.subtitle, playlist.artworkURL)) { [unowned self] page in
            let first = try await YouTube.shared.playlist(playlist.playlistId)
            page.track = header(first.title ?? playlist.title, first.subtitle ?? playlist.subtitle, first.artworkURL ?? playlist.artworkURL)
            let list = MoreList(first.tracks, more: first.continuation)
            return { [unowned self, weak page] in
                var rows = songListRows(list.items)
                if let more = list.more {
                    rows.append(MenuItem(id: "more", title: list.loading ? "Loading…" : "More Songs…", chevron: false, action: {
                        list.load(page) {
                            let next = try await YouTube.shared.playlist(playlist.playlistId, continuation: more)
                            return (next.tracks, next.continuation)
                        }
                    }))
                }
                return rows
            }
        }
    }

    /// One row of a page (an artist's albums, a genre's playlists…), with See All when it has more.
    func openShelf(_ shelf: Shelf) {
        let songs = shelf.items.compactMap { if case .song(let t) = $0 { return t } else { return nil } }
        push(Page(title: shelf.title) { [unowned self] in
            var rows: [MenuItem] = songs.count == shelf.items.count ? songListRows(songs) : shelf.items.map { row($0, context: songs) }
            if let more = shelf.more {
                rows.append(MenuItem(id: "seeall", title: "See All", action: { [unowned self] in openShelves(title: shelf.title, target: more) }))
            }
            return rows
        })
    }

    /// Any other page YouTube Music links to: one row of items is shown directly, several as a list.
    func openShelves(title: String, target: BrowseTarget) {
        remotePage(title: title) { [unowned self] _ in
            let shelves = try await YouTube.shared.shelves(target)
            return { [unowned self] in
                if shelves.count == 1, let only = shelves.first {
                    let songs = only.items.compactMap { if case .song(let t) = $0 { return t } else { return nil } }
                    return songs.count == only.items.count ? songListRows(songs) : only.items.map { row($0, context: songs) }
                }
                return shelves.map { shelf in
                    MenuItem(id: "shelf-" + shelf.title, title: shelf.title.isEmpty ? title : shelf.title,
                             action: { [unowned self] in openShelf(shelf) })
                }
            }
        }
    }

    // MARK: Radio

    func startRadio(from track: Track) {
        Task {
            do {
                try await player.playRadio(from: track)
                push(nowPlayingPage())
            } catch {
                push(Page(title: "Radio", message: error.localizedDescription))
            }
        }
    }

    /// A station mixed from up to three songs in the library.
    func libraryRadio() {
        let seeds = library.tracks.filter { !$0.id.hasPrefix("local-") }.shuffled().prefix(3)
        guard !seeds.isEmpty else {
            push(Page(title: "Radio", message: "Download a few songs first, and their radio plays here."))
            return
        }
        Task {
            var mixed: [Track] = []
            var stations: [[Track]] = []
            for seed in seeds { stations.append((try? await YouTube.shared.radio(seed.id)) ?? []) }
            // Take turns between the stations so it isn't one artist after another.
            var seen = Set<String>()
            for i in 0..<(stations.map(\.count).max() ?? 0) {
                for station in stations where i < station.count {
                    if seen.insert(station[i].id).inserted { mixed.append(station[i]) }
                }
            }
            guard !mixed.isEmpty else {
                push(Page(title: "Radio", message: "YouTube Music didn't answer. Try again in a moment."))
                return
            }
            player.play(mixed, startAt: 0)
            push(nowPlayingPage())
        }
    }

    // MARK: Discover and Browse

    /// Discover: radio from your music and like what's playing, then YouTube Music's home rows.
    func discoverPage() {
        remotePage(title: "Discover") { [unowned self] _ in
            let home = (try? await YouTube.shared.home()) ?? []
            return { [unowned self] in
                var rows: [MenuItem] = []
                if library.tracks.contains(where: { !$0.id.hasPrefix("local-") }) {
                    rows.append(MenuItem(id: "libraryradio", title: "Radio from Your Library", chevron: false,
                                         action: { [unowned self] in libraryRadio() }))
                }
                if let playing = player.current, !playing.id.hasPrefix("local-") {
                    rows.append(MenuItem(id: "similar", title: "Similar to \(playing.title)", chevron: false,
                                         action: { [unowned self] in startRadio(from: playing) }))
                }
                rows += home.map { shelf in
                    MenuItem(id: "shelf-" + shelf.title, title: shelf.title, action: { [unowned self] in openShelf(shelf) })
                }
                return rows
            }
        }
    }

    func browsePage() -> Page {
        Page(title: "Browse") { [unowned self] in
            let region = prefs.chartRegion == "global" ? "Global" : prefs.chartRegion.uppercased()
            return [
                MenuItem(id: "top20", title: "Top 20", detail: region, action: { [unowned self] in openChart(limit: 20) }),
                MenuItem(id: "top100", title: "Top 100", detail: region, action: { [unowned self] in openChart(limit: 100) }),
                MenuItem(id: "topartists", title: "Top Artists", detail: region, action: { [unowned self] in openTopArtists() }),
                MenuItem(id: "new", title: "New Releases", action: { [unowned self] in
                    openShelves(title: "New Releases", target: BrowseTarget(browseId: "FEmusic_new_releases_albums", params: nil))
                }),
                MenuItem(id: "genres", title: "Genres & Moods", action: { [unowned self] in openGenres() }),
            ]
        }
    }

    func openChart(limit: Int) {
        let region = prefs.chartRegion
        remotePage(title: "Top \(limit)") { [unowned self] page in
            let chart = try await YouTube.shared.topSongs(country: region)
            let entries = Array(chart.entries.prefix(limit))
            if let week = chart.week {
                page.track = header("Top \(limit)", region == "global" ? "Global" : Locale.current.localizedString(forRegionCode: region.uppercased()),
                                    nil, detail: "Week ending \(week)")
            }
            let movement: [String?] = entries.map { entry in
                guard let previous = entry.previousRank else { return "NEW" }
                if previous > entry.rank { return "▲\(previous - entry.rank)" }
                if previous < entry.rank { return "▼\(entry.rank - previous)" }
                return "–"
            }
            return { [unowned self] in songListRows(entries.map(\.track), numbered: true, details: movement) }
        }
    }

    func openTopArtists() {
        let region = prefs.chartRegion
        remotePage(title: "Top Artists") { [unowned self] _ in
            let artists = try await YouTube.shared.topArtists(country: region)
            return { [unowned self] in
                artists.enumerated().map { i, artist in
                    MenuItem(id: artist.channelId, title: "\(i + 1). \(artist.name)",
                             action: { [unowned self] in openArtist(artist.channelId, name: artist.name, artwork: artist.artworkURL) })
                }
            }
        }
    }

    /// YouTube Music's Moods & Genres, or a built-in list of genres if it doesn't answer.
    func openGenres() {
        remotePage(title: "Genres & Moods") { [unowned self] _ in
            let shelves = (try? await YouTube.shared.shelves(BrowseTarget(browseId: "FEmusic_moods_and_genres", params: nil))) ?? []
            return { [unowned self] in
                if !shelves.isEmpty {
                    return shelves.map { shelf in
                        MenuItem(id: "shelf-" + shelf.title, title: shelf.title, action: { [unowned self] in openShelf(shelf) })
                    }
                }
                return BuiltInGenres.all.map { genre in
                    MenuItem(id: genre.title, title: genre.title, action: { [unowned self] in
                        push(Page(title: genre.title) { [unowned self] in [
                            MenuItem(id: "songs", title: "Top Songs", action: { [unowned self] in openSearch(genre.songs, filter: .songs) }),
                            MenuItem(id: "playlists", title: "Playlists", action: { [unowned self] in openSearch(genre.playlists, filter: .playlists) }),
                        ] })
                    })
                }
            }
        }
    }
}

/// A list that loads its next page on request ("More Results…"), keeping what it has.
@MainActor
final class MoreList<Item: Hashable> {
    private(set) var items: [Item]
    private(set) var more: String?
    private(set) var loading = false

    init(_ items: [Item], more: String?) {
        self.items = items
        self.more = more
    }

    func load(_ page: Page?, next: @escaping () async throws -> ([Item], String?)) {
        guard !loading else { return }
        loading = true
        page?.objectWillChange.send()
        Task {
            if let (newItems, token) = try? await next() {
                let known = Set(items)
                items += newItems.filter { !known.contains($0) }
                more = token
            } else {
                more = nil
            }
            loading = false
            page?.objectWillChange.send()
        }
    }
}

/// Genres to search for when YouTube Music's own Moods & Genres page isn't available
/// (the Android app's list).
enum BuiltInGenres {
    struct Genre { let title, playlists, songs: String }

    static let all: [Genre] = [
        Genre(title: "Pop", playlists: "pop hits playlist", songs: "pop hits"),
        Genre(title: "Hip-Hop/Rap", playlists: "hip hop rap hits playlist", songs: "hip hop hits"),
        Genre(title: "R&B", playlists: "r&b hits playlist", songs: "r&b soul hits"),
        Genre(title: "Dance/Electronic", playlists: "dance electronic hits playlist", songs: "edm dance hits"),
        Genre(title: "Rock", playlists: "rock classics playlist", songs: "rock hits"),
        Genre(title: "Alternative", playlists: "alternative indie hits playlist", songs: "alternative indie songs"),
        Genre(title: "Country", playlists: "country hits playlist", songs: "country hits"),
        Genre(title: "Latin", playlists: "latin hits playlist", songs: "reggaeton latin hits"),
        Genre(title: "K-Pop", playlists: "kpop hits playlist", songs: "kpop hits"),
        Genre(title: "Afrobeats", playlists: "afrobeats hits playlist", songs: "afrobeats hits"),
        Genre(title: "Jazz", playlists: "jazz classics playlist", songs: "jazz classics"),
        Genre(title: "Classical", playlists: "classical music essentials", songs: "classical music masterpieces"),
        Genre(title: "Metal", playlists: "metal essentials playlist", songs: "heavy metal songs"),
        Genre(title: "Reggae", playlists: "reggae classics playlist", songs: "reggae hits"),
        Genre(title: "Soul & Funk", playlists: "soul funk classics playlist", songs: "soul funk classics"),
        Genre(title: "Chill", playlists: "chill vibes playlist", songs: "chill songs"),
        Genre(title: "Workout", playlists: "workout music playlist", songs: "workout songs"),
        Genre(title: "Party", playlists: "party hits playlist", songs: "party songs"),
        Genre(title: "Focus", playlists: "focus music study playlist", songs: "lofi study music"),
        Genre(title: "Sleep", playlists: "sleep music playlist", songs: "relaxing sleep music"),
        Genre(title: "Feel Good", playlists: "feel good songs playlist", songs: "feel good songs"),
        Genre(title: "Heartbreak", playlists: "sad songs playlist", songs: "sad songs"),
        Genre(title: "90s", playlists: "90s hits playlist", songs: "90s hits"),
        Genre(title: "80s", playlists: "80s hits playlist", songs: "80s hits"),
    ]
}
