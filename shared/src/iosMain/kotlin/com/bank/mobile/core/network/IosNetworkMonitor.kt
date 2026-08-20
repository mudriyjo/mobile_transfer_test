@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bank.mobile.core.network

import com.bank.mobile.core.time.DeviceEpochClock
import com.bank.mobile.core.time.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

class IosNetworkMonitor(
    private val clock: EpochClock = DeviceEpochClock,
) : NetworkMonitor {
    private val monitor = nw_path_monitor_create()
    private val _isOnline = MutableStateFlow(false)
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    private var snapshot = NetworkSnapshot.fromOnlineFlag(false, clock.nowMillis())
    override val currentSnapshot: NetworkSnapshot
        get() = snapshot

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            val online = nw_path_get_status(path) == nw_path_status_satisfied
            snapshot = NetworkSnapshot(
                reachability = if (online) {
                    NetworkReachability.AVAILABLE
                } else {
                    NetworkReachability.UNAVAILABLE
                },
                transports = if (online) {
                    setOf(NetworkTransport.UNKNOWN)
                } else {
                    setOf(NetworkTransport.NONE)
                },
                internetCapability = online,
                observedAtEpochMillis = clock.nowMillis(),
            )
            _isOnline.value = online
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
    }

    override fun close() {
        nw_path_monitor_cancel(monitor)
    }
}
