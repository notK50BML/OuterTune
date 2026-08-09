# OuterTune

[![OuterTune app icon](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/outertune.webp)](https://github.com/yuuichi-s/OuterTune/blob/dev/assets/outertune.webp)

[![Release](https://img.shields.io/badge/release-v0.16-orange)](https://github.com/notK50BML/Outertune/releases/latest) [![License](https://img.shields.io/github/license/yuuichi-s/OuterTune)](https://www.gnu.org/licenses/gpl-3.0) 

A personalisation-first music player, based on a minimalist application. The app is extremely lightweight, with the universal release around 10MB. In contrast, Spotify and Youtube Music's APKs are all 60mB or more. Still, this is feature-packed, with settings such as Discord Rich Presence, line-by-line lyrics, a special UI editor (still WIP), and much more. It has a dynamic Material You 3 theme and multiple player styles. 

Currently, we are working on movable UI elements and a visualiser. These are both very WIP projects, and we will continue to update the app, but these features may not be released soon.

I NEED A CAT! 

> [!NOTE]
> This is a fork based on [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune).
>
> Occasionally, release APKs would be uploaded, but to test the latest release, please use compile the APK yourself, please read the wiki (https://github.com/notK50BML/OuterTune/wiki/Getting-started).
>
## What This Fork Improves

This fork builds on [OuterTune/OuterTune](https://github.com/OuterTune/OuterTune) with a focus on personalisation, YouTube Music playback stability, lyrics, navigation, a clean UI, and local music playback.

### Movable UI Elements
- Added ability to configure UI elements
- Added a HTML file which is run fully offline and extremely lightweight
- Added multiple preset configurations to choose from
- Easily revertible to the original layout
- Simple and intuitive UI which lets you quickly export the current configuration
Note: As said before, this project is still a work-in-progress, with known bugs and issues. This feature is working, but it is definitely nowhere near an alpha release.

### Discord Rich Presence
- Added rich presence (huge thanks to https://github.com/reocat/OuterTune)
- Added clickable song and artist
- Added Song cover and artist icon
- Added a working progress bar
- Added token login along with normal login
- Added connection tester to diagnose any connection issues

### YouTube Music playback and display

- Fixed albums with missing tracks, crashes while opening playlists, and failed search result parsing
- Fixed the "Source error 2004" issue that could block YouTube Music playback
- Improved YouTube Music thumbnail resolution and allows for switching between low res and standard thumbnails (on some songs)
- Fixed a crash that could occur when opening playlists or albums while their data was being updated
- Fixed .m3u playlist import crashes and improved YouTube song matching
- Fixed incorrect Liked Music song sequence
- Fixed blurry cover art on some songs (again)
- Cleaned up the top bar and removed a redundant icon
- Added frosted glass and liquid player style

### Lyrics

- Uses LrcLib and caption tracks to improve lyrics matching and loading speed
- Adds a lyrics toggle button to the now-playing action bar
- Added SimpMusic and BetterLyrics as lyrics providers
- Queries enabled providers in parallel with timeouts
- Fetches lyrics in the playback service even while the lyrics panel is closed
- Added word-by-word lyrics, rendered in the normal word-highlight style

### Navigation and menus

- Adjusted bottom navigation so tab switching and re-tapping the active tab behave more naturally
- Fixed issues with the search bar, sorting, and list refreshes on the Folders screen
- Replaced the persistent search bar on tab screens with a top icon row (search, stats, and settings)
- Added swipe-to-skip to the mini player

### Local music playback

- Improved tag reading, song linking, and gapless playback for local music
- Fixed the album song count shown on album screens
- Added a Local tab for browsing on-device songs, albums, artists, and playlists with filters and search

### Display and settings

- Improved the tablet UI
- Automatically detects the system contrast setting on Android 14 and later
- Added custom accent colors
- Added a "keep audio focus" player setting
- Added a home screen grid showing your recent YouTube Music activity when signed in
- Added the current queue's name to the player's queue handle
- Replaced the account icon with the signed-in account's profile image
- Reorganised the settings screens, merging Appearance and Interface into "Appearance and controls" and adding a top-level Privacy screen
- Added Frosted Glass and Liquid player themes, along with a toggle for contrasting text for accessibility. 

### Playback and downloads

- Added a sleep timer that fades out and fully stops playback
- Added a Wi-Fi-only download toggle
- Improved fetch time for uncached songs

### Internal libraries and build tooling

- Updated Kotlin, KSP, NewPipeExtractor, Ktor, Android Gradle Plugin, Gradle, and related tooling

## Original features

OuterTune is a supercharged fork of [InnerTune](https://github.com/z-huang/InnerTune). This app is both a local media player, and a YouTube Music client.

- YouTube Music client features
  * Song downloading (offline playback) at 130kbps bitrate
  * Seamless playback: no ads & background playback
  * Account synchronization
    + Full playlist sync from the app to the remote account is temporarily unavailable
- Local audio file playback (ex. MP3, OGG, FLAC, etc.)
  * Play local and YouTube Music songs at the same time
  * Uses a custom tag extractor instead of MediaStore's broken metadata extractor! (e.g tags delimited with \ now show up properly)
- Sleek Material3 design with dynamic accenting
- Up to 30 self-saving queues which auto-update
- Synchronized lyrics, and support for word by word/Karaoke lyrics formats (e.g LRC, TTML) using a variety of providers
- Audio normalization, tempo/pitch adjustment, skip silence, inbuilt parametric equaliser and various other audio effects
- Android Auto support
- Support for Android 8 (Oreo) and higher

> [!NOTE]
> Android 8 (Oreo) and higher is supported. While the app may work on Android 7.x (Nougat), we do not officially support this version

## Screenshots

[![Main player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)

[![Player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)

[![Sync with YouTube Music](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)

[Full image gallery](https://github.com/yuuichi-s/OuterTune/tree/dev/assets/gallery)

> [!WARNING]
> If you're in a region where YouTube Music is not supported, you won't be able to use this app ***unless*** you have a proxy or VPN to connect to a YTM supported region.

## Building & Contributing

Just wish to build the app yourself, please see the [building and contribution notes](CONTRIBUTING.md).

### Submitting Translations

We use Weblate to translate OuterTune. For more details or to submit translations, visit our [Weblate page](https://hosted.weblate.org/projects/yuuichi-s-outertune/).

[![Translation status](https://hosted.weblate.org/widget/yuuichi-s-outertune/multi-auto.svg)](https://hosted.weblate.org/projects/yuuichi-s-outertune/)

Thank you very much for helping to make OuterTune accessible to many people worldwide.

## Help & Support

- For bugs **specific to this fork**, please open an [Issue in this repository](https://github.com/notK50BML/OuterTune/issues).

## Attribution

Thanks to all our contributors! Check them out [here](https://github.com/OuterTune/OuterTune/graphs/contributors)

[Outertune](github.com/OuterTune/OuterTune) and (https://github.com/yuuichi-s/OuterTune) for almost all the main code, this is just a casual project to fix some small errors, but almost ALL credit goes to them.

Note: this builds on a few other Outertune forks, so a lot of credit goes to https://github.com/reocat/OuterTune and of course https://github.com/yuuichi-s/OuterTune

[z-huang/InnerTune](https://github.com/z-huang/InnerTune) for providing an awesome base for this fork, none of this
would have been possible without it.

[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) for inspiration of a local music player
experience done right.

[Gramophone](https://github.com/FoedusProgramme/Gramophone) for emotional support, and a legendary lyrics parser



## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any
way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project
are owned by the respective owners.
