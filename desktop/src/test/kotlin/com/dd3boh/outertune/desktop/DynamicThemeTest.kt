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

/**
 * The generated scheme, checked for legibility rather than for looks.
 *
 * Taste is not testable and is not the risk here. The risk is that one particular album cover
 * produces an interface whose text cannot be read against its own background - which will not happen
 * on whatever cover is playing while this is being written, and will happen to someone.
 */
class DynamicThemeTest {

    /** A spread of seeds including the awkward ones: pure grey, near-black, fully saturated. */
    private val seeds = listOf(
        "red" to Color(0xFFE53935),
        "green" to Color(0xFF43A047),
        "blue" to Color(0xFF1E88E5),
        "yellow" to Color(0xFFFDD835),
        "purple" to Color(0xFF8E24AA),
        "grey" to Color(0xFF808080),
        "near black" to Color(0xFF0A0A0A),
        "near white" to Color(0xFFF5F5F5),
        "saturated cyan" to Color(0xFF00FFFF),
    )

    /** WCAG AA for body text. Below this, something is genuinely hard to read. */
    private val minimumContrast = 4.5f

    @Test
    fun `text is readable on its own surface, for every seed and both modes`() {
        seeds.forEach { (name, seed) ->
            listOf(true, false).forEach { dark ->
                val scheme = DynamicTheme.schemeFor(seed, dark)
                val where = "$name, ${if (dark) "dark" else "light"}"
                listOf(
                    "onBackground/background" to (scheme.onBackground to scheme.background),
                    "onSurface/surface" to (scheme.onSurface to scheme.surface),
                    "onSurfaceVariant/surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                    "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                    "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
                    "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
                    "onPrimaryContainer/primaryContainer" to
                        (scheme.onPrimaryContainer to scheme.primaryContainer),
                ).forEach { (pair, colours) ->
                    val ratio = DynamicTheme.contrast(colours.first, colours.second)
                    assertTrue(
                        "$where: $pair is only ${"%.1f".format(ratio)}:1",
                        ratio >= minimumContrast,
                    )
                }
            }
        }
    }

    @Test
    fun `a dark scheme is actually dark and a light one light`() {
        // The seed must not decide this. A near-black cover in light mode has to stay light, or the
        // theme setting means nothing.
        seeds.forEach { (name, seed) ->
            val dark = DynamicTheme.schemeFor(seed, dark = true)
            val light = DynamicTheme.schemeFor(seed, dark = false)
            assertTrue("$name dark background was not dark", DynamicTheme.luminance(dark.background) < 0.15f)
            assertTrue("$name light background was not light", DynamicTheme.luminance(light.background) > 0.6f)
        }
    }

    @Test
    fun `the accent follows the seed's hue`() {
        // The whole point: a red cover should not produce a blue interface.
        val (seedHue, _) = DynamicTheme.hueAndSaturation(Color(0xFF1E88E5))
        val (primaryHue, _) = DynamicTheme.hueAndSaturation(
            DynamicTheme.schemeFor(Color(0xFF1E88E5), dark = true).primary
        )
        assertEquals("primary drifted off the seed's hue", seedHue, primaryHue, 8f)
    }

    @Test
    fun `a grey seed still produces a visible accent`() {
        // A grey cover would otherwise give a grey interface where nothing is emphasised, which is
        // worse than an arbitrary accent.
        val scheme = DynamicTheme.schemeFor(Color(0xFF808080), dark = true)
        assertTrue(
            "the accent is indistinguishable from the surface",
            DynamicTheme.contrast(scheme.primary, scheme.surface) > 3f,
        )
    }

    @Test
    fun `no seed means the stock scheme`() {
        // Nothing playing, or artwork that has not loaded. It should look like a normal app rather
        // than like a failed one.
        assertEquals(
            androidx.compose.material3.darkColorScheme().background,
            DynamicTheme.schemeFor(null, dark = true).background,
        )
    }

    @Test
    fun `lightness of the seed does not change the hue chosen`() {
        // A dark and a light version of the same colour are the same colour, and should theme the
        // same way - otherwise the interface lurches between two albums by the same artist.
        val (darkHue, _) = DynamicTheme.hueAndSaturation(Color(0xFF0D47A1))
        val (lightHue, _) = DynamicTheme.hueAndSaturation(Color(0xFF90CAF9))
        assertEquals(darkHue, lightHue, 12f)
    }

    @Test
    fun `hsl round trips`() {
        seeds.forEach { (name, seed) ->
            val (hue, saturation) = DynamicTheme.hueAndSaturation(seed)
            if (saturation < 0.05f) return@forEach
            val lightness = (maxOf(seed.red, seed.green, seed.blue) +
                minOf(seed.red, seed.green, seed.blue)) / 2f
            val rebuilt = DynamicTheme.fromHsl(hue, saturation, lightness)
            assertEquals("$name red", seed.red, rebuilt.red, 0.02f)
            assertEquals("$name green", seed.green, rebuilt.green, 0.02f)
            assertEquals("$name blue", seed.blue, rebuilt.blue, 0.02f)
        }
    }

    @Test
    fun `every theme mode has a label`() {
        ThemeMode.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}
