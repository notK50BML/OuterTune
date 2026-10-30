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
 * A binding change, with whatever it took off other actions to make room.
 *
 * Returned rather than applied silently because the taking is invisible otherwise: the key simply
 * stops working somewhere else, days later, with nothing linking that back to the moment it was
 * reassigned. [previous] is the state before the change, which is all an Undo needs.
 */
data class Rebind(val bindings: KeyBindings, val displaced: List<Displaced>, val previous: KeyBindings? = null) {

    /** An action that lost a key, and whether losing it left that action with no key at all. */
    data class Displaced(val action: KeyAction, val chord: Chord, val leftUnbound: Boolean)

    /** What to put in front of the person, or null when nothing was displaced. */
    val message: String?
        get() = displaced.takeIf { it.isNotEmpty() }?.joinToString("; ") {
            if (it.leftUnbound) "${it.action.label} now has no key"
            else "${it.chord.label} taken from ${it.action.label}"
        }
}

/**
 * Which keys do what. Stored as only the actions that differ from their defaults, so a new action
 * added later arrives with its default rather than unbound.
 */
class KeyBindings(private val overrides: Map<KeyAction, List<Chord>>) {

    fun chordsFor(action: KeyAction): List<Chord> = overrides[action] ?: action.defaults

    fun isDefault(action: KeyAction) = action !in overrides

    /**
     * The action a chord triggers, or null for one bound to nothing.
     *
     * First match wins, and "first" is declaration order in [KeyAction] - so this only means
     * anything because [bind] and [reset] between them guarantee no chord is ever on two actions.
     * That used to hold for [bind] alone, and the gap showed: a key handed to another action while
     * this one was rebound away came back on [reset] without displacing it, both actions held it,
     * and which one a press reached came down to where the two happen to sit in the enum. Invisible,
     * and nothing to do with what anybody asked for.
     */
    fun actionFor(chord: Chord): KeyAction? = KeyAction.entries.firstOrNull { chord in chordsFor(it) }

    fun with(action: KeyAction, chords: List<Chord>): KeyBindings {
        val next = overrides.toMutableMap()
        if (chords == action.defaults) next.remove(action) else next[action] = chords.distinct()
        return KeyBindings(next)
    }

    /**
     * Takes [chord] off every action except [keep], recording what it took.
     *
     * Reported rather than refused: taking the key is exactly what was asked for, so the job is to
     * make the consequence visible, not to block it. [Rebind.Displaced.leftUnbound] marks the case
     * worth saying out loud on its own - an action whose only key this was now has none at all, and
     * nothing on the settings screen tells that apart from one nobody has touched.
     */
    private fun strip(chord: Chord, keep: KeyAction): Pair<KeyBindings, List<Rebind.Displaced>> {
        var next = this
        val taken = mutableListOf<Rebind.Displaced>()
        for (other in KeyAction.entries) {
            if (other == keep) continue
            val had = next.chordsFor(other)
            if (chord !in had) continue
            val left = had - chord
            taken += Rebind.Displaced(other, chord, leftUnbound = left.isEmpty())
            next = next.with(other, left)
        }
        return next to taken
    }

    /** Adds [chord] to [action], taking it off any other action that had it. */
    fun bind(action: KeyAction, chord: Chord): Rebind {
        val (next, taken) = strip(chord, action)
        return Rebind(next.with(action, next.chordsFor(action) + chord), taken)
    }

    /**
     * Puts [action] back to its defaults, displacing anything else that holds those keys.
     *
     * The displacing is the whole fix for a collision that was reachable in three ordinary steps.
     * Rebind Like off L; give L to Shuffle, which [bind] allows because Like no longer holds it;
     * reset Like. L is now on both. Dropping the override was all reset ever did, so it could not
     * have seen that coming - it never looked at what the defaults were about to land on top of.
     */
    fun reset(action: KeyAction): Rebind {
        var next = KeyBindings(overrides - action)
        val taken = mutableListOf<Rebind.Displaced>()
        for (chord in action.defaults) {
            val (stripped, took) = next.strip(chord, action)
            next = stripped
            taken += took
        }
        return Rebind(next, taken)
    }

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

/**
 * Asks the full player to do something it owns - see NowPlayingScreen.
 *
 * ShowLyrics is the mini player's lyrics button, which opens the player on the lyrics. It is not
 * ToggleLyrics because a toggle depends on what was showing before, and "lyrics" should never mean
 * "the cover" however the player happened to be left.
 */
enum class PlayerCommand { ToggleEqualiser, ToggleLyrics, ShowLyrics, ToggleQueue, Back }
