# OuterTune

[![OuterTune app icon](https://github.com/OuterTune/OuterTune/raw/dev/assets/outertune.webp)](https://github.com/OuterTune/OuterTune/blob/dev/assets/outertune.webp)

Keep Android Open! Please sign this petition: https://c.org/4ZMvPdZjpp

[![Android Stable](https://img.shields.io/github/v/tag/notk50bml/outertune?filter=%21%2A-Windows&sort=semver&label=Android%20Stable)](https://github.com/notk50bml/outertune/releases) [![Windows Stable](https://img.shields.io/github/v/release/notk50bml/outertune?filter=*-Windows&sort=semver&label=Windows%20Stable)](https://github.com/notk50bml/outertune/releases) [![License](https://img.shields.io/github/license/notK50BML/OuterTune)](https://www.gnu.org/licenses/gpl-3.0) 

A music player which puts your personalised experience above all else. This app was engineered to be lightweight, yet feature-packed. Along with the functions of the original app, this fork also adds word-by-word lyrics, a completely free-placement UI editor, an audio visualiser, Discord rich presence and much more. This player also is heavily customisable and features a Material You design for accents and player colours for a dynamic theme. 


Note: This app is currently in maintenance mode. Proper development of this app will continue in 2027, and fixes will be slower, but the app is currently already in a stable state (and all the more recent releases are mainly improvements and minor fixes).

The Windows application will soon be migrated to this [new repository](https://github.com/notK50BML/OuterTune-for-Windows) (not public yet). 

I really, really need a cat.

> [!NOTE]
> This is a fork based on [yuuichi-s/AsterTune](https://github.com/yuuichi-s/AsterTune), a fork of the original OuterTune repository. Credits and attributions to the many helpful projects and repositories are down below
>
> If you want to build the app yourself, clone the repository and run the "Build new APKs" workflow. Ensure that the patch being applied is called outertune-metrolist-engine.patch.



## What This Fork Improves

This fork builds on [yuuichi-s/AsterTune](https://github.com/yuuichi-s/OuterTune) with a focus on personalisation, YouTube Music playback stability, lyrics, navigation, a clean UI, and local music playback.

### Movable UI elements
- Added ability to configure UI elements
- Added a HTML file which is run fully offline and extremely lightweight
- Added multiple preset configurations to choose from
- Easily revertible to the original layout
- Simple and intuitive UI which lets you quickly export the current configuration
- Added ability to group and ungroup elements
- Improved presets

### Listen together
- Added ability to listen along to another person's device, as long as that device is still on the same network
- Fixed a bug where the devices will fall out of sync because of lag from the host-side device
- Added a feature to correct for the delay in the devices playing songs, tunable to a few milliseconds
- Improved pipeline to reduce drop-outs
- Experimental dual-phone stereo speaker system using listen together
- Automatic calibration before the start of a session (optional)

### Discord Rich Presence
- Spotify-style rich presence (huge thanks to [reocat](https://github.com/reocat/OuterTune))
- Clickable song and artist
- Song cover and artist icon
- Progress bar and song length, and your current time in the song
- Ability to paste a token or login normally
- Connection tester to diagnose any connection issues


### YouTube Music

- Frosted glass and liquid audio-reactive player styles
- Fixed albums with missing tracks, crashes while opening playlists, and failed search result parsing
- Fixed the "Source error 2004" issue that could block YouTube Music playback
- Improved YouTube Music thumbnail resolution and allows for switching between low res and standard thumbnails (on some songs)
- Fixed a crash that could occur when opening playlists or albums while their data was being updated
- Fixed .m3u playlist import crashes and improved YouTube song matching
- Fixed blurry cover art on some songs (again)
- Revamped top bar
- [InnerTubeX](https://github.com/MetrolistGroup/innertubex) is now the main stream and download backend
- Improved artist linking through fetching albums, splitting artists for Youtube songs, resolving duplicate names, and other techniques
- Improved artist image placement by rendering a square picture and realigning (thanks to [Metrolist](https://github.com/metrolistgroup/metrolist))
- Fixed a bug where only some songs would be added to a Youtube-synced playlist
- High Frequency Restoration, which is helpful in slightly improving quality of less detailed streamed songs
- Sleep timer that fades out and fully stops playback

### Lyrics

- Features word-synced lyrics on most songs, from BetterLyrics, QQMusic, LRCLIB word-synced and YouLy+
- Uses LrcLib and KuGou as backup line-synced providers
- Features an innovative lyrics interpolation engine for accurate word-synced lyrics interpolated from line-synced data
- Lyrics are queried in parallel to improve loading speeds
- Lyrics are fetched in the background, and can be cached and edited
- Offset settings in 10ms or 50ms increments
- Smart cleanup of YouTube subtitle lyrics to improve readability
- Ability to reorder provider priority

### Navigation and menus

- Adjusted bottom navigation so tab switching and re-tapping the active tab behave more naturally
- Fixed issues with the search bar, sorting, and list refreshes on the Folders screen
- Replaced the persistent search bar on tab screens with a top icon row (search, stats, and settings)
- Swipe-to-skip to the mini player

### Local music

- Improved tag reading, song linking, and gapless playback for local music
- Fixed the album song count shown on album screens
- Added a Local tab for browsing on-device songs, albums, artists, and playlists with filters and search

 ### Parametric equaliser
- 12-band parametric equaliser with adjustable frequencies
- Detailed band controls, including gain, Q-factor, and filter type
- Clipping and distortion protection on higher gain
- Bass and treble shelving 
- Compressor settings, including attack, release, compression ration, threshold and makeup gain
- Audio balance controls included in compressor
- Basic cross-fade
- Dynamically coloured equaliser surfaces, including frequency graphs and dials 
- Auto gain to compensate for lower volume (caused by the equaliser algorithm)
- AutoEQ support with an in-built oratory1990 database, and extra databases downloadable from the app
- Equaliser studio to tune custom equaliser profiles

 
### Display and settings

- Improved the tablet UI
- Automatically detects the system contrast setting on Android 14 and later
- Custom accent colours
- A "keep audio focus" player setting
- Added a home screen grid showing your recent YouTube Music activity when signed in
- Added the current queue's name to the player's queue handle
- Account icon is now the signed-in account's profile image
- Reorganised the settings screens, merging Appearance and Interface into "Appearance and controls" and adding a top-level Privacy screen
- Frosted Glass and Liquid player themes, along with a toggle for contrasting text for accessibility.
- Significantly revamped Liquid player theme by adding audio-reactive features
- Raymarched ferrofluid reactive audio renderer for more powerful devices
- Note: some features require Android 13+ support, and will be marked as such in settings
- Made the default English locale British English for better consistency
- Liquid glass is available on some surfaces, though it can be selectively turned off

### Downloads

- Wi-Fi-only download option
- Reduced fetch time for uncached songs
- Easy 1-click option to clear downloads
- Playlists improved to better show the status of downloading songs
- Notifications when downloads fail



### Auto updater

- Automatically searches for a new release on app startup (configurable)
- Can automatically download a new .apk file to update the app
- Beta releases can be excluded
- One-time permission required to install unknown apps using this app

### Cross-device syncing 

- A Google Firebase cloud database allows anyone to send syncs between authenticated devices
- Authentication requires typing in an 8-digit code into your host device
- Detailed setup instructions can be found in the [wiki](https://github.com/notK50BML/OuterTune/wiki/Setting-Up-Cross%E2%80%90Device-Sync)
- Sync only queries the database website, so the app is still 100% open source


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
> Version naming right now is a bit confusing. In order to clear up any questions, the naming convention is as follows: v0.xx is a stable release, and v0.xx.x is a stable fix. These fix releases usually fix bugs or layout problems, and add small features. A new stable release typically comprises one or more major feature changes, or large overhauls of the user interface. A v0.xx.xb release is a beta release, featuring the cutting edge of the app's development, though this release may have bugs. Though these beta releases are absolutely usable as your default player, some features may not feel as polished as they should be.

## Progress on Windows application
- Finished core framework and backend, so songs play properly
- Finished download pipeline
- Added working Google account sign-in integration
- Added Discord rich presence card, which is identical to the android version
- Replicated most major UI elements 
- Added a dedicated artist page
- Added a backup feature which lets you import data from your Android installation
- Added the new equaliser and compressor settings, along with most features
- Added settings page and most basic settings
- Added the ability to use any Windows-installed font

## What I'm Working On
- Improving equaliser to reduce bass distortion
- Improving UI
- Adding more settings
- Fixing some scaling and incorrect UI behaviour bugs
- Improving performance
- Animate more parts so the app feels smoother
- Bringing over more customisation settings from the Android app
- Significantly overhauling the ferrofluid visualiser (again) for desktops and people with dedicated GPUs
- Improving icon appearances to give the app a softer vibe

## Screenshots

Note: These screenshots are all from the original [AsterTune](https://github.com/yuuichi-s/AsterTune) repository. I will eventually create my own screenshots of my fork, but this isn't a high priority now.

[![Main player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/main-interface.jpg)

[![Player interface](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/player.jpg)

[![Sync with YouTube Music](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)](https://github.com/yuuichi-s/OuterTune/raw/dev/assets/ytm-sync.jpg)

[Full image gallery](https://github.com/yuuichi-s/OuterTune/tree/dev/assets/gallery)

> [!WARNING]
> If you're in a region where YouTube Music is not supported, you won't be able to use this app ***unless*** you have a proxy or VPN to connect to a YTM supported region.
> All offline features will work, and this app can still function as a normal offline playback app

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

[OuterTune](github.com/OuterTune/OuterTune) and [AsterTune by yuuichi-s](https://github.com/yuuichi-s/OuterTune) for almost all the main code, this is just a casual project to fix some small errors, but almost ALL credit goes to them.

Note: this builds on a few other Outertune forks, so a lot of credit goes to the OuterTune forks by [reocat](https://github.com/reocat/OuterTune) and of course [yuuichi-s](https://github.com/yuuichi-s/AsterTune)

[Metrolist](https://github.com/metrolistgroup/metrolist) for a really great framework of a stream and sync engine, without which this app would be 100% broken

[InterTune](https://github.com/ItzSkyeYT/InterTune) for great player styles, inspiration, and a few small snippets and fixes

[z-huang/InnerTune](https://github.com/z-huang/InnerTune) for providing an awesome base for this fork, none of this
would have been possible without it.

[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musicolet) for inspiration of a local music player
experience done right.

[Gramophone](https://github.com/FoedusProgramme/Gramophone) for emotional support, and a legendary lyrics parser

[Poweramp Equaliser](https://powerampapp.com/) for inspiration on how to make a good equaliser UI

[BetterLyrics](https://github.com/better-lyrics/better-lyrics) for their amazing word-by-word lyrics engine

[AutoEQ](https://github.com/jaakkopasanen/AutoEq) for the AutoEQ backend code

[Digital Sound Resolution Enhancer (DSRE)](https://github.com/Urabewe/DSRE---Digital-Sound-Resolution-Enhancer-English) for a lot of ideas and inspiration, not to mention some code

The original DSRE repository can be found [here](https://github.com/x1aoqv/DSRE---Digital-Sound-Resolution-Enhancer), but the content is largely in Chinese. The repository above is a fork of this one. Both projects have been extremely helpful in the creation of the High Frequency Restoration feature.

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any
way associated with YouTube, Google LLC or any of its affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project
are owned by the respective owners.

The creators of this app disclaim all responsibility for any damages resulting, directly or indirectly, from the use of this app. At best, you will be compensated with a picture of a cat. I still need a cat. 

