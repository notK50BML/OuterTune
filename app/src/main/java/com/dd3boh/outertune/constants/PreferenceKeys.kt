package com.dd3boh.outertune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Appearance
 */
val DynamicThemeKey = booleanPreferencesKey("dynamicTheme")
val CustomThemeKey = booleanPreferencesKey("customTheme")
val CustomThemeColorKey = intPreferencesKey("customThemeColor")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")

/**
 * Pick the player's text colour from the artwork's brightness instead of from the app theme.
 * Only consulted by the backgrounds that are drawn from the artwork, since it has nothing to
 * measure otherwise.
 */
val PlayerAutoTextContrastKey = booleanPreferencesKey("playerAutoTextContrast")

/** Drive the Liquid background's blobs from the actual audio instead of a fixed ambient drift. */
val LiquidAudioReactiveKey = booleanPreferencesKey("liquidAudioReactive")

/** What sits behind the Liquid style's colour blobs - a [com.dd3boh.outertune.constants.LiquidColorScheme]. */
val LiquidColorSchemeKey = stringPreferencesKey("liquidColorScheme")

/** The Liquid style's silhouette - a [com.dd3boh.outertune.constants.LiquidShapeStyle]. */
val LiquidShapeStyleKey = stringPreferencesKey("liquidShapeStyle")

/** Whether player/queue/lyrics text over the Liquid background follows its measured brightness. */
val LiquidTextContrastKey = booleanPreferencesKey("liquidTextContrast")

/** A real-time AGSL GPU shader ripple that launches on a beat, over whichever Liquid shape is
 *  drawn - only actually runs on Android 13+; a no-op below that regardless of this setting. */
val LiquidChromaticShockKey = booleanPreferencesKey("liquidChromaticShock")

/** Renders [com.dd3boh.outertune.constants.LiquidShapeStyle.FERROFLUID] as a raymarched AGSL GPU
 *  scene instead of the lightweight Canvas polygon - only actually runs on Android 13+, and is
 *  meaningfully heavier on the GPU/battery by design; opt-in and off by default for that reason. */
val LiquidFerrofluidGpuKey = booleanPreferencesKey("liquidFerrofluidGpu")

/**
 * Render quality for the GPU ferrofluid - a [com.dd3boh.outertune.ui.player.FerrofluidQuality]
 * name. Exists because the sensible pixel budget differs enormously between a phone on battery and
 * a WSA build on a desktop GPU, and no single fixed value serves both.
 */
val LiquidFerrofluidQualityKey = stringPreferencesKey("liquidFerrofluidQuality")

/**
 * How hard the audio drives the GPU ferrofluid - a
 * [com.dd3boh.outertune.ui.player.FerrofluidReactivity] name. Separate from quality because it is
 * a taste question, not a cost one: it changes how far the droplets travel for a given amount of
 * music, not how expensive a frame is to draw.
 */
val LiquidFerrofluidReactivityKey = stringPreferencesKey("liquidFerrofluidReactivity")

/** Show the app logo at the left of the home top bar. Off gives the search field the full width. */
val ShowTopBarLogoKey = booleanPreferencesKey("showTopBarLogo")
val RecognitionButtonPlacementKey = stringPreferencesKey("recognitionButtonPlacement")

/**
 * The imported player layout, stored as the raw JSON the editor produced.
 *
 * Kept as text rather than exploded into a preference per field so that a layout from a newer
 * editor survives a downgrade untouched, and so the user can see exactly what they imported.
 */
val PlayerLayoutKey = stringPreferencesKey("playerLayout")
val ShowQueueTitleKey = booleanPreferencesKey("showQueueTitle")
val DarkModeKey = stringPreferencesKey("darkMode")
val PureBlackKey = booleanPreferencesKey("pureBlack")
val ShowLikedAndDownloadedPlaylist = booleanPreferencesKey("showLikedAndDownloadedPlaylist")
val SwipeToQueueKey = booleanPreferencesKey("swipeToQueue")
val FlatSubfoldersKey = booleanPreferencesKey("flatSubfolders")
val TabletUiKey = booleanPreferencesKey("tabletUi")

