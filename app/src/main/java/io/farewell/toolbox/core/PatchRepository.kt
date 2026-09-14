package io.farewell.toolbox.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.farewell.patcher.FlashZipBuilder
import io.farewell.patcher.JarKind
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
        val nativeProps = if (device.supportedDevice) {
            PlayIntegritySetup.buildNativePropMap(context)
        } else {
            emptyMap()
        }
        if (nativeProps.isNotEmpty()) {
            step("Native prop patch: ${nativeProps.size} properties")
        }
        val daemonConfig = if (device.supportedDevice) {
            PlayIntegritySetup.buildDaemonConfig(context)
        } else {
            ""
        }
        if (daemonConfig.isNotEmpty()) {
            val entries = daemonConfig.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
            step("Native daemon config: $entries properties (system/etc/farewell/props.conf)")
        }

        for (target in device.profile.targets) {
            val name = target.systemPath.replace('/', '_')
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
            if (target.kind == JarKind.PROPS) {
                step("Patching ${target.systemPath} (native props)")
                val original = stockFile.readText()
                val result = io.farewell.patcher.PropPatcher.apply(original, nativeProps)
                patchedFile.writeText(result.content)
                step("  ${result.replaced} replaced, ${result.appended} appended")
            } else {
                step("Patching ${target.kind} (${stockFile.length() / 1024} KB)")
                val targetReport = JarPatcher.patch(stockFile, patchedFile, target.kind, hookDex, device.profile) {
                    report.append("  [${target.kind}] ").append(it).append('\n')
                }
                appendReport(::step, targetReport)
            }
            patchedFiles[target.zipPath] = patchedFile
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val patchZip = File(workDir, "Farewell-Patch-${device.profile.id}-$stamp.zip")
        step("Building flashable zip")
        val patchTemplate = templateEntries(stamp, restore = false, files = patchedFiles.keys)
        if (daemonConfig.isNotEmpty()) {
            patchTemplate["system_root/system/etc/farewell/props.conf"] =
                daemonConfig.toByteArray(Charsets.UTF_8)
        }
        FlashZipBuilder.build(patchZip, patchTemplate, patchedFiles)

        val backupZip = File(workDir, "Farewell-Stock-${device.profile.id}-$stamp.zip")
        FlashZipBuilder.build(backupZip, templateEntries(stamp, restore = true, files = stockFiles.keys), stockFiles)

        step("Restore zip: ${backupZip.name}")
        step("Done")
        BuildResult(patchZip, backupZip, report.toString())
    }

    fun exportToDownloads(file: File, displayName: String, mimeType: String = "application/zip"): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
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

    private fun templateEntries(stamp: String, restore: Boolean, files: Collection<String>): MutableMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/com/google/android/update-binary"] =
            context.assets.open("zip/META-INF/com/google/android/update-binary").use { it.readBytes() }
        entries["META-INF/com/google/android/updater-script"] =
            scriptFor(stamp, restore, files).toByteArray(Charsets.UTF_8)
        entries["META-INF/com/ks/mount.sh"] =
            context.assets.open("zip/META-INF/com/ks/mount.sh").use { it.readBytes() }
        entries["system_root/system/framework/keystore.patch"] =
            (if (restore) "stock" else "ks2 ${device.profile.id}").plus(" $stamp\n").toByteArray(Charsets.UTF_8)
        if (!restore) {
            entries["system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml"] =
                context.assets.open("zip/privapp-permissions-io.farewell.toolbox.xml").use { it.readBytes() }
        }
        return entries
    }

    private fun scriptFor(stamp: String, restore: Boolean, files: Collection<String>): String {
        val builder = StringBuilder()
        val title = if (restore) "Stock restore" else "Framework patch"
        builder.append("ui_print(\"======================================\");\n")
        builder.append("ui_print(\"  $title\");\n")
        builder.append("ui_print(\"======================================\");\n")
        if (restore) {
            builder.append("ui_print(\" Restoring originals captured before patching.\");\n")
        } else {
            builder.append("ui_print(\" After boot, open the Toolbox app and\");\n")
            builder.append("ui_print(\" run Apply native props (root helper).\");\n")
        }
        builder.append("ui_print(\" Wiping package cache...\");\n")
        builder.append("run_program(\"/sbin/sh\", \"-c\", \"rm -rf /data/system/package_cache\");\n")
        builder.append("ui_print(\" Mounting partitions...\");\n")
        builder.append("package_extract_file(\"META-INF/com/ks/mount.sh\", \"/tmp/mount.sh\");\n")
        builder.append("set_perm(0, 0, 0777, \"/tmp/mount.sh\");\n")
        builder.append("run_program(\"/tmp/mount.sh\", \"\");\n")
        builder.append("delete(\"/tmp/mount.sh\");\n")
        builder.append("sleep(2);\n")

        builder.append("ui_print(\" ${if (restore) "Restoring" else "Installing"} system files...\");\n")
        val roots = files.map { it.substringBefore('/') }.toSortedSet()
        if (roots.isEmpty()) {
            builder.append("package_extract_dir(\"system_root\", \"/system_root\");\n")
        } else {
            for (root in roots) {
                builder.append("package_extract_dir(\"$root\", \"/$root\");\n")
            }
        }

        builder.append("ui_print(\" Setting permissions...\");\n")
        val paths = files.map { "/$it" }.toMutableSet()
        paths += "/system_root/system/framework/keystore.patch"
        paths += "/system_root/system/etc/farewell/props.conf"
        if (!restore) {
            paths += "/system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml"
        }
        for (path in paths.sorted()) {
            builder.append("set_perm(0, 0, 0644, \"$path\");\n")
        }
        if (restore) {
            builder.append("delete(\"/system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml\");\n")
        }

        builder.append("ui_print(\" Clearing dalvik cache...\");\n")
        builder.append("run_program(\"/sbin/sh\", \"-c\", \"rm -rf /data/dalvik-cache /data/misc/apexdata/com.android.art/dalvik-cache\");\n")
        builder.append("ui_print(\" Clearing boot artifacts...\");\n")
        builder.append("run_program(\"/sbin/sh\", \"-c\", \"rm -f /system_root/system/framework/boot-framework.vdex\");\n")
        builder.append(
            "run_program(\"/sbin/sh\", \"-c\", \"rm -f /system_root/system/framework/arm/boot-framework.art " +
                "/system_root/system/framework/arm/boot-framework.oat /system_root/system/framework/arm/boot-framework.vdex " +
                "/system_root/system/framework/arm64/boot-framework.art /system_root/system/framework/arm64/boot-framework.oat " +
                "/system_root/system/framework/arm64/boot-framework.vdex\");\n"
        )
        builder.append(
            "run_program(\"/sbin/sh\", \"-c\", \"rm -f /system_root/system/framework/oat/arm/services.art " +
                "/system_root/system/framework/oat/arm/services.odex /system_root/system/framework/oat/arm/services.vdex " +
                "/system_root/system/framework/oat/arm64/services.art /system_root/system/framework/oat/arm64/services.odex " +
                "/system_root/system/framework/oat/arm64/services.vdex\");\n"
        )
        builder.append("ui_print(\" Unmounting...\");\n")
        builder.append("run_program(\"/sbin/busybox\", \"umount\", \"/system_root\");\n")
        builder.append("ui_print(\" Done. Reboot your device.\");\n")
        return builder.toString()
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
        private val FRAMEWORK_CAT =
            "cat /system/framework/framework.jar 2>/dev/null || cat /system_root/system/framework/framework.jar"
        private val MARKER_CLASS = io.farewell.patcher.HookIdentity.HOOK_CLASS
        private const val VERSION_PREFIX = "ks2-"
        private const val VERSION_SCAN_LIMIT = 1 shl 20
    }
}
