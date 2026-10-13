package com.internetazad369.vpn.data.parser

import com.internetazad369.vpn.data.model.ProxyConfig
import org.json.JSONObject
import java.net.URI
import java.util.UUID

object ProtocolParser {

    fun parse(rawUri: String): ProxyConfig? {
        val trimmed = rawUri.trim()
        if (trimmed.isBlank()) return null

        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("hysteria2://", ignoreCase = true) ||
                trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun truthy(value: Any?): Boolean {
        val v = value?.toString()?.trim()?.lowercase() ?: return false
        return v == "1" || v == "true" || v == "yes"
    }

    private fun parseVless(uriString: String): ProxyConfig? {
        val uri = URI(uriString)
        val userInfo = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val params = UriDecoderUtil.parseQueryParams(uri.rawQuery)
        val fragmentName = UriDecoderUtil.urlDecode(uri.fragment).ifBlank { "VLESS-$host:$port" }

        val security = params["security"] ?: "none"
        val flow = params["flow"]?.takeIf { it.isNotBlank() }
        val sni = params["sni"]?.takeIf { it.isNotBlank() } ?: params["peer"]
        val pbk = params["pbk"]?.takeIf { it.isNotBlank() }
        val sid = params["sid"]?.takeIf { it.isNotBlank() }
        val fp = params["fp"]?.takeIf { it.isNotBlank() }
        val network = params["type"] ?: params["net"] ?: "tcp"
        val path = params["path"]?.takeIf { it.isNotBlank() }
        val hostHeader = params["host"]?.takeIf { it.isNotBlank() }
        val serviceName = params["servicename"]?.takeIf { it.isNotBlank() }
        val packetEncoding = params["packetencoding"]?.trim()?.lowercase()
            ?.takeIf { it == "xudp" || it == "packetaddr" } ?: "xudp"
        val alpnList = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return ProxyConfig(
            id = UUID.nameUUIDFromBytes(uriString.toByteArray()).toString(),
            name = fragmentName,
            protocol = "VLESS",
            server = host,
            serverPort = port,
            uuid = userInfo,
            flow = flow,
            security = security,
            sni = sni,
            publicKey = pbk,
            shortId = sid,
            fingerprint = fp,
            network = network,
            path = path,
            host = hostHeader,
            serviceName = serviceName,
            alpn = alpnList,
            packetEncoding = packetEncoding,
            allowInsecure = truthy(params["allowinsecure"] ?: params["insecure"]),
            rawLink = uriString
        )
    }

    private fun parseVmess(uriString: String): ProxyConfig? {
        val payload = uriString.substring("vmess://".length).trim()
        val fragmentIdx = payload.indexOf('#')
        val (base64Part, fragmentName) = if (fragmentIdx >= 0) {
            payload.substring(0, fragmentIdx) to UriDecoderUtil.urlDecode(payload.substring(fragmentIdx + 1))
        } else {
            payload to ""
        }

        val decoded = UriDecoderUtil.safeBase64Decode(base64Part) ?: return null
        val json = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            return null
        }

        val server = json.optString("add").trim()
        val portStr = json.optString("port").trim()
        val port = portStr.toIntOrNull() ?: json.optInt("port", -1)
        if (server.isBlank() || port <= 0 || port > 65535) return null

        val uuid = json.optString("id").trim()
        if (uuid.isBlank()) return null

        val alterId = json.optInt("aid", 0)
        val net = json.optString("net", "tcp").lowercase().trim()
        val path = json.optString("path").takeIf { it.isNotBlank() }
        val host = json.optString("host").takeIf { it.isNotBlank() }
        val tls = json.optString("tls", "none").lowercase().trim()
        val sni = json.optString("sni").takeIf { it.isNotBlank() }
        val name = json.optString("ps").ifBlank { fragmentName.ifBlank { "VMess-$server:$port" } }
        val alpnStr = json.optString("alpn")
        val alpnList = if (alpnStr.isNotBlank()) alpnStr.split(",").map { it.trim() } else emptyList()

        return ProxyConfig(
            id = UUID.nameUUIDFromBytes(uriString.toByteArray()).toString(),
            name = name,
            protocol = "VMESS",
            server = server,
            serverPort = port,
            uuid = uuid,
            alterId = alterId,
            method = json.optString("scy").trim().takeIf { it.isNotBlank() },
            allowInsecure = truthy(json.opt("allowInsecure")),
            security = if (tls == "tls") "tls" else "none",
            sni = sni,
            network = net,
            path = path,
            host = host,
            alpn = alpnList,
            packetEncoding = "xudp",
            rawLink = uriString
        )
    }