val EnabledTabsKey = stringPreferencesKey("enabledTabs")
val EnabledFiltersKey = stringPreferencesKey("enabledFilters")
val DefaultOpenTabKey = stringPreferencesKey("defaultOpenTab")
val SlimNavBarKey = booleanPreferencesKey("slimNavBar")
val SliderStyleKey = stringPreferencesKey("sliderStyle")

/**
 * Content
 */
const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"
val YtmSyncKey = booleanPreferencesKey("ytmSync")
val YtmSyncContentKey = stringPreferencesKey("ytmSyncContent")
val YtmSyncModeKey = stringPreferencesKey("ytmSyncMode")
val YtmSyncConflictKey = stringPreferencesKey("ytmSyncConflict")
//val LikedAutoDownloadKey = stringPreferencesKey("likedAutoDownloadKey")
val ContentLanguageKey = stringPreferencesKey("contentLanguage")
val ContentCountryKey = stringPreferencesKey("contentCountry")
val ProxyEnabledKey = booleanPreferencesKey("proxyEnabled")
val ProxyUrlKey = stringPreferencesKey("proxyUrl")
val ProxyTypeKey = stringPreferencesKey("proxyType")

// sync time tracks
val LastFullSyncKey = longPreferencesKey("lastFullSync")
val LastLikeSongSyncKey = longPreferencesKey("lastLikeSongSync")
val LastLibSongSyncKey = longPreferencesKey("lastLibSongSync")
val LastAlbumSyncKey = longPreferencesKey("lastAlbumSync")
val LastArtistSyncKey = longPreferencesKey("lastArtistSync")
val LastPlaylistSyncKey = longPreferencesKey("lastPlaylistSync")
val LastRecentActivitySyncKey = longPreferencesKey("lastRecentActivitySync")


/**
 * Player & audio
 */
val AudioDecoderKey = intPreferencesKey("audioDecoder")
val AudioQualityKey = stringPreferencesKey("audioQuality")
val AudioOffloadKey = booleanPreferencesKey("enableOffload")
val AudioGaplessOffloadKey = booleanPreferencesKey("enableGaplessOffload")

val MaxQueuesKey = intPreferencesKey("maxQueues")
val ShowQueuesBesideCurrentKey = booleanPreferencesKey("showQueuesBesideCurrent")
val EqualizerSettingsKey = stringPreferencesKey("equalizerSettings")
val EqualizerProfilesKey = stringPreferencesKey("equalizerProfiles")
val ShowEqualizerButtonKey = booleanPreferencesKey("showEqualizerButton")
val ShowEqualizerHandleKey = booleanPreferencesKey("showEqualizerHandle")
/**
 * Dials vs sliders for the selected band's frequency/gain in the band editor - not the 12-band
 * gain strip above it or the bass/treble tone controls, both of which stay fixed (sliders and
 * dials respectively) regardless of this setting; see BandColumn's and ToneControlsRow's own docs
 * for why.
 */
val EqUseDialsKey = booleanPreferencesKey("eqUseDials")
/** Colors slider tracks/thumbs and dial arcs blue-green-yellow by how low/mid/high the value is. */
val EqValueColorGradientKey = booleanPreferencesKey("eqValueColorGradient")
val PersistentQueueKey = booleanPreferencesKey("persistentQueue")

val SeekIncrementKey = stringPreferencesKey("seekIncrement")
val SkipSilenceKey = booleanPreferencesKey("skipSilence")
val SkipOnErrorKey = booleanPreferencesKey("skipOnError")
val AudioNormalizationKey = booleanPreferencesKey("audioNormalization")
val IgnoreAudioFocusKey = booleanPreferencesKey("ignoreAudioFocus")
val AutoLoadMoreKey = booleanPreferencesKey("autoLoadMore")
val KeepAliveKey = booleanPreferencesKey("keepAlive")
val StopMusicOnTaskClearKey = booleanPreferencesKey("stopMusicOnTaskClear")

val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val RepeatModeKey = intPreferencesKey("repeatMode")
val LockQueueKey = booleanPreferencesKey("lockQueue")
val minPlaybackDurKey = intPreferencesKey("minPlaybackDur")

