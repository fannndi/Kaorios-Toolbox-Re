package io.farewell.toolbox.core

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.farewell.patcher.FlashZipBuilder
import io.farewell.patcher.JarKind
import io.farewell.patcher.JarPatcher
import io.farewell.patcher.PatchReport
import io.farewell.patcher.PatchTarget
import io.farewell.patcher.PlatformProfiles
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

    /**
     * Stock files dumped by the TWRP seed zip.
     *
     * The app's own external files dir needs no permission and TWRP can write
     * there, so a seed flash is what lets a build.prop be patched on a device
     * without root — the prop files are unreadable to both the app and the
     * shell (SELinux), only recovery and root can open them.
     */
    val seedDir: File
        get() = File(context.getExternalFilesDir(null), "seed")

    /**
     * Persistent copy of the stock files, saved by the backup action.
     *
     * The workflow this enables: back up the original once, then every later
     * build — including after an app update with a new hook — patches *these*
     * files, never the (possibly already patched) system ones. Internal storage,
     * so it survives app updates; a written copy also lives in the restore zip.
     */
    private val stockStore: File
        get() = File(context.filesDir, "stock").apply { mkdirs() }

    private fun storeFile(systemPath: String): File = File(stockStore, systemPath.replace('/', '_'))

    fun storedStockCount(): Int = stockStore.listFiles()?.count { it.isFile } ?: 0

    fun seedFileCount(): Int {
        val dir = seedDir
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    suspend fun detectStatus(): PatchStatus = withContext(Dispatchers.IO) {
        val probe = File(workDir, "installed-framework.jar")
        probe.delete()
        val source = pullFromSources("system/framework/framework.jar", probe, preferUnpatched = false)
            ?: return@withContext PatchStatus(
                RootShell.isRootAvailable(), false, null,
                "Could not read framework.jar (no root, no seed); export and flash the seed zip first",
                device.profile.id
            )
        val containsHook = source.patched
        val version = readVersion(probe)
        probe.delete()
        val capabilities = buildList {
            if (RootShell.isPrivileged(context, android.Manifest.permission.WRITE_SECURE_SETTINGS)) {
                add("privileged")
            }
            if (RootShell.isRootAvailable()) add("root")
            if (storedStockCount() > 0) add("backup ${storedStockCount()}")
            if (seedFileCount() > 0) add("seed ${seedFileCount()}")
        }.joinToString(", ").ifEmpty { "no extras" }
        if (containsHook) {
            PatchStatus(RootShell.isRootAvailable(), true, version, "Farewell patch installed (${source.label}) | $capabilities", device.profile.id)
        } else {
            PatchStatus(RootShell.isRootAvailable(), false, null, "Stock framework detected (${source.label}) | $capabilities", device.profile.id)
        }
    }

    /**
     * Reads one stock file without requiring root when possible.
     *
     * Order: the saved original (stock store), the TWRP seed, the live system
     * path, then `su cat`. With [preferUnpatched] an already-patched copy is
     * skipped and only used when nothing unpatched exists — that keeps a
     * restore artifact honest even on a device that is already flashed.
     */
    private data class StockSource(val label: String, val patched: Boolean)

    private fun pullFromSources(systemPath: String, dest: File, preferUnpatched: Boolean = true): StockSource? {
        var patchedFallback: StockSource? = null
        val candidates = listOf(
            storeFile(systemPath) to "stock store",
            File(seedDir, systemPath) to "seed",
            File("/$systemPath") to "direct"
        )
        for ((file, label) in candidates) {
            if (!readInto(file, dest)) continue
            val patched = containsAscii(dest, MARKER_CLASS.toByteArray(Charsets.US_ASCII))
            if (patched && preferUnpatched) {
                if (patchedFallback == null) patchedFallback = StockSource(label, true)
                continue
            }
            return StockSource(label, patched)
        }
        if (RootShell.isRootAvailable()) {
            val pull = RootShell.copyToFile(catCommand(systemPath), dest, timeoutSeconds = 600)
            if (pull.code == 0 && dest.length() > 0L) {
                val patched = containsAscii(dest, MARKER_CLASS.toByteArray(Charsets.US_ASCII))
                if (!(patched && preferUnpatched)) {
                    return StockSource("root", patched)
                }
                if (patchedFallback == null) patchedFallback = StockSource("root", true)
            }
        }
        dest.delete()
        return patchedFallback
    }

    private fun readInto(source: File, dest: File): Boolean {
        if (!source.exists() || !source.isFile) return false
        return try {
            source.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.length() > 0L
        } catch (throwable: Throwable) {
            dest.delete()
            false
        }
    }

    suspend fun buildPatch(onProgress: (String) -> Unit): BuildResult = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        fun step(message: String) {
            onProgress(message)
            report.append(message).append('\n')
        }

        step("Checking stock sources")
        check(device.supportedDevice) {
            "Unsupported device: ${device.device} / ${device.model}. Farewell patch targets surya only."
        }
        val hasRoot = RootShell.isRootAvailable()
        val seedCount = seedFileCount()
        step("Device: ${device.model} (${device.device}), ${device.miuiLabel}, Android ${device.androidApi}")
        step("Profile: ${device.profile.id} (${device.profile.label})")
        step(
            when {
                seedCount > 0 -> "Sources: seed ($seedCount files)${if (hasRoot) " + root" else ""}"
                hasRoot -> "Sources: root (seed not flashed)"
                else -> "Sources: direct + seed only (no root)"
            }
        )

        val hookDex = context.assets.open("hook.dex").use { it.readBytes() }
        step("Hook dex: ${hookDex.size} bytes")

        val patchedFiles = LinkedHashMap<String, File>()
        val stockFiles = LinkedHashMap<String, File>()
        var alreadyPatched = false
        val nativeProps = if (device.supportedDevice) {
            PlayIntegritySetup.buildNativePropMap(context)
        } else {
            emptyMap()
        }
        if (nativeProps.isNotEmpty()) {
            step("Runtime prop map: ${nativeProps.size} keys (union of all partitions)")
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

        // Per-SKU property files are imported at the very end of /vendor/build.prop
        // and /vendor/odm/etc/build.prop, so they win over anything we write into
        // build.prop itself. They must be patched too or the identity spoof is
        // silently reverted at boot. Deduped because a SKU may appear twice.
        val targets = LinkedHashMap<String, PatchTarget>()
        for (target in device.profile.targets) {
            targets[target.systemPath] = target
        }
        val skuTargets = PlatformProfiles.skuPropTargets(device.hardwareSku)
        for (target in skuTargets) {
            if (!targets.containsKey(target.systemPath)) {
                targets[target.systemPath] = target
            }
        }
        if (skuTargets.isNotEmpty()) {
            val skuLabel = device.hardwareSku.ifEmpty { "unknown" }
            step("SKU prop candidates: ${skuTargets.size} path(s), sku='$skuLabel'")
        }

        for (target in targets.values) {
            val name = target.systemPath.replace('/', '_')
            val stockFile = File(workDir, "${target.kind.name.lowercase()}-stock-$name")
            stockFile.delete()
            step("Reading /${target.systemPath}")
            val source = pullFromSources(target.systemPath, stockFile)
            if (source == null) {
                if (target.required) {
                    error(
                        "Could not read /${target.systemPath}: no root, and no seed copy. " +
                            "Flash Farewell-Seed-<profile>.zip in TWRP once, then build again."
                    )
                }
                step("  not present, skipped")
                continue
            }
            step("  source: ${source.label} (${stockFile.length() / 1024} KB)")
            if (source.patched) {
                // Every source was already patched. Patching again is harmless
                // (the engine detects the injected dex), but a restore zip built
                // from this would restore the *patch* — so it is skipped below.
                alreadyPatched = true
            } else {
                persistStock(target.systemPath, stockFile)
            }
            stockFiles[target.zipPath] = stockFile

            val patchedFile = File(workDir, "${target.kind.name.lowercase()}-patched-$name")
            if (target.kind == JarKind.PROPS) {
                // Each build.prop only receives the keys that belong to its own
                // partition: ro.product.odm.* goes to /vendor/odm/etc/build.prop,
                // never into /system/build.prop.
                val partitionProps = PlayIntegritySetup.propMapForTarget(context, target.propPartition)
                if (partitionProps.isEmpty()) {
                    step("  no spoof props for ${target.propPartition}, skipped")
                    continue
                }
                step("Patching ${target.systemPath} (${target.propPartition} props)")
                val original = stockFile.readText()
                val result = io.farewell.patcher.PropPatcher.apply(original, partitionProps)
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

        val patchExtras = LinkedHashMap<String, ByteArray>()
        patchExtras["system_root/system/framework/keystore.patch"] =
            "ks2 ${device.profile.id} $stamp\n".toByteArray(Charsets.UTF_8)
        patchExtras["system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml"] =
            context.assets.open("zip/privapp-permissions-io.farewell.toolbox.xml").use { it.readBytes() }
        if (daemonConfig.isNotEmpty()) {
            patchExtras["system_root/system/etc/farewell/props.conf"] = daemonConfig.toByteArray(Charsets.UTF_8)
        }
        // Rootless config: the same k2: blobs `settings put` would carry, but
        // flashed where every process can read them. The hook prefers Settings
        // and falls back to these files, so a rooted user keeps live updates.
        val zipConfig = PlayIntegritySetup.configForZip(context)
        if (zipConfig != null) {
            patchExtras["system_root/system/etc/farewell/keystore_cfg"] =
                zipConfig.first.toByteArray(Charsets.UTF_8)
            zipConfig.second?.let { keybox ->
                patchExtras["system_root/system/etc/farewell/keybox_cfg"] =
                    keybox.toByteArray(Charsets.UTF_8)
            }
            step("Config embedded in zip: keystore_cfg${if (zipConfig.second != null) " + keybox_cfg" else ""}")
        } else {
            step("Config not embedded (no PIF data yet) - apply Play Integrity setup first")
        }
        val patchTemplate = templateEntries(stamp, restore = false, payload = patchedFiles.keys, extras = patchExtras.keys)
        patchTemplate.putAll(patchExtras)
        FlashZipBuilder.build(patchZip, patchTemplate, patchedFiles)

        val backupZip: File?
        if (alreadyPatched) {
            // Refuse to fabricate a "stock" zip out of already-patched files:
            // flashing it would leave the device exactly as patched as before,
            // which is the worst possible thing to hand someone in a bootloop.
            backupZip = null
            step("Restore zip SKIPPED: the source jars are already patched.")
            step("  Use /data/media/0/Farewell/backup-*/restore.sh (from the first patch flash),")
            step("  or flash the stock ROM, to get a real restore.")
        } else {
            backupZip = File(workDir, "Farewell-Stock-${device.profile.id}-$stamp.zip")
            val stockExtras = mapOf(
                "system_root/system/framework/keystore.patch" to "stock $stamp\n".toByteArray(Charsets.UTF_8)
            )
            val stockTemplate = templateEntries(stamp, restore = true, payload = stockFiles.keys, extras = stockExtras.keys)
            stockTemplate.putAll(stockExtras)
            FlashZipBuilder.build(backupZip, stockTemplate, stockFiles)
            step("Restore zip: ${backupZip.name}")
        }

        step("Done")
        BuildResult(patchZip, backupZip, report.toString())
    }

    /**
     * Keeps an unpatched copy in the app's own store so later builds patch the
     * original even after the system files were flashed.
     */
    private fun persistStock(systemPath: String, file: File) {
        try {
            val target = storeFile(systemPath)
            if (target.exists() && target.length() == file.length()) return
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (throwable: Throwable) {
            // Best-effort: a failed store write must not fail the build.
        }
    }

    /**
     * Builds the restore zip from unpatched sources and keeps a copy of every
     * original in the app's stock store.
     *
     * This is the artifact to hand someone *before* flashing the patch: the
     * patch zip's own flash-time backup can only run once TWRP is up, so having
     * this on external storage is what makes "if it does not boot, flash the
     * backup" true. Once the store has the originals, later builds (including
     * after an app update that ships a new hook) patch those files instead of
     * the already-flashed system ones. Refuses to build when a source jar is
     * already patched — a restore zip from patched files would not restore
     * anything.
     */
    suspend fun buildStockZip(onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        check(device.supportedDevice) {
            "Unsupported device: ${device.device} / ${device.model}. Farewell patch targets surya only."
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val stockFiles = LinkedHashMap<String, File>()
        val targets = LinkedHashMap<String, PatchTarget>()
        for (target in device.profile.targets) {
            targets[target.systemPath] = target
        }
        for (target in PlatformProfiles.skuPropTargets(device.hardwareSku)) {
            if (!targets.containsKey(target.systemPath)) {
                targets[target.systemPath] = target
            }
        }
        for (target in targets.values) {
            val stockFile = File(workDir, "restore-stock-${target.systemPath.replace('/', '_')}")
            stockFile.delete()
            val source = pullFromSources(target.systemPath, stockFile)
            if (source == null) {
                if (target.required) {
                    error(
                        "Could not read /${target.systemPath}: no root, and no seed copy. " +
                            "Flash Farewell-Seed-<profile>.zip in TWRP once, then try again."
                    )
                }
                onProgress("  ${target.systemPath}: not present, skipped")
                continue
            }
            if (source.patched) {
                error(
                    "/${target.systemPath} is already patched everywhere (store, seed, system), so a restore zip " +
                        "built from it would NOT restore the stock ROM. Real restores come from " +
                        "/data/media/0/Farewell/backup-*/restore.sh (written by the first patch flash), or from " +
                        "flashing the stock ROM."
                )
            }
            persistStock(target.systemPath, stockFile)
            onProgress("  ${target.systemPath}: ${source.label}")
            stockFiles[target.zipPath] = stockFile
        }
        val zip = File(workDir, "Farewell-Stock-${device.profile.id}-$stamp.zip")
        val extras = mapOf(
            "system_root/system/framework/keystore.patch" to "stock $stamp\n".toByteArray(Charsets.UTF_8)
        )
        val template = templateEntries(stamp, restore = true, payload = stockFiles.keys, extras = extras.keys)
        template.putAll(extras)
        FlashZipBuilder.build(zip, template, stockFiles)
        zip
    }

    /**
     * Installs this APK as a privileged system app, once.
     *
     * A separate zip on purpose: the APK is ~33 MB, so bundling it into every
     * patch zip would triple the size for a one-time step. Flashed to
     * `/system/priv-app`, the app gains `REBOOT` (reboot / reboot to recovery
     * with no root) and `WRITE_SECURE_SETTINGS` (config writes with no root),
     * both listed in the allowlist that ships next to it because MIUI runs
     * `ro.control_privapp_permissions=enforce`.
     */
    fun buildPrivilegedZip(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(workDir, "Farewell-SystemApp-$stamp.zip")
        val apk = File(context.applicationInfo.sourceDir)
        check(apk.exists() && apk.length() > 0L) { "Cannot read this APK (${apk.absolutePath})" }
        val extras = mapOf(
            "system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml" to
                context.assets.open("zip/privapp-permissions-io.farewell.toolbox.xml").use { it.readBytes() },
            "system_root/system/framework/keystore.patch" to "sysapp $stamp\n".toByteArray(Charsets.UTF_8)
        )
        val template = templateEntries(
            stamp,
            restore = false,
            payload = listOf("system_root/system/priv-app/FarewellToolbox/FarewellToolbox.apk"),
            extras = extras.keys
        )
        template.putAll(extras)
        FlashZipBuilder.build(
            zip,
            template,
            mapOf("system_root/system/priv-app/FarewellToolbox/FarewellToolbox.apk" to apk)
        )
        return zip
    }

    /**
     * The seed zip: a tiny TWRP flashable that copies the stock files the app
     * cannot read (build.prop family) into the app's external files dir. After
     * one seed flash, every build works with no root.
     */
    fun buildSeedZip(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val zip = File(workDir, "Farewell-Seed-${device.profile.id}-$stamp.zip")
        val paths = LinkedHashSet<String>()
        for (target in device.profile.targets) {
            paths += target.zipPath
        }
        for (target in PlatformProfiles.skuPropTargets(device.hardwareSku)) {
            paths += target.zipPath
        }
        val template = LinkedHashMap<String, ByteArray>()
        template["META-INF/com/google/android/update-binary"] = assetText("zip/seed.sh")
        template["META-INF/com/google/android/updater-script"] = "#dummy\n".toByteArray(Charsets.UTF_8)
        template["META-INF/com/ks/mount.sh"] = assetText("zip/META-INF/com/ks/mount.sh")
        template["seed.txt"] = paths.sorted().joinToString("\n").plus("\n").toByteArray(Charsets.UTF_8)
        FlashZipBuilder.build(zip, template, emptyMap())
        return zip
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

    private fun templateEntries(
        stamp: String,
        restore: Boolean,
        payload: Collection<String>,
        extras: Collection<String>
    ): MutableMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/com/google/android/update-binary"] = assetText("zip/installer.sh")
        entries["META-INF/com/google/android/updater-script"] = "#dummy\n".toByteArray(Charsets.UTF_8)
        entries["META-INF/com/ks/mount.sh"] = assetText("zip/META-INF/com/ks/mount.sh")
        entries["manifest.txt"] = manifestFor(stamp, restore, payload + extras)
        return entries
    }

    /** Scripts must be LF-only: CRLF breaks TWRP's /sbin/sh. */
    private fun assetText(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .toByteArray(Charsets.UTF_8)

    private fun manifestFor(stamp: String, restore: Boolean, files: Collection<String>): ByteArray {
        val builder = StringBuilder()
        builder.append("# backup=").append(if (restore) "no" else "yes").append('\n')
        builder.append("# stamp=").append(stamp).append('\n')
        builder.append("# profile=").append(device.profile.id).append('\n')
        if (restore) {
            // The allowlist XML is deliberately NOT deleted: the patch/system-app
            // flashes may have installed this APK into /system/priv-app, and with
            // ro.control_privapp_permissions=enforce a privileged package with no
            // allowlist refuses to boot. Keeping the XML (harmless without the
            // system APK) is what keeps a restore from bricking the boot.
            builder.append("# delete=system_root/system/etc/farewell/props.conf")
            builder.append(",system_root/system/etc/farewell/keystore_cfg")
            builder.append(",system_root/system/etc/farewell/keybox_cfg\n")
        }
        for (path in files.toSortedSet()) {
            builder.append(path).append(" 0644\n")
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
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
        private val MARKER_CLASS = io.farewell.patcher.HookIdentity.HOOK_CLASS
        private const val VERSION_PREFIX = "ks2-"
        private const val VERSION_SCAN_LIMIT = 1 shl 20
    }
}
