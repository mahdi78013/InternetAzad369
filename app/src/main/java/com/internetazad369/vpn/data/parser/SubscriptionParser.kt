package com.internetazad369.vpn.data.parser

import com.internetazad369.vpn.data.model.ProfileMetadata
import com.internetazad369.vpn.data.model.ProxyConfig
import java.util.regex.Pattern

object SubscriptionParser {

    private val PROTOCOL_REGEX = Pattern.compile(
        "((?:vless|vmess|trojan|ss|hysteria2|hy2)://[^\\s]+)",
        Pattern.CASE_INSENSITIVE
    )

    private val HTTP_URL_REGEX = Pattern.compile(
        "https?://[^\\s#]+",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Parses the main profile text (e.g., from the Mahsa profile file) to extract
     * metadata and sub-subscription URLs.
     */
    fun parseProfile(content: String): ProfileMetadata {
        var title = "Mahsa"
        var updateInterval = 24
        var supportUrl: String? = null
        val subUrls = mutableListOf<String>()

        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#profile-title:", ignoreCase = true) -> {
                    title = trimmed.substring("#profile-title:".length).trim().ifBlank { "Mahsa" }
                }
                trimmed.startsWith("#profile-update-interval:", ignoreCase = true) -> {
                    val raw = trimmed.substring("#profile-update-interval:".length).trim()
                    updateInterval = raw.toIntOrNull() ?: 24
                }
                trimmed.startsWith("#support-url:", ignoreCase = true) -> {
                    supportUrl = trimmed.substring("#support-url:".length).trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> {
                    // Extract clean URL without fragment
                    val matcher = HTTP_URL_REGEX.matcher(trimmed)
                    if (matcher.find()) {
                        val cleanUrl = matcher.group()
                        if (!subUrls.contains(cleanUrl)) {
                            subUrls.add(cleanUrl)
                        }
                    }
                }
            }
        }

        return ProfileMetadata(
            title = title,
            updateIntervalHours = updateInterval,
            supportUrl = supportUrl,
            subUrls = subUrls
        )
    }

    /**
     * Extracts and parses proxy configs from raw text, automatically decoding
     * Base64 if detected, filtering invalid nodes and tracking skipped items.
     *
     * @return Pair of valid ProxyConfigs and count of skipped/invalid items.
     */
    fun parseConfigs(rawText: String): Pair<List<ProxyConfig>, Int> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList<ProxyConfig>() to 0

        // If the text does not directly contain protocol schemes, attempt Base64 decode
        val contentToParse = if (
            !trimmed.contains("vless://", ignoreCase = true) &&
            !trimmed.contains("vmess://", ignoreCase = true) &&
            !trimmed.contains("trojan://", ignoreCase = true) &&
            !trimmed.contains("ss://", ignoreCase = true) &&
            !trimmed.contains("hysteria2://", ignoreCase = true)
        ) {
            UriDecoderUtil.safeBase64Decode(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val matcher = PROTOCOL_REGEX.matcher(contentToParse)
        val validConfigs = mutableListOf<ProxyConfig>()
        val seenIds = mutableSetOf<String>()
        var skipped = 0
        var foundMatches = 0

        while (matcher.find()) {
            foundMatches++
            val link = matcher.group()
            val parsed = ProtocolParser.parse(link)
            if (parsed != null && !seenIds.contains(parsed.id)) {
                seenIds.add(parsed.id)
                validConfigs.add(parsed)
            } else {
                skipped++
            }
        }

        // Also check line count vs matched links to count corrupted lines
        val nonBlankLines = contentToParse.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (nonBlankLines.size > foundMatches) {
            skipped += (nonBlankLines.size - foundMatches)
        }

        return validConfigs to skipped
    }
}
