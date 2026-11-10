/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.CloudSyncFingerprintKey
import com.dd3boh.outertune.constants.CloudSyncRevKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.runBlocking

/** Cloud sync's memory in the app's settings. Read and written from the sync's IO thread only. */
class DataStoreCloudMemory(private val context: Context) : CloudSyncMemory {
    override var lastRev: String?
        get() = context.dataStore[CloudSyncRevKey]
        set(value) = write { if (value == null) it.remove(CloudSyncRevKey) else it[CloudSyncRevKey] = value }

    override var lastFingerprint: String?
        get() = context.dataStore[CloudSyncFingerprintKey]
        set(value) = write { if (value == null) it.remove(CloudSyncFingerprintKey) else it[CloudSyncFingerprintKey] = value }

    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking { context.dataStore.edit { block(it) } }
    }
}
