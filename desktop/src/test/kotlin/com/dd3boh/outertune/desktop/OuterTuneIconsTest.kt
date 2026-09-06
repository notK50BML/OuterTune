/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icons' geometry, measured rather than looked at.
 *
 * These are path strings typed by hand on a 960x960 grid, and a mistyped coordinate does not fail -
 * it draws something slightly wrong, slightly off-centre, or slightly the wrong size, sitting in a
 * row of icons that are correct. That is nearly invisible one glyph at a time and obvious as a row,
 * which is the worst way round to find it.
 *
 * So every icon is parsed and its bounding box checked: inside the viewport, and centred in it. The
 * second is what catches a transposed digit, because an icon drawn from the wrong origin is still a
 * perfectly valid path.
 */
class OuterTuneIconsTest {

    /** The extreme coordinates a path actually reaches. */
    private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val centreX get() = (minX + maxX) / 2
        val centreY get() = (minY + maxY) / 2
        val width get() = maxX - minX
        val height get() = maxY - minY
    }

    /**
     * Measures a path by parsing it properly and asking the result for its bounds.
     *
     * The first version of this read the numbers out of the string and paired them up, which is
     * wrong the moment a path uses a relative command - `v-180` is a length, not a position, and
     * `l160,-160` is an offset. Half the icons here are written that way, and the naive version
     * duly reported one of them as starting 240 units left of the viewport.
     *
     * That is worth recording rather than quietly fixing: a measurement that cannot read the thing
     * it measures produces confident, specific, wrong numbers, and the first instinct on seeing
     * them is to go and "fix" a glyph that was never broken.
     */
    private fun boundsOf(path: String): Bounds {
        val parsed = PathParser().parsePathString(path).toPath()
        val bounds = parsed.getBounds()
        return Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    @Test
    fun `every icon parses`() {
        // The first thing a mistyped path does is fail to parse, and an ImageVector that throws when
        // built takes the screen with it rather than drawing nothing.
        OuterTuneIcons.allPaths.forEach { (name, path) ->
            runCatching { PathParser().parsePathString(path).toNodes() }
                .onFailure { error("$name did not parse: ${it.message}") }
        }
        assertTrue("no icons were registered", OuterTuneIcons.allPaths.isNotEmpty())
    }

    @Test
    fun `every icon stays inside the viewport`() {
        // A coordinate outside 0..960 is clipped, so the glyph silently loses an edge.
        OuterTuneIcons.allPaths.forEach { (name, path) ->
            val b = boundsOf(path)
            assertTrue("$name starts left of the viewport at ${b.minX}", b.minX >= 0f)
            assertTrue("$name starts above the viewport at ${b.minY}", b.minY >= 0f)
            assertTrue("$name runs past the right edge at ${b.maxX}", b.maxX <= 960f)
            assertTrue("$name runs past the bottom edge at ${b.maxY}", b.maxY <= 960f)
        }
    }

    @Test
    fun `every icon is centred in the viewport`() {
        // The check that actually catches a transposed digit. An icon drawn from the wrong origin is
        // a perfectly valid path and renders happily - just not in the middle, so it sits visibly
        // higher or further left than its neighbours in a row of controls.
        //
        // The tolerance is generous because some glyphs are legitimately asymmetric - a skip icon is
        // a triangle and a bar - but a genuine mistake moves the centre far further than this.
        OuterTuneIcons.allPaths.forEach { (name, path) ->
            val b = boundsOf(path)
            assertTrue(
                "$name is centred at x=${b.centreX}, should be near 480",
                kotlin.math.abs(b.centreX - 480f) <= 60f,
            )
            assertTrue(
                "$name is centred at y=${b.centreY}, should be near 480",
                kotlin.math.abs(b.centreY - 480f) <= 60f,
            )
        }
    }

    @Test
    fun `every icon fills a sensible part of the viewport`() {
        // Too small and it looks lost beside the others; too large and it has no margin and reads as
        // heavier. Both are the kind of thing noticed only once the row is assembled.
        OuterTuneIcons.allPaths.forEach { (name, path) ->
            val b = boundsOf(path)
            assertTrue("$name is only ${b.width} wide", b.width >= 300f)
            assertTrue("$name is only ${b.height} tall", b.height >= 300f)
            assertTrue("$name is ${b.width} wide, filling the viewport edge to edge", b.width <= 900f)
            assertTrue("$name is ${b.height} tall, filling the viewport edge to edge", b.height <= 900f)
        }
    }

    @Test
    fun `the seek pair are mirror images of each other`() {
        // They sit either side of play and are read as a pair, so an asymmetry between them is
        // immediately visible even though neither is wrong on its own.
        val forward = boundsOf(OuterTuneIcons.allPaths.getValue("fastForward"))
        val rewind = boundsOf(OuterTuneIcons.allPaths.getValue("fastRewind"))

        assertTrue("widths differ: ${forward.width} vs ${rewind.width}", forward.width == rewind.width)
        assertTrue("heights differ: ${forward.height} vs ${rewind.height}", forward.height == rewind.height)
        // Mirroring about the centre line means the left edge of one is the right edge of the other.
        assertTrue(
            "not mirrored: forward ${forward.minX}..${forward.maxX}, rewind ${rewind.minX}..${rewind.maxX}",
            forward.minX == 960f - rewind.maxX && forward.maxX == 960f - rewind.minX,
        )
    }

    @Test
    fun `the equaliser bars stand on one baseline`() {
        // Bars rise from a floor. Centring each one individually would read as a bar chart of
        // nothing, which is a mistake that looks deliberate.
        val path = OuterTuneIcons.allPaths.getValue("equalizer")
        // Each subpath measured on its own. Split on the close command and stitched back into a
        // complete path, since a fragment starting mid-shape is not something a parser can read.
        val bars = path.split("Z").filter { it.isNotBlank() }.map { boundsOf(it + "Z") }
        assertTrue("expected three bars, found ${bars.size}", bars.size == 3)

        val baselines = bars.map { it.maxY }
        assertTrue("bars do not share a baseline: $baselines", baselines.distinct().size == 1)

        val heights = bars.map { it.height }
        assertTrue("bars are all the same height, which reads as a block: $heights", heights.distinct().size > 1)
    }
}
