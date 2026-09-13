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
        "com.google.android.gms.ui"
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

    private val staticBuildFields = mapOf(
        "TAGS" to "release-keys",
        "TYPE" to "user"
    )

    suspend fun apply(context: Context, flags: PlayIntegrityFlags): PlayIntegrityResult = withContext(Dispatchers.IO) {
        val dataDir = File(context.filesDir, "farewell-data")
        val pifFile = File(dataDir, "Pif-props.json")
        if (!pifFile.exists()) {
            return@withContext PlayIntegrityResult(false, "Pif-props.json not synced yet")
        }
        val pif = runCatching { JSONObject(pifFile.readText()) }.getOrNull()
            ?: return@withContext PlayIntegrityResult(false, "Pif-props.json is invalid")

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
            "Play Integrity config applied for ${pifPackages.size} Google packages" +
                if (keybox != null) " + keybox" else " (no keybox imported)",
            json
        )
    }

    fun keyboxImported(context: Context): Boolean = File(context.filesDir, KEYBOX_FILE).exists()

    fun importKeybox(context: Context, xml: String): Boolean {
        val trimmed = xml.trim()
        if (!trimmed.contains("<Certificate>") || !trimmed.contains("<PrivateKey>")) {
            return false
        }
        File(context.filesDir, KEYBOX_FILE).writeText(trimmed)
        return true
    }

    private fun buildConfig(pif: JSONObject, flags: PlayIntegrityFlags, hasKeybox: Boolean): String {
        val root = JSONObject()

        val flagsObject = JSONObject()
            .put("hide_dev_status", flags.hideDevStatus)
            .put("hide_app_list", flags.hideAppList)
            .put("secure_flag", flags.secureFlag)
            .put("keybox_spoof", flags.keyboxSpoof && hasKeybox)
        root.put("flags", flagsObject)

        val buildObject = JSONObject()
        for (pkg in pifPackages) {
            val entry = JSONObject(pif.toString())
            for ((key, value) in staticBuildFields) {
                entry.put(key, value)
            }
            buildObject.put(pkg, entry)
        }
        root.put("build", buildObject)

        val propKeys = mapOf(
            "ro.product.model" to "MODEL",
            "ro.product.brand" to "MANUFACTURER",
            "ro.product.manufacturer" to "MANUFACTURER",
            "ro.product.device" to "DEVICE",
            "ro.product.name" to "PRODUCT",
            "ro.build.fingerprint" to "FINGERPRINT",
            "ro.build.version.security_patch" to "SECURITY_PATCH"
        )
        val props = JSONObject()
        for ((key, value) in staticProps) {
            props.put(key, value)
        }
        for ((prop, field) in propKeys) {
            val value = pif.optString(field, "")
            if (value.isNotEmpty()) {
                props.put(prop, value)
            }
        }
        val globalProps = JSONObject()
        globalProps.put("*", props)
        root.put("props", globalProps)

        return root.toString()
    }

    private fun writeSetting(key: String, value: String): ShellResult {
        val escaped = value.replace("'", "'\\''")
        return RootShell.run("settings put global $key '$escaped'", timeoutSeconds = 120)
    }

    private const val KEYBOX_FILE = "ks2-keybox.xml"
}
