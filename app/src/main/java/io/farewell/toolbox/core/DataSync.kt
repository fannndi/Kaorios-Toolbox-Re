package io.farewell.toolbox.core

import android.content.Context
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
        for (name in files) {
            val target = File(dataDir, name)
            try {
                val connection = (URL(baseUrl.trimEnd('/') + "/" + name).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                connection.connect()
                if (connection.responseCode == 200) {
                    connection.inputStream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    downloaded++
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
            DataSyncResult(true, "$downloaded/${files.size} files updated", version)
        }
    }

    fun cachedVersion(context: Context): String? {
        val file = File(context.filesDir, "farewell-data/date-update.txt")
        return if (file.exists()) file.readText().trim() else null
    }
}