val SleepTimerFadeKey = booleanPreferencesKey("sleepTimerFade")
val SleepTimerFadeDurationKey = intPreferencesKey("sleepTimerFadeDuration")
val SleepTimerDefaultMinutesKey = intPreferencesKey("sleepTimerDefaultMinutes")
val SleepTimerShowOnPlayerKey = booleanPreferencesKey("sleepTimerShowOnPlayer")

val CrossfadeKey = booleanPreferencesKey("crossfade")
/** How long before a track ends its volume starts fading out, in seconds. */
val CrossfadeDurationKey = intPreferencesKey("crossfadeDuration")

/**
 * When an artist page has no "Songs" list section of its own, show its "Videos" section (if any)
 * laid out like a song list instead of the video grid/carousel it renders as by default.
 */
val ShowArtistVideosAsSongsKey = booleanPreferencesKey("showArtistVideosAsSongs")


/**
 * Lyrics
 */
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val ShowLyricsOnClickKey = booleanPreferencesKey("showLyricsOnClick")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val MultilineLrcKey = booleanPreferencesKey("multilineLrc")
val LyricTrimKey = booleanPreferencesKey("lyricTrim")
val LyricSourcePrefKey = booleanPreferencesKey("preferLocalLyrics")
val LyricFontSizeKey = intPreferencesKey("lyricFontSize")
val LyricClickable = booleanPreferencesKey("lyricClickable")
val LyricKaraokeEnable = booleanPreferencesKey("lyricKaraokeEnable")

/**
 * Beta: invent word timings for line-synced lyrics so the karaoke sweep works on ordinary LRC
 * files, instead of only the rare ones carrying real per-word timestamps. Off by default - it is a
 * guess, and a guess should be opted into. Has no effect unless [LyricKaraokeEnable] is on, since
 * it only supplies the timings that setting's sweep consumes.
 */
val LyricEstimatedWordSync = booleanPreferencesKey("lyricEstimatedWordSync")

/**
 * Milliseconds added to the playback position before deciding which lyric line is current.
 *
 * Positive shows lines earlier, negative later - which is the way round it needs to be, because the
 * complaint is always "the words are late" or "the words are early" about the *lyrics*, not about
 * the song. Corrects a badly timed lyric file rather than the playback, so it is stored per app
 * rather than per song: the same source tends to be off by a similar amount across a library, and a
 * per-song value would mean setting it again for every track.
 */
val LyricOffsetKey = intPreferencesKey("lyricOffset")

/**
 * Milliseconds a follower aims ahead of the host in a listen-together session.
 *
 * A correction the app cannot make for itself. Sync is measured between the two players' reported
 * positions, but neither of those is what anybody hears: each device puts sound out some time after
 * its player says so, and how long depends on the device, and on whether it is going through
 * speakers, wired headphones or Bluetooth - which alone can be a fifth of a second. So two devices
 * can agree perfectly on position and still be audibly apart, and nothing in the protocol can see
 * it. Positive values make this device run ahead, which is the usual direction: a follower on
 * Bluetooth is late.
 */
val ListenTogetherOffsetKey = intPreferencesKey("listenTogetherOffset")
val LyricUpdateSpeed = stringPreferencesKey("lyricUpdateSpeed")
val EnableLyricsPrefetchKey = booleanPreferencesKey("enableLyricsPrefetch")
val LyricsPrefetchCountKey = intPreferencesKey("lyricsPrefetchCount")

/**
 * Precaches the next song's stream URL shortly before the current one ends, instead of resolving it
 * at the track transition. Timed automatically (not a fixed delay): a resolved stream URL's PoToken
 * has been observed to go stale after roughly a minute regardless of when it's used, so this fires
 * only once the current song is close enough to ending that the precached URL will still be fresh
 * when playback actually reaches it. Naturally spreads resolution timing across the whole queue
 * instead of clustering every request at each transition. On by default: at most one extra request
 * per song, timed to land within the freshness window it needs to.
 */
val EnableStreamPrecacheKey = booleanPreferencesKey("enableStreamPrecache")

