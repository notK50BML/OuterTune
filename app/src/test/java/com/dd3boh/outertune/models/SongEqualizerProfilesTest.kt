/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-song equaliser overrides, stored and read back.
 *
 * The part worth pinning is the eviction. This is one preference string holding every override
 * there is, so it has to be bounded - and a bound that drops the wrong entry loses a curve someone
 * dialled in by ear, silently, with nothing on screen afterwards to say it ever existed.
 */
class SongEqualizerProfilesTest {

    private fun curve(gain: Float) = EqualizerSettings(
        enabled = true,
        bands = EqualizerSettings.DEFAULT_BANDS.map { it.copy(gainDb = gain) },
    )

    @Test
    fun `an override survives the round trip`() {
        val profiles = SongEqualizerProfiles.put(emptyMap(), "abc123", curve(4f), now = 1_000L)
        val read = SongEqualizerProfiles.fromJson(SongEqualizerProfiles.toJson(profiles.values))

        assertEquals(1, read.size)
        val back = read.getValue("abc123")
        assertEquals(1_000L, back.updatedAt)
        assertEquals(curve(4f).bands.map { it.gainDb }, back.settings.bands.map { it.gainDb })
        assertTrue(back.settings.enabled)
    }

    @Test
    fun `the override carries its own enabled flag`() {
        // The whole point of the feature: "equaliser off everywhere except this one track", and
        // the mirror case, "on everywhere except this one". Neither works if the global switch
        // still has to agree.
        val off = SongEqualizerProfiles.put(emptyMap(), "s", curve(4f).copy(enabled = false), 1L)
        val read = SongEqualizerProfiles.fromJson(SongEqualizerProfiles.toJson(off.values))
        assertEquals(false, read.getValue("s").settings.enabled)
    }

    @Test
    fun `removing an override leaves the others`() {
        var profiles = SongEqualizerProfiles.put(emptyMap(), "a", curve(1f), 1L)
        profiles = SongEqualizerProfiles.put(profiles, "b", curve(2f), 2L)
        profiles = SongEqualizerProfiles.remove(profiles, "a")

        assertNull(profiles["a"])
        assertEquals(1, profiles.size)
        assertTrue("b" in profiles)
    }

    @Test
    fun `writing the same song twice replaces rather than accumulates`() {
        var profiles = SongEqualizerProfiles.put(emptyMap(), "a", curve(1f), 1L)
        profiles = SongEqualizerProfiles.put(profiles, "a", curve(9f), 2L)

        assertEquals(1, profiles.size)
        assertEquals(9f, profiles.getValue("a").settings.bands[0].gainDb, 0.001f)
    }

    // ---- the bound ----------------------------------------------------------------------------

    @Test
    fun `the oldest is evicted, never the one just written`() {
        // The failure this exists to stop: an eviction that drops by iteration order rather than
        // by age can throw away the curve the user is adjusting at the moment they adjust it.
        var profiles = emptyMap<String, SongEqualizerProfile>()
        for (i in 0 until SongEqualizerProfiles.MAX_SONGS) {
            profiles = SongEqualizerProfiles.put(profiles, "song$i", curve(1f), now = i.toLong())
        }
        assertEquals(SongEqualizerProfiles.MAX_SONGS, profiles.size)

        profiles = SongEqualizerProfiles.put(profiles, "newest", curve(5f), now = 9_999L)

        assertEquals(SongEqualizerProfiles.MAX_SONGS, profiles.size)
        assertTrue("the song just written was evicted", "newest" in profiles)
        assertNull("the oldest should have gone", profiles["song0"])
        assertTrue("a recent one was evicted instead of the oldest", "song99" in profiles)
    }

    @Test
    fun `re-saving an old song makes it recent again`() {
        // Age is last-touched, not first-created. Without this, a curve used every day would be
        // evicted ahead of one set once and never played since.
        var profiles = emptyMap<String, SongEqualizerProfile>()
        for (i in 0 until SongEqualizerProfiles.MAX_SONGS) {
            profiles = SongEqualizerProfiles.put(profiles, "song$i", curve(1f), now = i.toLong())
        }
        profiles = SongEqualizerProfiles.put(profiles, "song0", curve(2f), now = 5_000L)
        profiles = SongEqualizerProfiles.put(profiles, "newest", curve(5f), now = 9_999L)

        assertTrue("the refreshed song was evicted despite being touched last", "song0" in profiles)
        assertNull("song1 should have been the oldest", profiles["song1"])
    }

    // ---- what must not throw -------------------------------------------------------------------

    @Test
    fun `junk reads as no overrides rather than throwing`() {
        // An exception here would happen inside the playback service's settings collector, which
        // would take the equaliser - and with it the audio pipeline - down over a bad string.
        assertTrue(SongEqualizerProfiles.fromJson("").isEmpty())
        assertTrue(SongEqualizerProfiles.fromJson("   ").isEmpty())
        assertTrue(SongEqualizerProfiles.fromJson("not json").isEmpty())
        assertTrue(SongEqualizerProfiles.fromJson("{}").isEmpty())
        assertTrue(SongEqualizerProfiles.fromJson("""{"songs":"wrong type"}""").isEmpty())
    }

    @Test
    fun `a newer schema reads as empty rather than as a guess`() {
        val json = """{"schemaVersion":99,"songs":[{"songId":"a","settings":{}}]}"""
        assertTrue(SongEqualizerProfiles.fromJson(json).isEmpty())
    }

    @Test
    fun `one unreadable entry does not take the others with it`() {
        val good = SongEqualizerProfiles.toJson(
            SongEqualizerProfiles.put(emptyMap(), "good", curve(3f), 1L).values
        )
        // Splice a nameless entry in beside the good one.
        val broken = good.replace(""""songs":[""", """"songs":[{"updatedAt":1},""")
        val read = SongEqualizerProfiles.fromJson(broken)

        assertEquals(1, read.size)
        assertTrue("good" in read)
    }
}
