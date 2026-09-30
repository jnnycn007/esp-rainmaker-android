// Copyright 2026 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Tracks whether the phone currently has a network that can reach the internet.
 *
 * Needed by the param transport priority: a node flagged online by the last cloud
 * sync is only reachable through the cloud while the phone itself has internet.
 */
object EspNetworkMonitor {

    private const val TAG = "EspNetworkMonitor"

    private var connectivityManager: ConnectivityManager? = null

    /** null until the callback (or a direct read) has reported once. */
    @Volatile
    private var hasInternet: Boolean? = null

    fun interface Listener {
        fun onConnectivityChanged(connected: Boolean)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            // Validation has usually not finished yet; onCapabilitiesChanged corrects this.
            update(hasUsableInternet(capabilitiesOf(network)))
        }

        override fun onLost(network: Network) {
            update(false)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(hasUsableInternet(capabilities))
        }

        private fun update(usable: Boolean) {
            val changed = hasInternet != usable
            hasInternet = usable
            if (changed) {
                Log.d(TAG, "Internet reachable: $usable")
                for (listener in listeners) {
                    try {
                        listener.onConnectivityChanged(usable)
                    } catch (e: Exception) {
                        Log.e(TAG, "Connectivity listener failed: ${e.message}")
                    }
                }
            }
        }
    }

    /** Call once from [com.espressif.EspApplication.onCreate]. Safe to call again. */
    @JvmStatic
    fun init(context: Context) {
        if (connectivityManager != null) {
            return
        }
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Log.e(TAG, "ConnectivityManager unavailable")
            return
        }
        connectivityManager = manager
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * True when the phone has a network that can actually reach the internet.
     *
     * Reads the [ConnectivityManager] directly until the callback has reported once.
     * Only an unreadable ConnectivityManager resolves to true, so a cloud call that
     * would have succeeded is not blocked by a state we could not determine.
     */
    @JvmStatic
    fun isConnectedToNetwork(): Boolean {
        hasInternet?.let { return it }
        return readCurrentState()
    }

    private fun readCurrentState(): Boolean {
        val manager = connectivityManager ?: return true
        return try {
            val network = manager.activeNetwork ?: return false
            hasUsableInternet(capabilitiesOf(network))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read network state: ${e.message}")
            true
        }
    }

    private fun capabilitiesOf(network: Network): NetworkCapabilities? {
        return connectivityManager?.getNetworkCapabilities(network)
    }

    /**
     * NET_CAPABILITY_INTERNET alone only says the network is *meant* to provide internet -
     * Wi-Fi advertises it even when the router has no WAN, which made the app believe a
     * cloud path existed and suppress the BLE fallback. VALIDATED is the system's verdict
     * that it actually reached the internet, so both are required.
     */
    private fun hasUsableInternet(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) {
            return false
        }
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
