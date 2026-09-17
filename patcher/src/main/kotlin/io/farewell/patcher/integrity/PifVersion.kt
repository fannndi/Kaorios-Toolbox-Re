package io.farewell.patcher.integrity

/**
 * Staleness guard for synced PIF data.
 *
 * A data source can go backwards: a CI job that fails to scrape the newest OTA,
 * or a branch whose `Pif-props.json` was overwritten by an older release, will
 * serve a fingerprint whose `SECURITY_PATCH` is months behind the one already
 * on the device. Applying it silently *weakens* the spoof (Play Integrity reads
 * the patch level, and on Android 13+ STRONG requires a patch from the last 12
 * months), so a sync must never move that date backwards.
 *
 * Pure string logic so it lives here (the only module whose tests run without
 * Android) instead of in the app's [DataSync].
 */
object PifVersion {

    /** `SECURITY_PATCH` as `yyyy-MM-dd`, or null when absent/unreadable. */
    fun securityPatch(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val value = org.json.JSONObject(json).optString("SECURITY_PATCH", "").trim()
            if (value.length >= 10) value.substring(0, 10) else null
        } catch (throwable: Throwable) {
            null
        }
    }

    /**
     * True when [candidate] must not replace [current].
     *
     * Only a readable candidate patch that is strictly older than a readable
     * current patch blocks the sync. Anything unreadable is allowed through:
     * refusing to sync because a file could not be parsed would break offline
     * and first-run flows for no gain.
     */
    fun isCandidateStale(current: String?, candidate: String?): Boolean {
        val currentPatch = securityPatch(current) ?: return false
        val candidatePatch = securityPatch(candidate) ?: return false
        return candidatePatch < currentPatch
    }
}
