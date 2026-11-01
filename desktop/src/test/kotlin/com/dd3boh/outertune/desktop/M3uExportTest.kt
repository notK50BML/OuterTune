/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The playlist menu's Export M3U. Pinned to the phone's format line for line, because the phone's
 * import is what reads these back: "<artists> - <title>" after the comma, artists split on ";", and
 * the location on the next line.
 */
class M3uExportTest {

    @Test
    fun `matches the phone's export`() {
        val text = m3u(
            listOf(
                M3uEntry(337, "Daft Punk;Julian Casablancas", "Instant Crush", m3uLocation("abc123", null)),
                M3uEntry(-1, "Local Band", "Demo", m3uLocation("local:x", File("C:\\Music\\demo.mp3"))),
            )
        )
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:337,Daft Punk;Julian Casablancas - Instant Crush\n" +
                "https://youtube.com/watch?v=abc123\n" +
                "#EXTINF:-1,Local Band - Demo\n" +
                File("C:\\Music\\demo.mp3").absolutePath + "\n",
            text,
        )
    }

    @Test
    fun `an empty playlist is just the header`() {
        assertEquals("#EXTM3U\n", m3u(emptyList()))
    }
}
