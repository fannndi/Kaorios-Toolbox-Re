package io.farewell.patcher

enum class MiuiMajor(val code: Int) {
    NONE(0),
    MIUI12(12),
    MIUI13(13),
    MIUI14(14)
}

/**
 * Which partition a build.prop belongs to. Property files are read in init's
 * fixed order (system -> system_ext -> vendor -> odm -> product) and later files
 * win for duplicate keys, so a spoofed value must be written to the file that
 * actually defines the key on that ROM. Writing `ro.product.odm.model` into
 * `/system/build.prop` would be ignored (or worse, shadow the real source).
 */
enum class PropPartition {
    SYSTEM,
    PRODUCT,
    SYSTEM_EXT,
    VENDOR,
    ODM,
    OTHER
}

data class PatchTarget(
    val kind: JarKind,
    val systemPath: String,
    val required: Boolean = true
) {
    /**
     * Path of the file inside the flashable zip.
     * Files on the system partition live under system_root (TWRP mount point),
     * other dynamic partitions (product/vendor/system_ext/odm) keep their path.
     */
    val zipPath: String
        get() = if (systemPath.startsWith("system/")) "system_root/$systemPath" else systemPath

    /** Partition a property file belongs to; only meaningful for [JarKind.PROPS]. */
    val propPartition: PropPartition
        get() = when {
            systemPath.startsWith("system_ext/") -> PropPartition.SYSTEM_EXT
            systemPath.startsWith("product/") -> PropPartition.PRODUCT
            systemPath.startsWith("vendor/odm/") || systemPath.startsWith("odm/") -> PropPartition.ODM
            systemPath.startsWith("vendor/") -> PropPartition.VENDOR
            systemPath.startsWith("system/") -> PropPartition.SYSTEM
            else -> PropPartition.OTHER
        }
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

    /**
     * Where `com.android.providers.settings.SettingsProvider` actually lives on
     * surya MIUI 12/13/14 — verified by extracting the stock APKs. It is NOT in
     * services.jar, so no SERVICES-kind rule can ever reach it.
     */
    const val SETTINGS_PROVIDER_APK = "system/priv-app/SettingsProvider/SettingsProvider.apk"

    /*
     * Property files audited on surya MIUI 12 / 13 / 14 (stock ROM extractions).
     * MIUI 12 (Android 10) is the odd one out: product props live in
     * /product/build.prop and there is no system_ext partition at all, while
     * MIUI 13/14 (Android 12) moved them to /product/etc/build.prop and added
     * /system_ext/etc/build.prop. Vendor/ODM paths are stable across all three.
     */
    private fun systemProp() = PatchTarget(JarKind.PROPS, "system/build.prop", required = false)
    private fun systemDefaultProp() = PatchTarget(JarKind.PROPS, "system/default.prop", required = false)
    private fun productPropLegacy() = PatchTarget(JarKind.PROPS, "product/build.prop", required = false)
    private fun productProp() = PatchTarget(JarKind.PROPS, "product/etc/build.prop", required = false)
    private fun systemExtProp() = PatchTarget(JarKind.PROPS, "system_ext/etc/build.prop", required = false)
    private fun vendorProp() = PatchTarget(JarKind.PROPS, "vendor/build.prop", required = false)
    private fun vendorDefaultProp() = PatchTarget(JarKind.PROPS, "vendor/default.prop", required = false)
    private fun odmProp() = PatchTarget(JarKind.PROPS, "vendor/odm/etc/build.prop", required = false)

    /**
     * SKU values shipped in the stock surya MIUI 12/13/14 vendor trees. The
     * device picks one at boot through `ro.boot.product.hardware.sku`.
     */
    val KNOWN_SKUS = listOf("surya", "karna")

    /**
     * The per-SKU property files that `build.prop` imports **at the very end**:
     *
     *     /vendor/build.prop:452        import /vendor/build_${sku}.prop
     *     /vendor/odm/etc/build.prop:28 import /vendor/odm/etc/build_${sku}.prop
     *
     * Because the import sits after the `ro.product.*` / `ro.*.build.*` block,
     * the SKU file is the last writer for every key it re-declares, and on these
     * ROMs that is exactly the identity block:
     *
     *     ro.product.odm.model        ro.odm.build.fingerprint
     *     ro.product.vendor.model     ro.vendor.build.fingerprint
     *     ro.bootimage.build.fingerprint
     *
     * Patching only `build.prop` therefore looks successful — the keys really are
     * rewritten — but the imported SKU file silently restores the stock values at
     * boot. Audited on all three surya ROMs; see
     * `Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md`.
     *
     * The device's own SKU is patched first, then every known SKU so a single zip
     * still works when shared with a sibling device (karna = POCO X3 Pro).
     * Missing files are skipped because every target is `required = false`.
     */
    fun skuPropTargets(sku: String?): List<PatchTarget> {
        val names = LinkedHashSet<String>()
        sku?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { names += it }
        names += KNOWN_SKUS
        return names.flatMap { name ->
            listOf(
                PatchTarget(JarKind.PROPS, "vendor/build_$name.prop", required = false),
                PatchTarget(JarKind.PROPS, "vendor/odm/etc/build_$name.prop", required = false)
            )
        }
    }

    /** MIUI 12 / Android 10: product props in /product/build.prop, no system_ext. */
    private val legacyPropTargets = listOf(
        systemProp(),
        systemDefaultProp(),
        productPropLegacy(),
        vendorProp(),
        vendorDefaultProp(),
        odmProp()
    )

    /** MIUI 13/14 / Android 12+: product props in /product/etc, system_ext present. */
    private val modernPropTargets = listOf(
        systemProp(),
        productProp(),
        systemExtProp(),
        vendorProp(),
        vendorDefaultProp(),
        odmProp()
    )

    val SURYA_MIUI12 = PlatformProfile(
        id = "surya-miui12",
        deviceCodename = "surya",
        androidApi = 29,
        miui = MiuiMajor.MIUI12,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        ) + legacyPropTargets
    )

    val SURYA_MIUI13 = PlatformProfile(
        id = "surya-miui13",
        deviceCodename = "surya",
        androidApi = 31,
        miui = MiuiMajor.MIUI13,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        ) + modernPropTargets
    )

    val SURYA_MIUI14 = PlatformProfile(
        id = "surya-miui14",
        deviceCodename = "surya",
        androidApi = 31,
        miui = MiuiMajor.MIUI14,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        ) + modernPropTargets
    )

    val MODERN = PlatformProfile(
        id = "modern-a13plus",
        deviceCodename = null,
        androidApi = 33,
        miui = MiuiMajor.NONE,
        targets = listOf(
            PatchTarget(JarKind.FRAMEWORK, FRAMEWORK),
            PatchTarget(JarKind.SERVICES, SERVICES)
        ) + modernPropTargets
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
