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
 * Field shapes follow the public discovery document
 * (`https://playintegrity.googleapis.com/$discovery/rest?version=v1`):
 * ```
 * {
 *   "deviceIntegrity": {
 *     "deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY", ...],
 *     "deviceAttributes": { "sdkVersion": 31 },
 *     "recentDeviceActivity": { "deviceActivityLevel": "LEVEL_2" }
 *   },
 *   "appIntegrity": { "appRecognitionVerdict": "PLAY_RECOGNIZED", "packageName": "..." },
 *   "accountDetails": { "appLicensingVerdict": "LICENSED" },
 *   "environmentDetails": {
 *     "playProtectVerdict": "NO_ISSUES",
 *     "appAccessRiskVerdict": { "appsDetected": ["KNOWN_INSTALLED"] },
 *     "locationSpoofingRiskVerdict": ["LOW_RISK_DEVICE"]
 *   },
 *   "testingDetails": { "isTestingResponse": false }
 * }
 * ```
 *
 * Best-effort like every other parser here: malformed input yields null, never
 * a throw, so a garbled paste cannot break the caller. Unknown enum strings
 * are carried through verbatim so a future Google verdict still shows up.
 */
object VerdictParser {

    const val STRONG = "MEETS_STRONG_INTEGRITY"
    const val DEVICE = "MEETS_DEVICE_INTEGRITY"
    const val BASIC = "MEETS_BASIC_INTEGRITY"
    const val VIRTUAL = "MEETS_VIRTUAL_INTEGRITY"
    const val UNKNOWN = "UNKNOWN"

    data class IntegrityVerdict(
        val deviceVerdicts: Set<String>,
        val legacyDeviceVerdicts: Set<String>,
        val appVerdict: String?,
        val licensingVerdict: String?,
        val packageName: String?,
        val sdkVersion: Int?,
        val activityLevel: String?,
        val playProtectVerdict: String?,
        val appsDetected: List<String>,
        val locationSpoofingRisk: List<String>,
        val isTestingResponse: Boolean,
        val requestPackageName: String?,
    ) {
        val meetsStrong: Boolean get() = STRONG in deviceVerdicts
        val meetsDevice: Boolean get() = DEVICE in deviceVerdicts || meetsStrong
        val meetsBasic: Boolean get() = BASIC in deviceVerdicts || meetsDevice
        val isVirtual: Boolean get() = VIRTUAL in deviceVerdicts
    }

    fun parse(json: String): IntegrityVerdict? {
        if (json.isBlank()) return null
        return try {
            val root = org.json.JSONObject(json)
            val device = root.optJSONObject("deviceIntegrity")
            val app = root.optJSONObject("appIntegrity")
            val account = root.optJSONObject("accountDetails")
            val env = root.optJSONObject("environmentDetails")
            val testing = root.optJSONObject("testingDetails")
            val request = root.optJSONObject("requestDetails")
            val risk = env?.optJSONObject("appAccessRiskVerdict")
            IntegrityVerdict(
                deviceVerdicts = stringSet(device?.optJSONArray("deviceRecognitionVerdict")),
                legacyDeviceVerdicts = stringSet(device?.optJSONArray("legacyDeviceRecognitionVerdict")),
                appVerdict = app?.optString("appRecognitionVerdict", null)?.takeIf { it.isNotEmpty() },
                licensingVerdict = account?.optString("appLicensingVerdict", null)?.takeIf { it.isNotEmpty() },
                packageName = app?.optString("packageName", null)?.takeIf { it.isNotEmpty() },
                sdkVersion = device?.optJSONObject("deviceAttributes")
                    ?.optInt("sdkVersion", -1)?.takeIf { it >= 0 },
                activityLevel = device?.optJSONObject("recentDeviceActivity")
                    ?.optString("deviceActivityLevel", null)?.takeIf { it.isNotEmpty() },
                playProtectVerdict = env?.optString("playProtectVerdict", null)?.takeIf { it.isNotEmpty() },
                appsDetected = stringList(risk?.optJSONArray("appsDetected")),
                locationSpoofingRisk = stringList(env?.optJSONArray("locationSpoofingRiskVerdict")),
                isTestingResponse = testing?.optBoolean("isTestingResponse", false) == true,
                requestPackageName = request?.optString("requestPackageName", null)?.takeIf { it.isNotEmpty() },
            )
        } catch (throwable: Throwable) {
            null
        }
    }

