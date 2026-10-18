/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import java.awt.event.KeyEvent as AwtKey

/**
 * One key with its modifiers - "Ctrl+Shift+E". The key is AWT's virtual key code, which is stable
 * across keyboard layouts and sessions, so a stored binding means the same key next time.
 */
data class Chord(val code: Int, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false, val meta: Boolean = false) {

    fun encode(): String = buildString {
        if (ctrl) append("c")
        if (alt) append("a")
        if (shift) append("s")
        if (meta) append("m")
        append(":").append(code)
    }

    /** As shown in settings: "Ctrl+Shift+E". */
    val label: String
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (shift) add("Shift")
            if (meta) add("Win")
            add(keyName(code))
        }.joinToString("+")

    companion object {
        private val MODIFIERS = setOf(AwtKey.VK_SHIFT, AwtKey.VK_CONTROL, AwtKey.VK_ALT, AwtKey.VK_META, AwtKey.VK_WINDOWS, AwtKey.VK_ALT_GRAPH)

        fun decode(text: String): Chord? {
            val (mods, code) = text.split(":").takeIf { it.size == 2 } ?: return null
            return Chord(code.toIntOrNull() ?: return null, 'c' in mods, 'a' in mods, 's' in mods, 'm' in mods)
        }

        /** The chord a key press makes, or null for a lone modifier. */
        fun of(event: KeyEvent): Chord? {
            val code = event.key.nativeKeyCode
            if (code == 0 || code in MODIFIERS) return null
            return Chord(code, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
        }

        private fun keyName(code: Int): String = when (code) {
            AwtKey.VK_SPACE -> "Space"
            AwtKey.VK_LEFT -> "Left"
            AwtKey.VK_RIGHT -> "Right"
            AwtKey.VK_UP -> "Up"
            AwtKey.VK_DOWN -> "Down"
            AwtKey.VK_ESCAPE -> "Esc"
            AwtKey.VK_SLASH -> "/"
            else -> AwtKey.getKeyText(code)
        }

        fun key(code: Int) = Chord(code)
        fun ctrl(code: Int) = Chord(code, ctrl = true)
        fun shift(code: Int) = Chord(code, shift = true)
    }
}

/** Everything a key can do. The defaults are what a fresh install has; each can be changed. */
enum class KeyAction(val label: String, val defaults: List<Chord>) {
    PlayPause("Play / pause", listOf(Chord.key(AwtKey.VK_SPACE), Chord.key(AwtKey.VK_K))),
    Next("Next song", listOf(Chord.ctrl(AwtKey.VK_RIGHT), Chord.shift(AwtKey.VK_N))),
    Previous("Previous song", listOf(Chord.ctrl(AwtKey.VK_LEFT), Chord.shift(AwtKey.VK_P))),
    SeekForward("Forward 10 seconds", listOf(Chord.key(AwtKey.VK_RIGHT))),
    SeekBack("Back 10 seconds", listOf(Chord.key(AwtKey.VK_LEFT))),
    VolumeUp("Volume up", listOf(Chord.key(AwtKey.VK_UP))),
    VolumeDown("Volume down", listOf(Chord.key(AwtKey.VK_DOWN))),
    Mute("Mute", listOf(Chord.key(AwtKey.VK_M))),
    Shuffle("Shuffle", listOf(Chord.key(AwtKey.VK_S))),
    Repeat("Repeat", listOf(Chord.key(AwtKey.VK_R))),
    Like("Like", listOf(Chord.key(AwtKey.VK_L))),
    Equaliser("Equaliser", listOf(Chord.key(AwtKey.VK_E))),
    Lyrics("Lyrics", listOf(Chord.key(AwtKey.VK_Y))),
    Queue("Queue", listOf(Chord.key(AwtKey.VK_Q))),
    FullPlayer("Open / close the player", listOf(Chord.key(AwtKey.VK_F))),
    Back("Back / close", listOf(Chord.key(AwtKey.VK_ESCAPE))),
    Search("Search", listOf(Chord.ctrl(AwtKey.VK_F), Chord.key(AwtKey.VK_SLASH))),
    SleepTimer("Sleep timer", listOf(Chord.key(AwtKey.VK_T))),
}

/**
 * Which keys do what. Stored as only the actions that differ from their defaults, so a new action
 * added later arrives with its default rather than unbound.
 */
class KeyBindings(private val overrides: Map<KeyAction, List<Chord>>) {

    fun chordsFor(action: KeyAction): List<Chord> = overrides[action] ?: action.defaults

    fun isDefault(action: KeyAction) = action !in overrides

    /** The action a chord triggers. The first action listing it wins, should two share one. */
    fun actionFor(chord: Chord): KeyAction? = KeyAction.entries.firstOrNull { chord in chordsFor(it) }

    fun with(action: KeyAction, chords: List<Chord>): KeyBindings {
        val next = overrides.toMutableMap()
        if (chords == action.defaults) next.remove(action) else next[action] = chords.distinct()
        return KeyBindings(next)
    }

    /** Adds [chord] to [action], taking it off any other action that had it. */
    fun bind(action: KeyAction, chord: Chord): KeyBindings {
        var next = this
        for (other in KeyAction.entries) {
            if (other != action && chord in next.chordsFor(other)) next = next.with(other, next.chordsFor(other) - chord)
        }
        return next.with(action, next.chordsFor(action) + chord)
    }

    fun reset(action: KeyAction) = KeyBindings(overrides - action)

    fun encode(): String = overrides.entries.joinToString(";") { (action, chords) ->
        action.name + "=" + chords.joinToString("|") { it.encode() }
    }

    companion object {
        val DEFAULT = KeyBindings(emptyMap())

        fun decode(text: String): KeyBindings = KeyBindings(
            text.split(";").mapNotNull { entry ->
                val (name, chords) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
                val action = KeyAction.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                action to chords.split("|").filter { it.isNotBlank() }.mapNotNull(Chord::decode)
            }.toMap()
        )
    }
}

/**
 * The window's key handler, set by the app as its state changes. The window hands it every key not
 * already used by whatever has focus - so typing in a text field types, and never triggers anything.
 */
object KeyRouter {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null

    /**
     * Text fields that have focus right now - see [typingField]. While any does, no shortcut runs:
     * a letter typed into a box reaches the window as well as the box, so typing "cloud" into the
     * AutoEQ search skipped to the next song. Only the main search box used to be exempt.
     */
    private val typingIn: MutableSet<Any> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    val typing: Boolean get() = typingIn.isNotEmpty()

    internal fun typing(field: Any, focused: Boolean) {
        if (focused) typingIn += field else typingIn -= field
    }

    fun handle(event: KeyEvent): Boolean = if (typing) false else handler?.invoke(event) ?: false
}

/** Marks a text field, so the keyboard shortcuts stand aside while it has focus - see KeyRouter. */
fun Modifier.typingField(): Modifier = composed {
    val field = remember { Any() }
    DisposableEffect(field) { onDispose { KeyRouter.typing(field, false) } }
    onFocusChanged { KeyRouter.typing(field, it.isFocused) }
}

/** Asks the full player to do something it owns - see NowPlayingScreen. */
enum class PlayerCommand { ToggleEqualiser, ToggleLyrics, ToggleQueue, Back }
