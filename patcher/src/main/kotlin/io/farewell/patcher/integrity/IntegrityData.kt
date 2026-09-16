package io.farewell.patcher.integrity

import java.io.File
import java.net.HttpURLConnection

object IntegrityData {

    private const val ROOT_URL = "https://android.googleapis.com/attestation/root"
    private const val STATUS_URL = "https://android.googleapis.com/attestation/status"

    /**
     * How long a cached snapshot stays usable.
     *
     * Both endpoints answer with `Cache-Control: public, max-age=86400`, so Google
     * considers them fresh for 24 hours. That matters most for the status list: its
     * `Last-Modified` moves (it was 7 days old when this was checked), so a cache
     * that never expires would keep reporting a newly revoked keybox as valid.
     */
    const val MAX_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

    data class RevocationEntry(
        val status: String,
        val reason: String?,
        val comment: String? = null,
        val expires: String? = null
    ) {
        val softBanned: Boolean get() = status == "SUSPENDED"

        override fun toString(): String {
            val parts = mutableListOf(status)
            if (!reason.isNullOrEmpty()) parts += reason
            if (!expires.isNullOrEmpty()) parts += "expires $expires"
            if (!comment.isNullOrEmpty()) parts += comment
            return parts.joinToString(", ")
        }
    }

    data class Snapshot(
        val rootPems: List<String>,
        val statuses: Map<String, RevocationEntry>,
        val rootFile: File,
        val statusFile: File
    )

    /** True when a cached file is missing, empty, or older than [MAX_AGE_MILLIS]. */
    fun isStale(file: File, now: Long = System.currentTimeMillis()): Boolean =
        !file.exists() || file.length() == 0L || (now - file.lastModified()) > MAX_AGE_MILLIS

    fun download(directory: File, force: Boolean = false): Snapshot {
        directory.mkdirs()
        val rootFile = File(directory, "google-attestation-roots.json")
        val statusFile = File(directory, "google-attestation-status.json")
        refresh(rootFile, ROOT_URL, force)
        refresh(statusFile, STATUS_URL, force)
        return load(rootFile, statusFile)
    }

    /**
     * Refetch [file] when it is stale. A failed refresh falls back to the cached
     * copy so the tool still works offline — but only when a usable copy exists and
     * the caller did not explicitly ask for a refresh.
     */
    private fun refresh(file: File, url: String, force: Boolean) {
        if (!force && !isStale(file)) return
        val cachedIsUsable = file.exists() && file.length() > 0L
        try {
            file.writeText(fetch(url))
        } catch (throwable: Throwable) {
            if (force || !cachedIsUsable) throw throwable
        }
    }

    fun load(rootFile: File, statusFile: File): Snapshot {
        val rootPems = parseRoots(rootFile.readText())
        val statuses = parseStatus(statusFile.readText())
        return Snapshot(rootPems, statuses, rootFile, statusFile)
    }

    private fun fetch(url: String): String {
        val connection = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
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
                result[key] = RevocationEntry(
                    status = status.uppercase(),
                    reason = entry.optString("reason", null),
                    comment = entry.optString("comment", null),
                    expires = entry.optString("expires", null)
                )
            }
        }
        return result
    }
}
