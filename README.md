# Auralys

A YouTube Music client for Android, made by DAPZ.DEV.

*Pemutar musik YouTube Music untuk Android, dibuat oleh DAPZ.DEV.*

Auralys is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist) and, like Metrolist, is free software under the
[GNU General Public License v3.0](LICENSE). Parts taken from other open-source projects are listed in [NOTICE](NOTICE).

Download: https://auralys.my.id/

## What Auralys adds

- Liquid Glass look with a refracting glass navigation bar
- Dynamic Island with synced lyrics
- Lyrics on the lock screen
- Song poster maker with automatic photo cut-out
- Spotify account: playlists and Liked Songs, Spotify Canvas animated covers
- Playlist import from Spotify and Apple Music links
- Sound styles: Sped up, Slowed + reverb, Nightcore, Lo-fi, 8D, Concert
- Easy equalizer with presets
- Listening streak, statistics and recap
- Resizable home-screen widget
- Music files stored on the phone
- Sync with Auralys for Windows by QR code
- Lyrics from several sources, with a source picker

Plus everything from Metrolist: streaming from YouTube Music, downloads, synced lyrics, account login, Listen Together,
music recognition and more.

## Building

Requirements: JDK 21 and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleFossRelease
```

Release builds are signed with the key described in `~/AuralysKeys/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). That file and the key are not part of this repository; without them the build is signed with
the debug key.

## Credits

- [Metrolist](https://github.com/MetrolistGroup/Metrolist) and its contributors, and the projects Metrolist builds on
  ([InnerTune](https://github.com/z-huang/InnerTune), [OuterTune](https://github.com/DD3Boh/OuterTune))
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (Apache 2.0), [Convx](https://github.com/cosmictaserdev-creator/Convx)
  and [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0), [MODNet](https://github.com/ZHKKKe/MODNet) (Apache 2.0):
  see [NOTICE](NOTICE) for what was taken from each

## Disclaimer

Auralys is not affiliated with, endorsed by or associated with YouTube, Google LLC, Spotify, Apple or Metrolist Group.
All trademarks belong to their respective owners.
