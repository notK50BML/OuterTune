/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Where settings are kept.
 *
 * An interface with two methods rather than a direct dependency on [Database], so the settings
 * themselves can be tested without a file on disk - and so that where they live can change later
 * without touching every setting.
 */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Everything the desktop app lets someone change.
 *
 * Each setting is a Compose state backed by the store: reading one inside a composable subscribes to
 * it, and writing one both updates the screen and persists it. That is the whole reason for the
 * delegates below - the alternative is every setting appearing in three places (a default, a load, a
 * save), which is three places for them to disagree.
 *
 * Defaults are the behaviour the app had before there was a settings screen, so installing an update
 * changes nothing until something is deliberately turned on.
 */
class Settings(private val store: SettingsStore) {

    /** Every setting's key, and how to re-read it from the store - for profiles (see Profiles). */
    private val reloaders = LinkedHashMap<String, () -> Unit>()

    /** The keys of every setting, in declaration order. */
    val keys: Set<String> get() = reloaders.keys

    /** The stored text of a setting, as a profile carries it. */
    fun raw(key: String): String? = store.get(key)

    /** Writes a setting's stored text and updates what is on screen. Unknown keys are ignored. */
    fun putRaw(key: String, value: String) {
        val reload = reloaders[key] ?: return
        store.put(key, value)
        reload()
    }


    // ---- Playback --------------------------------------------------------------------------

    /** The five-second seek buttons in the transport row. Off, as on Android. */
    var showSeekButtons by bool("player.seekButtons", false)

    /**
     * The spectrum bars on the player.
     *
     * Off. Between the credits and the seek bar they read as something the player was doing rather
     * than something the song was, and they pushed the controls down the column to do it.
     */
    var showVisualizer by bool("player.visualizer", false)

    /** The title-row buttons, in order, as [PlayerButtonKind] codes. Empty means the original set. */
    var playerButtons by string("player.buttons", "")

    /** Account and sync - the phone's Settings > Account and sync, option for option. */
    var syncAuto by bool("sync.auto", true)
    /** What a sync brings in, in the phone's letters - see [SyncKind]. */
    var syncContent by string("sync.content", SyncKind.ALL)
    var syncMode by enum("sync.mode", SyncMode.ReadWrite, SyncMode.entries)
    var syncConflict by enum("sync.conflict", SyncConflict.AddOnly, SyncConflict.entries)
    /** Browse signed in - personal recommendations, premium-only albums - or signed out. */
    var useLoginForBrowse by bool("account.loginForBrowse", true)

    /** Quick buttons beside the ⋮ on song rows, and whether the ⋮ shows - see RowButtons. */
    var rowButtons by string("ui.rowButtons", "")
    var rowMenu by bool("ui.rowMenu", true)

    /** An imported Windows player layout (see PlayerLayout), as its file's text. Empty: built-in. */
    var playerLayout by string("player.layout", "")

    // ---- From the phone's Player and audio settings ---------------------------------------------

    /** Keeps the queue, and where in it playback was, between sessions. */
    var persistentQueue by bool("player.persistentQueue", true)

    /** The saved queue - see QueueSnapshot. */
    var savedQueue by string("player.savedQueue", "")

    /** The queues kept aside (see PlayerQueue.saved), as a JSON array of queue snapshots. */
    var savedQueues by string("player.savedQueues", "")

    /** How many queues are kept aside - the phone's max queues. */
    var maxQueues by int("player.maxQueues", 19)

    /** Evens out volume between tracks using YouTube's loudness figure. Only ever turns down. */
    var audioNormalisation by bool("player.normalise", false)

    /** Moves on to the next song when one cannot be played, rather than stopping. */
    var skipOnError by bool("player.skipOnError", true)

    /** How much of a song must be heard before it counts as played - for history and Stats. */
    var minPlayPercent by int("player.minPlayPercent", 30)

    /** Sleep timer: fade the music out over its last half minute rather than cutting it. */
    var sleepFade by bool("sleep.fade", true)

    /** How long that fade lasts, in seconds. The phone's default is 30 and so is this. */
    var sleepFadeSeconds by int("sleep.fadeSeconds", 30)