/**
 * How the remote lyric providers are consulted, and in what order.
 *
 * The order is a comma-joined list of provider ids. Ids that are not recognised are ignored and
 * providers missing from the list are appended in their built-in order, so the preference survives
 * a provider being added, renamed or removed. Empty means "the built-in order".
 */
val LyricsFetchModeKey = stringPreferencesKey("lyricsFetchMode")
val LyricsProviderOrderKey = stringPreferencesKey("lyricsProviderOrder")


/**
 * Storage
 */
val DownloadExtraPathKey = stringPreferencesKey("dlExtraPath") // previously "downloadExtraPath"
val DownloadPathKey = stringPreferencesKey("dlPath") // previously "downloadPath"
val DownloadOnWifiOnlyKey = booleanPreferencesKey("downloadOnWifiOnly")
val MaxImageCacheSizeKey = intPreferencesKey("maxImageCacheSize")
val MaxSongCacheSizeKey = intPreferencesKey("maxSongCacheSize")

/**
 * When a full-size artwork request fails to load, fall back to a lower-resolution one instead of
 * showing nothing - the CDN occasionally rejects a very large size (or times out on it) for art
 * that loads fine smaller.
 *
 * This is a fallback for a failure, not a preference about quality: asking for artwork at the size
 * it will be drawn at is unconditional now (see [com.dd3boh.outertune.utils.remoteArtwork]).
 */
val ArtworkFallbackToLowResKey = booleanPreferencesKey("artworkFallbackToLowRes")

/**
 * Downloads a song's full-resolution thumbnail into app-managed storage alongside its audio, kept
 * independently of the audio download (see DownloadUtil.downloadThumbnail/removeThumbnail) so it
 * can be fetched or removed on its own without touching the downloaded song itself.
 */
val DownloadThumbnailsKey = booleanPreferencesKey("downloadThumbnails")

/**
 * Automatic backup - writes into Downloads/OuterTune Backup on its own, on a schedule, with no
 * folder to pick: internal app storage (everything the manual Backup action copies) is wiped on
 * uninstall, so this is meant to land somewhere that survives that, without a SAF picker dialog
 * standing in the way of it actually running unattended. See [com.dd3boh.outertune.utils.writeAutoBackup].
 */
val AutoBackupEnabledKey = booleanPreferencesKey("autoBackupEnabled")

/** How often to write a new automatic backup - a count of [AutoBackupIntervalUnitKey]. */
val AutoBackupIntervalValueKey = intPreferencesKey("autoBackupIntervalValue")
/** The unit [AutoBackupIntervalValueKey] counts - a [com.dd3boh.outertune.constants.BackupIntervalUnit] name. */
val AutoBackupIntervalUnitKey = stringPreferencesKey("autoBackupIntervalUnit")

/** How many automatic backups to keep before deleting the oldest. */
val AutoBackupKeepCountKey = intPreferencesKey("autoBackupKeepCount")

/** Epoch millis of the last successful automatic backup - null/unset means one has never run. */
val LastAutoBackupKey = longPreferencesKey("lastAutoBackup")

/**
 * Discord Integration
 */
val DiscordTokenKey = stringPreferencesKey("discordToken")
val DiscordInfoDismissedKey = booleanPreferencesKey("discordInfoDismissed_v2")
val DiscordUsernameKey = stringPreferencesKey("discordUsername")
val DiscordNameKey = stringPreferencesKey("discordName")
val EnableDiscordRPCKey = booleanPreferencesKey("discordRPCEnable")


/**
 * Privacy
 */
val PauseListenHistoryKey = booleanPreferencesKey("pauseListenHistory")
val PauseRemoteListenHistoryKey = booleanPreferencesKey("pauseRemoteListenHistory")
val PauseSearchHistoryKey = booleanPreferencesKey("pauseSearchHistory")
val EnableKugouKey = booleanPreferencesKey("enableKugou")
val EnableLrcLibKey = booleanPreferencesKey("enableLrcLib")
val EnableBetterLyricsKey = booleanPreferencesKey("enableBetterLyrics")
val EnableSimpMusicKey = booleanPreferencesKey("enableSimpMusic")
val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")


/**
 * Local library
 */