    private fun parseTrojan(uriString: String): ProxyConfig? {
        val uri = URI(uriString)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val params = UriDecoderUtil.parseQueryParams(uri.rawQuery)
        val fragmentName = UriDecoderUtil.urlDecode(uri.fragment).ifBlank { "Trojan-$host:$port" }

        val sni = params["sni"]?.takeIf { it.isNotBlank() } ?: params["peer"] ?: host
        val network = params["type"] ?: params["net"] ?: "tcp"
        val path = params["path"]?.takeIf { it.isNotBlank() }
        val hostHeader = params["host"]?.takeIf { it.isNotBlank() }
        val alpnList = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return ProxyConfig(
            id = UUID.nameUUIDFromBytes(uriString.toByteArray()).toString(),
            name = fragmentName,
            protocol = "TROJAN",
            server = host,
            serverPort = port,
            password = password,
            security = "tls",
            sni = sni,
            network = network,
            path = path,
            host = hostHeader,
            alpn = alpnList,
            allowInsecure = truthy(params["allowinsecure"] ?: params["insecure"]),
            rawLink = uriString
        )
    }

    private fun parseShadowsocks(uriString: String): ProxyConfig? {
        val trimmed = uriString.substring("ss://".length)
        val fragmentIdx = trimmed.indexOf('#')
        val (mainPart, fragmentName) = if (fragmentIdx >= 0) {
            trimmed.substring(0, fragmentIdx) to UriDecoderUtil.urlDecode(trimmed.substring(fragmentIdx + 1))
        } else {
            trimmed to ""
        }

        // Check format: ss://base64(method:password@host:port) or ss://base64(method:password)@host:port
        val atIdx = mainPart.indexOf('@')
        val (method, password, host, port) = if (atIdx >= 0) {
            val userPart = mainPart.substring(0, atIdx)
            val hostPortPart = mainPart.substring(atIdx + 1)
            val decodedUser = UriDecoderUtil.safeBase64Decode(userPart) ?: userPart
            val colonUser = decodedUser.indexOf(':')
            if (colonUser < 0) return null
            val m = decodedUser.substring(0, colonUser)
            val p = decodedUser.substring(colonUser + 1)
            val colonHost = hostPortPart.lastIndexOf(':')
            if (colonHost < 0) return null
            val h = hostPortPart.substring(0, colonHost)
            val pt = hostPortPart.substring(colonHost + 1).split("/")[0].toIntOrNull() ?: return null
            Quadruple(m, p, h, pt)
        } else {
            val decoded = UriDecoderUtil.safeBase64Decode(mainPart) ?: return null
            val at = decoded.indexOf('@')
            if (at < 0) return null
            val user = decoded.substring(0, at)
            val hostPort = decoded.substring(at + 1)
            val colonUser = user.indexOf(':')
            if (colonUser < 0) return null
            val m = user.substring(0, colonUser)
            val p = user.substring(colonUser + 1)
            val colonHost = hostPort.lastIndexOf(':')
            if (colonHost < 0) return null
            val h = hostPort.substring(0, colonHost)
            val pt = hostPort.substring(colonHost + 1).split("/")[0].toIntOrNull() ?: return null
            Quadruple(m, p, h, pt)
        }

        val name = fragmentName.ifBlank { "Shadowsocks-$host:$port" }

        return ProxyConfig(
            id = UUID.nameUUIDFromBytes(uriString.toByteArray()).toString(),
            name = name,
            protocol = "SHADOWSOCKS",
            server = host,
            serverPort = port,
            password = password,
            method = method,
            rawLink = uriString
        )
    }

    private fun parseHysteria2(uriString: String): ProxyConfig? {
        val uri = URI(uriString)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val params = UriDecoderUtil.parseQueryParams(uri.rawQuery)
        val fragmentName = UriDecoderUtil.urlDecode(uri.fragment).ifBlank { "Hysteria2-$host:$port" }

        val sni = params["sni"]?.takeIf { it.isNotBlank() } ?: host
        val obfsPassword = params["obfs-password"] ?: params["obfs"]
        val alpnList = params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("h3")

        return ProxyConfig(
            id = UUID.nameUUIDFromBytes(uriString.toByteArray()).toString(),
            name = fragmentName,
            protocol = "HYSTERIA2",
            server = host,
            serverPort = port,
            password = password,
            security = "tls",
            sni = sni,
            hysteriaObfsPassword = obfsPassword,
            alpn = alpnList,
            allowInsecure = truthy(params["insecure"] ?: params["allowinsecure"]),
            rawLink = uriString
        )
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
