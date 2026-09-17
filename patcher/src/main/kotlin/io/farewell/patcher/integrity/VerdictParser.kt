package io.farewell.patcher.integrity

/**
 * Reader for a **decrypted** Play Integrity verdict — the JSON returned by
 * `v1.decodeIntegrityToken` of the Play Integrity API.
 *
 * The integrity token itself is encrypted and can only be opened server-side
 * with a Google Cloud project + OAuth, so neither the hook nor this tool can
 * decode the raw token. But once the user decodes it on their own project,
 * they paste the verdict JSON here and this answers "did my spoof pass?"
 * with the same verdict vocabulary the STRONG readiness report uses.
 *
 * Shape (from the public API):
 * ```
 * {
 *   "deviceIntegrity": { "deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY", ...] },
 *   "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED", "packageName": "..." },
 *   "accountDetails": { "appLicensingVerdict": "LICENSED" }
 * }
 * ```
 *
 * Best-effort like every other parser here: malformed input yields null, never
 * a throw, so a garbled paste cannot break the caller.
 */
object VerdictParser {

    const val STRONG = "MEETS_STRONG_INTEGRITY"
    const val DEVICE = "MEETS_DEVICE_INTEGRITY"
    const val BASIC = "MEETS_BASIC_INTEGRITY"

    data class IntegrityVerdict(
        val deviceVerdicts: Set<String>,
        val appVerdict: String?,
        val licensingVerdict: String?,
        val packageName: String?,
    ) {
        val meetsStrong: Boolean get() = STRONG in deviceVerdicts
        val meetsDevice: Boolean get() = DEVICE in deviceVerdicts || meetsStrong
        val meetsBasic: Boolean get() = BASIC in deviceVerdicts || meetsDevice
    }

    fun parse(json: String): IntegrityVerdict? {
        if (json.isBlank()) return null
        return try {
            val root = org.json.JSONObject(json)
            val device = root.optJSONObject("deviceIntegrity")
            val verdicts = LinkedHashSet<String>()
            val array = device?.optJSONArray("deviceRecognitionVerdict")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "")
                    if (value.isNotEmpty()) verdicts += value
                }
            }
            val app = root.optJSONObject("appIntegrity")
            val account = root.optJSONObject("accountDetails")
            IntegrityVerdict(
                deviceVerdicts = verdicts,
                appVerdict = app?.optString("appRecognitionVerdict", null)?.takeIf { it.isNotEmpty() },
                licensingVerdict = account?.optString("appLicensingVerdict", null)?.takeIf { it.isNotEmpty() },
                packageName = app?.optString("packageName", null)?.takeIf { it.isNotEmpty() },
            )
        } catch (throwable: Throwable) {
            null
        }
    }

    /** Human-readable verdict lines, mirroring the app's STRONG readiness model. */
    fun summarize(verdict: IntegrityVerdict): List<String> {
        val lines = mutableListOf<String>()
        lines += "Device verdict: ${verdict.deviceVerdicts.sorted().joinToString(", ").ifEmpty { "(none)" }}"
        lines += "STRONG=${verdict.meetsStrong} DEVICE=${verdict.meetsDevice} BASIC=${verdict.meetsBasic}"
        if (verdict.deviceVerdicts.isEmpty()) {
            lines += "No device verdict: the request was unevaluated or failed before attestation ran."
            lines += "Re-request integrity after Apply Play Integrity setup + Refresh + clear Play Store."
        } else if (!verdict.meetsDevice) {
            lines += "BASIC-only verdict: PIF identity applied but no hardware-backed boot proof."
            lines += "Import a valid (non-revoked) keybox and re-apply, then verify it against Google lists."
        } else if (!verdict.meetsStrong) {
            lines += "DEVICE without STRONG: keybox present but Google does not trust the boot chain."
            lines += "Usual causes: revoked or soft-banned keybox, or (Android 13+) a PIF patch older than 12 months."
        } else {
            lines += "STRONG verdict: hardware-backed boot integrity is accepted by Google."
        }
        when (verdict.appVerdict) {
            null, "", "UNEVALUATED" -> lines += "App verdict: unevaluated (no app signal in this token)."
            "PLAY_RECOGNIZED" -> lines += "App verdict: PLAY_RECOGNIZED${verdict.packageName?.let { " ($it)" }.orEmpty()}"
            else -> lines += "App verdict: ${verdict.appVerdict} (sideloaded or tampered build is the usual cause)."
        }
        when (verdict.licensingVerdict) {
            null, "", "UNEVALUATED" -> { /* licensing is optional; stay quiet */ }
            "LICENSED" -> lines += "Licensing: LICENSED"
            else -> lines += "Licensing: ${verdict.licensingVerdict} (account does not own the app install)."
        }
        return lines
    }
}
