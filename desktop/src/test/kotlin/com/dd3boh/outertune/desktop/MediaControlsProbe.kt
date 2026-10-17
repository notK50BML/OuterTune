package com.dd3boh.outertune.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Frame

/** Live, with OT_PROBE: attaches Windows' media controls to a real (unshown) window. */
class MediaControlsProbe {
    @Test
    fun attaches() {
        assumeTrue(System.getenv("OT_PROBE") != null)
        val frame = Frame("OuterTune probe")
        frame.addNotify()
        WindowsMediaControls.start(frame)
        repeat(40) { if (!WindowsMediaControls.active) Thread.sleep(100) }
        println("PROBE active=${WindowsMediaControls.active}")
        WindowsMediaControls.update("OuterTune connection test", "OuterTune", "Probe", "https://lh3.googleusercontent.com/a=w544-h544", playing = true)
        Thread.sleep(1500)
        WindowsMediaControls.update(null, null, null, null, playing = null)
        Thread.sleep(500)
        frame.dispose()
        assertTrue(WindowsMediaControls.active)
    }
}
