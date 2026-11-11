/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.CloudRelayDeviceKey
import com.dd3boh.outertune.constants.CloudRelaySentKey
import com.dd3boh.outertune.constants.CloudRelayTakenKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.runBlocking

/** Cloud relay's memory in the app's settings. Read and written from the relay's IO thread only. */
class DataStoreCloudMemory(private val context: Context) : CloudSyncMemory {
    override var deviceId: String?
        get() = context.dataStore[CloudRelayDeviceKey]?.ifBlank { null }
        set(value) = write { if (value == null) it.remove(CloudRelayDeviceKey) else it[CloudRelayDeviceKey] = value }

    override var sentUpTo: Long
        get() = context.dataStore[CloudRelaySentKey] ?: 0L
        set(value) = write { it[CloudRelaySentKey] = value }

    override var taken: Set<String>
        get() = context.dataStore[CloudRelayTakenKey].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        set(value) = write { it[CloudRelayTakenKey] = value.joinToString(",") }

    private fun write(block: (MutablePreferences) -> Unit) {
        runBlocking { context.dataStore.edit { block(it) } }
    }
}
