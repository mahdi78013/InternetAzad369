package com.internetazad369.vpn.data.model

data class ProfileMetadata(
    val title: String = "Mahsa",
    val updateIntervalHours: Int = 24,
    val supportUrl: String? = null,
    val subUrls: List<String> = emptyList()
)

data class TrafficStats(
    val uplinkTotalBytes: Long = 0L,
    val downlinkTotalBytes: Long = 0L,
    val uplinkSpeedBps: Long = 0L,
    val downlinkSpeedBps: Long = 0L
)
