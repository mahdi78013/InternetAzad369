package com.internetazad369.vpn.data.model

/**
 * Immutable representation of a parsed proxy node.
 */
data class ProxyConfig(
    val id: String,
    val name: String,
    val protocol: String, // VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA2
    val server: String,
    val serverPort: Int,
    val uuid: String? = null,
    val password: String? = null,
    val flow: String? = null, // e.g. xtls-rprx-vision
    val security: String = "none", // none, tls, reality
    val sni: String? = null,
    val publicKey: String? = null, // pbk for reality
    val shortId: String? = null, // sid for reality
    val fingerprint: String? = null, // fp
    val network: String = "tcp", // tcp, ws, grpc, httpupgrade, xhttp
    val path: String? = null,
    val host: String? = null,
    val serviceName: String? = null, // grpc
    val alterId: Int = 0, // vmess
    val method: String? = null, // shadowsocks
    val alpn: List<String> = emptyList(),
    val packetEncoding: String? = null, // xudp
    val allowInsecure: Boolean = false,
    val hysteriaObfsPassword: String? = null,
    val rawLink: String = ""
)