    private fun stringSet(array: org.json.JSONArray?): Set<String> {
        val out = LinkedHashSet<String>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotEmpty()) out += value
            }
        }
        return out
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotEmpty()) out += value
            }
        }
        return out
    }

    /** Human-readable verdict lines, mirroring the app's STRONG readiness model. */
    fun summarize(verdict: IntegrityVerdict): List<String> {
        val lines = mutableListOf<String>()
        lines += "Device verdict: ${verdict.deviceVerdicts.sorted().joinToString(", ").ifEmpty { "(none)" }}"
        lines += "STRONG=${verdict.meetsStrong} DEVICE=${verdict.meetsDevice} BASIC=${verdict.meetsBasic}"
        if (verdict.isTestingResponse) {
            lines += "TESTING response: this verdict was statically overridden for a tester, it says nothing about the spoof."
        }
        if (verdict.deviceVerdicts.isEmpty()) {
            lines += "No device verdict: the request was unevaluated or failed before attestation ran."
            lines += "Re-request integrity after Apply Play Integrity setup + Refresh + clear Play Store."
        } else if (UNKNOWN in verdict.deviceVerdicts) {
            lines += "UNKNOWN device verdict: Play has insufficient information to evaluate this device."
        } else if (verdict.isVirtual) {
            lines += "VIRTUAL verdict: token came from an emulator, not a physical device."
        } else if (!verdict.meetsDevice) {
            lines += "BASIC-only verdict: PIF identity applied but no hardware-backed boot proof."
            lines += "Import a valid (non-revoked) keybox and re-apply, then verify it against Google lists."
        } else if (!verdict.meetsStrong) {
            lines += "DEVICE without STRONG: keybox present but Google does not trust the boot chain."
            lines += "Usual causes: revoked or soft-banned keybox, or (Android 13+) a PIF patch older than 12 months."
        } else {
            lines += "STRONG verdict: hardware-backed boot integrity is accepted by Google."
        }
        if (verdict.legacyDeviceVerdicts.isNotEmpty()) {
            lines += "Legacy verdict: ${verdict.legacyDeviceVerdicts.sorted().joinToString(", ")}"
        }
        verdict.sdkVersion?.let { lines += "Token SDK: $it" }
        verdict.activityLevel?.let { level ->
            lines += "Device activity: $level" + when (level) {
                "LEVEL_3", "LEVEL_4" -> " (hyperactive attestation: possible token farming flag)."
                "UNEVALUATED", "DEVICE_ACTIVITY_LEVEL_UNSPECIFIED" -> " (not evaluated)."
                else -> "."
            }
        }
        when (verdict.appVerdict) {
            null, "", "UNEVALUATED", UNKNOWN -> lines += "App verdict: unevaluated (no app signal in this token)."
            "PLAY_RECOGNIZED" -> lines += "App verdict: PLAY_RECOGNIZED${verdict.packageName?.let { " ($it)" }.orEmpty()}"
            else -> lines += "App verdict: ${verdict.appVerdict} (sideloaded or tampered build is the usual cause)."
        }
        when (verdict.licensingVerdict) {
            null, "", "UNEVALUATED", UNKNOWN -> { /* licensing is optional; stay quiet */ }
            "LICENSED" -> lines += "Licensing: LICENSED"
            else -> lines += "Licensing: ${verdict.licensingVerdict} (account does not own the app install)."
        }
        when (verdict.playProtectVerdict) {
            null, "", "UNEVALUATED", "PLAY_PROTECT_VERDICT_UNSPECIFIED", "NO_DATA" -> { /* stay quiet */ }
            "NO_ISSUES" -> lines += "Play Protect: no issues."
            else -> lines += "Play Protect: ${verdict.playProtectVerdict} (warnings or risk found on device)."
        }
        val riskyAccess = verdict.appsDetected.filterNot {
            it == "APPS_DETECTED_UNSPECIFIED" || it == "KNOWN_INSTALLED" || it == "UNKNOWN_INSTALLED"
        }
        if (riskyAccess.isNotEmpty()) {
            lines += "App access risk: ${riskyAccess.joinToString(", ")} (another app can capture, overlay or control the screen)."
        }
        val spoofingRisk = verdict.locationSpoofingRisk.filter { "HIGH_RISK" in it || "MEDIUM_RISK" in it }
        if (spoofingRisk.isNotEmpty()) {
            lines += "Location spoofing risk: ${spoofingRisk.joinToString(", ")}."
        }
        verdict.requestPackageName?.let { lines += "Requested for: $it" }
        return lines
    }
}
