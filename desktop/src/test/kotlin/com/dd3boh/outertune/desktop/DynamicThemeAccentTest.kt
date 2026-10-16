/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Whether a second colour from the cover actually reaches the scheme.
 *
 * The scheme used to derive every accent from one hue, with secondary and tertiary at fixed +30 and
 * -30 degree shifts off it. That is a defensible way to build a palette and it is also exactly why
 * a generated window reads as one wash of colour: nothing in it disagrees with anything else. Real
 * covers usually have two colours worth using, and using the second is the difference between a
 * window that is tinted and one that is themed.
 */
class DynamicThemeAccentTest {

    private fun hueOf(colour: Color): Float {
        val maxC = maxOf(colour.red, colour.green, colour.blue)
        val minC = minOf(colour.red, colour.green, colour.blue)
        val delta = maxC - minC
        if (delta < 1e-4f) return 0f
        val hue = when (maxC) {
            colour.red -> 60f * (((colour.green - colour.blue) / delta) % 6f)
            colour.green -> 60f * (((colour.blue - colour.red) / delta) + 2f)
            else -> 60f * (((colour.red - colour.green) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }

    private fun separation(a: Float, b: Float): Float {
        var d = abs(a - b) % 360f
        if (d > 180f) d = 360f - d
        return d
    }

    private val blue = Color(0xFF2050C0)
    private val orange = Color(0xFFD07020)

    @Test
    fun `a second hue moves tertiary away from primary`() {
        val single = DynamicTheme.schemeFor(blue, dark = true)
        val paired = DynamicTheme.schemeFor(blue, dark = true, accentSeed = orange)

        val singleGap = separation(hueOf(single.primary), hueOf(single.tertiary))
        val pairedGap = separation(hueOf(paired.primary), hueOf(paired.tertiary))

        assertTrue(
            "tertiary did not move: $singleGap degrees without the accent, $pairedGap with it",
            pairedGap > singleGap + 20f,
        )
    }

    @Test
    fun `tertiary lands near the hue the cover actually offered`() {
        val scheme = DynamicTheme.schemeFor(blue, dark = true, accentSeed = orange)
        val gap = separation(hueOf(scheme.tertiary), hueOf(orange))
        assertTrue("tertiary is $gap degrees from the accent the cover gave", gap < 25f)
    }

    @Test
    fun `primary is still the cover's own colour`() {
        // The accent is a second opinion, not a replacement - the window should still be the
        // colour of the record that is playing.
        val scheme = DynamicTheme.schemeFor(blue, dark = true, accentSeed = orange)
        val gap = separation(hueOf(scheme.primary), hueOf(blue))
        assertTrue("primary drifted $gap degrees from the seed", gap < 20f)
    }

    @Test
    fun `a cover with one colour behaves exactly as before`() {
        // The fallback has to be the old behaviour rather than something new, because a cover with
        // no second hue is common and this must not make those worse.
        val before = DynamicTheme.schemeFor(blue, dark = true)
        val after = DynamicTheme.schemeFor(blue, dark = true, accentSeed = null)
        assertEquals(before.tertiary, after.tertiary)
        assertEquals(before.primary, after.primary)
        assertEquals(before.surface, after.surface)
    }

    @Test
    fun `no seed at all is still the plain scheme`() {
        // Compared colour by colour rather than object to object: ColorScheme does not implement
        // structural equality, so two identical schemes are never equal to each other and a test
        // that compared them would fail whatever the code did.
        val plain = DynamicTheme.schemeFor(null, dark = true)
        val withAccent = DynamicTheme.schemeFor(null, dark = true, accentSeed = orange)
        assertEquals(plain.primary, withAccent.primary)
        assertEquals(plain.tertiary, withAccent.tertiary)
        assertEquals(plain.surface, withAccent.surface)
    }

    @Test
    fun `it works in light as well as dark`() {
        val single = DynamicTheme.schemeFor(blue, dark = false)
        val paired = DynamicTheme.schemeFor(blue, dark = false, accentSeed = orange)
        assertTrue(
            separation(hueOf(paired.primary), hueOf(paired.tertiary)) >
                separation(hueOf(single.primary), hueOf(single.tertiary)) + 20f
        )
    }
}
