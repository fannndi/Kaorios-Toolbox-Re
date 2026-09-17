package io.farewell.patcher

import org.json.JSONObject

/**
 * The spoofed device identity, resolved from a PIF profile.
 *
 * Surya MIUI ROMs do not define the plain `ro.product.*` keys in any build.prop.
 * init derives them at boot from `ro.product.<partition>.<field>` following
 * `ro.product.property_source_order` (audited: `odm,vendor,product,product_services,
 * system` on MIUI 12 and `odm,vendor,product,system_ext,system` on MIUI 13/14).
 * Because `odm` comes first, the effective `ro.product.model` on surya comes from
 * `/vendor/odm/etc/build.prop`, not from `/system/build.prop`.
 *
 * Setting only the plain keys is not enough: any reader that asks for
 * `ro.product.odm.model` (DroidGuard does) still sees the real POCO X3. So the same
 * values are written to every partition variant — the "prop unification" step.
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

/** The parts of a build fingerprint, e.g. `brand/product/device:release/id/incremental:type/tags`. */
data class FingerprintInfo(
    val brand: String,
    val product: String,
    val device: String,
    val release: String,
    val id: String,
    val incremental: String,
    val type: String,
    val tags: String
)

/**
 * Builds the per-partition property maps that make a spoofed identity survive boot.
 *
 * Pure JVM code with no Android dependency, so it lives beside the rest of the
 * app↔hook protocol, is covered by `PropSpoofTest`, and can be driven from the
 * patcher CLI (`--dump-prop-maps`) to check a ROM without a device.
 */
object PropSpoof {

    /**
     * Bootloader/hardening properties for the runtime layer (daemon + hook).
     * `ro.boot.*` cannot be written to a property file at all, so these are only
     * useful through the hook and the native daemon.
     */
    val STATIC_PROPS: Map<String, String> = linkedMapOf(
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

    /** The subset of [STATIC_PROPS] that is meaningful inside a build.prop. */
    val BUILDABLE_STATIC_PROPS: Map<String, String> = linkedMapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "ro.build.selinux" to "1"
    )

    /** Extra properties only the native daemon can apply. */
    val DAEMON_BOOT_PROPS: Map<String, String> = linkedMapOf(
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
     * Property map for one partition's build.prop. Keys carry the partition prefix
     * that belongs in that file, so `/vendor/odm/etc/build.prop` only ever receives
     * `ro.product.odm.*` / `ro.odm.build.*`.
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
            // explicitly, otherwise init's property_source_order derivation wins.
            PropPartition.SYSTEM -> {
                for ((field, value) in identity.productValues()) {
                    if (value.isNotEmpty()) {
                        map["ro.product.$field"] = value
                    }
                }
                map["ro.build.product"] = identity.product
                // The keys below come from parsing the fingerprint, which can fail
                // while `usable` still holds (brand/device/model/product/fingerprint
                // are all present but the fingerprint has no parseable structure).
                // Writing them unconditionally would put `ro.build.id=` and friends
                // into build.prop — blanking a stock property is worse than leaving
                // it alone, so they are only written when there is a value.
                if (identity.description.isNotEmpty()) map["ro.build.description"] = identity.description
                if (identity.tags.isNotEmpty()) map["ro.build.tags"] = identity.tags
                if (identity.type.isNotEmpty()) map["ro.build.type"] = identity.type
                if (identity.id.isNotEmpty()) map["ro.build.id"] = identity.id
                if (identity.incremental.isNotEmpty()) {
                    map["ro.build.version.incremental"] = identity.incremental
                }
                if (identity.release.isNotEmpty()) {
                    map["ro.build.version.release"] = identity.release
                }
                map["ro.build.fingerprint"] = identity.fingerprint
                if (identity.securityPatch.isNotEmpty()) {
                    map["ro.build.version.security_patch"] = identity.securityPatch
                    map["ro.build.version.real_security_patch"] = identity.securityPatch
                }
                // Hardening flags.
                // Audited: MIUI 13/14 declare ro.secure / ro.debuggable / ro.adb.secure
                // directly in /system/build.prop, so they can be rewritten here. MIUI 12
                // does NOT: there is no /system/default.prop on that ROM and the flags
                // come from the ramdisk default.prop inside boot.img. Because ro.*
                // properties are write-once, appending them to build.prop would be
                // ignored — only the boot-classpath hook can answer for them there.
                // They are still emitted so the same map drives the daemon and the hook.
                map.putAll(BUILDABLE_STATIC_PROPS)
            }
            // /vendor/build.prop carries the boot image fingerprint; ro.adb.secure
            // actually lives in /vendor/default.prop, which is also VENDOR.
            PropPartition.VENDOR -> {
                map["ro.bootimage.build.fingerprint"] = identity.fingerprint
                map["ro.adb.secure"] = "1"
                // The privileged-app safety net. MIUI declares
                // ro.control_privapp_permissions=enforce in /vendor/build.prop
                // (audited: all three surya ROMs, no other file), and ro.* is
                // write-once — so this file is the only place the relaxation can
                // go, and replacing the value in place is what the patcher does.
                // `log` keeps a privapp allowlist mismatch from refusing to boot
                // the package (ours is correct, but a future MIUI update could
                // add a permission we do not list yet); the platform still grants
                // every permission the allowlist names.
                map["ro.control_privapp_permissions"] = "log"
            }
            else -> Unit
        }
        return map
    }

    /** Union of every partition's map — used for the runtime daemon and the overlay export. */
    fun buildUnifiedPropMap(identity: SpoofIdentity): Map<String, String> {
        if (!identity.usable) return emptyMap()
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
        for ((key, value) in STATIC_PROPS) {
            map[key] = value
        }
        return map
    }

    /** Resolve an identity from a PIF JSON object, falling back to the fingerprint. */
    fun identityFrom(pif: JSONObject): SpoofIdentity? {
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

    /** `brand/product/device:release/id/incremental:type/tags`. */
    fun parseFingerprint(fingerprint: String): FingerprintInfo? {
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
}
