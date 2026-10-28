/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A live stream played through the real player, silently, for longer than several of its segments:
 * it has to keep playing rather than finish after the first few seconds. `OT_PROBE=1` only - it goes
 * to the network and opens an audio line.
 */
class LivePlaybackProbe {
    @Test
    fun keepsPlaying() {
        assumeTrue(System.getenv("OT_PROBE") != null)
        val id = System.getenv("OT_LIVE_ID") ?: "4xDzrJKXOOY"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = DesktopPlayer().apply { volume = 0f }
        var finished = false
        player.onFinished = { finished = true }
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        if (System.getenv("OT_BACKGROUND") != null) player.inBackground = true
        try {
            player.play(scope, id, "live")
            Thread.sleep(6_000)
            val cpuStart = os.processCpuTime
            val wallStart = System.nanoTime()
            repeat(15) {
                Thread.sleep(2_000)
                println("t=${(it + 1) * 2}s state=${player.state.value} position=${player.positionMs.value}ms " +
                    "duration=${player.durationMs.value} finished=$finished details=${player.stream.value?.format}")
            }
            val cpuMs = (os.processCpuTime - cpuStart) / 1_000_000
            val wallMs = (System.nanoTime() - wallStart) / 1_000_000
            println("CPU while playing: ${cpuMs}ms over ${wallMs}ms = ${"%.1f".format(cpuMs * 100.0 / wallMs)}% of one core")
            val state = player.state.value
            assertTrue("still playing after 30s, was $state", state is PlaybackState.Playing && !finished)
            assertTrue("position kept moving, was ${player.positionMs.value}", player.positionMs.value > 20_000)
        } finally {
            player.stop()
            scope.cancel()
        }
    }

    /** Pause pressed while the song is still being fetched holds it: it arrives paused, then plays on play. */
    @Test
    fun pausedWhileLoading() {
        assumeTrue(System.getenv("OT_PROBE") != null)
        val id = System.getenv("OT_LIVE_ID") ?: "5yx6BWlEVcY"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = DesktopPlayer().apply { volume = 0f }
        try {
            player.play(scope, id, "live")
            assertTrue("loading at first, was ${player.state.value}", player.state.value is PlaybackState.Loading)
            player.togglePause()
            Thread.sleep(10_000)
            println("after 10s paused: ${player.state.value} position=${player.positionMs.value}")
            assertTrue("still paused once loaded, was ${player.state.value}", player.state.value is PlaybackState.Paused)
            assertTrue("nothing played while paused, was ${player.positionMs.value}", player.positionMs.value < 1_000)
            player.togglePause()
            Thread.sleep(6_000)
            println("after resuming 6s: ${player.state.value} position=${player.positionMs.value}")
            assertTrue("playing after play, was ${player.state.value}", player.state.value is PlaybackState.Playing)
            assertTrue("moving after play, was ${player.positionMs.value}", player.positionMs.value > 2_000)
        } finally {
            player.stop()
            scope.cancel()
        }
    }
}
