# Tempo for iPhone: iPod classic edition

Tempo on iOS looks and works like an iPod classic. It has an anodized aluminium face in silver
or black, the 4:3 colour screen with its silver title bar and blue selection bar, and a working
click wheel that clicks, rocks and buzzes like the real one. You search YouTube Music, download songs, and they go into your library for
offline listening.

## Using it

- **Click wheel**: slide your finger around the ring to scroll. It clicks and taps with every
  step, like the real one did.
  - **MENU** goes back. Hold MENU to jump to the main menu.
  - **⏮ / ⏭** go to the previous or next song. **⏯** plays or pauses.
  - **Center button** selects. Hold it for more options (on a song: its page, with Download,
    Play Next and Delete).
- You can also tap rows on the screen directly.
- **Search**: turn the wheel through the letters and press the center button to type each one,
  then choose **GO**. Or tap the search box to use the keyboard. Results come from YouTube
  Music's catalogue. Select a song to play it (streamed) or **Download** it.
- **Music → Artists / Albums / Songs**: everything you've downloaded, playable offline.
- **Downloads** shows songs that are downloading or failed. Holding the center button on a
  search result downloads it straight away.
- **Shuffle Songs** shuffles your whole library.
- **Now Playing** shows the artwork with its reflection, the progress bar and the time left.
  Turn the wheel to change the volume. Press the center button to scrub through the song with
  the wheel.
- **Settings**: Shuffle, Repeat, Body (Silver/Black), Backlight, Clicker, Haptics, About, and Delete All
  Songs.
- Music keeps playing in the background, with lock screen and Control Center controls.
- Downloaded songs are in Files → On My iPhone → Tempo (as `.m4a`), along with the artwork.

## Installing

GitHub Actions builds an unsigned `.ipa` on every push that changes `ios/`. You can find it in
the **Build iOS** workflow run's artifacts. Pushing a tag `ios-vX.Y.Z` publishes it as a release.
Sign and install it with [AltStore](https://altstore.io), [Sideloadly](https://sideloadly.io)
or a similar tool using your Apple ID. With a free Apple ID the app has to be re-signed every 7 days.

## Building

Open the project in Xcode 16 or later. It uses [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
brew install xcodegen
cd ios && xcodegen generate && open Tempo.xcodeproj
```

Pick your team under Signing & Capabilities and run it on your iPhone. iOS 17 or later is required.

## How it works

- **Search** uses YouTube Music's own web API (InnerTube, the `WEB_REMIX` client). It tries
  the Songs filter first and falls back to all results, without podcasts.
- **Streams** come from InnerTube's player endpoint. The app tries several YouTube app clients
  in turn (Android VR, iOS, Android), because those return direct audio URLs. Only AAC
  (`audio/mp4`) is used, because AVPlayer can't play YouTube's Opus/WebM. Downloads are fetched
  in 4 MB ranges, the way YouTube's apps do it.
- If YouTube asks to "confirm you're not a bot", every client is refused. That depends on your
  network, so try again later or on another connection (Wi‑Fi or cellular).
- The library is `Documents/library.json` plus the audio and artwork files.
