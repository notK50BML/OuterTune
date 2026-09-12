/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Light, dark, or whatever the desktop is set to. */
enum class ThemeMode(val label: String) {
    System("Follow the system"),
    Light("Light"),
    Dark("Dark"),
}

/**
 * A colour scheme built from the cover of whatever is playing.
 *
 * This is the desktop's answer to Material You. Android seeds its scheme from the wallpaper, which
 * is a thing the system hands over; a desktop has no equivalent, but it does have a large picture on
 * screen that the person chose - so the album art is the seed. The result is that the whole window
 * shifts with the music rather than only the player, which was the previous state and read as the
 * player belonging to a different application than the library.
 *
 * Built by hand rather than by pulling in material-color-utilities. That library is the right answer
 * on Android, where it is already present; here it would be a new dependency for one function, and
 * the part of it that matters - tones of one hue at fixed lightnesses - is short enough to write and
 * test directly. What it gives up is the perceptual tone mapping, so a very saturated seed is
 * slightly less even across the ramp than Android's would be. That is a fair trade for not shipping
 * a colour science library to tint a music player.
 *
 * The contrast pairs are checked by tests rather than by eye, because an unreadable scheme for one
 * particular album cover is exactly the bug that never shows up while developing. Those tests found
 * a real one on the first run: green accents failed the readability threshold where red and blue
 * passed, because HSL lightness is not perceived lightness. Accents are now searched to meet a
 * contrast target rather than placed at a fixed lightness.
 */
object DynamicTheme {

    /**
     * Contrast every text-on-colour pair is held to.
     *
     * WCAG AA for body text. A shade over the 4.5 minimum, so a pair that only just qualifies does
     * not fall under it through the float rounding between generating a colour and measuring it.
     */
    private const val MIN_CONTRAST = 4.6f

    fun schemeFor(seed: Color?, dark: Boolean): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        if (seed == null) return base

        val (hue, saturation) = hueAndSaturation(seed)
        // A grey cover would otherwise produce a grey interface with no accent at all - a scheme
        // where nothing is emphasised is worse than one whose accent is arbitrary.
        val s = saturation.coerceIn(0.25f, 0.85f)

        fun tone(lightness: Float, sat: Float = s, shift: Float = 0f) =
            fromHsl((hue + shift + 360f) % 360f, sat, lightness)

        /**
         * An accent at roughly [lightness], moved until its own label is readable on it.
         *
         * The search is what makes this scheme legible by construction rather than by luck. HSL
         * lightness is not perceived lightness: green at 0.38 is far brighter to the eye than blue
         * at 0.38, because luminance weights green more than three times as heavily. A fixed ramp
         * therefore produces accents that pass for most hues and quietly fail for green and yellow -
         * which is a bug nobody finds until the wrong album is playing.
         */
        fun accent(lightness: Float, on: Color, sat: Float = s, shift: Float = 0f): Color {
            // Away from the label it has to contrast with: darker under pale text, lighter under
            // dark text.
            val step = if (luminance(on) > 0.5f) -0.02f else 0.02f
            var l = lightness
            repeat(50) {
                val candidate = tone(l, sat, shift)
                if (contrast(candidate, on) >= MIN_CONTRAST) return candidate
                l += step
                if (l !in 0f..1f) return tone(l.coerceIn(0f, 1f), sat, shift)
            }
            return tone(l.coerceIn(0f, 1f), sat, shift)
        }

