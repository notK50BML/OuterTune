/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import android.content.Context

/**
 * Cloud relay's memory of this device: its id in the relay, what it last sent, which bundles it has
 * taken.
 *
 * Deliberately not in the app's settings, which a backup carries whole: restored onto a second
 * phone, the same id would make two devices one in the relay's eyes, and bundles one of them took
 * would count as taken by both. Kept apart, a restored device starts as a new one - it gets a new
 * id and asks the others for everything once, which the play identity makes harmless.
 */
class DataStoreCloudMemory(context: Context) : CloudSyncMemory {
    private val prefs = context.getSharedPreferences("cloud_relay", Context.MODE_PRIVATE)

    override var deviceId: String?
        get() = prefs.getString("device", null)?.ifBlank { null }
        set(value) = prefs.edit().putString("device", value).apply()

    override var sentUpTo: Long
        get() = prefs.getLong("sent", 0L)
        set(value) = prefs.edit().putLong("sent", value).apply()

    override var taken: Set<String>
        get() = prefs.getString("taken", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString("taken", value.joinToString(",")).apply()
}
