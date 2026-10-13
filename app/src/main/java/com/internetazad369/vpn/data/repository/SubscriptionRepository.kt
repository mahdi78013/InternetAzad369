package com.internetazad369.vpn.data.repository

import android.content.Context
import android.util.Log
import com.internetazad369.vpn.data.model.ProfileMetadata
import com.internetazad369.vpn.data.model.ProxyConfig
import com.internetazad369.vpn.data.parser.ProtocolParser
import com.internetazad369.vpn.data.parser.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class SubscriptionRepository(private val context: Context) {

    companion object {
        private const val TAG = "SubscriptionRepo"

        // Full reference configuration URL as requested
        const val ORIGINAL_SUBSCRIPTION_URL =
            "https://raw.githubusercontent.com/hiddify/hiddify-app/refs/heads/main/test.configs/mahsa#Mahsa"

        const val DISPLAY_PROFILE_TITLE = "Mahsa"

        // Cache filename for validated configs
        private const val CACHE_FILE_NAME = "valid_profile_cache.json"

        // Maximum allowable response size in bytes (3 MB) to prevent memory exhaustion
        private const val MAX_RESPONSE_SIZE = 3 * 1024 * 1024L

        // Known fallback sub-URLs in case the primary profile endpoint is blocked or temporarily down
        private val FALLBACK_SUB_URLS = listOf(
            "https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mci/sub_1.txt",
            "https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mci/sub_2.txt",
            "https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mtn/sub_1.txt",
            "https://raw.githubusercontent.com/mahsanet/MahsaFreeConfig/refs/heads/main/mtn/sub_2.txt"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cachedConfigsInMemory: List<ProxyConfig> = emptyList()
    private var totalSkippedConfigsCount: Int = 0
    private var profileMetadata: ProfileMetadata = ProfileMetadata(title = DISPLAY_PROFILE_TITLE)

    fun getProfileMetadata(): ProfileMetadata = profileMetadata
    fun getSkippedCount(): Int = totalSkippedConfigsCount

    /**
     * Resolves the clean network URL from the reference URL by stripping any Fragment.
     */
    fun getCleanFetchUrl(url: String = ORIGINAL_SUBSCRIPTION_URL): String {
        val hashIdx = url.indexOf('#')
        return if (hashIdx >= 0) url.substring(0, hashIdx).trim() else url.trim()
    }

    /**
     * Fetches, validates, and prepares proxy configurations.
     * Guaranteed not to overwrite healthy cache with empty or broken configurations.
     *
     * @param forceRefresh If true, forces network request; otherwise returns memory cache if fresh.
     * @return List of validated ProxyConfigs ready for core execution.
     */
    suspend fun getValidatedConfigs(forceRefresh: Boolean = false): List<ProxyConfig> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedConfigsInMemory.isNotEmpty()) {
            return@withContext cachedConfigsInMemory
        }

        totalSkippedConfigsCount = 0
        var fetchedConfigs: List<ProxyConfig> = emptyList()

        try {
            // 1. Fetch main profile
            val cleanUrl = getCleanFetchUrl()
            val mainProfileContent = fetchUrlContent(cleanUrl)

            if (!mainProfileContent.isNullOrBlank()) {
                val metadata = SubscriptionParser.parseProfile(mainProfileContent)
                profileMetadata = metadata

                val subUrlsToFetch = metadata.subUrls.ifEmpty { FALLBACK_SUB_URLS }
                val collectedConfigs = mutableListOf<ProxyConfig>()
                val seenIds = mutableSetOf<String>()

                // 2. Fetch configs from each sub-URL
                for (subUrl in subUrlsToFetch) {
                    try {
                        val subContent = fetchUrlContent(subUrl) ?: continue
                        val (configs, skipped) = SubscriptionParser.parseConfigs(subContent)
                        totalSkippedConfigsCount += skipped

                        for (cfg in configs) {
                            if (!seenIds.contains(cfg.id)) {
                                seenIds.add(cfg.id)
                                collectedConfigs.add(cfg)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Sub-URL fetch failed: $subUrl (${e.message})")
                    }
                }

                if (collectedConfigs.isNotEmpty()) {
                    fetchedConfigs = collectedConfigs
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary subscription fetch failed: ${e.message}")
        }

        // 3. If primary fetch returned nothing, try fallback sub-URLs directly
        if (fetchedConfigs.isEmpty()) {
            Log.i(TAG, "Attempting direct fallback sub-urls...")
            val fallbackConfigs = mutableListOf<ProxyConfig>()
            val seenIds = mutableSetOf<String>()

            for (fallbackUrl in FALLBACK_SUB_URLS) {
                try {
                    val subContent = fetchUrlContent(fallbackUrl) ?: continue
                    val (configs, skipped) = SubscriptionParser.parseConfigs(subContent)
                    totalSkippedConfigsCount += skipped

                    for (cfg in configs) {
                        if (!seenIds.contains(cfg.id)) {
                            seenIds.add(cfg.id)
                            fallbackConfigs.add(cfg)
                        }
                    }
                } catch (_: Exception) {}
            }

            if (fallbackConfigs.isNotEmpty()) {
                fetchedConfigs = fallbackConfigs
            }
        }

        // 4. Cache validation policy:
        // If fresh fetch was successful, persist to disk cache
        if (fetchedConfigs.isNotEmpty()) {
            saveToDiskCache(fetchedConfigs)
            cachedConfigsInMemory = fetchedConfigs
            Log.i(TAG, "Successfully loaded ${fetchedConfigs.size} valid configs (skipped: $totalSkippedConfigsCount)")
            return@withContext fetchedConfigs
        }

        // 5. Offline fallback: If network failed, load from valid disk cache
        val diskCached = loadFromDiskCache()
        if (diskCached.isNotEmpty()) {
            Log.w(TAG, "Network unavailable, using ${diskCached.size} cached configs from disk")
            cachedConfigsInMemory = diskCached
            return@withContext diskCached
        }

        // 6. Complete failure
        throw IOException("عدم امکان دریافت اشتراک و نبود تنظیمات ذخیره‌شده معتبر")
    }

    /**
     * Fetches HTTP/HTTPS content safely with size limits and error checks.
     */
    private fun fetchUrlContent(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "v2rayNG/1.8.12 sing-box/1.10.0 (Android; Mobile)")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP error ${response.code} for URL: $url")
                return null
            }

            val body = response.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_SIZE) {
                Log.w(TAG, "Response size exceeded limit: $contentLength bytes")
                return null
            }

            return body.string()
        }
    }

    /**
     * Persists valid configs to local JSON cache.
     */
    private fun saveToDiskCache(configs: List<ProxyConfig>) {
        try {
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            val jsonArray = JSONArray()

            for (cfg in configs) {
                val obj = JSONObject().apply {
                    put("id", cfg.id)
                    put("name", cfg.name)
                    put("protocol", cfg.protocol)
                    put("server", cfg.server)
                    put("serverPort", cfg.serverPort)
                    put("uuid", cfg.uuid ?: "")
                    put("password", cfg.password ?: "")
                    put("flow", cfg.flow ?: "")
                    put("security", cfg.security)
                    put("sni", cfg.sni ?: "")
                    put("publicKey", cfg.publicKey ?: "")
                    put("shortId", cfg.shortId ?: "")
                    put("fingerprint", cfg.fingerprint ?: "")
                    put("network", cfg.network)
                    put("path", cfg.path ?: "")
                    put("host", cfg.host ?: "")
                    put("serviceName", cfg.serviceName ?: "")
                    put("alterId", cfg.alterId)
                    put("method", cfg.method ?: "")
                    put("packetEncoding", cfg.packetEncoding ?: "")
                    put("allowInsecure", cfg.allowInsecure)
                    put("rawLink", cfg.rawLink)
                    val alpnArray = JSONArray()
                    cfg.alpn.forEach { alpnArray.put(it) }
                    put("alpn", alpnArray)
                }
                jsonArray.put(obj)
            }

            cacheFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache: ${e.message}")
        }
    }

    /**
     * Reads valid configs from local JSON cache.
     */
    private fun loadFromDiskCache(): List<ProxyConfig> {
        return try {
            val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
            if (!cacheFile.exists()) return emptyList()

            val text = cacheFile.readText()
            if (text.isBlank()) return emptyList()

            val jsonArray = JSONArray(text)
            val result = mutableListOf<ProxyConfig>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val alpnList = mutableListOf<String>()
                val alpnArr = obj.optJSONArray("alpn")
                if (alpnArr != null) {
                    for (j in 0 until alpnArr.length()) {
                        alpnList.add(alpnArr.getString(j))
                    }
                }

                result.add(
                    ProxyConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        protocol = obj.getString("protocol"),
                        server = obj.getString("server"),
                        serverPort = obj.getInt("serverPort"),
                        uuid = obj.optString("uuid").takeIf { it.isNotBlank() },
                        password = obj.optString("password").takeIf { it.isNotBlank() },
                        flow = obj.optString("flow").takeIf { it.isNotBlank() },
                        security = obj.optString("security", "none"),
                        sni = obj.optString("sni").takeIf { it.isNotBlank() },
                        publicKey = obj.optString("publicKey").takeIf { it.isNotBlank() },
                        shortId = obj.optString("shortId").takeIf { it.isNotBlank() },
                        fingerprint = obj.optString("fingerprint").takeIf { it.isNotBlank() },
                        network = obj.optString("network", "tcp"),
                        path = obj.optString("path").takeIf { it.isNotBlank() },
                        host = obj.optString("host").takeIf { it.isNotBlank() },
                        serviceName = obj.optString("serviceName").takeIf { it.isNotBlank() },
                        alterId = obj.optInt("alterId", 0),
                        method = obj.optString("method").takeIf { it.isNotBlank() },
                        alpn = alpnList,
                        packetEncoding = obj.optString("packetEncoding").takeIf { it.isNotBlank() },
                        allowInsecure = obj.optBoolean("allowInsecure", false),
                        rawLink = obj.optString("rawLink")
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache: ${e.message}")
            emptyList()
        }
    }
}