val LocalLibraryEnableKey = booleanPreferencesKey("localLibraryEnable")


/**
 * Local media scanner
 */
val AutomaticScannerKey = booleanPreferencesKey("autoLocalScanner")
val ScannerSensitivityKey = stringPreferencesKey("scannerSensitivity")
val ScannerImplKey = stringPreferencesKey("scannerImpl")
val ScannerStrictFilePathsKey = booleanPreferencesKey("scannerStrictFilePaths")
val ScannerStrictExtKey = booleanPreferencesKey("scannerStrictExt")
//val LookupYtmArtistsKey = booleanPreferencesKey("lookupYtmArtists") // removed key

val ScanPathsKey = stringPreferencesKey("inclScanPaths") // previously "scanPaths"
val ExcludedScanPathsKey = stringPreferencesKey("exclScanPaths") // previously "excludedScanPaths"
val LastLocalScanKey = longPreferencesKey("lastLocalScan")

/**
 * Experimental settings
 */
val DevSettingsKey = booleanPreferencesKey("devSettings")
val OobeStatusKey = intPreferencesKey("oobeStatus")
val SwipeToSkipKey = booleanPreferencesKey("swipeToSkip")


/**
 * Non-settings UI preferences
 */
val SongSortTypeKey = stringPreferencesKey("songSortType")
val SongSortDescendingKey = booleanPreferencesKey("songSortDescending")
val FolderSortTypeKey = stringPreferencesKey("folderSortType")
val FolderSongSortTypeKey = stringPreferencesKey("folderSongSortType")
val FolderSongSortDescendingKey = booleanPreferencesKey("folderSongSortDescending")
val PlaylistSongSortTypeKey = stringPreferencesKey("playlistSongSortType")
val PlaylistSongSortDescendingKey = booleanPreferencesKey("playlistSongSortDescending")
val ArtistSortTypeKey = stringPreferencesKey("artistSortType")
val ArtistSortDescendingKey = booleanPreferencesKey("artistSortDescending")
val AlbumSortTypeKey = stringPreferencesKey("albumSortType")
val AlbumSortDescendingKey = booleanPreferencesKey("albumSortDescending")
val PlaylistSortTypeKey = stringPreferencesKey("playlistSortType")
val PlaylistSortDescendingKey = booleanPreferencesKey("playlistSortDescending")
val LibrarySortTypeKey = stringPreferencesKey("librarySortType")
val LibrarySortDescendingKey = booleanPreferencesKey("librarySortDescending")
val ArtistSongSortTypeKey = stringPreferencesKey("artistSongSortType")
val ArtistSongSortDescendingKey = booleanPreferencesKey("artistSongSortDescending")

val SongFilterKey = stringPreferencesKey("songFilter")
val ArtistFilterKey = stringPreferencesKey("artistFilter")
val ArtistViewTypeKey = stringPreferencesKey("artistViewType")
val AlbumFilterKey = stringPreferencesKey("albumFilter")
val PlaylistFilterKey = stringPreferencesKey("playlistFilter")
val AlbumViewTypeKey = stringPreferencesKey("albumViewType")
val PlaylistViewTypeKey = stringPreferencesKey("playlistViewType")
val LibraryFilterKey = stringPreferencesKey("libraryFilter")
val LibraryViewTypeKey = stringPreferencesKey("libraryViewType")

val LocalFilterKey = stringPreferencesKey("localFilter")
val LocalViewTypeKey = stringPreferencesKey("localViewType")
val LocalSongSortTypeKey = stringPreferencesKey("localSongSortType")
val LocalSongSortDescendingKey = booleanPreferencesKey("localSongSortDescending")
val LocalAlbumSortTypeKey = stringPreferencesKey("localAlbumSortType")
val LocalAlbumSortDescendingKey = booleanPreferencesKey("localAlbumSortDescending")
val LocalArtistSortTypeKey = stringPreferencesKey("localArtistSortType")
val LocalArtistSortDescendingKey = booleanPreferencesKey("localArtistSortDescending")
val LocalPlaylistSortTypeKey = stringPreferencesKey("localPlaylistSortType")
val LocalPlaylistSortDescendingKey = booleanPreferencesKey("localPlaylistSortDescending")

