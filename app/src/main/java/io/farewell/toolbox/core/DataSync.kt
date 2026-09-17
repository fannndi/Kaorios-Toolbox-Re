package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.integrity.PifVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class DataSyncResult(val ok: Boolean, val message: String, val version: String?)

object DataSync {

    private val files = listOf(
        "Pif-props.json",
        "device-model.json",
        "app-props.json",
        "date-update.txt",
        "quotes.txt",
        "Blacklist.txt"
    )

    suspend fun sync(context: Context, baseUrl: String): DataSyncResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext DataSyncResult(false, "Data base URL is empty", null)
        }
        val dataDir = File(context.filesDir, "farewell-data").apply { mkdirs() }
        var downloaded = 0
        var kept = 0
        for (name in files) {
            val target = File(dataDir, name)
            try {
                val connection = (URL(baseUrl.trimEnd('/') + "/" + name).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                connection.connect()
                if (connection.responseCode == 200) {
                    if (name == "Pif-props.json") {
                        // A source that went backwards (failed CI scrape, an
                        // older branch overwriting the JSON) must never replace
                        // a fresher fingerprint: Play Integrity reads the patch
                        // level, and on Android 13+ STRONG needs a recent one.
                        val temp = File(dataDir, "Pif-props.json.tmp")
                        connection.inputStream.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (PifVersion.isCandidateStale(currentPif(context, dataDir), temp.readText())) {
                            temp.delete()
                            kept++
                        } else {
                            temp.renameTo(target)
                            downloaded++
                        }
                    } else {
                        connection.inputStream.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        downloaded++
                    }
                }
                connection.disconnect()
            } catch (throwable: Throwable) {
                target.delete()
            }
        }
        val version = File(dataDir, "date-update.txt").takeIf { it.exists() }?.readText()?.trim()
        if (downloaded == 0) {
            DataSyncResult(false, "No files downloaded from $baseUrl", version)
        } else {
            val keptNote = if (kept > 0) ", $kept kept (server copy is older)" else ""
            DataSyncResult(true, "$downloaded/${files.size} files updated$keptNote", version)
        }
    }

    /** Newest PIF already on the device: the synced copy, else the bundled one. */
    private fun currentPif(context: Context, dataDir: File): String? {
        val synced = File(dataDir, "Pif-props.json").takeIf { it.exists() }?.readText()
        if (synced != null) return synced
        return runCatching {
            context.assets.open("Pif-props.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    fun cachedVersion(context: Context): String? {
        val file = File(context.filesDir, "farewell-data/date-update.txt")
        return if (file.exists()) file.readText().trim() else null
    }
}
