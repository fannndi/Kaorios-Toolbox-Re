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
}
