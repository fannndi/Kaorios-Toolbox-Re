package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.integrity.IntegrityData
import io.farewell.patcher.integrity.KeyboxVerifier
import io.farewell.patcher.integrity.VerdictParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object IntegrityCheck {

    suspend fun verifyImportedKeybox(context: Context): String = withContext(Dispatchers.IO) {
        val keyboxFile = File(context.filesDir, "ks2-keybox.xml")
        if (!keyboxFile.exists()) {
            return@withContext "No keybox imported yet"
        }
        val directory = File(context.filesDir, "integrity-data")
        val snapshot = try {
            IntegrityData.download(directory)
        } catch (throwable: Throwable) {
            return@withContext "Could not fetch Google lists: ${throwable.message}"
        }
        val report = try {
            KeyboxVerifier.verify(keyboxFile.readText(), snapshot.rootPems, snapshot.statuses)
        } catch (throwable: Throwable) {
            return@withContext "Verification failed: ${throwable.message}"
        }
        (report.lines() + report.summary()).joinToString("\n")
    }

    fun cachedStatusCount(context: Context): Int {
        val status = File(context.filesDir, "integrity-data/google-attestation-status.json")
        if (!status.exists()) return 0
        return runCatching {
            val root = org.json.JSONObject(status.readText())
            root.optJSONObject("entries")?.length() ?: 0
        }.getOrDefault(0)
    }

    fun compareVerdict(context: Context, basic: Boolean, device: Boolean, strong: Boolean): String {        val lines = mutableListOf<String>()
        val keybox = PlayIntegritySetup.keyboxImported(context)
        val pif = PlayIntegritySetup.loadPif(context)
        val patch = pif?.optString("SECURITY_PATCH", "").orEmpty()
        val sdk = android.os.Build.VERSION.SDK_INT
        lines += "Play Store verdict: BASIC=$basic DEVICE=$device STRONG=$strong"
        lines += "Our inputs: keybox=${if (keybox) "imported" else "missing"}, PIF patch=${patch.ifEmpty { "none" }}, Android $sdk"

        if (!basic) {
            lines += "BASIC failed: the app itself is likely not recognized (sideloaded) or Play Protect is off."
            lines += "Install the build through Play (internal testing) if you need PLAY_RECOGNIZED."
        }
        if (basic && !device) {
            lines += "DEVICE failed: check that PIF is applied (Apply Play Integrity setup), the patch is not older than the device patch,"
            lines += "and that DroidGuard was restarted after applying (Refresh + clear Play Store). Run STRONG readiness check for details."
        }
        if (device && !strong) {
            if (!keybox) {
                lines += "STRONG failed and no keybox is imported: hardware-backed boot proof needs a keybox."
            } else {
                lines += "STRONG failed with a keybox present: verify the keybox (Google lists) - revoked, soft-banned or non-Google root"
                lines += "are the usual causes. On Android 13+, an out-of-date PIF patch (>12 months) also breaks STRONG."
            }
        }
        if (strong && keybox) {
            lines += "STRONG matches our readiness model: hardware-backed signals are in place."
        }
        val cachedStatus = cachedStatusCount(context)
        if (cachedStatus > 0) {
            lines += "Google status list cache: $cachedStatus entries"
        }
        return lines.joinToString("\n")
    }

    /**
     * "Did my spoof pass?" from a decrypted verdict. The raw integrity token is
     * encrypted (JWE) and only Google's server can open it, so the user decodes
     * it on their own Play Console project and pastes the tokenPayloadExternal
     * JSON here. Pure string in/out, so the CLI and the app share the wording
     * through [VerdictParser].
     */
    fun decodeVerdictJson(text: String): String {
        val pasted = text.trim()
        if (pasted.isEmpty()) {
            return "Paste the decrypted decodeIntegrityToken JSON first."
        }
        if (VerdictParser.looksLikeRawToken(pasted)) {
            return listOf(
                "That is a RAW encrypted token - only Google's server can open it.",
                "Decrypt it (POST https://playintegrity.googleapis.com/v1/PACKAGE_NAME:decodeIntegrityToken",
                "with a service-account OAuth token), then paste the tokenPayloadExternal JSON here.",
                "Decrypt each token exactly once: re-decrypting clears every verdict."
            ).joinToString("\n")
        }
        val verdict = VerdictParser.parse(pasted)
            ?: return "Not a verdict JSON. Decrypt the token server-side first, then paste the JSON here."
        return VerdictParser.summarize(verdict).joinToString("\n")
    }

    suspend fun readinessReport(context: Context): String = withContext(Dispatchers.IO) {        val lines = mutableListOf<String>()
        lines += "Play Integrity STRONG readiness"
        val sdk = android.os.Build.VERSION.SDK_INT
        if (sdk >= 33) {
            lines += "Android $sdk: STRONG also requires OS + vendor security patches from the last 12 months"
        } else {
            lines += "Android $sdk: STRONG requires only hardware-backed boot integrity (no patch recency requirement)"
        }

        val pif = PlayIntegritySetup.loadPif(context)
        val pifPatch = pif?.optString("SECURITY_PATCH", "").orEmpty()
        if (pifPatch.isEmpty()) {
            lines += "PIF patch: missing (sync or bundled fallback not available)"
        } else {
            val ageMonths = patchAgeMonths(pifPatch)
            val fresh = ageMonths <= 12
            lines += if (sdk >= 33) {
                "PIF patch $pifPatch (age ${ageMonths}m): ${if (fresh) "OK for STRONG" else "TOO OLD for STRONG"}"
            } else {
                "PIF patch $pifPatch (age ${ageMonths}m): consistency only, not required for STRONG"
            }
            val realPatch = android.os.Build.VERSION.SECURITY_PATCH.orEmpty()
            if (realPatch.isNotEmpty() && realPatch > pifPatch) {
                lines += "WARN: device patch $realPatch is newer than PIF patch $pifPatch (patch is never moved backwards)"
            }
            lines += "Attestation stamps os/vendor/boot patch levels from the PIF patch"
        }

        val keyboxFile = File(context.filesDir, "ks2-keybox.xml")
        if (keyboxFile.exists()) {
            val snapshot = try {
                IntegrityData.download(File(context.filesDir, "integrity-data"))
            } catch (throwable: Throwable) {
                lines += "Keybox: could not fetch Google lists (${throwable.message})"
                return@withContext lines.joinToString("\n")
            }
            val report = KeyboxVerifier.verify(keyboxFile.readText(), snapshot.rootPems, snapshot.statuses)
            lines += "Keybox: ${report.summary()}"
            lines += report.lines()
        } else {
            lines += "Keybox: not imported"
        }

        val hasHardwareSignals = keyboxFile.exists()
        lines += if (hasHardwareSignals) {
            "Verdict outlook: hardware-backed signals available (keybox + software keypair path)"
        } else {
            "Verdict outlook: without a keybox only BASIC/DEVICE (software) signals are possible"
        }
        lines.joinToString("\n")
    }

    private fun patchAgeMonths(patch: String): Int {
        return try {
            val year = patch.substring(0, 4).toInt()
            val month = patch.substring(5, 7).toInt()
            val now = java.util.Calendar.getInstance()
            (now.get(java.util.Calendar.YEAR) - year) * 12 + (now.get(java.util.Calendar.MONTH) + 1 - month)
        } catch (throwable: Throwable) {
            99
        }
    }
}
