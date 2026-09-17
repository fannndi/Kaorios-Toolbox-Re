package io.farewell.patcher.integrity

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/**
 * Checks a package against Google's **Android Developer ID Status API** — the
 * server-to-server endpoint behind Android developer verification.
 *
 * Why this matters to this project: from September 30, 2026 app registration is
 * required for installs from participating stores on certified devices in
 * Brazil, Indonesia, Singapore and Thailand, and the requirement goes global in
 * 2027. Two paths stay exempt, and this tool relies on both: **ADB installs**
 * ("apps installed using ADB won't require verification") and apps that are part
 * of the system image — which is exactly what the system-app zip produces when
 * it installs this APK into `/system/priv-app`.
 *
 * The API is public but keyed: it answers `403 PERMISSION_DENIED` for callers
 * without an API key and `400 API_KEY_INVALID` for a bad one, so the caller
 * supplies their own Google Cloud key (no OAuth needed).
 *
 * Endpoint:
 * `GET https://androiddeveloperidstatus.googleapis.com/v1/packages/{pkg}/packageRegistrationStatus:check`
 * where `{pkg}` is the package name with dots replaced by hyphens, plus an
 * optional `certificateFingerprint` (SHA-256 hex of the signing certificate).
 */
object DevRegistration {

    const val HOST = "https://androiddeveloperidstatus.googleapis.com/v1/packages"

    const val REGISTERED = "REGISTERED"
    const val NOT_REGISTERED = "NOT_REGISTERED"
    const val OTHER_FINGERPRINT = "REGISTERED_WITH_ANOTHER_CERTIFICATE_FINGERPRINT"

    data class Result(
        val state: String,
        val detail: String,
        val raw: String?,
        val url: String,
    ) {
        val registered: Boolean get() = state == REGISTERED
        fun summary(): String = when (state) {
            REGISTERED -> "REGISTERED: the package (and fingerprint) is owned by a verified developer"
            OTHER_FINGERPRINT ->
                "REGISTERED with a DIFFERENT certificate: the package name is taken; this signature is not the registered one"
            NOT_REGISTERED ->
                "NOT_REGISTERED: fine for ADB installs and system-image apps, not installable from participating stores"
            "NO_KEY" -> "No API key: the status API needs a Google Cloud API key"
            "BAD_KEY" -> "The API key was rejected (400 API_KEY_INVALID)"
            "UNKNOWN" -> "Unrecognised response from the status API"
            else -> state
        }
    }

    /** One HTTP GET with an optional API key. Injectable so tests need no network. */
    interface Fetcher {
        fun get(url: String, apiKey: String?): Pair<Int, String>
    }

    private object HttpFetcher : Fetcher {
        override fun get(url: String, apiKey: String?): Pair<Int, String> {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "farewell-toolbox")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("X-Goog-Api-Key", apiKey.trim())
                }
            }
            connection.connect()
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return code to body
        }
    }

    fun urlFor(packageName: String, certificateFingerprint: String? = null): String {
        val resource = packageName.trim().replace('.', '-')
        val builder = StringBuilder("$HOST/$resource/packageRegistrationStatus:check")
        if (!certificateFingerprint.isNullOrBlank()) {
            builder.append("?certificateFingerprint=")
                .append(certificateFingerprint.trim().lowercase().replace(":", ""))
        }
        return builder.toString()
    }

    fun check(
        packageName: String,
        certificateFingerprint: String? = null,
        apiKey: String?,
        fetcher: Fetcher = HttpFetcher,
    ): Result {
        require(packageName.isNotBlank()) { "package name is required" }
        val url = urlFor(packageName, certificateFingerprint)
        if (apiKey.isNullOrBlank()) {
            return Result(
                state = "NO_KEY",
                detail = "The Android Developer ID Status API requires an API key (Google Cloud, enable the API).",
                raw = null,
                url = url,
            )
        }
        val (code, body) = try {
            fetcher.get(url, apiKey)
        } catch (throwable: Throwable) {
            return Result("UNKNOWN", "Request failed: ${throwable.message}", null, url)
        }
        return when {
            code == 400 && body.contains("API_KEY_INVALID") ->
                Result("BAD_KEY", "Google rejected the API key (400 API_KEY_INVALID).", body, url)

            code == 403 ->
                Result("NO_KEY", "Google refused the caller (403): ${errorMessage(body) ?: "no API key identity"}.", body, url)

            code != 200 ->
                Result("UNKNOWN", "HTTP $code from the status API: ${errorMessage(body) ?: body.take(120)}", body, url)

            else -> {
                // Documented shape: {"name": "packages/<pkg>/packageRegistrationStatus", "state": "REGISTERED"}
                val state = runCatching { JSONObject(body).optString("state", "") }.getOrDefault("")
                when {
                    state.isEmpty() -> Result("UNKNOWN", "No state in the response", body, url)
                    state == REGISTERED -> Result(REGISTERED, "Registered by a verified developer.", body, url)
                    state == OTHER_FINGERPRINT -> Result(
                        OTHER_FINGERPRINT,
                        "The package name is registered with a different signing certificate.",
                        body,
                        url,
                    )
                    state == NOT_REGISTERED -> Result(NOT_REGISTERED, "Not registered.", body, url)
                    else -> Result(state, "State from Google: $state", body, url)
                }
            }
        }
    }

    private fun errorMessage(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
}
