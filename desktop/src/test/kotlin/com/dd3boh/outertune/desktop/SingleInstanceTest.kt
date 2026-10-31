/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A second start finds the first holding the lock and asks it to show itself. Claimed twice from one
 * process here: the second tryLock fails on the held lock just as another process's would.
 */
class SingleInstanceTest {

    @Test
    fun `a second start hands over to the first and does not carry on`() {
        val dir = Files.createTempDirectory("ot-instance").toFile()
        val shown = CountDownLatch(1)
        SingleInstance.onActivate = { shown.countDown() }
        try {
            assertTrue("the first start runs", SingleInstance.claim(dir))
            assertFalse("the second start stops", SingleInstance.claim(dir))
            assertTrue("and the first is asked to show itself", shown.await(5, TimeUnit.SECONDS))
        } finally {
            SingleInstance.onActivate = {}
        }
    }
}
