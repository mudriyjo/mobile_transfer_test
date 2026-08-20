package com.bank.mobile.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkMonitor(
    context: Context,
    private val clock: EpochClock = DeviceEpochClock,
) : NetworkMonitor {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isOnline = MutableStateFlow(connectivityManager.currentConnectivity())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    private var snapshot = connectivityManager.currentNetworkSnapshot()
    override val currentSnapshot: NetworkSnapshot
        get() = snapshot

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish(connectivityManager.getNetworkCapabilities(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publish(capabilities)
        }

        override fun onLost(network: Network) {
            publish(connectivityManager.activeNetwork?.let(connectivityManager::getNetworkCapabilities))
        }
    }

    init {
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(callback)
    }

    private fun publish(capabilities: NetworkCapabilities?) {
        snapshot = capabilities.toSnapshot()
        _isOnline.value = snapshot.isOnline
    }

    private fun ConnectivityManager.currentConnectivity(): Boolean {
        val network = activeNetwork ?: return false
        val capabilities = getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun ConnectivityManager.currentNetworkSnapshot(): NetworkSnapshot =
        activeNetwork?.let(::getNetworkCapabilities).toSnapshot()

    private fun NetworkCapabilities?.toSnapshot(): NetworkSnapshot {
        if (this == null) {
            return NetworkSnapshot.fromOnlineFlag(false, clock.nowMillis())
        }
        val hasInternetCapability = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transports = buildSet {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
            if (isEmpty()) add(NetworkTransport.OTHER)
        }
        return NetworkSnapshot(
            reachability = if (hasInternetCapability) {
                NetworkReachability.AVAILABLE
            } else {
                NetworkReachability.UNAVAILABLE
            },
            transports = transports,
            internetCapability = hasInternetCapability,
            validatedByPlatform = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            expensive = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            constrained = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            observedAtEpochMillis = clock.nowMillis(),
        )
    }
}
