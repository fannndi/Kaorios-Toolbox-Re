package io.farewell.toolbox.core

import android.content.Context
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

    suspend fun refresh(context: Context): PlayIntegrityResult = withContext(Dispatchers.IO) {
        val sync = DataSync.sync(context, io.farewell.toolbox.BuildConfig.DATA_BASE_URL)
        val applied = apply(context, PlayIntegrityFlags())
        if (!applied.ok) {
            return@withContext applied
        }
        RootShell.run("am force-stop com.google.android.gms.unstable; am force-stop com.google.android.gms", 60)
        RootShell.run("pm clear com.android.vending", 120)
        PlayIntegrityResult(true, "${applied.message}. GMS/Play Store refreshed (${sync.message})")
    }

    fun keyboxImported(context: Context): Boolean = File(context.filesDir, KEYBOX_FILE).exists()

    fun loadPif(context: Context): JSONObject? {
        val pifFile = File(context.filesDir, "farewell-data/Pif-props.json")
        return when {
            pifFile.exists() -> runCatching { JSONObject(pifFile.readText()) }.getOrNull()
            else -> runCatching {
                JSONObject(context.assets.open("Pif-props.json").use { it.readBytes().toString(Charsets.UTF_8) })
            }.getOrNull()
        }
    }

    fun importKeybox(context: Context, xml: String): Boolean {
        val trimmed = xml.trim()
        if (!trimmed.contains("<Certificate>") || !trimmed.contains("<PrivateKey>")) {
            return false
        }
        File(context.filesDir, KEYBOX_FILE).writeText(trimmed)
        return true
    }

    fun buildPropOverlay(context: Context): String? {
        val dataDir = File(context.filesDir, "farewell-data")
        val pifFile = File(dataDir, "Pif-props.json")
        val pif = when {
            pifFile.exists() -> runCatching { JSONObject(pifFile.readText()) }.getOrNull()
            else -> runCatching {
                JSONObject(context.assets.open("Pif-props.json").use { it.readBytes().toString(Charsets.UTF_8) })
            }.getOrNull()
        } ?: return null
        val lines = sortedMapOf<String, String>()
        lines.putAll(devicePropsFrom(pif))
        lines.putAll(buildableStaticProps)
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

    private fun devicePropsFrom(pif: JSONObject): LinkedHashMap<String, String> {
        val fingerprint = pif.optString("FINGERPRINT", "")
        val info = parseFingerprint(fingerprint)
        val brand = pif.optString("BRAND", "").ifEmpty { info?.brand.orEmpty() }
        val product = pif.optString("PRODUCT", "").ifEmpty { info?.product.orEmpty() }
        val device = pif.optString("DEVICE", "").ifEmpty { info?.device.orEmpty() }
        val manufacturer = pif.optString("MANUFACTURER", "").ifEmpty { brand }
        val model = pif.optString("MODEL", "")
        val patch = pif.optString("SECURITY_PATCH", "")

        val props = linkedMapOf<String, String>()
        if (fingerprint.isNotEmpty()) {
            props["ro.build.fingerprint"] = fingerprint
        }
        if (brand.isNotEmpty()) {
            props["ro.product.brand"] = brand
            props["ro.product.manufacturer"] = manufacturer
        }
        if (product.isNotEmpty()) {
            props["ro.product.name"] = product
            props["ro.build.product"] = product
        }
        if (device.isNotEmpty()) {
            props["ro.product.device"] = device
        }
        if (model.isNotEmpty()) {
            props["ro.product.model"] = model
            props["ro.product.system.model"] = model
        }
        info?.let {
            props["ro.build.description"] =
                "${it.product}-${it.type} ${it.release} ${it.id} ${it.incremental} ${it.tags}"
            props["ro.build.tags"] = it.tags
            props["ro.build.type"] = it.type
            props["ro.system.build.tags"] = it.tags
            props["ro.system.build.type"] = it.type
        }
        if (patch.isNotEmpty()) {
            props["ro.build.version.security_patch"] = patch
            props["ro.vendor.build.security_patch"] = patch
            props["ro.system.build.version.security_patch"] = patch
            props["ro.build.version.real_security_patch"] = patch
        }
        for (part in listOf("odm", "vendor", "product", "system_ext")) {
            for (field in listOf("model", "brand", "manufacturer", "device", "name")) {
                props["ro.product.$part.$field"] = ""
            }
        }
        return props
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
        val patch = pif.optString("SECURITY_PATCH", "")

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

        val deviceProps = linkedMapOf<String, String>()
        if (brand.isNotEmpty()) {
            deviceProps["ro.product.brand"] = brand
            deviceProps["ro.product.manufacturer"] = manufacturer
        }
        if (product.isNotEmpty()) {
            deviceProps["ro.product.name"] = product
            deviceProps["ro.build.product"] = product
        }
        if (device.isNotEmpty()) {
            deviceProps["ro.product.device"] = device
        }
        if (model.isNotEmpty()) {
            deviceProps["ro.product.model"] = model
            deviceProps["ro.product.system.model"] = model
        }
        info?.let {
            deviceProps["ro.build.description"] =
                "${it.product}-${it.type} ${it.release} ${it.id} ${it.incremental} ${it.tags}"
            deviceProps["ro.build.tags"] = it.tags
            deviceProps["ro.build.type"] = it.type
            deviceProps["ro.system.build.tags"] = it.tags
            deviceProps["ro.system.build.type"] = it.type
        }
        if (patch.isNotEmpty()) {
            deviceProps["ro.build.version.security_patch"] = patch
            deviceProps["ro.vendor.build.security_patch"] = patch
            deviceProps["ro.system.build.version.security_patch"] = patch
            deviceProps["ro.build.version.real_security_patch"] = patch
        }
        for (part in listOf("odm", "vendor", "product", "system_ext")) {
            for (field in listOf("model", "brand", "manufacturer", "device", "name")) {
                deviceProps["ro.product.$part.$field"] = ""
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
}
