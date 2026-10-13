package com.internetazad369.vpn.service

import com.internetazad369.vpn.data.model.ConnectionState
import com.internetazad369.vpn.data.model.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnServiceStateHolder {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    fun updateState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updateTraffic(
        uplinkTotal: Long,
        downlinkTotal: Long,
        uplinkSpeed: Long,
        downlinkSpeed: Long
    ) {
        _trafficStats.value = TrafficStats(
            uplinkTotalBytes = uplinkTotal,
            downlinkTotalBytes = downlinkTotal,
            uplinkSpeedBps = uplinkSpeed,
            downlinkSpeedBps = downlinkSpeed
        )
    }

    fun reset() {
        _connectionState.value = ConnectionState.Disconnected
        _trafficStats.value = TrafficStats()
    }
}