    /** Which AAC bitrate the stream fetch asks for - see [AudioQuality]. */
    var audioQuality by enum("player.audioQuality", AudioQuality.Auto, AudioQuality.entries)

    /**
     * How far the seek buttons move, in seconds.
     *
     * The buttons themselves are [showSeekButtons]; this is only their size. The phone folds the
     * two into one setting where "off" hides them, but the two controls already exist here and
     * collapsing them would quietly change what the existing switch means.
     */
    var seekIncrementSeconds by int("player.seekIncrement", 5)

    /** What the sleep timer offers first, in minutes. */
    var sleepDefaultMinutes by int("sleep.defaultMinutes", 30)

    // ---- Content -------------------------------------------------------------------------------

    /** YouTube's content language and country - what Home, search and charts are made for. */
    var contentLanguage by string("content.language", "en")
    var contentCountry by string("content.country", "US")

    // ---- Lyrics providers ------------------------------------------------------------------------

    var lyricsLrclib by bool("lyrics.lrclib", true)
    var lyricsKugou by bool("lyrics.kugou", true)

    /** Whether the queue handle names the queue beside its arrow, as the phone's does. */
    var showQueueTitle by bool("player.queueTitle", true)

    /** Keyboard shortcuts that differ from the defaults - see KeyBindings. */
    var keyBindings by string("keys.bindings", "")

    /** Whether the queue is docked down the right of the main window. */
    var sideQueueOpen by bool("window.sideQueue", true)

    /**
     * Whether to ask BetterLyrics for word-by-word timings first.
     *
     * On. Word timings are strictly more than line timings - a word-timed file can always be shown
     * as lines, and the reverse is not true. It is a setting because it is the one provider that
     * regularly refuses: its API answers 401 for songs it has not already cached, so on an unlucky
     * run it costs a request per song and returns nothing.
     */
    var wordByWordLyrics by bool("lyrics.wordByWord", true)

    /** Whether clicking the cover swaps it for the lyrics. */
    var lyricsOnCoverClick by bool("lyrics.coverClick", true)

    /**
     * Shifts every lyric line, in milliseconds.
     *
     * Positive makes them appear later. Files timed against a different master of the same song are
     * common, and this is the one control that fixes them.
     */
    var lyricsOffsetMs by int("lyrics.offsetMs", 0)

    // ---- Appearance ------------------------------------------------------------------------

    /**
     * Whether controls take their colour from their own value - yellow low, green middle, blue high.
     *
     * On. It is what makes a row of bands readable as a shape rather than as twelve thumbs to trace,
     * but it is also a strong look, so it is the first thing worth being able to turn off.
     */
    var colourByValue by bool("ui.colourByValue", true)

    /**
     * Whether the whole window takes its colours from the cover of what is playing.
     *
     * On. It is the desktop's answer to Material You - Android seeds from the wallpaper, which the
     * system hands over; a desktop has no equivalent, but it does have a large picture on screen
     * that the person chose. Off, the app keeps one fixed scheme.
     */
    var dynamicTheme by bool("ui.dynamicTheme", true)

    /** Light, dark, or whatever the desktop is set to. */
    var themeMode by enum("ui.themeMode", ThemeMode.System, ThemeMode.entries)

    /** How the player's background is drawn behind the cover's colours. */
    var background by enum("ui.background", BackgroundStyle.Gradient, BackgroundStyle.entries)

    /** How hard the music drives the ferrofluid background. */
    var ferroReactivity by enum("ui.ferroReactivity", FerroReactivity.Normal, FerroReactivity.entries)

    /** The player's seek bar - the phone's three styles, squiggly being its default too. */
    var sliderStyle by enum("ui.sliderStyle", SeekBarStyle.Squiggly, SeekBarStyle.entries)

    /** What scrolling over the player does - see [PlayerScroll]. */
    var playerScroll by enum("player.scroll", PlayerScroll.Anywhere, PlayerScroll.entries)

    /**
     * The typeface the whole app is set in, by family name as the system knows it. Empty is the
     * built-in default. Any font installed on the machine can be chosen - see [SystemFonts].
     */
    var fontFamily by string("ui.font", "")

