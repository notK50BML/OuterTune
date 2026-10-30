/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * How [resize] rewrites a cover URL for the size it will be drawn at.
 *
 * The YouTube video thumbnail cases matter most: a song that YouTube only has as a music video is
 * stored with its maxresdefault URL, and that image does not exist for a low-resolution upload. The
 * small size has to come back as a different, always-present image, or neither the rows nor
 * ArtworkFallbackInterceptor's retry can show anything for such a song.
 */
class ArtworkResizeTest {

    private val video = "https://i.ytimg.com/vi/jNQXAC9IVRw"
    private val query = "?sqp=-oaymwEWCJADEOEBIAQqCggAEOADGC0guwJIWg&rs=AMzJL3kM7kvTkFa2VXqFWIiftNGeowOcjQ"

    @Test
    fun `a large size asks for the full-resolution video thumbnail`() {
        assertEquals("$video/maxresdefault.jpg$query", "$video/hqdefault.jpg$query".resize(1080, 1080))
        assertEquals("$video/maxresdefault.jpg", "$video/sddefault.jpg".resize(1080, 1080))
    }

    @Test
    fun `a small size turns a stored maxresdefault into mqdefault, which every video has`() {
        assertEquals("$video/mqdefault.jpg", "$video/maxresdefault.jpg".resize(150, 150))
        assertEquals("$video/mqdefault.jpg$query", "$video/maxresdefault.jpg$query".resize(320, 320))
    }

    @Test
    fun `the low-res fallback finds a different URL to retry for a failed maxresdefault`() {
        // ArtworkFallbackInterceptor gives up when resize(320, 320) returns the URL unchanged.
        val failed = "$video/maxresdefault.jpg"
        assertNotEquals(failed, failed.resize(320, 320))
    }

    @Test
    fun `small sizes leave YouTube Music's own cropped thumbnails alone`() {
        val cropped = "$video/hq720.jpg?sqp=-oaymwEKCNUGEN8DIABIWg&rs=AMzJL3kdYKDuJwx"
        assertEquals(cropped, cropped.resize(150, 150))
        assertEquals("$video/hqdefault.jpg$query", "$video/hqdefault.jpg$query".resize(150, 150))
    }

    @Test
    fun `album art is asked for at the drawn size`() {
        val art = "https://yt3.googleusercontent.com/pbzBdDb17dzalZDm7YdsYLfUeEr1_mmm9eG4GYU4z8vio13sRpvN9-Q6SF3gatZbpcQRqsZwMiikHHA"
        assertEquals("$art=w1080-h1080-p-l90-rj", "$art=w120-h120-l90-rj".resize(1080, 1080))
        // Newer covers carry a trailing token; the resized URL drops it and still loads.
        assertEquals("$art=w320-h320-p-l90-rj", "$art=w544-h544-l90-rj-dcrXKQMS0H".resize(320, 320))
    }
}
