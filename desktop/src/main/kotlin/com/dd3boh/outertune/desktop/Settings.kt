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

    // ---- Library ---------------------------------------------------------------------------

    /** Whether played songs are recorded at all. */
    var keepHistory by bool("library.history", true)

    // ------------------------------------------------------------------------------------------

    private fun bool(key: String, default: Boolean) =
        setting(key, default, { it.toBooleanStrictOrNull() }, { it.toString() })

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
