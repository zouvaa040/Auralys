<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_static_foreground.png" alt="Auralys logo" width="160" />

# Auralys

**A YouTube Music player for Android, dressed in Liquid Glass.**

*Pemutar YouTube Music untuk Android dengan tampilan Liquid Glass.*

<br/>

[![Version](https://img.shields.io/badge/version-4.3.6-C4653F?style=for-the-badge&labelColor=1C1A18)](https://auralys.my.id/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-C4653F?style=for-the-badge&logo=android&logoColor=white&labelColor=1C1A18)](https://auralys.my.id/)
[![License](https://img.shields.io/badge/license-GPL--3.0-C4653F?style=for-the-badge&labelColor=1C1A18)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-C4653F?style=for-the-badge&logo=kotlin&logoColor=white&labelColor=1C1A18)](app/src/main/kotlin)

<br/>

<a href="https://auralys.my.id/">
  <img src="https://img.shields.io/badge/Download%20Auralys-auralys.my.id-C4653F?style=for-the-badge&logo=android&logoColor=white&labelColor=1C1A18" alt="Download Auralys" height="46" />
</a>

<br/>
<br/>

[**Features**](#features) · [**Build**](#build-from-source) · [**License**](#license) · [**Credits**](#credits)

</div>

---

<h2 align="center"><a id="features"></a>Features</h2>

<table>
  <tr>
    <td width="50%" valign="top">

#### Look and feel
- Liquid Glass design with a refracting glass navigation bar
- Dynamic Island with synced lyrics
- Lyrics on the lock screen
- Ambient mode and animated covers (Spotify Canvas, Apple Music motion artwork)
- App icon styles, app fonts and themes
- Resizable home-screen widget

</td>
    <td width="50%" valign="top">

#### Sound
- Easy equalizer with presets
- Sound styles: Sped up, Slowed + reverb, Nightcore, Lo-fi, 8D, Concert
- Crossfade, gapless playback and loudness normalization
- Sleep timer and music alarm

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Lyrics
- Synced lyrics with word-by-word highlighting
- Several lyrics sources, with a picker per song
- Lyrics translation

</td>
    <td width="50%" valign="top">

#### Library
- YouTube Music account and library sync
- Downloads for offline listening
- Music files stored on the phone
- Spotify account: playlists and Liked Songs
- Import playlists from Spotify and Apple Music links

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Share and create
- Song poster maker with automatic photo cut-out
- Listen Together with friends

</td>
    <td width="50%" valign="top">

#### More
- Listening streak, statistics and recap
- Music recognition
- Sync with Auralys for Windows by QR code

</td>
  </tr>
</table>

---

<h2 align="center"><a id="build-from-source"></a>Build from source</h2>

Requirements: JDK 21 and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleFossRelease
```

Release builds are signed with the key described in `~/AuralysKeys/keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`). That file and the key are not part of this repository; without them the build is signed with
the debug key.

---

<h2 align="center"><a id="license"></a>License</h2>

Auralys is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist) and, like Metrolist, is free software under the
[GNU General Public License v3.0](LICENSE). Parts taken from other open-source projects are listed in [NOTICE](NOTICE).

<h2 align="center"><a id="credits"></a>Credits</h2>

<p align="center">
  <a href="https://github.com/MetrolistGroup/Metrolist">Metrolist</a> and its contributors
</p>

---

<div align="center">

Auralys is not affiliated with, endorsed by or associated with YouTube, Google LLC, Spotify, Apple or Metrolist Group.
All trademarks belong to their respective owners.

<br/>
<br/>

**Made by DAPZ.DEV**

</div>