    /** Text size across the app, in percent. */
    var textScale by int("ui.textScale", 100)

    /** True black behind everything in dark mode, as the phone's "pure black" does. */
    var pureBlack by bool("ui.pureBlack", false)

    /** Which section the window opens on. */
    var startTab by enum("ui.startTab", NavTab.Home, NavTab.entries.filter { it != NavTab.Settings })

    /** Which sections the rail shows, in order - the phone's enabled tabs. See [NavTab.decode]. */
    var tabs by string("ui.tabs", NavTab.DEFAULT)

    // ---- Content ---------------------------------------------------------------------------

    /** Leaves explicit songs out of search results, Home and artist pages. */
    var hideExplicit by bool("content.hideExplicit", false)

    /** Recent searches, newest first, one per line. Shown under the search box while it is empty. */
    var searchHistory by string("search.history", "")

    /** Folders on this computer whose music is in the library, one path per line. */
    var localFolders by string("local.folders", "")

    /** Whether local files are looked at all. Off means the folders below are simply not read. */
    var localEnabled by bool("local.enabled", true)

    /**
     * Whether the folders are rescanned every time the app starts.
     *
     * On by default, which is what it did before this setting existed - and unconditionally, which
     * is the problem it solves: a large local library made every launch pay for a full scan with no
     * way to decline. Off still leaves the manual rescan.
     */
    var localScanOnStart by bool("local.scanOnStart", true)

        /** Whether searches are remembered at all. */
    var keepSearchHistory by bool("search.keepHistory", true)

    // ---- Lyrics (display) ------------------------------------------------------------------

    /** Lyrics text size on the player, in percent. */
    var lyricsTextScale by int("lyrics.textScale", 100)

    /** Where lyric lines sit: centred, as the phone defaults to, or ranged left. */
    var lyricsAlignment by enum("lyrics.align", LyricsAlignment.Centre, LyricsAlignment.entries)

    /**
     * Whether lines without word timings are still sung word by word, with the timings estimated
     * from the line's length and its punctuation - see [EstimatedWordSync].
     */
    var estimateWordTimings by bool("lyrics.estimateWords", true)

    // ---- Equaliser -------------------------------------------------------------------------

    /**
     * The equaliser, and what it is set to.
     *
     * Stored as four plain strings rather than a structure, because that is what the store holds -
     * see [EqProfiles] for the codec and for why the curve is written as bands rather than as a row
     * of gains.
     *
     * None of this existed before: closing the window discarded the switch, the curve, and any
     * AutoEQ correction that had been fetched for a specific pair of headphones - which is minutes
     * of work to redo and the one curve nobody could reproduce by ear.
     */
    var eqEnabled by bool("eq.enabled", false)

    /** The current curve. Empty means the flat default, which is what a fresh install has. */
    var eqBands by string("eq.bands", "")

    /** Which profile name is highlighted. Empty when the curve belongs to none of them. */
    var eqProfile by string("eq.profile", "")

    /** Saved overrides and user-created profiles - see [EqProfiles.encode]. */
    var eqProfiles by string("eq.profiles", "")

    /** The compressor's dials. Empty until one of them is moved. */
    var eqCompressor by string("eq.compressor", "")

    /** Left/right balance, -1 to 1. Centre, which is where anyone who has not moved it wants it. */
    var eqBalance by float("eq.balance", 0f)

    /**
     * Whether the live spectrum is drawn behind the equaliser's response curve.
     *
     * On. It is the one thing that turns the curve from a picture of twelve numbers into something
     * you can actually equalise against - a cut at 3kHz means nothing on its own and means a great
     * deal when 3kHz is visibly where the energy is. A setting because it is a per-frame redraw of
     * the panel, which is worth declining on a slow machine.
     */
    var eqSpectrum by bool("eq.spectrum", true)

