package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.integrity.IntegrityData
import io.farewell.patcher.integrity.KeyboxVerifier
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
            KeyboxVerifier.verify(keyboxFile.readText(), snapshot.rootPems, snapshot.revoked)
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

    suspend fun readinessReport(context: Context): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
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
            val report = KeyboxVerifier.verify(keyboxFile.readText(), snapshot.rootPems, snapshot.revoked)
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
