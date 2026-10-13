package com.internetazad369.vpn

import com.internetazad369.vpn.data.parser.SubscriptionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionParserTest {

    @Test
    fun testParseProfileMetadataAndSubUrls() {
        val sampleMahsaProfile = """
            #profile-title: Mahsa
            #profile-update-interval: 24
            #subscription-userinfo: upload=0; download=0; total=10737418240000000; expire=2546249531
            #support-url: https://github.com/mahsanet/MahsaFreeConfig
            #profile-web-page-url: https://github.com/mahsanet/MahsaFreeConfig

            https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mci/sub_1.txt
            https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mci/sub_2.txt
            https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mtn/sub_1.txt
        """.trimIndent()

        val metadata = SubscriptionParser.parseProfile(sampleMahsaProfile)

        assertEquals("Mahsa", metadata.title)
        assertEquals(24, metadata.updateIntervalHours)
        assertEquals("https://github.com/mahsanet/MahsaFreeConfig", metadata.supportUrl)
        assertEquals(3, metadata.subUrls.size)
        assertTrue(metadata.subUrls.contains("https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mci/sub_1.txt"))
    }

    @Test
    fun testParseConfigsMultiLine() {
        val rawLinks = """
            vless://c7336a70-9d56-4ef9-8cb8-8eeb9c82a3e3@217.69.167.167:43000?security=reality&encryption=none&pbk=yVypeNRnQF6yM3z2FJLL6jJcvZY8SArZN34JQbiEzzY#Node1
            trojan://pass@188.114.97.6:443?security=tls&sni=example.com#Node2
            invalid-random-text-line
        """.trimIndent()

        val (configs, skipped) = SubscriptionParser.parseConfigs(rawLinks)

        assertEquals(2, configs.size)
        assertEquals("VLESS", configs[0].protocol)
        assertEquals("TROJAN", configs[1].protocol)
        assertEquals(1, skipped)
    }
}
