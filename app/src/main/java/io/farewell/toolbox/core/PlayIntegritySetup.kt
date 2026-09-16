package io.farewell.toolbox.core

import android.content.Context
import io.farewell.patcher.PropPartition
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

    private val staticProps = linkedMapOf(
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "ro.build.selinux" to "1",
        "sys.oem_unlock_allowed" to "0"
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

        val json = buildConfig(pif, flags, keybox != null)
        val configWrite = writeSetting("sys_keystore_cfg", json)
        if (configWrite.code != 0) {
            return@withContext PlayIntegrityResult(false, "Failed to write config: ${configWrite.output.trim()}")
        }
        if (keybox != null) {
            val keyboxWrite = writeSetting("sys_keybox_cfg", keybox)
            if (keyboxWrite.code != 0) {
                return@withContext PlayIntegrityResult(false, "Config saved, keybox failed: ${keyboxWrite.output.trim()}")
            }
        }
        PlayIntegrityResult(
            true,
            "Play Integrity config applied for ${pifPackages.size} packages" +
                if (keybox != null) " + keybox" else " (no keybox imported)",
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
        val write = writeSetting("sys_keybox_cfg", chosen.readText())
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

    fun buildNativePropMap(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>(buildUnifiedPropMap(context))
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
        val identity = identityFrom(pif) ?: return emptyMap()
        return propMapFor(partition, identity)
    }

    fun buildDaemonProps(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map.putAll(buildNativePropMap(context))
        map.putAll(daemonBootProps)
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
        val unified = buildUnifiedPropMap(context)
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

    private val buildableStaticProps = linkedMapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "ro.build.selinux" to "1"
    )

    private val daemonBootProps = linkedMapOf(
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.flash.locked" to "1",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.warranty_bit" to "0",
        "ro.secureboot.lockstate" to "locked",
        "ro.adb.secure" to "1",
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.warranty_bit" to "0",
        "sys.oem_unlock_allowed" to "0"
    )

    /**
     * One spoofed device identity, resolved from the PIF JSON.
     *
     * Surya MIUI ROMs do not define the plain `ro.product.*` keys in any
     * build.prop. init derives them at boot from `ro.product.<partition>.<field>`
     * following `ro.product.property_source_order` (audited: `odm,vendor,product,
     * product_services,system` on MIUI 12 and `odm,vendor,product,system_ext,system`
     * on MIUI 13/14). On surya the effective `ro.product.model` therefore comes
     * from `/vendor/odm/etc/build.prop`, not from `/system/build.prop`.
     *
     * Setting only the plain keys is not enough: any reader that asks for
     * `ro.product.odm.model` (DroidGuard does) still sees the real POCO X3.
     * So the same values are written to every partition variant — this is the
     * "prop unification" step.
     */
    data class SpoofIdentity(
        val brand: String,
        val device: String,
        val model: String,
        val product: String,
        val manufacturer: String,
        val fingerprint: String,
        val description: String,
        val tags: String,
        val type: String,
        val id: String,
        val release: String,
        val incremental: String,
        val securityPatch: String
    ) {
        val usable: Boolean
            get() = brand.isNotEmpty() && device.isNotEmpty() && model.isNotEmpty() &&
                product.isNotEmpty() && fingerprint.isNotEmpty()
    }

    private fun SpoofIdentity.productValues(): Map<String, String> = mapOf(
        "brand" to brand,
        "device" to device,
        "model" to model,
        "name" to product,
        "manufacturer" to manufacturer
    )

    private fun SpoofIdentity.buildValues(): Map<String, String> = mapOf(
        "fingerprint" to fingerprint,
        "tags" to tags,
        "type" to type,
        "id" to id,
        "version.incremental" to incremental,
        "version.release" to release
    )

    /**
     * Property map for one partition's build.prop. Keys are emitted with the
     * partition prefix that belongs in that file, so `/vendor/odm/etc/build.prop`
     * only ever receives `ro.product.odm.*` / `ro.odm.build.*`.
     */
    fun propMapFor(partition: PropPartition, identity: SpoofIdentity): Map<String, String> {
        if (!identity.usable) return emptyMap()
        val part = when (partition) {
            PropPartition.SYSTEM -> "system"
            PropPartition.PRODUCT -> "product"
            PropPartition.SYSTEM_EXT -> "system_ext"
            PropPartition.VENDOR -> "vendor"
            PropPartition.ODM -> "odm"
            PropPartition.OTHER -> return emptyMap()
        }
        val map = linkedMapOf<String, String>()
        for ((field, value) in identity.productValues()) {
            if (value.isNotEmpty()) {
                map["ro.product.$part.$field"] = value
            }
        }
        for ((field, value) in identity.buildValues()) {
            if (value.isNotEmpty()) {
                map["ro.$part.build.$field"] = value
            }
        }
        if (identity.securityPatch.isNotEmpty()) {
            map["ro.$part.build.version.security_patch"] = identity.securityPatch
        }

        when (partition) {
            // /system/build.prop also owns the unprefixed keys. They must be set
            // explicitly, otherwise init's product_source_order derivation wins.
            PropPartition.SYSTEM -> {
                for ((field, value) in identity.productValues()) {
                    if (value.isNotEmpty()) {
                        map["ro.product.$field"] = value
                    }
                }
                map["ro.build.product"] = identity.product
                map["ro.build.description"] = identity.description
                map["ro.build.tags"] = identity.tags
                map["ro.build.type"] = identity.type
                map["ro.build.id"] = identity.id
                map["ro.build.version.incremental"] = identity.incremental
                map["ro.build.version.release"] = identity.release
                map["ro.build.fingerprint"] = identity.fingerprint
                if (identity.securityPatch.isNotEmpty()) {
                    map["ro.build.version.security_patch"] = identity.securityPatch
                    map["ro.build.version.real_security_patch"] = identity.securityPatch
                }
                // Hardening flags.
                // Audited: MIUI 13/14 declare ro.secure / ro.debuggable /
                // ro.adb.secure directly in /system/build.prop, so they can be
                // rewritten here. MIUI 12 does NOT: there is no
                // /system/default.prop on that ROM and the flags come from the
                // ramdisk default.prop inside boot.img. Because ro.* properties
                // are write-once, appending them to build.prop would be ignored —
                // only the boot-classpath hook (SystemProperties filter) can
                // answer for them on MIUI 12. They are still emitted here so the
                // same map drives the daemon and the hook config.
                map.putAll(buildableStaticProps)
            }
            // /vendor/build.prop carries the boot image fingerprint and ro.adb.secure
            // (the latter actually lives in /vendor/default.prop).
            PropPartition.VENDOR -> {
                map["ro.bootimage.build.fingerprint"] = identity.fingerprint
                map["ro.adb.secure"] = "1"
            }
            else -> Unit
        }
        return map
    }

    /** Union of every partition's map — used for the runtime daemon and the overlay export. */
    fun buildUnifiedPropMap(context: Context): Map<String, String> {
        val pif = loadPif(context) ?: return emptyMap()
        val identity = identityFrom(pif) ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (partition in listOf(
            PropPartition.SYSTEM,
            PropPartition.PRODUCT,
            PropPartition.SYSTEM_EXT,
            PropPartition.VENDOR,
            PropPartition.ODM
        )) {
            map.putAll(propMapFor(partition, identity))
        }
        for ((key, value) in staticProps) {
            map[key] = value
        }
        return map
    }

    private fun identityFrom(pif: JSONObject): SpoofIdentity? {
        val fingerprint = pif.optString("FINGERPRINT", "")
        val info = parseFingerprint(fingerprint)
        val brand = pif.optString("BRAND", "").ifEmpty { info?.brand.orEmpty() }
        val product = pif.optString("PRODUCT", "").ifEmpty { info?.product.orEmpty() }
        val device = pif.optString("DEVICE", "").ifEmpty { info?.device.orEmpty() }
        val manufacturer = pif.optString("MANUFACTURER", "").ifEmpty { brand }
        val model = pif.optString("MODEL", "")
        val patch = pif.optString("SECURITY_PATCH", "")
        val tags = info?.tags ?: "release-keys"
        val type = info?.type ?: "user"
        val id = info?.id.orEmpty()
        val release = info?.release.orEmpty()
        val incremental = info?.incremental.orEmpty()
        val description = if (info != null) {
            "${info.product}-${info.type} ${info.release} ${info.id} ${info.incremental} ${info.tags}"
        } else {
            ""
        }
        return SpoofIdentity(
            brand = brand,
            device = device,
            model = model,
            product = product,
            manufacturer = manufacturer,
            fingerprint = fingerprint,
            description = description,
            tags = tags,
            type = type,
            id = id,
            release = release,
            incremental = incremental,
            securityPatch = patch
        )
    }

    private fun buildConfig(pif: JSONObject, flags: PlayIntegrityFlags, hasKeybox: Boolean): String {
        val root = JSONObject()

        val flagsObject = JSONObject()
            .put("hide_dev_status", flags.hideDevStatus)
            .put("hide_app_list", flags.hideAppList)
            .put("secure_flag", flags.secureFlag)
            .put("keybox_spoof", flags.keyboxSpoof && hasKeybox)
        root.put("flags", flagsObject)

        val fingerprint = pif.optString("FINGERPRINT", "")
        val info = parseFingerprint(fingerprint)
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
        val identity = identityFrom(pif)
        val deviceProps = linkedMapOf<String, String>()
        if (identity != null) {
            for (partition in listOf(
                PropPartition.SYSTEM,
                PropPartition.PRODUCT,
                PropPartition.SYSTEM_EXT,
                PropPartition.VENDOR,
                PropPartition.ODM
            )) {
                deviceProps.putAll(propMapFor(partition, identity))
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
        for ((key, value) in staticProps) {
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

        return root.toString()
    }

    private data class FingerprintInfo(
        val brand: String,
        val product: String,
        val device: String,
        val release: String,
        val id: String,
        val incremental: String,
        val type: String,
        val tags: String
    )

    private fun parseFingerprint(fingerprint: String): FingerprintInfo? {
        val sections = fingerprint.split(":")
        if (sections.size < 3) return null
        val first = sections[0].split("/")
        if (first.size < 3) return null
        val second = sections[1].split("/")
        val third = sections[2].split("/")
        return FingerprintInfo(
            brand = first[0],
            product = first[1],
            device = first[2].substringBefore("/"),
            release = second.getOrElse(0) { "" },
            id = second.getOrElse(1) { "" },
            incremental = second.getOrElse(2) { "" },
            type = if (third.isNotEmpty() && third[0].contains("userdebug")) "userdebug"
            else if (third.isNotEmpty() && third[0].contains("eng")) "eng" else "user",
            tags = third.getOrElse(1) { "release-keys" }
        )
    }

    private fun writeSetting(key: String, value: String): ShellResult {
        val escaped = Codec.encode(value).replace("'", "'\\''")
        return RootShell.run("settings put global $key '$escaped'", timeoutSeconds = 120)
    }

    private const val KEYBOX_FILE = "ks2-keybox.xml"
    private const val KEYBOX_DIR = "keyboxes"
    private const val KEYBOX_INDEX = "keybox-active.txt"
}
