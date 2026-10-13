package com.internetazad369.vpn.data.parser

import android.util.Base64
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UriDecoderUtil {

    fun urlDecode(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }

    fun parseQueryParams(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = urlDecode(pair.substring(0, idx)).lowercase().trim()
                val value = urlDecode(pair.substring(idx + 1)).trim()
                result[key] = value
            } else if (pair.isNotBlank()) {
                result[urlDecode(pair).lowercase().trim()] = ""
            }
        }
        return result
    }

    /**
     * Safely decodes Base64 encoded string with standard or URL-safe encoding,
     * adding padding if missing.
     */
    fun safeBase64Decode(encoded: String): String? {
        val clean = encoded.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")

        if (clean.isBlank()) return null

        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }

        // Try java.util.Base64 first (works natively on JVM unit tests and Android API 26+)
        try {
            val bytes = if (padded.contains("-") || padded.contains("_")) {
                java.util.Base64.getUrlDecoder().decode(padded)
            } else {
                java.util.Base64.getDecoder().decode(padded)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val decoded = String(bytes, StandardCharsets.UTF_8)
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        } catch (_: Exception) {}

        // Fallback for older Android runtimes
        val flagsToTry = intArrayOf(
            Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.DEFAULT
        )

        for (flags in flagsToTry) {
            try {
                val bytes = Base64.decode(padded, flags)
                if (bytes != null && bytes.isNotEmpty()) {
                    val decoded = String(bytes, StandardCharsets.UTF_8)
                    if (decoded.isNotBlank()) {
                        return decoded
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
