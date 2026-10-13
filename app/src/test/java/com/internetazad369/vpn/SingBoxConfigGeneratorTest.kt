package com.internetazad369.vpn

import com.internetazad369.vpn.core.SingBoxConfigGenerator
import com.internetazad369.vpn.data.model.ProxyConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigGeneratorTest {

    @Test
    fun testGenerateConfigContainsRequiredSections() {
        val proxy1 = ProxyConfig(
            id = "id-1",
            name = "TestNode1",
            protocol = "VLESS",
            server = "1.2.3.4",
            serverPort = 443,
            uuid = "uuid-1234",
            security = "reality",
            publicKey = "pbk123",
            shortId = "sid123"
        )
        val proxy2 = ProxyConfig(
            id = "id-2",
            name = "TestNode2",
            protocol = "TROJAN",
            server = "5.6.7.8",
            serverPort = 8443,
            password = "pass",
            security = "tls"
        )

        val jsonStr = SingBoxConfigGenerator.generate(listOf(proxy1, proxy2))
        assertNotNull(jsonStr)

        val json = JSONObject(jsonStr)
        assertTrue(json.has("log"))
        assertTrue(json.has("dns"))
        assertTrue(json.has("inbounds"))
        assertTrue(json.has("outbounds"))
        assertTrue(json.has("route"))

        // Check inbounds has tun
        val inbounds = json.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals("tun-in", tun.getString("tag"))
        val addresses = tun.getJSONArray("address")
        assertEquals("172.19.0.1/30", addresses.getString(0))

        // Check outbounds has node-0, node-1, and auto urltest group
        val outbounds = json.getJSONArray("outbounds")
        var foundAuto = false
        var autoOutboundsCount = 0

        for (i in 0 until outbounds.length()) {
            val out = outbounds.getJSONObject(i)
            if (out.optString("tag") == "auto") {
                foundAuto = true
                assertEquals("urltest", out.getString("type"))
                autoOutboundsCount = out.getJSONArray("outbounds").length()
            }
        }

        assertTrue("Auto urltest group must exist", foundAuto)
        assertEquals("Auto group must contain both nodes", 2, autoOutboundsCount)

        // Check route final destination is auto
        val route = json.getJSONObject("route")
        assertEquals("auto", route.getString("final"))
    }

    private fun outboundsByTag(json: JSONObject): Map<String, JSONObject> {
        val arr = json.getJSONArray("outbounds")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("tag") }
    }

    @Test
    fun testTunIsIpv4OnlyAndDnsIsNotLeakedOrLooped() {
        val proxy = ProxyConfig(
            id = "a", name = "n", protocol = "TROJAN", server = "1.2.3.4",
            serverPort = 443, password = "p", security = "tls"
        )
        val json = JSONObject(SingBoxConfigGenerator.generate(listOf(proxy), systemDns = listOf("10.202.10.10")))

        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1, tun.getJSONArray("address").length())

        val dns = json.getJSONObject("dns")
        assertEquals("dns-remote", dns.getString("final"))
        assertEquals("ipv4_only", dns.getString("strategy"))
        val servers = dns.getJSONArray("servers")
        val direct = (0 until servers.length()).map { servers.getJSONObject(it) }.first { it.getString("tag") == "dns-direct" }
        assertEquals("10.202.10.10", direct.getString("server"))

        // Proxy domains must never be resolved through the detoured remote resolver (resolve loop).
        val route = json.getJSONObject("route")
        assertEquals("dns-direct", route.getJSONObject("default_domain_resolver").getString("server"))

        val auto = outboundsByTag(json).getValue("auto")
        assertTrue(auto.getBoolean("interrupt_exist_connections"))
    }

    @Test
    fun testPreResolvedHostIsDialedByIpButKeepsSniAndHostHeader() {
        val proxy = ProxyConfig(
            id = "a", name = "n", protocol = "VLESS", server = "cdn.example.org",
            serverPort = 443, uuid = "u", security = "tls", sni = "cdn.example.org",
            network = "ws", path = "/ws"
        )
        val json = JSONObject(
            SingBoxConfigGenerator.generate(listOf(proxy), resolvedHosts = mapOf("cdn.example.org" to "104.16.1.1"))
        )
        val node = outboundsByTag(json).getValue("node-0")
        assertEquals("104.16.1.1", node.getString("server"))
        assertEquals("cdn.example.org", node.getJSONObject("tls").getString("server_name"))
        assertEquals("cdn.example.org", node.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        assertFalse(node.has("domain_resolver"))
    }

    @Test
    fun testUnresolvedDomainGetsExplicitBootstrapResolver() {
        val proxy = ProxyConfig(
            id = "a", name = "n", protocol = "TROJAN", server = "tr.example.net",
            serverPort = 443, password = "p", security = "tls", sni = "tr.example.net"
        )
        val node = outboundsByTag(JSONObject(SingBoxConfigGenerator.generate(listOf(proxy)))).getValue("node-0")
        assertEquals("tr.example.net", node.getString("server"))
        assertEquals("dns-direct", node.getString("domain_resolver"))
    }

    @Test
    fun testVmessTlsNeverUsedAsCipherAndUnsupportedTransportsAreDropped() {
        val vmess = ProxyConfig(
            id = "v", name = "v", protocol = "VMESS", server = "1.1.1.1", serverPort = 443,
            uuid = "u", security = "tls", network = "ws"
        )
        val xhttp = ProxyConfig(
            id = "x", name = "x", protocol = "VLESS", server = "2.2.2.2", serverPort = 443,
            uuid = "u", security = "tls", network = "xhttp"
        )
        val json = JSONObject(SingBoxConfigGenerator.generate(listOf(xhttp, vmess)))
        val outbounds = outboundsByTag(json)
        assertEquals("auto", outbounds.getValue("node-0").getString("security"))
        assertFalse(outbounds.containsKey("node-1"))
        assertEquals(1, outbounds.getValue("auto").getJSONArray("outbounds").length())
    }

    @Test
    fun testNodeListIsCapped() {
        val many = (1..200).map {
            ProxyConfig(
                id = "id-$it", name = "n$it", protocol = "TROJAN", server = "10.0.${it / 250}.${it % 250 + 1}",
                serverPort = 443, password = "p$it", security = "tls"
            )
        }
        val auto = outboundsByTag(JSONObject(SingBoxConfigGenerator.generate(many))).getValue("auto")
        assertEquals(SingBoxConfigGenerator.MAX_NODES, auto.getJSONArray("outbounds").length())
    }

    @Test
    fun testInvalidPacketEncodingFlowAndFingerprintAreSanitized() {
        val proxy = ProxyConfig(
            id = "id-x",
            name = "Bad",
            protocol = "VLESS",
            server = "1.2.3.4",
            serverPort = 443,
            uuid = "uuid-1234",
            flow = "none",
            security = "tls",
            fingerprint = "bogus",
            packetEncoding = "none"
        )
        val json = JSONObject(SingBoxConfigGenerator.generate(listOf(proxy)))
        val node = json.getJSONArray("outbounds").getJSONObject(0)
        assertFalse(node.has("packet_encoding"))
        assertFalse(node.has("flow"))
        assertFalse(node.getJSONObject("tls").has("utls"))
    }
}
