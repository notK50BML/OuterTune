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
val EqSliderStyleKey = stringPreferencesKey("eqSliderStyle")
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
val LyricUpdateSpeed = stringPreferencesKey("lyricUpdateSpeed")
val EnableLyricsPrefetchKey = booleanPreferencesKey("enableLyricsPrefetch")
val LyricsPrefetchCountKey = intPreferencesKey("lyricsPrefetchCount")

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
 * When true, remote artwork URLs are rewritten to request an image at least as large as the
 * view that will display it. YouTube hands out small thumbnails (often 60-226px) in browse and
 * library responses; using them verbatim leaves album covers visibly soft on a full-width
 * player. When false, URLs are used exactly as returned, which is what OuterTune did before.
 */
val HighResArtworkKey = booleanPreferencesKey("highResArtwork")

/**
 * Discord Integration
 */
val DiscordTokenKey = stringPreferencesKey("discordToken")
val DiscordInfoDismissedKey = booleanPreferencesKey("discordInfoDismissed_v2")
val DiscordUsernameKey = stringPreferencesKey("discordUsername")
val DiscordNameKey = stringPreferencesKey("discordName")
val EnableDiscordRPCKey = booleanPreferencesKey("discordRPCEnable")
val DiscordRPCClearAfterMinutesKey = intPreferencesKey("discordRPCClearAfterMinutes")


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