    /**
     * The four above, as the panel wants them.
     *
     * The conversion lives here rather than in the panel so that the panel depends on an interface
     * it can be handed a fake of, and so the encoding is applied in exactly one place.
     */
    fun equalizerStore(): EqStore = object : EqStore {
        override var enabled: Boolean
            get() = eqEnabled
            set(value) { eqEnabled = value }

        override var bands: List<EqBand>
            get() = EqProfiles.decodeBands(eqBands) ?: Equalizer.DEFAULT_BANDS
            set(value) { eqBands = EqProfiles.encodeBands(value) }

        override var activeProfile: String?
            get() = eqProfile.takeIf { it.isNotBlank() }
            set(value) { eqProfile = value.orEmpty() }

        override var profiles: List<EqProfile>
            get() = EqProfiles.decode(eqProfiles)
            set(value) { eqProfiles = EqProfiles.encode(value) }

        override var compressor: CompressorPrefs?
            get() = EqProfiles.decodeCompressor(eqCompressor)
            set(value) { eqCompressor = value?.let(EqProfiles::encodeCompressor).orEmpty() }

        override var balance: Float
            get() = eqBalance
            set(value) { eqBalance = value }
    }

    // ---- Library ---------------------------------------------------------------------------

    /**
     * Whether a backup is taken on a schedule - see [AutoBackup].
     *
     * On by default. Everything this app knows is in one file, and the cost of a copy beside it is
     * a few megabytes; the cost of not having one is the library.
     */
    var autoBackup by bool("backup.auto", true)

    /** How often, in hours. */
    var autoBackupHours by int("backup.autoHours", 24)

    /** How many to keep before the oldest is deleted. */
    var autoBackupKeep by int("backup.autoKeep", 5)

    /** Whether played songs are recorded at all. */
    var keepHistory by bool("library.history", true)

    /**
     * Whether a play is also reported to the signed-in YouTube Music account.
     *
     * Off by default, which is the one place this deliberately does not follow the phone. There the
     * setting is a *pause* switch and reporting is on, because the phone has always done it; this
     * app never has, so defaulting it on would mean an update quietly starting to write to
     * somebody's YouTube history. That is a thing to be asked for, not inherited.
     *
     * Only meaningful while signed in, and the settings screen hides it otherwise - see
     * [AccountState.SignedIn]. It also rides on [keepHistory]: a play that is not recorded here is
     * not reported there either, matching the phone, where the remote switch sits inside the local
     * one's block.
     */
    var shareHistoryWithYtm by bool("library.historyYtm", false)

    // ---- Network ------------------------------------------------------------------------------

    /** Whether API requests go through a proxy. */
    var proxyEnabled by bool("net.proxyEnabled", false)

    /** The proxy as host:port. Only read when [proxyEnabled]. */
    var proxyUrl by string("net.proxyUrl", "")

    /** HTTP or SOCKS. */
    var proxyType by enum("net.proxyType", ProxyKind.Http, ProxyKind.entries)

    // ---- Discord rich presence ---------------------------------------------------------------

    /**
     * The Discord user token the gateway connection authenticates with.
     *
     * Same mechanism the phone uses: this drives a real account's presence over the same gateway a
     * Discord client itself connects to, rather than anything Discord's developer portal issues -
     * there is no legitimate bot/OAuth path to *someone's own* "currently listening to" card.
     */
    var discordToken by string("discord.token", "")

    /** Filled in once, from [com.my.kizzy.rpc.KizzyRPC.getUserInfo], so the settings screen can
     * show who is signed in instead of just "a token is set". */
    var discordUsername by string("discord.username", "")
    var discordDisplayName by string("discord.displayName", "")

    /** Whether to actually publish presence updates, independent of whether a token is stored. */
    var enableDiscordRpc by bool("discord.enabled", true)

    // ---- Listen Together --------------------------------------------------------------------

    /**
     * How far ahead of the host a follower should aim, in milliseconds.
     *
     * The app cannot measure this itself - it only ever compares two players' reported positions,
     * and neither of those is what is actually audible once output latency (a Bluetooth link alone
     * can add a fifth of a second) is accounted for. Set by ear, and applied live so it can be.
     */
    var listenTogetherOffsetMs by int("listentogether.offsetMs", 0)

    // ------------------------------------------------------------------------------------------

    private fun bool(key: String, default: Boolean) =
        setting(key, default, { it.toBooleanStrictOrNull() }, { it.toString() })

