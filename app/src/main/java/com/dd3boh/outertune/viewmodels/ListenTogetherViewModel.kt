/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.listentogether.DiscoveredHost
import com.dd3boh.outertune.listentogether.ListenTogetherManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Hands the listen-together screen its manager.
 *
 * Barely a view model, and deliberately so. The session outlives any screen - a host keeps sharing
 * while the user browses their library - so the state lives in the singleton manager and this only
 * survives configuration changes on the way to it. Holding session state here instead would end a
 * session on rotation.
 */
@HiltViewModel
class ListenTogetherViewModel @Inject constructor(
    val manager: ListenTogetherManager,
) : ViewModel() {

    /**
     * A fresh browse.
     *
     * Cold, and returned rather than held, so collection is tied to the screen: multicast browsing
     * keeps the radio busier than idle and has no reason to continue once nobody is looking.
     */
    fun discoverHosts(): Flow<List<DiscoveredHost>> = manager.discoverHosts()
}
