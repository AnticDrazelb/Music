# Tempo

An Apple Music–style music player for Android that streams from YouTube.

<p>
<img src="docs/screenshots/01_home.jpg" width="200">
<img src="docs/screenshots/05_chart_top100.jpg" width="200">
<img src="docs/screenshots/09_now_playing.jpg" width="200">
<img src="docs/screenshots/10_queue.jpg" width="200">
</p>
<p>
<img src="docs/screenshots/03_browse.jpg" width="200">
<img src="docs/screenshots/12_downloaded.jpg" width="200">
<img src="docs/screenshots/13_song_menu.jpg" width="200">
<img src="docs/screenshots/01_home_dark.jpg" width="200">
</p>

## Features

- **Charts**: official YouTube Charts. Top 40 and Top 100 songs and top artists, for Global
  and about 60 countries. Charts update weekly and show rank movement (▲ ▼ NEW). Chart songs
  use the official audio ("art track") rather than the music video.
- **Home**: Top Picks, Recently Played, Top Songs, Top Artists, and playlist shelves that
  rotate daily.
- **Browse**: chart tiles, 16 genres and 12 moods/activities. Each has its own page with
  top songs and playlists.
- **Search**: live suggestions, recent searches, and filters for Songs, Albums, Artists and
  Playlists. Results load more as you scroll.
- **Similar songs**: "Create Station" builds a radio from any song. **Autoplay (∞)** keeps
  adding similar songs when your queue runs out.
- **Now Playing**: the background colour comes from the artwork. The artwork shrinks when
  paused. Includes a scrubber, volume, a ♥ favourite button, sleep timer, share, and a queue
  with shuffle, repeat, autoplay, drag-to-reorder and swipe-to-remove.
- **Downloads**: save any song, album, playlist or your Favorites for offline listening.
  Downloads run in the background with a progress notification and play straight from
  storage, with no connection needed. Manage them in Library → Downloaded or in Settings.
- **Library**: your own playlists, Favorite Songs (♥), Recently Played, Downloaded, and saved
  albums, playlists and artists. All stored on the device.
- **Background playback**: lock-screen and notification controls, Bluetooth/headset
  buttons, and audio focus. Replays come from a 512 MB on-device audio cache.
- **Share a YouTube or YouTube Music link** to Tempo to play it.
- Light and dark mode follow the system setting. The app is locked to portrait.

## Install

1. Download `Tempo-1.1.0.apk` (see the latest build artifact or release). It installs over
   an earlier version and keeps your library.
2. Open it on your phone and allow "Install unknown apps" for your browser or file manager.
3. Android 8.0 (API 26) or newer.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # unit tests
./gradlew testDebugUnitTest -Dlive=true         # also hit the real YouTube endpoints
./gradlew testDebugUnitTest -Dscreenshots=true  # render screenshots to app/screenshots/
```

Release builds are signed with the key in `signing/` so new builds install as updates over
old ones and keep your library. **Keep this repository private.** Anyone with that key can
sign an APK that Android will accept as an update to Tempo.

## How it works

| Layer | Notes |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3 restyled to look like iOS/Apple Music |
| Playback | Media3 ExoPlayer in a `MediaSessionService`. A resolving data source turns `tempo://youtube/<id>` into a signed audio URL just before playback. |
| YouTube | [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) for search, stream URLs, playlists, channels and radio mixes |
| Charts | `charts.youtube.com` browse API (`data/charts/ChartsRepository.kt`) |
| Storage | Room for playlists, favourites, history and the download queue. DataStore for settings. |
| Downloads | `data/download/`. Chunked, resumable range requests in a foreground service. Files are kept in the app's private music folder. |

## Caveats

- Streaming from YouTube this way is against YouTube's Terms of Service. This app is for
  personal use.
- YouTube changes its internal APIs often. If playback or search stops working, bump
  `newpipeExtractor` in `gradle/libs.versions.toml` to the latest
  [release](https://github.com/TeamNewPipe/NewPipeExtractor/releases) and rebuild.
- Some networks, especially VPNs and data centres, get "confirm you're not a bot" responses
  from YouTube.
- Tempo is not affiliated with Apple, Google or YouTube.

## License

GPL-3.0, inherited from NewPipe Extractor. See [LICENSE](LICENSE).
