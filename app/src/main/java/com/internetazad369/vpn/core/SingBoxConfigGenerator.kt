package com.internetazad369.vpn.core

import com.internetazad369.vpn.data.model.ProxyConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SingBoxConfigGenerator {

    /** Upper bound of nodes inside the urltest group (every node is probed at start and every interval). */
    const val MAX_NODES = 40

    private const val DNS_REMOTE = "dns-remote"
    private const val DNS_DIRECT = "dns-direct"

    private val SUPPORTED_TRANSPORTS = setOf("", "tcp", "ws", "websocket", "grpc", "httpupgrade")
    private val VALID_PACKET_ENCODINGS = setOf("packetaddr", "xudp")
    private val VALID_FINGERPRINTS = setOf(
        "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized"
    )
    private val VMESS_CIPHERS = setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-ctr")

    /**
     * Builds a sing-box (1.12 / 1.13 compatible) JSON configuration.
     *
     * @param resolvedHosts domain -> IPv4 map resolved by the app BEFORE the tunnel is up
     *        (see [HostResolver]). Proxy servers that appear in this map are dialed by IP, so the
     *        core never needs DNS to reach them. TLS SNI / WS Host are preserved from the original domain.
     * @param systemDns DNS servers of the underlying (non-VPN) network. Used only for the "direct"
     *        resolver that bootstraps proxy domains which could not be pre-resolved.
     */
    fun generate(
        proxies: List<ProxyConfig>,
        logFile: File? = null,
        resolvedHosts: Map<String, String> = emptyMap(),
        systemDns: List<String> = emptyList(),
        logLevel: String = "info"
    ): String {
        require(proxies.isNotEmpty()) { "حداقل یک کانفیگ برای راه‌اندازی موردنیاز است" }

        val usable = selectUsableProxies(proxies)
        require(usable.isNotEmpty()) { "هیچ کانفیگ سازگار با هسته در اشتراک یافت نشد" }

        val root = JSONObject()

        // 1. Log
        root.put("log", JSONObject().apply {
            put("disabled", false)
            put("level", logLevel)
            put("timestamp", true)
            if (logFile != null) put("output", logFile.absolutePath)
        })

        // 2. DNS
        //  - dns-remote : UDP 8.8.8.8 carried INSIDE the tunnel through the proxy group. Zero TLS cert errors.
        //  - dns-remote-tcp : TCP 8.8.8.8 fallback inside tunnel.
        //  - dns-direct : plain UDP to the operator's own resolver, from a protected socket (never through the
        //                 tunnel). Only used to bootstrap proxy hostnames that were not pre-resolved.
        val directDnsIp = systemDns.firstOrNull { isIpv4(it) } ?: "1.1.1.1"
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "udp")
                    put("tag", DNS_REMOTE)
                    put("server", "8.8.8.8")
                    put("server_port", 53)
                    put("detour", "auto")
                })
                put(JSONObject().apply {
                    put("type", "tcp")
                    put("tag", "dns-remote-tcp")
                    put("server", "8.8.8.8")
                    put("server_port", 53)
                    put("detour", "auto")
                })
                put(JSONObject().apply {
                    put("type", "https")
                    put("tag", "dns-remote-doh")
                    put("server", "1.1.1.1")
                    put("server_port", 443)
                    put("path", "/dns-query")
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        put("server_name", "cloudflare-dns.com")
                        put("insecure", true)
                    })
                    put("detour", "auto")
                })
                put(JSONObject().apply {
                    put("type", "udp")
                    put("tag", DNS_DIRECT)
                    put("server", directDnsIp)
                    put("server_port", 53)
                })
            })
            put("final", DNS_REMOTE)
            // The tunnel is IPv4-only: never hand IPv6 answers to apps (they would stall on a dead v6 path).
            put("strategy", "ipv4_only")
        })

        // 3. Inbounds
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply { put("172.19.0.1/30") })
                put("mtu", 1500)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
            })
        })

        // 4. Outbounds
        val outbounds = JSONArray()
        val tags = mutableListOf<String>()
        usable.forEachIndexed { i, proxy ->
            val tag = "node-$i"
            tags.add(tag)
            outbounds.put(buildSingleProxyOutbound(proxy, tag, resolvedHosts[proxy.server]))
        }

        outbounds.put(JSONObject().apply {
            put("type", "urltest")
            put("tag", "auto")
            put("outbounds", JSONArray().apply { tags.forEach { put(it) } })
            put("url", "http://cp.cloudflare.com/generate_204")
            put("interval", "1m")
            put("tolerance", 50)
            put("interrupt_exist_connections", true)
        })

        outbounds.put(JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        })
        root.put("outbounds", outbounds)

        // 5. Route
        root.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            // Any dial-by-domain that is not covered by an explicit resolver must NOT fall back to
            // dns-remote (that one is detoured through "auto" => resolve-loop).
            put("default_domain_resolver", JSONObject().apply {
                put("server", DNS_DIRECT)
                put("strategy", "ipv4_only")
            })
            put("rules", JSONArray().apply {
                put(JSONObject().apply { put("action", "sniff") })
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                put(JSONObject().apply {
                    put("port", JSONArray().apply { put(53) })
                    put("action", "hijack-dns")
                })
                put(JSONObject().apply {
                    put("ip_is_private", true)
                    put("action", "route")
                    put("outbound", "direct")
                })
            })
            put("final", "auto")
        })

        return root.toString(2)
    }

    // ------------------------------------------------------------------
    // Node selection
    // ------------------------------------------------------------------

    /**
     * Drops nodes sing-box cannot run correctly (they would silently degrade to raw TCP and just burn
     * urltest slots), removes duplicates and caps the list, interleaving protocol families so the
     * limited slots still cover as many different "fingerprints" as possible.
     */
    internal fun selectUsableProxies(proxies: List<ProxyConfig>): List<ProxyConfig> {
        val filtered = proxies.filter { isSupported(it) }
        val deduped = filtered.distinctBy {
            "${it.protocol}|${it.server}|${it.serverPort}|${it.uuid ?: it.password}|${it.network}|${it.path}"
        }
        if (deduped.size <= MAX_NODES) return deduped

        val groups = deduped
            .groupBy { "${it.protocol}|${it.security}|${it.network}" }
            .values
            .map { it.toMutableList() }
        val result = ArrayList<ProxyConfig>(MAX_NODES)
        while (result.size < MAX_NODES && groups.any { it.isNotEmpty() }) {
            for (g in groups) {
                if (result.size >= MAX_NODES) break
                if (g.isNotEmpty()) result.add(g.removeAt(0))
            }
        }
        return result
    }

    private fun isSupported(p: ProxyConfig): Boolean {
        if (p.server.isBlank() || p.serverPort !in 1..65535) return false
        return when (p.protocol.uppercase()) {
            "VLESS" -> !p.uuid.isNullOrBlank() &&
                p.network.lowercase() in SUPPORTED_TRANSPORTS &&
                (!p.security.equals("reality", true) || !p.publicKey.isNullOrBlank())
            "VMESS" -> !p.uuid.isNullOrBlank() && p.network.lowercase() in SUPPORTED_TRANSPORTS
            "TROJAN" -> !p.password.isNullOrBlank() && p.network.lowercase() in SUPPORTED_TRANSPORTS
            "SHADOWSOCKS", "SS" -> !p.password.isNullOrBlank() && !p.rawLink.contains("plugin=", true)
            "HYSTERIA2", "HY2" -> !p.password.isNullOrBlank()
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // Outbound builders
    // ------------------------------------------------------------------

    private fun buildSingleProxyOutbound(proxy: ProxyConfig, tag: String, resolvedIp: String?): JSONObject {
        val out = JSONObject()
        out.put("tag", tag)

        when (proxy.protocol.uppercase()) {
            "VLESS" -> {
                out.put("type", "vless")
                putServer(out, proxy, resolvedIp)
                out.put("uuid", proxy.uuid ?: "")
                // sing-box only knows "xtls-rprx-vision"; anything else (e.g. "none") makes the whole core fail to start.
                if (proxy.flow?.trim()?.lowercase()?.startsWith("xtls-rprx-vision") == true) {
                    out.put("flow", "xtls-rprx-vision")
                }
                putPacketEncoding(out, proxy.packetEncoding)
                buildTlsAndReality(out, proxy)
                buildTransport(out, proxy, resolvedIp != null)
            }

            "VMESS" -> {
                out.put("type", "vmess")
                putServer(out, proxy, resolvedIp)
                out.put("uuid", proxy.uuid ?: "")
                // NOTE: ProxyConfig.security holds the TLS mode ("tls"/"none") for VMess, never a cipher.
                out.put("security", proxy.method?.takeIf { it in VMESS_CIPHERS } ?: "auto")
                out.put("alter_id", proxy.alterId)
                putPacketEncoding(out, proxy.packetEncoding)
                if (proxy.security.equals("tls", ignoreCase = true)) buildTls(out, proxy)
                buildTransport(out, proxy, resolvedIp != null)
            }

            "TROJAN" -> {
                out.put("type", "trojan")
                putServer(out, proxy, resolvedIp)
                out.put("password", proxy.password ?: "")
                buildTls(out, proxy)
                buildTransport(out, proxy, resolvedIp != null)
            }

            "SHADOWSOCKS", "SS" -> {
                out.put("type", "shadowsocks")
                putServer(out, proxy, resolvedIp)
                out.put("method", proxy.method ?: "aes-256-gcm")
                out.put("password", proxy.password ?: "")
            }

            "HYSTERIA2", "HY2" -> {
                out.put("type", "hysteria2")
                putServer(out, proxy, resolvedIp)
                out.put("password", proxy.password ?: "")
                out.put("tls", JSONObject().apply {
                    put("enabled", true)
                    val serverName = proxy.sni ?: proxy.server
                    if (serverName.isNotBlank()) put("server_name", serverName)
                    put("insecure", proxy.allowInsecure)
                    put("alpn", JSONArray().apply {
                        if (proxy.alpn.isNotEmpty()) proxy.alpn.forEach { put(it) } else put("h3")
                    })
                })
                if (!proxy.hysteriaObfsPassword.isNullOrBlank()) {
                    out.put("obfs", JSONObject().apply {
                        put("type", "salamander")
                        put("password", proxy.hysteriaObfsPassword)
                    })
                }
            }

            else -> {
                out.put("type", "direct")
            }
        }

        return out
    }

    /**
     * sing-box accepts only "", "packetaddr" and "xudp". Subscriptions routinely carry `packetEncoding=none`
     * (or "udp", "off"...) and a single invalid value aborts the start of the whole core
     * ("unknown packet encoding: none"), so anything unknown is dropped / defaulted to xudp.
     */
    private fun putPacketEncoding(out: JSONObject, raw: String?) {
        val v = raw?.trim()?.lowercase().orEmpty()
        when {
            v in VALID_PACKET_ENCODINGS -> out.put("packet_encoding", v)
            v.isEmpty() -> out.put("packet_encoding", "xudp")
            // explicit "none"/unknown: leave the field out (plain UDP-over-stream, always valid)
        }
    }

    private fun validFingerprint(fp: String?): String? =
        fp?.trim()?.lowercase()?.takeIf { it in VALID_FINGERPRINTS }

    /** Dials the pre-resolved IP when we have one, otherwise the hostname with an explicit bootstrap resolver. */
    private fun putServer(out: JSONObject, proxy: ProxyConfig, resolvedIp: String?) {
        out.put("server", resolvedIp ?: proxy.server)
        out.put("server_port", proxy.serverPort)
        if (resolvedIp == null && !isIpAddress(proxy.server)) {
            out.put("domain_resolver", DNS_DIRECT)
        }
    }

    private fun buildTls(out: JSONObject, proxy: ProxyConfig) {
        out.put("tls", JSONObject().apply {
            put("enabled", true)
            val serverName = proxy.sni ?: proxy.host ?: proxy.server
            if (serverName.isNotBlank()) put("server_name", serverName)
            put("insecure", proxy.allowInsecure)
            if (proxy.alpn.isNotEmpty()) {
                put("alpn", JSONArray().apply { proxy.alpn.forEach { put(it) } })
            }
            validFingerprint(proxy.fingerprint)?.let { fp ->
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", fp)
                })
            }
        })
    }

    private fun buildTlsAndReality(out: JSONObject, proxy: ProxyConfig) {
        if (proxy.security.equals("reality", ignoreCase = true) || !proxy.publicKey.isNullOrBlank()) {
            out.put("tls", JSONObject().apply {
                put("enabled", true)
                val serverName = proxy.sni ?: proxy.server
                if (serverName.isNotBlank()) put("server_name", serverName)
                put("insecure", proxy.allowInsecure)
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", proxy.publicKey ?: "")
                    if (!proxy.shortId.isNullOrBlank()) put("short_id", proxy.shortId)
                })
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", validFingerprint(proxy.fingerprint) ?: "chrome")
                })
            })
        } else if (proxy.security.equals("tls", ignoreCase = true)) {
            buildTls(out, proxy)
        }
    }

    private fun buildTransport(out: JSONObject, proxy: ProxyConfig, dialedByIp: Boolean) {
        // When we dial by IP the Host header must still carry the original domain, otherwise CDN-fronted
        // nodes (the vast majority of WS/gRPC/HTTPUpgrade nodes) cannot route the request.
        val hostHeader = proxy.host?.takeIf { it.isNotBlank() }
            ?: if (dialedByIp) proxy.sni?.takeIf { it.isNotBlank() } ?: proxy.server else null

        val transport = JSONObject()
        when (proxy.network.lowercase()) {
            "ws", "websocket" -> {
                transport.put("type", "ws")
                if (!proxy.path.isNullOrBlank()) transport.put("path", proxy.path)
                if (hostHeader != null) {
                    transport.put("headers", JSONObject().apply { put("Host", hostHeader) })
                }
                out.put("transport", transport)
            }

            "grpc" -> {
                transport.put("type", "grpc")
                if (!proxy.serviceName.isNullOrBlank()) transport.put("service_name", proxy.serviceName)
                out.put("transport", transport)
            }

            "httpupgrade" -> {
                transport.put("type", "httpupgrade")
                if (hostHeader != null) transport.put("host", hostHeader)
                if (!proxy.path.isNullOrBlank()) transport.put("path", proxy.path)
                out.put("transport", transport)
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isIpv4(host: String): Boolean =
        host.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$"))

    private fun isIpAddress(host: String): Boolean {
        if (host.isBlank()) return false
        return isIpv4(host) || host.contains(":")
    }
}
