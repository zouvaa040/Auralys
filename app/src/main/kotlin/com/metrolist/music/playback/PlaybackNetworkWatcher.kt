/*
 * Auralys (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import timber.log.Timber

/**
 * What the player needs to know about the DEFAULT network (the one the app's requests use), in one place, pure:
 *  - [validated]: Android checked that it really reaches the internet (NET_CAPABILITY_VALIDATED);
 *  - [unmetered]: it is not charged per byte (Wi-Fi, an unmetered 5G plan).
 *
 * [onCapabilities] returns true when a validated default network APPEARS: the default network became validated, or a new
 * default network is validated. That is the moment a playback waiting after connection errors retries at once
 * (review 2026-11-12, finding 6: the old code waited for the "online" flag to change, and a network that stayed "online"
 * while DNS or the server failed never changed it).
 */
internal class DefaultNetworkState {
    var network: Any? = null
        private set
    var validated: Boolean = false
        private set
    var unmetered: Boolean = false
        private set

    fun onCapabilities(
        network: Any,
        validated: Boolean,
        unmetered: Boolean,
    ): Boolean {
        val appeared = validated && (!this.validated || this.network != network)
        this.network = network
        this.validated = validated
        this.unmetered = unmetered
        return appeared
    }

    fun onLost(network: Any) {
        if (this.network != network) return
        this.network = null
        validated = false
        unmetered = false
    }
}

/**
 * Follows the default network for the player ([DefaultNetworkState]); callbacks come on [handler] (the main thread), and
 * [onValidatedNetwork] runs there when a validated default network appears. [unmetered] may be read from any thread
 * (the playback thread asks it for every load decision, see [NetworkAwareLoadControl]).
 */
internal class PlaybackNetworkWatcher(
    private val connectivityManager: ConnectivityManager,
    private val handler: Handler,
    private val onValidatedNetwork: () -> Unit,
) {
    private val state = DefaultNetworkState()

    @Volatile
    var unmetered: Boolean = initialUnmetered()
        private set

    @Volatile
    var validated: Boolean = false
        private set

    private var registered = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val appeared =
                    state.onCapabilities(
                        network = network,
                        validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        unmetered = isUnmetered(networkCapabilities),
                    )
                publish()
                if (appeared) {
                    try {
                        onValidatedNetwork()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "validated-network handler failed")
                    }
                }
            }

            override fun onLost(network: Network) {
                state.onLost(network)
                publish()
            }
        }

    fun start() {
        if (registered) return
        registered =
            try {
                connectivityManager.registerDefaultNetworkCallback(callback, handler)
                true
            } catch (e: Exception) {
                // too many callbacks registered or a broken OEM service: the timed retries still work without it
                Timber.tag(TAG).w(e, "default network callback not registered")
                false
            }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun publish() {
        unmetered = state.unmetered
        validated = state.validated
    }

    private fun initialUnmetered(): Boolean =
        try {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)?.let(::isUnmetered) ?: false
        } catch (_: Exception) {
            false
        }

    private companion object {
        const val TAG = "PlaybackNetwork"

        fun isUnmetered(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    }
}
