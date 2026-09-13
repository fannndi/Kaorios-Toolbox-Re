package io.farewell.toolbox.core

import android.os.Build
import io.farewell.patcher.MiuiMajor
import io.farewell.patcher.PlatformProfile
import io.farewell.patcher.PlatformProfiles

data class DeviceProfileInfo(
    val model: String,
    val device: String,
    val androidApi: Int,
    val miuiName: String,
    val miuiMajor: MiuiMajor,
    val profile: PlatformProfile,
    val supportedDevice: Boolean
) {
    val miuiLabel: String
        get() = if (miuiName.isNullOrEmpty()) "MIUI not detected" else "MIUI $miuiName"
}

object DeviceDetector {

    private val suryaModels = setOf("M2007J20CG", "M2007J20CT", "M2007J20CI")

    fun detect(): DeviceProfileInfo {
        val miuiName = systemProperty("ro.miui.ui.version.name")
        val miuiMajor = parseMiui(miuiName)
        val device = Build.DEVICE.orEmpty()
        val supported = PlatformProfiles.supportsDevice(device) || Build.MODEL in suryaModels
        val profile = PlatformProfiles.resolve(device, Build.VERSION.SDK_INT, miuiMajor)
        return DeviceProfileInfo(
            model = Build.MODEL.orEmpty(),
            device = device,
            androidApi = Build.VERSION.SDK_INT,
            miuiName = miuiName,
            miuiMajor = miuiMajor,
            profile = profile,
            supportedDevice = supported
        )
    }

    private fun systemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val value = clazz.getMethod("get", String::class.java).invoke(null, key)
            value as? String ?: ""
        } catch (throwable: Throwable) {
            ""
        }
    }

    private fun parseMiui(name: String): MiuiMajor {
        val digits = name.trim().removePrefix("V").takeWhile { it.isDigit() }
        val major = when {
            digits.length >= 2 -> digits.substring(0, 2).toIntOrNull() ?: 0
            digits.length == 1 -> digits.toIntOrNull() ?: 0
            else -> 0
        }
        return MiuiMajor.entries.firstOrNull { it.code == major } ?: MiuiMajor.NONE
    }
}
