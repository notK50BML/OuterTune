/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

/**
 * The fonts installed on this machine, and turning one into something Compose can set text in.
 *
 * Read from the OS through AWT, which already enumerates them for Swing, so every family the system
 * itself offers is choosable - not a list baked into the app.
 */
object SystemFonts {

    /**
     * Every installed family, sorted, without the hidden dot-prefixed ones macOS keeps for itself.
     *
     * From Skia's font manager rather than AWT. AWT's GraphicsEnvironment loads every font file on
     * the machine before it answers - many seconds with a large collection - and this was being
     * asked on the UI thread, which froze the window the moment the font picker opened. Skia only
     * enumerates, and its names are the ones its matcher resolves, so every name listed is one that
     * will actually render. Still read off the UI thread: see [load].
     */
    val families: List<String> by lazy {
        runCatching {
            val manager = org.jetbrains.skia.FontMgr.default
            (0 until manager.familiesCount).map { manager.getFamilyName(it) }
        }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && !it.startsWith(".") }
            .distinct()
            .sortedBy { it.lowercase() }
    }

    /** [families], fetched on a background thread. */
    suspend fun load(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { families }

    /**
     * [name] as a family, or null for the default.
     *
     * Several weights are named so bold and medium text pick the family's own heavier faces where it
     * has them; Skia falls back to synthesising the weight where it does not.
     */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    fun family(name: String): FontFamily? {
        if (name.isBlank()) return null
        return FontFamily(
            SystemFont(name, FontWeight.Normal),
            SystemFont(name, FontWeight.Medium),
            SystemFont(name, FontWeight.SemiBold),
            SystemFont(name, FontWeight.Bold),
        )
    }

    /** Material's type scale, every style set in [family]. */
    fun typography(family: FontFamily?): Typography {
        val base = Typography()
        if (family == null) return base
        fun TextStyle.f() = copy(fontFamily = family)
        return Typography(
            displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
            headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
            titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
            bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
            labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
        )
    }
}