val PlaylistEditLockKey = booleanPreferencesKey("playlistEditLock")

val SearchSourceKey = stringPreferencesKey("searchSource")

val VisitorDataKey = stringPreferencesKey("visitorData")
val DataSyncIdKey = stringPreferencesKey("dataSyncId")
val InnerTubeCookieKey = stringPreferencesKey("innerTubeCookie")
val AccountNameKey = stringPreferencesKey("accountName")
val AccountEmailKey = stringPreferencesKey("accountEmail")
val AccountChannelHandleKey = stringPreferencesKey("accountChannelHandle")
val AccountImageUrlKey = stringPreferencesKey("accountImageUrl")
val AccountImageFetchedKey = booleanPreferencesKey("accountImageFetched")


/**
 * Misc
 */
val LastUpdateCheckKey = longPreferencesKey("lastUpdateCheck")
val LastVersionKey = stringPreferencesKey("lastVersion")
val UpdateAvailableKey = booleanPreferencesKey("updateAvailable")

val LanguageCodeToName = mapOf(
    "af" to "Afrikaans",
    "az" to "Azərbaycan",
    "id" to "Bahasa Indonesia",
    "ms" to "Bahasa Malaysia",
    "ca" to "Català",
    "cs" to "Čeština",
    "da" to "Dansk",
    "de" to "Deutsch",
    "et" to "Eesti",
    "en-GB" to "English (UK)",
    "en" to "English (US)",
    "es" to "Español (España)",
    "es-419" to "Español (Latinoamérica)",
    "eu" to "Euskara",
    "fil" to "Filipino",
    "fr" to "Français",
    "fr-CA" to "Français (Canada)",
    "gl" to "Galego",
    "hr" to "Hrvatski",
    "zu" to "IsiZulu",
    "is" to "Íslenska",
    "it" to "Italiano",
    "sw" to "Kiswahili",
    "lt" to "Lietuvių",
    "hu" to "Magyar",
    "nl" to "Nederlands",
    "no" to "Norsk",
    "or" to "Odia",
    "uz" to "O‘zbe",
    "pl" to "Polski",
    "pt-PT" to "Português",
    "pt" to "Português (Brasil)",
    "ro" to "Română",
    "sq" to "Shqip",
    "sk" to "Slovenčina",
    "sl" to "Slovenščina",
    "fi" to "Suomi",
    "sv" to "Svenska",
    "bo" to "Tibetan བོད་སྐད།",
    "vi" to "Tiếng Việt",
    "tr" to "Türkçe",
    "bg" to "Български",
    "ky" to "Кыргызча",
    "kk" to "Қазақ Тілі",
    "mk" to "Македонски",
    "mn" to "Монгол",
    "ru" to "Русский",
    "sr" to "Српски",
    "uk" to "Українська",
    "el" to "Ελληνικά",
    "hy" to "Հայերեն",
    "iw" to "עברית",
    "ur" to "اردو",
    "ar" to "العربية",
    "fa" to "فارسی",
    "ne" to "नेपाली",
    "mr" to "मराठी",
    "hi" to "हिन्दी",
    "bn" to "বাংলা",
    "pa" to "ਪੰਜਾਬੀ",
    "gu" to "ગુજરાતી",
    "ta" to "தமிழ்",
    "te" to "తెలుగు",
    "kn" to "ಕನ್ನಡ",
    "ml" to "മലയാളം",
    "si" to "සිංහල",
    "th" to "ภาษาไทย",
    "lo" to "ລາວ",
    "my" to "ဗမာ",
    "ka" to "ქართული",
    "am" to "አማርኛ",
    "km" to "ខ្មែរ",
    "zh-CN" to "中文 (简体)",
    "zh-TW" to "中文 (繁體)",
    "zh-HK" to "中文 (香港)",
    "ja" to "日本語",
    "ko" to "한국어",
)

