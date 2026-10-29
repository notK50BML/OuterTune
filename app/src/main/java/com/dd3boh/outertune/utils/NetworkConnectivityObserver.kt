/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkStatus = Channel<Boolean>(Channel.CONFLATED)
    val networkStatus = _networkStatus.receiveAsFlow()

    /**
     * Every network with internet that is up right now. "Offline" is none of them - not the loss of
     * any one: switching from Wi-Fi to mobile data loses the Wi-Fi after the mobile network is
     * already up, and reporting that loss as offline left the app saying so while it was online.
     */
    private val available = java.util.Collections.synchronizedSet(HashSet<Network>())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            available.add(network)
            _networkStatus.trySend(true)
        }

        override fun onLost(network: Network) {
            available.remove(network)
            _networkStatus.trySend(available.isNotEmpty())
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}