package io.farewell.patcher.integrity

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object IntegrityData {

    private const val ROOT_URL = "https://android.googleapis.com/attestation/root"
    private const val STATUS_URL = "https://android.googleapis.com/attestation/status"

    data class RevocationEntry(val status: String, val reason: String?) {
        override fun toString(): String = if (reason.isNullOrEmpty()) status else "$status ($reason)"
    }

    data class Snapshot(
        val rootPems: List<String>,
        val statuses: Map<String, RevocationEntry>,
        val rootFile: File,
        val statusFile: File
    )

    fun download(directory: File, force: Boolean = false): Snapshot {
        directory.mkdirs()
        val rootFile = File(directory, "google-attestation-roots.json")
        val statusFile = File(directory, "google-attestation-status.json")
        if (force || !rootFile.exists() || rootFile.length() == 0L) {
            rootFile.writeText(fetch(ROOT_URL))
        }
        if (force || !statusFile.exists() || statusFile.length() == 0L) {
            statusFile.writeText(fetch(STATUS_URL))
        }
        return load(rootFile, statusFile)
    }

    fun load(rootFile: File, statusFile: File): Snapshot {
        val rootPems = parseRoots(rootFile.readText())
        val statuses = parseStatus(statusFile.readText())
        return Snapshot(rootPems, statuses, rootFile, statusFile)
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
        }
        connection.connect()
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun parseRoots(json: String): List<String> {
        return runCatching { org.json.JSONArray(json) }.getOrNull()?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it, null) }
        } ?: emptyList()
    }

    private fun parseStatus(json: String): Map<String, RevocationEntry> {
        val result = LinkedHashMap<String, RevocationEntry>()
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return result
        val entries = root.optJSONObject("entries") ?: return result
        val keys = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = entries.optJSONObject(key) ?: continue
            val status = entry.optString("status", "")
            if (status.isNotEmpty()) {
                result[key] = RevocationEntry(status.uppercase(), entry.optString("reason", null))
            }
        }
        return result
    }
}
