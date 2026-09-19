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
    }

    // ---- Library ---------------------------------------------------------------------------

    /** Whether played songs are recorded at all. */
    var keepHistory by bool("library.history", true)

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
}
