/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * What this build calls itself, for the About section and crash logs.
 *
 * The desktop app has its own release line now (0.3 beta), separate from the phone's 0.10.x.
 * [NUMERIC] is the same version as Windows' installer and jpackage need it - digits only - and is
 * what build.gradle.kts passes them; keep the two in step.
 */
object DesktopVersion {
    const val NAME = "0.3b"
    const val NUMERIC = "0.3.0"
}