    private fun string(key: String, default: String) =
        setting(key, default, { it }, { it })

    private fun int(key: String, default: Int) =
        setting(key, default, { it.toIntOrNull() }, { it.toString() })

    // Finiteness is checked here rather than left to the caller: a stored NaN would propagate into
    // a gain and silence a channel, and the fallback to the default is exactly the right answer.
    private fun float(key: String, default: Float) =
        setting(key, default, { it.toFloatOrNull()?.takeIf(Float::isFinite) }, { it.toString() })

    private fun <T : Enum<T>> enum(key: String, default: T, values: List<T>) =
        setting(key, default, { stored -> values.firstOrNull { it.name == stored } }, { it.name })

    /**
     * One setting, read once and then held in memory.
     *
     * A value that fails to parse falls back to the default rather than throwing. Settings are
     * written by this app and only this app, so a bad one means something already went wrong - and
     * refusing to start because a stored string is not a number would turn a cosmetic preference
     * into a broken install.
     */
    private fun <T> setting(
        key: String,
        default: T,
        parse: (String) -> T?,
        encode: (T) -> String,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> =
            mutableStateOf(store.get(key)?.let(parse) ?: default)

        init {
            reloaders[key] = { state.value = store.get(key)?.let(parse) ?: default }
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            store.put(key, encode(value))
        }
    }
}

/**
 * What sits behind the player.
 *
 * The cover's own colours drive all of these; what differs is how they are laid down. Frosted glass
 * and the blurred cover were asked for by name.
 */
enum class BackgroundStyle(val label: String, val description: String) {
    Gradient("Gradient", "Two colours sampled from the cover, top to bottom"),
    Solid("Solid", "One colour from the cover, flat"),
    Frosted("Frosted glass", "The cover blurred behind a translucent pane"),
    BlurredCover("Blurred cover", "The cover itself, blurred to fill the window"),
    Ferrofluid("Ferrofluid", "A pool of ferrofluid that the music magnetises into spikes"),
}

enum class LyricsAlignment(val label: String) { Centre("Centred"), Left("Left") }

/**
 * What a scroll over the full player does.
 *
 * Up opens the equaliser, down puts the player away - one axis, two directions, which is about as
 * much as a gesture with nothing drawn on screen can carry. Either way the scroll is ignored when
 * something underneath already used it, so reading the lyrics or the queue never moves the player.
 */
enum class PlayerScroll(val label: String, val description: String) {
    Anywhere("Anywhere", "Scroll up for the equaliser, down to shrink the player"),

    /**
     * The same two gestures, each confined to the half of the window it pulls from: the equaliser
     * comes down from the top edge, and the player goes away downward. The halves are the cost -
     * nothing marks where they meet, so the same flick two pixels apart does different things. It
     * is offered rather than imposed for that reason, and the free version stays the default.
     */
    ByPosition("By cursor position", "Equaliser only near the top, shrink only near the bottom"),

    Off("Off", "Scrolling never moves the player"),
}

/**
 * Which AAC stream to ask YouTube for.
 *
 * All three are AAC-LC in MP4 - the one container the decode chain is built around - so this picks
 * a bitrate and changes nothing else. Auto is the itag the probes settled on, which is the format
 * every working client reliably offers; it is "the one known to work here", not "the biggest
 * number", and High falls back to it when nothing larger is published for a track.
 */
enum class AudioQuality(val label: String, val description: String) {
    Auto("Automatic", "The format known to work everywhere, about 128 kbps"),
    High("High", "The best AAC stream offered for each song"),
    Low("Low", "The smallest AAC stream, for a slow or metered connection"),
}

/** The two proxy kinds java.net.Proxy understands, as the phone offers them. */
enum class ProxyKind(val label: String) {
    Http("HTTP"),
    Socks("SOCKS"),
    ;

    fun toJavaType(): java.net.Proxy.Type =
        if (this == Socks) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
}

/** The player's seek bar, as on the phone. */
enum class SeekBarStyle(val label: String, val description: String) {
    Squiggly("Squiggly", "A gentle wave that moves while the song plays"),
    Default("Default", "A thick rounded bar"),
    Slim("Slim", "A thin rounded line"),
}
