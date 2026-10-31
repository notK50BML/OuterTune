/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Each song's own lyrics timing offset: how it is shown, how the wheel moves it, and how the library
 * keeps it.
 */
class LyricsOffsetTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-offset-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `offsets are shown signed, with a real minus`() {
        assertEquals("+250 ms", formatLyricsOffset(250))
        assertEquals("−100 ms", formatLyricsOffset(-100))
        assertEquals("0 ms", formatLyricsOffset(0))
    }

    @Test
    fun `a wheel notch is a whole step and up is later`() {
        val wheel = OffsetWheel()
        assertEquals(1, wheel.notches(-1f))
        assertEquals(-2, wheel.notches(2f))
    }

    @Test
    fun `a touchpad's small deltas add up rather than each taking a step`() {
        val wheel = OffsetWheel()
        assertEquals(0, wheel.notches(-0.5f))
        assertEquals(1, wheel.notches(-0.5f))
        assertEquals(0, wheel.notches(-0.75f))
        // 1.5 since the last step: one more, and the half left over is carried.
        assertEquals(1, wheel.notches(-0.75f))
        // Turning back uses up the carry before it steps the other way.
        assertEquals(0, wheel.notches(1f))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `scrolling over the control changes the offset as it goes, finer with Shift`() {
        var value by mutableStateOf(0)
        val scene = ImageComposeScene(500, 400, Density(1f), Dispatchers.Unconfined) {
            MaterialTheme { LyricsOffsetControl(value, { value = it }, modifier = Modifier.width(400.dp)) }
        }
        try {
            scene.render(0)
            val over = Offset(200f, 40f)
            scene.sendPointerEvent(PointerEventType.Move, over)
            scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, -1f))
            assertEquals(LYRICS_OFFSET_STEP_MS, value)
            scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, -2f))
            assertEquals(3 * LYRICS_OFFSET_STEP_MS, value)
            // Shift-wheel, as Windows delivers it: sideways.
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                over,
                scrollDelta = Offset(1f, 0f),
                keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = true),
            )
            assertEquals(3 * LYRICS_OFFSET_STEP_MS - LYRICS_OFFSET_FINE_STEP_MS, value)
            // Never past the limit, however far it is scrolled.
            scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, -500f))
            assertEquals(LYRICS_OFFSET_LIMIT_MS, value)
            // Reset from outside - the dialog's button - and the wheel carries on from there, not
            // from where it last left the value.
            scene.render(1_000_000L)
            value = 0
            scene.render(2_000_000L)
            scene.sendPointerEvent(PointerEventType.Scroll, over, scrollDelta = Offset(0f, -1f))
            assertEquals(LYRICS_OFFSET_STEP_MS, value)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a song never adjusted has no offset, and each song keeps its own`() {
        val db = Database(File(dir, "library.db"))
        assertEquals(0, db.lyricsOffset("a"))
        db.putLyricsOffset("a", 250)
        db.putLyricsOffset("b", -100)
        assertEquals(250, db.lyricsOffset("a"))
        assertEquals(-100, db.lyricsOffset("b"))
        db.putLyricsOffset("a", 300)
        assertEquals(300, db.lyricsOffset("a"))
        db.close()
    }

    @Test
    fun `the offset outlives fetching the lyrics again, and is kept for songs not in the library`() {
        val db = Database(File(dir, "library.db"))
        db.upsertSong(StoredSong("a", "Song", "Artist"))
        db.putLyrics("a", "[00:01.00]la", "LRCLIB")
        db.putLyricsOffset("a", 400)
        // "Fetch lyrics again" clears the cached lyrics first.
        db.deleteLyrics("a")
        assertEquals(400, db.lyricsOffset("a"))
        // A search result playing before anything has saved it.
        db.putLyricsOffset("unknown", -50)
        assertEquals(-50, db.lyricsOffset("unknown"))
        db.close()
    }

    @Test
    fun `setting it back to zero forgets it, and it survives reopening the library`() {
        Database(File(dir, "library.db")).apply {
            putLyricsOffset("a", 120)
            putLyricsOffset("b", 80)
            putLyricsOffset("b", 0)
            close()
        }
        val reopened = Database(File(dir, "library.db"))
        assertEquals(120, reopened.lyricsOffset("a"))
        assertEquals(0, reopened.lyricsOffset("b"))
        reopened.close()
    }
}
