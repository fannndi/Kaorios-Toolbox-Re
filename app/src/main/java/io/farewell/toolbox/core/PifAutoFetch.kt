package io.farewell.toolbox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object PifAutoFetch {

    private const val VERSIONS_URL = "https://developer.android.com/about/versions"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    data class Result(val pif: JSONObject, val source: String)

    suspend fun fetch(context: android.content.Context, onProgress: (String) -> Unit): Result? =
        withContext(Dispatchers.IO) {
            try {
                onProgress("Fetching Android versions page")
                val versions = get(VERSIONS_URL) ?: return@withContext null

                val versionUrls = Regex("https://developer.android.com/about/versions/[0-9][^\"]*")
                    .findAll(versions)
                    .map { it.value }
                    .distinct()
                    .sortedDescending()
                    .toList()
                if (versionUrls.isEmpty()) {
                    onProgress("No Android version pages found")
                    return@withContext null
                }

                var versionUrl = versionUrls.first()
                var stablePage = get(versionUrl) ?: return@withContext null
                var otaPath = Regex("href=\"([^\"]*download-ota[^\"]*)\"").find(stablePage)?.groupValues?.get(1)
                if (otaPath == null && versionUrls.size > 1) {
                    onProgress("Latest page has no OTA, using previous")
                    versionUrl = versionUrls[1]
                    stablePage = get(versionUrl) ?: return@withContext null
                    otaPath = Regex("href=\"([^\"]*download-ota[^\"]*)\"").find(stablePage)?.groupValues?.get(1)
                }
                if (otaPath == null) {
                    onProgress("No OTA download page found")
                    return@withContext null
                }
                val otaPageUrl = if (otaPath.startsWith("http")) otaPath else "https://developer.android.com$otaPath"
                onProgress("Fetching OTA page")
                val otaPage = get(otaPageUrl) ?: return@withContext null

                val model = Regex("(?s)<tr[^>]*id=\"[^\"]*\"[^>]*>.*?<td>(.*?)</td>")
                    .find(otaPage)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()

                val zipLinks = Regex("href=\"([^\"]+\\.zip)\"").findAll(otaPage)
                    .map { it.groupValues[1] }
                    .toList()
                val preferredDevices = listOf("husky_beta", "tokay_beta", "komodo_beta", "akita_beta")
                val otaLink = preferredDevices.firstNotNullOfOrNull { device ->
                    zipLinks.firstOrNull { it.contains(device) }
                } ?: zipLinks.firstOrNull { it.contains("_beta") } ?: zipLinks.firstOrNull()
                ?: return@withContext null
                val absoluteLink = if (otaLink.startsWith("http")) otaLink else "https://developer.android.com$otaLink"

                onProgress("Reading OTA metadata")
                val metadata = getRange(absoluteLink, 0, 20000) ?: return@withContext null
                val fingerprint = Regex("post-build=(.+)").find(metadata)?.groupValues?.get(1)?.trim()
                    ?: return@withContext null
                val securityPatch = Regex("security-patch-level=(.+)").find(metadata)?.groupValues?.get(1)?.trim()
                    ?: return@withContext null

                val parts = fingerprint.split(":")
                val first = parts.getOrNull(0)?.split("/").orEmpty()
                var product = first.getOrNull(1).orEmpty()
                var device = first.getOrNull(2).orEmpty()
                if (product.isEmpty()) {
                    val name = absoluteLink.substringAfterLast('/').substringBefore('-')
                    product = name
                    device = name.removeSuffix("_beta")
                }

                val pif = JSONObject()
                    .put("MANUFACTURER", "Google")
                    .put("MODEL", modelOf(device) ?: model ?: device)
                    .put("FINGERPRINT", fingerprint)
                    .put("PRODUCT", product)
                    .put("DEVICE", device)
                    .put("SECURITY_PATCH", securityPatch)
                    .put("DEVICE_INITIAL_SDK_INT", "32")

                val dataDir = File(context.filesDir, "farewell-data").apply { mkdirs() }
                File(dataDir, "Pif-props.json").writeText(pif.toString(2))
                Result(pif, "Google OTA ($device, $securityPatch)")
            } catch (throwable: Throwable) {
                onProgress("Auto-fetch failed: ${throwable.message}")
                null
            }
        }

    private fun modelOf(device: String): String? = when (device) {
        "husky" -> "Pixel 8 Pro"
        "tokay" -> "Pixel 9"
        "komodo" -> "Pixel 9 Pro XL"
        "akita" -> "Pixel 8a"
        "caiman" -> "Pixel 9 Pro"
        "shiba" -> "Pixel 8"
        "oriole" -> "Pixel 6"
        "raven" -> "Pixel 6 Pro"
        "panther" -> "Pixel 7"
        "cheetah" -> "Pixel 7 Pro"
        "lynx" -> "Pixel 7a"
        "felix" -> "Pixel Fold"
        "tangorpro" -> "Pixel Tablet"
        else -> null
    }

    private fun manufacturerOf(fingerprintPrefix: String): String {
        val brand = fingerprintPrefix.substringBefore('/').lowercase()
        return when (brand) {
            "google" -> "Google"
            "samsung" -> "samsung"
            "xiaomi" -> "Xiaomi"
            "oneplus" -> "OnePlus"
            else -> brand.replaceFirstChar { it.uppercase() }
        }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.connect()
            if (connection.responseCode != 200) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (throwable: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getRange(url: String, start: Long, end: Long): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Range", "bytes=$start-$end")
        }
        return try {
            connection.connect()
            if (connection.responseCode != 200 && connection.responseCode != 206) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.ISO_8859_1) }
        } catch (throwable: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
