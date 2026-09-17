package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.PropPartition
import io.farewell.patcher.PropSpoof
import io.farewell.patcher.SpoofIdentity
import io.farewell.patcher.SpoofRules
import io.farewell.patcher.integrity.IntegrityData
import io.farewell.patcher.integrity.KeyboxVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class PlayIntegrityFlags(
    val hideDevStatus: Boolean = true,
    val hideAppList: Boolean = true,
    val secureFlag: Boolean = false,
    val keyboxSpoof: Boolean = true
)

data class PlayIntegrityResult(val ok: Boolean, val message: String, val configJson: String = "")

object PlayIntegritySetup {

    private val pifPackages = listOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf",
        "com.google.android.gms.ui",
        "io.github.vvb2060.keyattestation",
        "io.github.qwq233.keyattestation",
        "com.reveny.nativecheck",
        "icu.nullptr.nativetest",
        "io.liankong.riskdetector",
        "luna.safe.luna"
    )

    suspend fun apply(context: Context, flags: PlayIntegrityFlags): PlayIntegrityResult = withContext(Dispatchers.IO) {
        val dataDir = File(context.filesDir, "farewell-data")
        val pifFile = File(dataDir, "Pif-props.json")
        val pif = when {
            pifFile.exists() -> runCatching { JSONObject(pifFile.readText()) }.getOrNull()
            else -> runCatching {
                JSONObject(context.assets.open("Pif-props.json").use { it.readBytes().toString(Charsets.UTF_8) })
            }.getOrNull()
        } ?: return@withContext PlayIntegrityResult(false, "No PIF data (sync failed and no bundled fallback)")

        val keyboxFile = File(context.filesDir, KEYBOX_FILE)
        val keybox = if (keyboxFile.exists()) keyboxFile.readText() else null

        val rules = SpoofRulesStore.load(context)
        val json = buildConfig(pif, flags, keybox != null, rules)
        val configWrite = writeSetting(context, "sys_keystore_cfg", json)
        if (configWrite.code != 0) {
            return@withContext PlayIntegrityResult(false, "Failed to write config: ${configWrite.output.trim()}")
        }
        if (keybox != null) {
            val keyboxWrite = writeSetting(context, "sys_keybox_cfg", keybox)
            if (keyboxWrite.code != 0) {
                return@withContext PlayIntegrityResult(false, "Config saved, keybox failed: ${keyboxWrite.output.trim()}")
            }
        }
        val ruleNote = if (rules.isEmpty) {
            "no per-app rules"
        } else {
            "${rules.ruleCount} per-app rule(s): " +
                listOfNotNull(
                    rules.installer.size.takeIf { it > 0 }?.let { "$it installer" },
                    rules.settings.values.sumOf { t -> t.values.sumOf { it.size } }
                        .takeIf { it > 0 }?.let { "$it setting" },
                    rules.remove.values.sumOf { it.size }.takeIf { it > 0 }?.let { "$it removal" },
                    rules.features.size.takeIf { it > 0 }?.let { "$it feature" }
                ).joinToString(", ")
        }
        PlayIntegrityResult(
            true,
            "Play Integrity config applied for ${pifPackages.size} packages" +
                if (keybox != null) " + keybox" else " (no keybox imported)" +
                " ($ruleNote)",
            json
        )
    }

    suspend fun refresh(context: Context, onProgress: (String) -> Unit = {}): PlayIntegrityResult = withContext(Dispatchers.IO) {
        onProgress("Fetching latest PIF from Google OTA")
        val live = PifAutoFetch.fetch(context, onProgress)
        val syncNote = if (live != null) {
            "PIF updated from ${live.source}"
        } else {
            val sync = DataSync.sync(context, io.farewell.toolbox.BuildConfig.DATA_BASE_URL)
            sync.message
        }
        val applied = apply(context, PlayIntegrityFlags())
        if (!applied.ok) {
            return@withContext applied
        }
        RootShell.run("am force-stop com.google.android.gms.unstable; am force-stop com.google.android.gms", 60)
        RootShell.run("pm clear com.android.vending", 120)
        PlayIntegrityResult(true, "${applied.message}. $syncNote. GMS/Play Store refreshed")
    }

    fun keyboxImported(context: Context): Boolean = activeKeyboxFile(context).exists()

    fun keyboxFiles(context: Context): List<File> {
        val dir = File(context.filesDir, KEYBOX_DIR).apply { mkdirs() }
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".xml") }?.sortedBy { it.name } ?: emptyList()
    }

    fun activeKeyboxFile(context: Context): File = File(context.filesDir, KEYBOX_FILE)

    fun activeKeyboxIndex(context: Context): Int {
        val indexFile = File(context.filesDir, KEYBOX_INDEX)
        return indexFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    }

    fun importKeybox(context: Context, xml: String): Boolean {
        val trimmed = xml.trim()
        if (!trimmed.contains("<Certificate>") || !trimmed.contains("<PrivateKey>")) {
            return false
        }
        val dir = File(context.filesDir, KEYBOX_DIR).apply { mkdirs() }
        var index = 1
        while (File(dir, "keybox-$index.xml").exists()) {
            index++
        }
        val stored = File(dir, "keybox-$index.xml")
        stored.writeText(trimmed)
        if (!activeKeyboxFile(context).exists()) {
            setActiveKeybox(context, index - 1)
        }
        return true
    }

    fun setActiveKeybox(context: Context, index: Int): Boolean {
        val files = keyboxFiles(context)
        if (index !in files.indices) {
            return false
        }
        val chosen = files[index]
        activeKeyboxFile(context).writeText(chosen.readText())
        File(context.filesDir, KEYBOX_INDEX).writeText(index.toString())
        val write = writeSetting(context, "sys_keybox_cfg", chosen.readText())
        return write.code == 0
    }

    suspend fun validateAndPickHealthiest(context: Context, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val files = keyboxFiles(context)
            if (files.isEmpty()) {
                return@withContext "No keybox imported yet"
            }
            val snapshot = try {
                IntegrityData.download(File(context.filesDir, "integrity-data"))
            } catch (throwable: Throwable) {
                return@withContext "Could not fetch Google lists: ${throwable.message}"
            }
            val summary = StringBuilder()
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for ((index, file) in files.withIndex()) {
                val report = try {
                    KeyboxVerifier.verify(file.readText(), snapshot.rootPems, snapshot.statuses)
                } catch (throwable: Throwable) {
                    summary.append("#${index + 1}: error (${throwable.message})\n")
                    continue
                }
                val revoked = report.leafRevocation?.contains("REVOKED") == true
                val rank = when {
                    report.problems.isNotEmpty() -> 30
                    revoked -> 20
                    report.chainValid && report.warnings.isEmpty() -> 0
                    report.chainValid -> 5
                    else -> 10
                }
                summary.append("#${index + 1} (${file.name}): ${report.summary()}\n")
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }
            if (bestIndex >= 0) {
                val applied = setActiveKeybox(context, bestIndex)
                summary.append("Selected keybox #${bestIndex + 1}")
                if (!applied) {
                    summary.append(" (setting write failed)")
                }
            } else {
                summary.append("No usable keybox found")
            }
            onProgress(summary.toString())
            summary.toString()
        }

    fun loadPif(context: Context): JSONObject? {
        val pifFile = File(context.filesDir, "farewell-data/Pif-props.json")
        return when {
            pifFile.exists() -> runCatching { JSONObject(pifFile.readText()) }.getOrNull()
            else -> runCatching {
                JSONObject(context.assets.open("Pif-props.json").use { it.readBytes().toString(Charsets.UTF_8) })
            }.getOrNull()
        }
    }

    /**
     * Union of every partition's property map for the loaded PIF — used by the
     * native daemon and the overlay export. The map itself is built by [PropSpoof];
     * this only resolves which PIF to use.
     */
    private fun unifiedPropMap(context: Context): Map<String, String> {
        val pif = loadPif(context) ?: return emptyMap()
        val identity = PropSpoof.identityFrom(pif) ?: return emptyMap()
        return PropSpoof.buildUnifiedPropMap(identity)
    }

    fun buildNativePropMap(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>(unifiedPropMap(context))
        if (map.isEmpty()) {
            return emptyMap()
        }
        map[NativeService.STATUS_PROP] = "off"
        map.remove("")
        return map
    }

    /** Property map for one partition's build.prop, selected by the flash target. */
    fun propMapForTarget(context: Context, partition: PropPartition): Map<String, String> {
        val pif = loadPif(context) ?: return emptyMap()
        val identity = PropSpoof.identityFrom(pif) ?: return emptyMap()
        return PropSpoof.propMapFor(partition, identity)
    }

    fun buildDaemonProps(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map.putAll(buildNativePropMap(context))
        map.putAll(PropSpoof.DAEMON_BOOT_PROPS)
        map[NativeService.STATUS_PROP] = "pending"
        return map
    }

    fun buildDaemonConfig(context: Context): String {
        val builder = StringBuilder()
        builder.append("# Farewell native property service configuration\n")
        builder.append("# generated by Farewell Toolbox, read by /system/bin/farewelld\n")
        for ((key, value) in buildDaemonProps(context)) {
            builder.append(key).append('=').append(value).append('\n')
        }
        return builder.toString()
    }

    fun buildPropOverlay(context: Context): String? {
        val unified = unifiedPropMap(context)
        if (unified.isEmpty()) {
            return null
        }
        val lines = sortedMapOf<String, String>()
        lines.putAll(unified)
        val builder = StringBuilder()
        builder.append("# Farewell Toolbox PIF prop overlay\n")
        builder.append("# Drop into system.prop / product.prop of the ROM build.\n")
        builder.append("# Note: ro.boot.* bootloader props cannot be overridden here.\n")
        for ((key, value) in lines) {
            if (key.startsWith("ro.boot.")) continue
            builder.append(key).append('=').append(value).append('\n')
        }
        return builder.toString()
    }

    /**
     * The config for a rootless device: the same `k2:` blobs [apply] would write
     * into `Settings.Global`, so the patch zip can ship them to
     * `/system/etc/farewell/` and the hook falls back to them. Returns
     * `(keystore_cfg, keybox_cfg?)`, or null when there is no PIF data yet.
     */
    fun configForZip(context: Context): Pair<String, String?>? {
        val pif = loadPif(context) ?: return null
        val keyboxFile = File(context.filesDir, KEYBOX_FILE)
        val keybox = keyboxFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val rules = SpoofRulesStore.load(context)
        val json = buildConfig(pif, PlayIntegrityFlags(), keybox != null, rules)
        return Codec.encode(json) to keybox?.let { Codec.encode(it) }
    }

    private fun buildConfig(
        pif: JSONObject,
        flags: PlayIntegrityFlags,
        hasKeybox: Boolean,
        rules: SpoofRules
    ): String {
        val root = JSONObject()

        val flagsObject = JSONObject()
            .put("hide_dev_status", flags.hideDevStatus)
            .put("hide_app_list", flags.hideAppList)
            .put("secure_flag", flags.secureFlag)
            .put("keybox_spoof", flags.keyboxSpoof && hasKeybox)
        root.put("flags", flagsObject)

        val fingerprint = pif.optString("FINGERPRINT", "")
        val info = PropSpoof.parseFingerprint(fingerprint)
        val brand = pif.optString("BRAND", "").ifEmpty { info?.brand.orEmpty() }
        val product = pif.optString("PRODUCT", "").ifEmpty { info?.product.orEmpty() }
        val device = pif.optString("DEVICE", "").ifEmpty { info?.device.orEmpty() }
        val manufacturer = pif.optString("MANUFACTURER", "").ifEmpty { brand }
        val model = pif.optString("MODEL", "")

        val buildExtras = linkedMapOf(
            "TAGS" to (info?.tags ?: "release-keys"),
            "TYPE" to (info?.type ?: "user"),
            "BRAND" to brand,
            "PRODUCT" to product,
            "DEVICE" to device,
            "MANUFACTURER" to manufacturer,
            "MODEL" to model
        )
        info?.let {
            buildExtras["ID"] = it.id
            buildExtras["DISPLAY"] = it.id
            buildExtras["INCREMENTAL"] = it.incremental
            buildExtras["RELEASE"] = it.release
        }

        // The hook's SystemProperties filter must answer for every partition
        // variant, not just the plain ro.product.* / ro.build.* keys.
        val identity = PropSpoof.identityFrom(pif)
        val deviceProps = linkedMapOf<String, String>()
        if (identity != null) {
            for (partition in listOf(
                PropPartition.SYSTEM,
                PropPartition.PRODUCT,
                PropPartition.SYSTEM_EXT,
                PropPartition.VENDOR,
                PropPartition.ODM
            )) {
                deviceProps.putAll(PropSpoof.propMapFor(partition, identity))
            }
        }

        val buildObject = JSONObject()
        for (pkg in pifPackages) {
            val entry = JSONObject(pif.toString())
            entry.put("FINGERPRINT", fingerprint)
            for ((key, value) in buildExtras) {
                if (value.isNotEmpty()) {
                    entry.put(key, value)
                }
            }
            buildObject.put(pkg, entry)
        }
        root.put("build", buildObject)

        val props = JSONObject()
        val globalProps = JSONObject()
        for ((key, value) in PropSpoof.STATIC_PROPS) {
            globalProps.put(key, value)
        }
        props.put("*", globalProps)
        for (pkg in pifPackages) {
            val entry = JSONObject()
            for ((key, value) in deviceProps) {
                entry.put(key, value)
            }
            props.put(pkg, entry)
        }
        root.put("props", props)

        // Per-app spoof rules (installer source, Settings values/removals, system
        // features). The hook has always read these sections; until now nothing
        // wrote them, so the patched call sites had no config to act on.
        rules.applyTo(root)

        return root.toString()
    }

    private fun writeSetting(context: Context, key: String, value: String): ShellResult {
        val encoded = Codec.encode(value)
        // Rootless path first: a privileged install (the patch zip puts this APK
        // in /system/priv-app with WRITE_SECURE_SETTINGS in the allowlist) can
        // write Settings.Global directly, no shell involved.
        val app = context.applicationContext as? android.app.Application
        if (app != null &&
            app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return try {
                android.provider.Settings.Global.putString(context.contentResolver, key, encoded)
                ShellResult(0, "settings written directly")
            } catch (throwable: Throwable) {
                ShellResult(1, throwable.message ?: "direct settings write failed")
            }
        }
        val escaped = encoded.replace("'", "'\\''")
        return RootShell.run("settings put global $key '$escaped'", timeoutSeconds = 120)
    }

    private const val KEYBOX_FILE = "ks2-keybox.xml"
    private const val KEYBOX_DIR = "keyboxes"
    private const val KEYBOX_INDEX = "keybox-active.txt"
}
