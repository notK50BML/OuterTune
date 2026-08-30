# OuterTune

[![OuterTune app icon](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/outertune.webp)](https://github.com/yuuichi-s/OuterTune/blob/dev/assets/outertune.webp)

[![Release](https://img.shields.io/badge/release-v0.19-orange)](https://github.com/notK50BML/Outertune/releases/latest) [![License](https://img.shields.io/github/license/yuuichi-s/OuterTune)](https://www.gnu.org/licenses/gpl-3.0) 

A personalisation-first music player, based on a minimalist application. The app is extremely lightweight, with the universal release around 10MB. Still, this is feature-packed, with settings such as Discord Rich Presence, line-by-line lyrics, a dynamic audio visualiser, a free-position UI editor (still WIP), and much more. This player features extensive Material 3 customisation options and player styles for dynamic theming. 

I really, really need a cat.

> [!NOTE]
> This is a fork based on [yuuichi-s/OuterTune](https://github.com/yuuichi-s/OuterTune), a fork of the original OuterTune repository.
>
> While APKs would be uploaded, beta or nightly releases are rare in APK form. If you want to get a fix for a problem as soon as possible or try out new features, please read the [wiki](https://github.com/notK50BML/OuterTune/wiki/Getting-started) for instructions on how to compile the APK yourself.
>
> I am also currently working on a Windows build of this app, so it is quite possible that android development may slow down a bit. To be honest though, this app already has more than enough features for most people, so adding more is probably counter-productive anyway. Of course, the android branch will still continue to receive maintenance updates and new features, but some time would be spent on the new windows build.


## What This Fork Improves

This fork builds on [yuuichi-s/OuterTune](https://github.com/yuuichi-s/OuterTune) with a focus on personalisation, YouTube Music playback stability, lyrics, navigation, a clean UI, and local music playback.

### Movable UI Elements
- Added ability to configure UI elements
- Added a HTML file which is run fully offline and extremely lightweight
- Added multiple preset configurations to choose from
- Easily revertible to the original layout
- Simple and intuitive UI which lets you quickly export the current configuration
- Added ability to group and ungroup elements
- Improved presets
- Note: As mentioned above, this project is still a work-in-progress, with known bugs and issues. This feature is working, but it is definitely nowhere near an alpha release.

### Discord Rich Presence
- Added rich presence (huge thanks to [reocat](https://github.com/reocat/OuterTune))
- Added clickable song and artist
- Added song cover and artist icon
- Added a working progress bar
- Added token login along with normal login
- Added connection tester to diagnose any connection issues
- Improved normal login authentication process

### YouTube Music

- Fixed albums with missing tracks, crashes while opening playlists, and failed search result parsing
- Fixed the "Source error 2004" issue that could block YouTube Music playback
- Improved YouTube Music thumbnail resolution and allows for switching between low res and standard thumbnails (on some songs)
- Fixed a crash that could occur when opening playlists or albums while their data was being updated
- Fixed .m3u playlist import crashes and improved YouTube song matching
- Fixed blurry cover art on some songs (again)
- Cleaned up the top bar and removed a redundant icon
- Added frosted glass and liquid player style
- Added [InnerTubeX](https://github.com/MetrolistGroup/innertubex) as the main stream and download backend
- Improved artist linking through fetching albums, splitting artists for Youtube songs, resolving duplicate names, and other techniques
- Improved artist image placement by rendering a square picture and realigning (thanks to [Metrolist](https://github.com/metrolistgroup/metrolist))
- Fixed the broken remote history page
- Added a feature which allows remote listens to count to total song stats

### Lyrics

- Uses LrcLib and caption tracks to improve lyrics matching and loading speed
- Added a lyrics toggle button to the now-playing action bar (can be hidden in settings now as well)
- Added SimpMusic and BetterLyrics as lyrics providers
- Queries enabled providers in parallel with timeouts
- Fetches lyrics in the playback service even while the lyrics panel is closed
- Added word-by-word lyrics, rendered in the word-highlight style
- Improved lyrics pipeline
- Fixed "cannot parse lyrics" errors
- Fixed word-by-word lyrics rendering breaking when battery saver was enabled (now can be enabled under Lyrics > Advanced > Karaoke style lyrics)

### Navigation and menus

- Adjusted bottom navigation so tab switching and re-tapping the active tab behave more naturally
- Fixed issues with the search bar, sorting, and list refreshes on the Folders screen
- Replaced the persistent search bar on tab screens with a top icon row (search, stats, and settings)
- Added swipe-to-skip to the mini player

### Local music

- Improved tag reading, song linking, and gapless playback for local music
- Fixed the album song count shown on album screens
- Added a Local tab for browsing on-device songs, albums, artists, and playlists with filters and search
- Added an inbuilt parametric equaliser and compressor
- Improved equaliser and compressor pipeline for better, less artefact-prone audio
- Added a crossfade function
- Fixed a bug with colours in the equaliser bands
- Removed redundant settings for the equaliser page (Contrast by colour was unneeded as the cards were by default grey anyway)
- Added AutoEQ support, though this is still WIP

### Display and settings

- Improved the tablet UI
- Automatically detects the system contrast setting on Android 14 and later
- Added custom accent colours
- Added a "keep audio focus" player setting
- Added a home screen grid showing your recent YouTube Music activity when signed in
- Added the current queue's name to the player's queue handle
- Replaced the account icon with the signed-in account's profile image
- Reorganised the settings screens, merging Appearance and Interface into "Appearance and controls" and adding a top-level Privacy screen
- Added Frosted Glass and Liquid player themes, along with a toggle for contrasting text for accessibility.
- Significantly revamped Liquid player theme by adding audio-reactive features
- Note: some features require Android 13+ support, and will be marked as such in settings
- Made the default English locale British English for better consistency
- Improved performance for the Liquid Ferrofluid's raymarched renderer and added quality settings

### Downloads

- Added a sleep timer that fades out and fully stops playback
- Added a Wi-Fi-only download option
- Improved fetch time for uncached songs
- Fixed a bug where song download speeds were limited by googlevideo limits

### Other features
- Added an APK auto-downloader


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
>
> Version naming right now is a bit confusing. In order to clear up any questions, the naming convention is as follows: v0.xx is a stable alpha release, and v0.xx.x is a stable alpha fix release. These fix releases usually fix bugs or layout problems, as well as adding small features. A new alpha release typically comprises one or more major feature changes, or large overhauls of the user interface. A v0.xx.xb release is a beta release, featuring the cutting edge of the app's development, though this release may have bugs. Though these beta releases are absolutely usable as your default player, some features may not feel as polished as they should be.

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

[OuterTune](github.com/OuterTune/OuterTune) and [OuterTune by yuuichi-s](https://github.com/yuuichi-s/OuterTune) for almost all the main code, this is just a casual project to fix some small errors, but almost ALL credit goes to them.

Note: this builds on a few other Outertune forks, so a lot of credit goes to the OuterTune forks by [reocat](https://github.com/reocat/OuterTune) and of course [yuuichi-s](https://github.com/yuuichi-s/OuterTune)

[Metrolist](https://github.com/metrolistgroup/metrolist) for a really great framework of a stream and sync engine, without which this app would be 100% broken

[z-huang/InnerTune](https://github.com/z-huang/InnerTune) for providing an awesome base for this fork, none of this
would have been possible without it.

[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) for inspiration of a local music player
experience done right.

[Gramophone](https://github.com/FoedusProgramme/Gramophone) for emotional support, and a legendary lyrics parser

[BetterLyrics](https://github.com/better-lyrics/better-lyrics) for their amazing word-by-word lyrics engine



## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any
way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project
are owned by the respective owners.
