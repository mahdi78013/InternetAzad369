package com.internetazad369.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves proxy hostnames with the system resolver on the underlying network BEFORE the VPN is
 * established. This removes the chicken-and-egg problem "to reach the proxy I need DNS, to get DNS I
 * need the proxy" and does not depend on 1.1.1.1:53 / DoH being reachable from the operator.
 */
object HostResolver {

    private const val PARALLELISM = 16

    suspend fun resolveAll(
        context: Context,
        hosts: Collection<String>,
        timeoutMs: Long = 3500
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.let { UnderlyingNetworks.pick(it) }
        val gate = Semaphore(PARALLELISM)

        coroutineScope {
            hosts.distinct()
                .filter { it.isNotBlank() && !looksLikeIp(it) }
                .map { host ->
                    async {
                        gate.withPermit {
                            val ip = withTimeoutOrNull(timeoutMs) {
                                runInterruptible {
                                    try {
                                        val all = if (network != null) network.getAllByName(host)
                                        else InetAddress.getAllByName(host)
                                        all.filterIsInstance<Inet4Address>()
                                            .firstOrNull { !isBogon(it) }
                                            ?.hostAddress
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                            if (ip != null) host to ip else null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
    }

    /**
     * Filters poisoned / sinkhole answers (e.g. the 10.10.34.x block-page addresses returned by
     * national DNS filtering for blocked names).
     */
    internal fun isBogon(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress || isCgnat(a)

    private fun isCgnat(a: InetAddress): Boolean {
        val b = a.address
        return b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
    }

    private fun looksLikeIp(host: String): Boolean =
        host.contains(":") || host.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$"))
}
