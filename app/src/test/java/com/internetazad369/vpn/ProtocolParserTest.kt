package com.internetazad369.vpn

import com.internetazad369.vpn.data.parser.ProtocolParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParserTest {

    @Test
    fun testParseVlessReality() {
        val link = "vless://c7336a70-9d56-4ef9-8cb8-8eeb9c82a3e3@217.69.167.167:43000?security=reality&encryption=none&pbk=yVypeNRnQF6yM3z2FJLL6jJcvZY8SArZN34JQbiEzzY&headerType=none&fp=random&type=tcp&flow=xtls-rprx-vision&sni=217.69.167.167&sid=5c97b3bc#TestReality"
        val proxy = ProtocolParser.parse(link)

        assertNotNull(proxy)
        assertEquals("VLESS", proxy!!.protocol)
        assertEquals("217.69.167.167", proxy.server)
        assertEquals(43000, proxy.serverPort)
        assertEquals("c7336a70-9d56-4ef9-8cb8-8eeb9c82a3e3", proxy.uuid)
        assertEquals("reality", proxy.security)
        assertEquals("xtls-rprx-vision", proxy.flow)
        assertEquals("yVypeNRnQF6yM3z2FJLL6jJcvZY8SArZN34JQbiEzzY", proxy.publicKey)
        assertEquals("5c97b3bc", proxy.shortId)
        assertEquals("TestReality", proxy.name)
    }

    @Test
    fun testParseTrojan() {
        val link = "trojan://secretpass@188.114.97.6:443?security=tls&sni=example.com&type=ws&path=%2Fws#TestTrojan"
        val proxy = ProtocolParser.parse(link)

        assertNotNull(proxy)
        assertEquals("TROJAN", proxy!!.protocol)
        assertEquals("188.114.97.6", proxy.server)
        assertEquals(443, proxy.serverPort)
        assertEquals("secretpass", proxy.password)
        assertEquals("tls", proxy.security)
        assertEquals("example.com", proxy.sni)
        assertEquals("ws", proxy.network)
        assertEquals("/ws", proxy.path)
        assertEquals("TestTrojan", proxy.name)
    }

    @Test
    fun testParseVmess() {
        // {"add":"192.168.1.100","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"ws","path":"/vmess","tls":"tls","ps":"ServerVMess"}
        val base64Payload = "eyJhZGQiOiIxOTIuMTY4LjEuMTAwIiwicG9ydCI6Ijg0NDMiLCJpZCI6ImI4MzEzODFkLTYzMjQtNGQ1My1hZDRmLThjZGE0OGIzMDgxMSIsImFpZCI6IjAiLCJuZXQiOiJ3cyIsInBhdGgiOiIvdm1lc3MiLCJ0bHMiOiJ0bHMiLCJwcyI6IlNlcnZlclZNZXNzIn0="
        val link = "vmess://$base64Payload"
        val proxy = ProtocolParser.parse(link)

        assertNotNull(proxy)
        assertEquals("VMESS", proxy!!.protocol)
        assertEquals("192.168.1.100", proxy.server)
        assertEquals(8443, proxy.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", proxy.uuid)
        assertEquals("ws", proxy.network)
        assertEquals("/vmess", proxy.path)
        assertEquals("tls", proxy.security)
        assertEquals("ServerVMess", proxy.name)
    }

    @Test
    fun testParseHysteria2() {
        val link = "hysteria2://pass123@1.2.3.4:443?sni=fast.server.com&obfs=salamander&obfs-password=secret#FastHy2"
        val proxy = ProtocolParser.parse(link)

        assertNotNull(proxy)
        assertEquals("HYSTERIA2", proxy!!.protocol)
        assertEquals("1.2.3.4", proxy.server)
        assertEquals(443, proxy.serverPort)
        assertEquals("pass123", proxy.password)
        assertEquals("fast.server.com", proxy.sni)
        assertEquals("secret", proxy.hysteriaObfsPassword)
        assertEquals("FastHy2", proxy.name)
    }
}