val CountryCodeToName = mapOf(
    "DZ" to "Algeria",
    "AR" to "Argentina",
    "AU" to "Australia",
    "AT" to "Austria",
    "AZ" to "Azerbaijan",
    "BH" to "Bahrain",
    "BD" to "Bangladesh",
    "BY" to "Belarus",
    "BE" to "Belgium",
    "BO" to "Bolivia",
    "BA" to "Bosnia and Herzegovina",
    "BR" to "Brazil",
    "BG" to "Bulgaria",
    "KH" to "Cambodia",
    "CA" to "Canada",
    "CL" to "Chile",
    "HK" to "Hong Kong",
    "CO" to "Colombia",
    "CR" to "Costa Rica",
    "HR" to "Croatia",
    "CY" to "Cyprus",
    "CZ" to "Czech Republic",
    "DK" to "Denmark",
    "DO" to "Dominican Republic",
    "EC" to "Ecuador",
    "EG" to "Egypt",
    "SV" to "El Salvador",
    "EE" to "Estonia",
    "FI" to "Finland",
    "FR" to "France",
    "GE" to "Georgia",
    "DE" to "Germany",
    "GH" to "Ghana",
    "GR" to "Greece",
    "GT" to "Guatemala",
    "HN" to "Honduras",
    "HU" to "Hungary",
    "IS" to "Iceland",
    "IN" to "India",
    "ID" to "Indonesia",
    "IQ" to "Iraq",
    "IE" to "Ireland",
    "IL" to "Israel",
    "IT" to "Italy",
    "JM" to "Jamaica",
    "JP" to "Japan",
    "JO" to "Jordan",
    "KZ" to "Kazakhstan",
    "KE" to "Kenya",
    "KR" to "South Korea",
    "KW" to "Kuwait",
    "LA" to "Lao",
    "LV" to "Latvia",
    "LB" to "Lebanon",
    "LY" to "Libya",
    "LI" to "Liechtenstein",
    "LT" to "Lithuania",
    "LU" to "Luxembourg",
    "MK" to "Macedonia",
    "MY" to "Malaysia",
    "MT" to "Malta",
    "MX" to "Mexico",
    "ME" to "Montenegro",
    "MA" to "Morocco",
    "NP" to "Nepal",
    "NL" to "Netherlands",
    "NZ" to "New Zealand",
    "NI" to "Nicaragua",
    "NG" to "Nigeria",
    "NO" to "Norway",
    "OM" to "Oman",
    "PK" to "Pakistan",
    "PA" to "Panama",
    "PG" to "Papua New Guinea",
    "PY" to "Paraguay",
    "PE" to "Peru",
    "PH" to "Philippines",
    "PL" to "Poland",
    "PT" to "Portugal",
    "PR" to "Puerto Rico",
    "QA" to "Qatar",
    "RO" to "Romania",
    "RU" to "Russian Federation",
    "SA" to "Saudi Arabia",
    "SN" to "Senegal",
    "RS" to "Serbia",
    "SG" to "Singapore",
    "SK" to "Slovakia",
    "SI" to "Slovenia",
    "ZA" to "South Africa",
    "ES" to "Spain",
    "LK" to "Sri Lanka",
    "SE" to "Sweden",
    "CH" to "Switzerland",
    "TW" to "Taiwan",
    "TZ" to "Tanzania",
    "TH" to "Thailand",
    "TN" to "Tunisia",
    "TR" to "Turkey",
    "UG" to "Uganda",
    "UA" to "Ukraine",
    "AE" to "United Arab Emirates",
    "GB" to "United Kingdom",
    "US" to "United States",
    "UY" to "Uruguay",
    "VE" to "Venezuela (Bolivarian Republic)",
    "VN" to "Vietnam",
    "YE" to "Yemen",
    "ZW" to "Zimbabwe",
)

// Updater
val AutoCheckUpdatesKey = booleanPreferencesKey("autoCheckUpdates")
val AutoDownloadUpdatesKey = booleanPreferencesKey("autoDownloadUpdates")
/** Which build flavour to pull, since only a matching one can replace this install. */
val UpdateFlavorKey = stringPreferencesKey("updateFlavor")

/** Whether Stats counts plays from this account's other devices, read from YouTube's own history. */
val StatsIncludeRemoteKey = booleanPreferencesKey("statsIncludeRemote")