        return if (dark) {
            val onPrimary = tone(0.14f, sat = s * 0.7f)
            val onSecondary = tone(0.14f, sat = s * 0.4f, shift = 30f)
            val onTertiary = tone(0.14f, sat = s * 0.5f, shift = -30f)
            val onPrimaryContainer = tone(0.92f)
            base.copy(
                primary = accent(0.72f, onPrimary),
                onPrimary = onPrimary,
                primaryContainer = accent(0.30f, onPrimaryContainer),
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent(0.70f, onSecondary, sat = s * 0.5f, shift = 30f),
                onSecondary = onSecondary,
                secondaryContainer = tone(0.28f, sat = s * 0.5f, shift = 30f),
                onSecondaryContainer = tone(0.90f, sat = s * 0.5f, shift = 30f),
                tertiary = accent(0.72f, onTertiary, sat = s * 0.6f, shift = -30f),
                onTertiary = onTertiary,
                // Surfaces are tinted, not coloured. Enough that the window feels of a piece with
                // the music; not enough that text has to fight the background it sits on.
                background = tone(0.07f, sat = s * 0.35f),
                onBackground = tone(0.93f, sat = s * 0.12f),
                surface = tone(0.09f, sat = s * 0.35f),
                onSurface = tone(0.93f, sat = s * 0.12f),
                surfaceVariant = tone(0.18f, sat = s * 0.28f),
                onSurfaceVariant = tone(0.82f, sat = s * 0.15f),
                surfaceContainer = tone(0.13f, sat = s * 0.32f),
                surfaceContainerHigh = tone(0.17f, sat = s * 0.32f),
                surfaceContainerHighest = tone(0.21f, sat = s * 0.32f),
                surfaceContainerLow = tone(0.10f, sat = s * 0.32f),
                surfaceContainerLowest = tone(0.05f, sat = s * 0.32f),
                outline = tone(0.55f, sat = s * 0.2f),
                outlineVariant = tone(0.30f, sat = s * 0.2f),
            )
        } else {
            val onPrimary = tone(0.99f, sat = s * 0.15f)
            val onSecondary = tone(0.99f, sat = s * 0.15f, shift = 30f)
            val onTertiary = tone(0.99f, sat = s * 0.15f, shift = -30f)
            val onPrimaryContainer = tone(0.14f)
            base.copy(
                primary = accent(0.38f, onPrimary),
                onPrimary = onPrimary,
                primaryContainer = accent(0.88f, onPrimaryContainer),
                onPrimaryContainer = onPrimaryContainer,
                secondary = accent(0.40f, onSecondary, sat = s * 0.5f, shift = 30f),
                onSecondary = onSecondary,
                secondaryContainer = tone(0.88f, sat = s * 0.5f, shift = 30f),
                onSecondaryContainer = tone(0.16f, sat = s * 0.5f, shift = 30f),
                tertiary = accent(0.38f, onTertiary, sat = s * 0.6f, shift = -30f),
                onTertiary = onTertiary,
                background = tone(0.98f, sat = s * 0.18f),
                onBackground = tone(0.10f, sat = s * 0.20f),
                surface = tone(0.98f, sat = s * 0.18f),
                onSurface = tone(0.10f, sat = s * 0.20f),
                surfaceVariant = tone(0.90f, sat = s * 0.22f),
                onSurfaceVariant = tone(0.28f, sat = s * 0.25f),
                surfaceContainer = tone(0.94f, sat = s * 0.20f),
                surfaceContainerHigh = tone(0.91f, sat = s * 0.20f),
                surfaceContainerHighest = tone(0.88f, sat = s * 0.20f),
                surfaceContainerLow = tone(0.96f, sat = s * 0.20f),
                surfaceContainerLowest = Color.White,
                outline = tone(0.45f, sat = s * 0.2f),
                outlineVariant = tone(0.78f, sat = s * 0.2f),
            )
        }
    }

    /**
     * [target], eased into rather than cut to.
     *
     * The comment that used to sit at the call site claimed this happened and nothing did it, which
     * is worse than not having done it - a reader trusts the comment over the code. The reasoning
     * was right, though: a cover's colours arrive a moment after the song does, because the image
     * has to be fetched and sampled, so a hard switch lands as a flash of the wrong scheme followed
     * by the right one.
     *
     * Only the roles that carry colour are animated. The rest are taken from [target] directly -
     * animating forty fields to move the six that visibly change is work per frame for nothing.
     */
    @Composable
    fun animated(target: ColorScheme, durationMillis: Int = 600): ColorScheme {
        val spec = tween<Color>(durationMillis)

        @Composable
        fun ease(colour: Color) = animateColorAsState(colour, spec).value

        return target.copy(
            primary = ease(target.primary),
            onPrimary = ease(target.onPrimary),
            primaryContainer = ease(target.primaryContainer),
            onPrimaryContainer = ease(target.onPrimaryContainer),
            secondary = ease(target.secondary),
            secondaryContainer = ease(target.secondaryContainer),
            tertiary = ease(target.tertiary),
            background = ease(target.background),
            onBackground = ease(target.onBackground),
            surface = ease(target.surface),
            onSurface = ease(target.onSurface),
            surfaceVariant = ease(target.surfaceVariant),
            onSurfaceVariant = ease(target.onSurfaceVariant),
            surfaceContainer = ease(target.surfaceContainer),
            surfaceContainerHigh = ease(target.surfaceContainerHigh),
            surfaceContainerHighest = ease(target.surfaceContainerHighest),
            surfaceContainerLow = ease(target.surfaceContainerLow),
            outline = ease(target.outline),
            outlineVariant = ease(target.outlineVariant),
        )
    }

    /**
     * The seed's hue, and how colourful it is.
     *
     * Lightness is deliberately discarded. A cover that is mostly black and one that is mostly pale
     * should give the same family of accents - the interface decides its own lightness from whether
     * it is in dark or light mode, and letting the artwork drive that too would make a dark album
     * produce an unreadably dark interface in light mode.
     */
    internal fun hueAndSaturation(colour: Color): Pair<Float, Float> {
        val r = colour.red
        val g = colour.green
        val b = colour.blue
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        if (delta < 1e-4f) return 0f to 0f

        val hue = when (maxC) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val lightness = (maxC + minC) / 2f
        val saturation = delta / (1f - abs(2f * lightness - 1f)).coerceAtLeast(1e-4f)
        return ((hue + 360f) % 360f) to saturation.coerceIn(0f, 1f)
    }

    /** HSL to RGB, the standard conversion. */
    internal fun fromHsl(hue: Float, saturation: Float, lightness: Float): Color {
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(h % 2f - 1f))
        val (r, g, b) = when {
            h < 1f -> Triple(c, x, 0f)
            h < 2f -> Triple(x, c, 0f)
            h < 3f -> Triple(0f, c, x)
            h < 4f -> Triple(0f, x, c)
            h < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }

    /**
     * Relative luminance, as WCAG defines it.
     *
     * Used by the tests to check that text is readable on what it sits on. Not a rendering concern,
     * but it is the only way to state "this scheme is legible" as something that can fail.
     */
    internal fun luminance(colour: Color): Float {
        fun channel(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(
            ((v + 0.055f) / 1.055f).toDouble(), 2.4,
        ).toFloat()
        return 0.2126f * channel(colour.red) + 0.7152f * channel(colour.green) + 0.0722f * channel(colour.blue)
    }

    /** Contrast ratio between two colours, from 1 (identical) to 21 (black on white). */
    internal fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }
}
