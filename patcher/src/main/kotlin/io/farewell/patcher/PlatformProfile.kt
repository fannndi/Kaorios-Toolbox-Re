package io.farewell.patcher

enum class MiuiMajor(val code: Int) {
    NONE(0),
    MIUI12(12),
    MIUI13(13),
    MIUI14(14)
}

data class PatchTarget(
    val kind: JarKind,
    val systemPath: String,
    val required: Boolean = true
) {
    val zipPath: String get() = "system_root/$systemPath"
}

data class PlatformProfile(
    val id: String,
    val deviceCodename: String?,
    val androidApi: Int,
    val miui: MiuiMajor,
    val targets: List<PatchTarget>
) {
    val label: String
        get() = when (miui) {
            MiuiMajor.NONE -> "Android $androidApi+"
            else -> "MIUI ${miui.code} (Android $androidApi)"
        }
}

object PlatformProfiles {

    private const val FRAMEWORK = "system/framework/framework.jar"
    private const val SERVICES = "system/framework/services.jar"
    private const val SETTINGS_PROVIDER = "system/priv-app/SettingsProvider/SettingsProvider.apk"

    val SURYA_MIUI12 = PlatformProfile(
        id = "surya-miui12",
        deviceCodename = "surya",
        androidApi = 29,
        miui = MiuiMajor.MIUI12,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        )
    )

    val SURYA_MIUI13 = PlatformProfile(
        id = "surya-miui13",
        deviceCodename = "surya",
        androidApi = 31,
        miui = MiuiMajor.MIUI13,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        )
    )

    val SURYA_MIUI14 = PlatformProfile(
        id = "surya-miui14",
        deviceCodename = "surya",
        androidApi = 31,
        miui = MiuiMajor.MIUI14,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        )
    )

    val MODERN = PlatformProfile(
        id = "modern-a13plus",
        deviceCodename = null,
        androidApi = 33,
        miui = MiuiMajor.NONE,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        )
    )

    val ALL = listOf(SURYA_MIUI12, SURYA_MIUI13, SURYA_MIUI14, MODERN)

    fun byId(id: String): PlatformProfile =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: error("Unknown profile: $id (${ALL.joinToString { it.id }})")

    fun resolve(codename: String?, androidApi: Int, miui: MiuiMajor): PlatformProfile {
        val surya = codename?.equals("surya", ignoreCase = true) == true
        if (surya) {
            SURYA_MIUI12.takeIf { miui == MiuiMajor.MIUI12 }?.let { return it }
            SURYA_MIUI13.takeIf { miui == MiuiMajor.MIUI13 }?.let { return it }
            SURYA_MIUI14.takeIf { miui == MiuiMajor.MIUI14 }?.let { return it }
            if (androidApi == 29) return SURYA_MIUI12
            if (androidApi == 31) return SURYA_MIUI14
        }
        return MODERN.copy(androidApi = maxOf(androidApi, MODERN.androidApi))
    }

    fun supportsDevice(codename: String?): Boolean =
        codename?.equals("surya", ignoreCase = true) == true
}
