package com.internetazad369.vpn.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Helpers to find the REAL (non-VPN) network while our own VPN may already be up.
 *
 * Why this exists: `ConnectivityManager.activeNetwork` and an unfiltered NetworkCallback both return
 * our own tun interface once the VPN is established, which must never be reported to sing-box as the
 * "default interface".
 */
object UnderlyingNetworks {

    @Suppress("DEPRECATION")
    fun pick(cm: ConnectivityManager): Network? {
        val candidates = try {
            cm.allNetworks.toList()
        } catch (_: Exception) {
            emptyList()
        }
        return candidates
            .mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val rank = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    else -> 3
                }
                Triple(net, validated, rank)
            }
            .sortedWith(compareByDescending<Triple<Network, Boolean, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()
            ?.first
    }

    fun interfaceName(cm: ConnectivityManager, network: Network): String? =
        cm.getLinkProperties(network)?.interfaceName?.takeIf { it.isNotBlank() }

    /** Kernel interface index for [name], or -1 when it does not exist (-1 == "no network" for libbox). */
    fun interfaceIndex(name: String): Int = try {
        NetworkInterface.getByName(name)?.index ?: -1
    } catch (_: Exception) {
        -1
    }

    /** IPv4 DNS servers handed out by the operator / Wi-Fi on the underlying network. */
    fun systemDnsServers(cm: ConnectivityManager): List<String> {
        val net = pick(cm) ?: return emptyList()
        val servers = cm.getLinkProperties(net)?.dnsServers ?: return emptyList()
        return servers
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress }
    }
}
