package io.farewell.toolbox.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.farewell.patcher.FlashZipBuilder
import io.farewell.patcher.JarPatcher
import io.farewell.patcher.PatchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PatchStatus(
    val root: Boolean,
    val installed: Boolean,
    val installedVersion: String?,
    val message: String,
    val profileId: String
)

data class BuildResult(
    val zip: File,
    val backupZip: File?,
    val report: String
)

class PatchRepository(private val context: Context) {

    val device: DeviceProfileInfo = DeviceDetector.detect()

    private val workDir: File
        get() = File(context.cacheDir, "farewell-work").apply { mkdirs() }

    suspend fun detectStatus(): PatchStatus = withContext(Dispatchers.IO) {
        if (!RootShell.isRootAvailable()) {
            return@withContext PatchStatus(false, false, null, "Root access unavailable", device.profile.id)
        }
        val probe = File(workDir, "installed-framework.jar")
        probe.delete()
        val result = RootShell.copyToFile(FRAMEWORK_CAT, probe, timeoutSeconds = 300)
        if (result.code != 0 || !probe.exists() || probe.length() == 0L) {
            return@withContext PatchStatus(true, false, null, "Could not read framework.jar: ${result.output}", device.profile.id)
        }
        val containsHook = containsAscii(probe, MARKER_CLASS.toByteArray(Charsets.US_ASCII))
        val version = readVersion(probe)
        probe.delete()
        if (containsHook) {
            PatchStatus(true, true, version, "Farewell patch installed", device.profile.id)
        } else {
            PatchStatus(true, false, null, "Stock framework detected", device.profile.id)
        }
    }

    suspend fun buildPatch(onProgress: (String) -> Unit): BuildResult = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        fun step(message: String) {
            onProgress(message)
            report.append(message).append('\n')
        }

        step("Checking root access")
        check(RootShell.isRootAvailable()) { "Root access unavailable" }
        check(device.supportedDevice) {
            "Unsupported device: ${device.device} / ${device.model}. Farewell patch targets surya only."
        }
        step("Device: ${device.model} (${device.device}), ${device.miuiLabel}, Android ${device.androidApi}")
        step("Profile: ${device.profile.id} (${device.profile.label})")

        val hookDex = context.assets.open("hook.dex").use { it.readBytes() }
        step("Hook dex: ${hookDex.size} bytes")

        val patchedFiles = LinkedHashMap<String, File>()
        val stockFiles = LinkedHashMap<String, File>()

        for (target in device.profile.targets) {
            val name = target.systemPath.substringAfterLast('/')
            val stockFile = File(workDir, "${target.kind.name.lowercase()}-stock-$name")
            stockFile.delete()
            step("Pulling /${target.systemPath}")
            val pull = RootShell.copyToFile(catCommand(target.systemPath), stockFile, timeoutSeconds = 600)
            if (pull.code != 0 || stockFile.length() == 0L) {
                if (target.required) {
                    error("Failed to pull /${target.systemPath}: ${pull.output}")
                }
                step("  not present, skipped")
                continue
            }
            stockFiles[target.zipPath] = stockFile

            val patchedFile = File(workDir, "${target.kind.name.lowercase()}-patched-$name")
            step("Patching ${target.kind} (${stockFile.length() / 1024} KB)")
            val targetReport = JarPatcher.patch(stockFile, patchedFile, target.kind, hookDex, device.profile) {
                report.append("  [${target.kind}] ").append(it).append('\n')
            }
            appendReport(::step, targetReport)
            patchedFiles[target.zipPath] = patchedFile
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val patchZip = File(workDir, "Farewell-Patch-${device.profile.id}-$stamp.zip")
        step("Building flashable zip")
        FlashZipBuilder.build(patchZip, templateEntries(stamp), patchedFiles)

        val backupZip = File(workDir, "Farewell-Stock-${device.profile.id}-$stamp.zip")
        FlashZipBuilder.build(backupZip, templateEntries(stamp), stockFiles)

        step("Done")
        BuildResult(patchZip, backupZip, report.toString())
    }

    fun exportToDownloads(file: File, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Farewell")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create download entry")
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Could not open download stream")
        return uri.toString()
    }

    private fun appendReport(step: (String) -> Unit, report: PatchReport) {
        step("  ${report.summary()}")
        for (outcome in report.outcomes) {
            val mark = if (outcome.applied) "+" else "-"
            step("  $mark ${outcome.rule} ${outcome.detail} ${outcome.target}")
        }
    }

    private fun templateEntries(stamp: String): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/com/google/android/update-binary"] =
            context.assets.open("zip/META-INF/com/google/android/update-binary").use { it.readBytes() }
        entries["META-INF/com/google/android/updater-script"] =
            context.assets.open("zip/META-INF/com/google/android/updater-script").use { it.readBytes() }
        entries["META-INF/com/farewell/mount.sh"] =
            context.assets.open("zip/META-INF/com/farewell/mount.sh").use { it.readBytes() }
        entries["system_root/system/framework/farewell.patch"] =
            "Farewell-Toolbox ${device.profile.id} $stamp\n".toByteArray(Charsets.UTF_8)
        return entries
    }

    private fun catCommand(systemPath: String): String =
        "cat /$systemPath 2>/dev/null || cat /system_root/$systemPath"

    private fun readVersion(file: File): String? {
        val bytes = ByteArray(VERSION_SCAN_LIMIT)
        file.inputStream().use { input ->
            val read = input.read(bytes)
            if (read <= 0) return null
            val text = String(bytes, 0, read, Charsets.ISO_8859_1)
            val index = text.indexOf(VERSION_PREFIX)
            if (index < 0) return null
            val end = text.indexOf('\u0000', index)
            return if (end < 0) text.substring(index) else text.substring(index, end)
        }
    }

    private fun containsAscii(file: File, needle: ByteArray, chunkSize: Int = 1 shl 20): Boolean {
        if (needle.isEmpty()) return false
        file.inputStream().use { input ->
            val buffer = ByteArray(chunkSize + needle.size)
            var carry = 0
            while (true) {
                val read = input.read(buffer, carry, chunkSize)
                if (read <= 0) return false
                val length = carry + read
                if (indexOf(buffer, needle, length) >= 0) return true
                carry = minOf(needle.size - 1, length)
                buffer.copyInto(buffer, 0, length - carry, length)
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, length: Int): Int {
        var start = 0
        while (start <= length - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return start
            start++
        }
        return -1
    }

    companion object {
        private const val FRAMEWORK_CAT =
            "cat /system/framework/framework.jar 2>/dev/null || cat /system_root/system/framework/framework.jar"
        private const val MARKER_CLASS = "Landroid/security/farewell/FarewellHook;"
        private const val VERSION_PREFIX = "farewell-"
        private const val VERSION_SCAN_LIMIT = 1 shl 20
    }
}
