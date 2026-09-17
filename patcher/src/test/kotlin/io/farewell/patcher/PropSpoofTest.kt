package io.farewell.patcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PropSpoof] builds the per-partition property maps that make a spoofed identity
 * survive boot. Getting a prefix wrong means the value lands in a file that init
 * ignores, so the mapping is asserted key by key rather than by spot check.
 */
class PropSpoofTest {

    private val identity = SpoofIdentity(
        brand = "google",
        device = "husky",
        model = "Pixel 8 Pro",
        product = "husky_beta",
        manufacturer = "Google",
        fingerprint = "google/husky_beta/husky:CANARY/ZP11.260515.009/15513807:user/release-keys",
        description = "husky_beta-user CANARY ZP11.260515.009 15513807 release-keys",
        tags = "release-keys",
        type = "user",
        id = "ZP11.260515.009",
        release = "CANARY",
        incremental = "15513807",
        securityPatch = "2026-06-05"
    )

    // --- per-partition prefixes ---------------------------------------------

    @Test
    fun everyPartitionGetsItsOwnPrefixedKeys() {
        val expectedPrefix = mapOf(
            PropPartition.SYSTEM to "system",
            PropPartition.PRODUCT to "product",
            PropPartition.SYSTEM_EXT to "system_ext",
            PropPartition.VENDOR to "vendor",
            PropPartition.ODM to "odm"
        )
        for ((partition, prefix) in expectedPrefix) {
            val map = PropSpoof.propMapFor(partition, identity)
            assertEquals(
                "$partition must carry ro.product.$prefix.model",
                "Pixel 8 Pro",
                map["ro.product.$prefix.model"]
            )
            assertEquals(
                "$partition must carry ro.$prefix.build.fingerprint",
                identity.fingerprint,
                map["ro.$prefix.build.fingerprint"]
            )
            assertEquals(
                "$partition must carry the security patch",
                "2026-06-05",
                map["ro.$prefix.build.version.security_patch"]
            )
        }
    }

    @Test
    fun onlySystemCarriesTheUnprefixedKeys() {
        val system = PropSpoof.propMapFor(PropPartition.SYSTEM, identity)

        assertEquals("Pixel 8 Pro", system["ro.product.model"])
        assertEquals("google", system["ro.product.brand"])
        assertEquals("husky", system["ro.product.device"])
        assertEquals("husky_beta", system["ro.product.name"])
        assertEquals("Google", system["ro.product.manufacturer"])
        assertEquals(identity.fingerprint, system["ro.build.fingerprint"])
        assertEquals(identity.description, system["ro.build.description"])
        assertEquals("husky_beta", system["ro.build.product"])

        // The other partitions must not write flat keys: init derives them from the
        // first partition in property_source_order, so a flat key in a lower-priority
        // file would be misleading at best.
        for (partition in listOf(
            PropPartition.PRODUCT,
            PropPartition.SYSTEM_EXT,
            PropPartition.VENDOR,
            PropPartition.ODM
        )) {
            val map = PropSpoof.propMapFor(partition, identity)
            assertFalse("$partition must not write ro.product.model", map.containsKey("ro.product.model"))
            assertFalse("$partition must not write ro.build.fingerprint", map.containsKey("ro.build.fingerprint"))
        }
    }

    @Test
    fun vendorAlsoCarriesTheBootImageFingerprintAndAdbSecure() {
        val vendor = PropSpoof.propMapFor(PropPartition.VENDOR, identity)

        assertEquals(identity.fingerprint, vendor["ro.bootimage.build.fingerprint"])
        assertEquals("1", vendor["ro.adb.secure"])
    }

    @Test
    fun vendorRelaxesPrivappEnforcementInTheOnlyFileThatDefinesIt() {
        // Audited on all three surya ROMs: ro.control_privapp_permissions lives
        // in /vendor/build.prop and nowhere else, and ro.* is write-once, so a
        // value in any other partition would be ignored. `log` keeps a privapp
        // allowlist mismatch from refusing to boot our system-app package.
        val vendor = PropSpoof.propMapFor(PropPartition.VENDOR, identity)

        assertEquals("log", vendor["ro.control_privapp_permissions"])
        for (partition in listOf(PropPartition.SYSTEM, PropPartition.PRODUCT, PropPartition.ODM)) {
            assertNull(
                "$partition must not carry the vendor prop",
                PropSpoof.propMapFor(partition, identity)["ro.control_privapp_permissions"]
            )
        }
    }

    @Test
    fun systemCarriesTheHardeningFlags() {
        val system = PropSpoof.propMapFor(PropPartition.SYSTEM, identity)

        for ((key, value) in PropSpoof.BUILDABLE_STATIC_PROPS) {
            assertEquals("$key must be hardened in build.prop", value, system[key])
        }
    }

    @Test
    fun theOtherPartitionIsIgnored() {
        assertTrue(PropSpoof.propMapFor(PropPartition.OTHER, identity).isEmpty())
    }

    // --- unusable identities -------------------------------------------------

    @Test
    fun anIncompleteIdentityProducesNoMap() {
        val broken = identity.copy(model = "")
        assertFalse(broken.usable)
        for (partition in PropPartition.entries) {
            assertTrue(
                "$partition must be skipped for an unusable identity",
                PropSpoof.propMapFor(partition, broken).isEmpty()
            )
        }
        assertTrue(PropSpoof.buildUnifiedPropMap(broken).isEmpty())
    }

    @Test
    fun usabilityNeedsTheFiveCoreFields() {
        assertTrue(identity.usable)
        assertFalse(identity.copy(brand = "").usable)
        assertFalse(identity.copy(device = "").usable)
        assertFalse(identity.copy(model = "").usable)
        assertFalse(identity.copy(product = "").usable)
        assertFalse(identity.copy(fingerprint = "").usable)
    }

    @Test
    fun emptyOptionalFieldsAreNotEmitted() {
        val sparse = identity.copy(
            description = "",
            id = "",
            release = "",
            incremental = "",
            securityPatch = ""
        )
        val map = PropSpoof.propMapFor(PropPartition.SYSTEM, sparse)

        assertFalse(map.containsKey("ro.build.description"))
        assertFalse(map.containsKey("ro.build.id"))
        assertFalse(map.containsKey("ro.build.version.incremental"))
        assertFalse(map.containsKey("ro.build.version.release"))
        assertFalse(map.containsKey("ro.build.version.security_patch"))
        assertFalse(map.containsKey("ro.build.version.real_security_patch"))
        // The required fields are still there.
        assertEquals("Pixel 8 Pro", map["ro.product.model"])
    }

    /**
     * An unparseable fingerprint still passes [SpoofIdentity.usable] when the PIF
     * supplies brand/device/model/product, so `id`, `release`, `incremental` and
     * `description` all come out empty. Writing those unconditionally would put
     * `ro.build.id=` into build.prop — blanking a stock property, which is worse
     * than not spoofing it, because Play Integrity reads `Build.ID`.
     */
    @Test
    fun anUnparseableFingerprintDoesNotBlankStockBuildKeys() {
        val pif = JSONObject(
            """
            {
              "BRAND": "google",
              "DEVICE": "husky",
              "MODEL": "Pixel 8 Pro",
              "PRODUCT": "husky_beta",
              "FINGERPRINT": "garbage"
            }
            """.trimIndent()
        )
        val resolved = PropSpoof.identityFrom(pif)!!
        assertTrue("the five core fields are present", resolved.usable)

        val system = PropSpoof.propMapFor(PropPartition.SYSTEM, resolved)
        assertFalse("must not blank ro.build.id", system.containsKey("ro.build.id"))
        assertFalse(system.containsKey("ro.build.version.incremental"))
        assertFalse(system.containsKey("ro.build.version.release"))
        assertFalse(system.containsKey("ro.build.description"))
        // The spoof that *is* known still lands.
        assertEquals("Pixel 8 Pro", system["ro.product.model"])
        assertEquals("garbage", system["ro.build.fingerprint"])
    }

    /**
     * PropPatcher writes `key=value` verbatim, so a blank value would erase a stock
     * property. No map may ever contain one.
     */
    @Test
    fun noMapEverContainsAnEmptyValue() {
        val identities = listOf(
            identity,
            identity.copy(description = "", id = "", release = "", incremental = "", securityPatch = "")
        )
        for (candidate in identities) {
            for (partition in PropPartition.entries) {
                val map = PropSpoof.propMapFor(partition, candidate)
                assertTrue(
                    "$partition produced a blank value: ${map.filterValues { it.isEmpty() }}",
                    map.values.none { it.isEmpty() }
                )
            }
            assertTrue(
                "the unified map produced a blank value",
                PropSpoof.buildUnifiedPropMap(candidate).values.none { it.isEmpty() }
            )
        }
    }

    // --- unified map ---------------------------------------------------------

    @Test
    fun theUnifiedMapCoversEveryPartitionPlusTheRuntimeProps() {
        val unified = PropSpoof.buildUnifiedPropMap(identity)

        for (prefix in listOf("system", "product", "system_ext", "vendor", "odm")) {
            assertEquals(
                "the unified map needs $prefix",
                "Pixel 8 Pro",
                unified["ro.product.$prefix.model"]
            )
        }
        for ((key, value) in PropSpoof.STATIC_PROPS) {
            assertEquals("$key must be in the unified map", value, unified[key])
        }
    }

    @Test
    fun theDaemonPropsIncludeTheOnesOnlyItCanApply() {
        // ro.boot.* cannot be written to a property file, so they only exist here.
        assertTrue(PropSpoof.DAEMON_BOOT_PROPS.containsKey("ro.boot.verifiedbootstate"))
        assertTrue(PropSpoof.DAEMON_BOOT_PROPS.containsKey("ro.secureboot.lockstate"))
        assertFalse(
            "a build.prop must never claim to set ro.boot.*",
            PropSpoof.BUILDABLE_STATIC_PROPS.keys.any { it.startsWith("ro.boot.") }
        )
    }

    // --- fingerprint parsing -------------------------------------------------

    @Test
    fun parsesAFingerprint() {
        val info = PropSpoof.parseFingerprint(identity.fingerprint)!!

        assertEquals("google", info.brand)
        assertEquals("husky_beta", info.product)
        assertEquals("husky", info.device)
        assertEquals("CANARY", info.release)
        assertEquals("ZP11.260515.009", info.id)
        assertEquals("15513807", info.incremental)
        assertEquals("user", info.type)
        assertEquals("release-keys", info.tags)
    }

    @Test
    fun detectsTheBuildType() {
        val userdebug = "google/husky_beta/husky:CANARY/ZP1/1:userdebug/test-keys"
        assertEquals("userdebug", PropSpoof.parseFingerprint(userdebug)!!.type)

        val eng = "google/husky_beta/husky:CANARY/ZP1/1:eng/test-keys"
        assertEquals("eng", PropSpoof.parseFingerprint(eng)!!.type)
    }

    @Test
    fun rejectsMalformedFingerprints() {
        assertNull(PropSpoof.parseFingerprint(""))
        assertNull(PropSpoof.parseFingerprint("not-a-fingerprint"))
        assertNull(PropSpoof.parseFingerprint("a/b:c"))
    }

    // --- identity from a PIF profile ----------------------------------------

    @Test
    fun buildsAnIdentityFromAPifProfile() {
        val pif = JSONObject(
            """
            {
              "MANUFACTURER": "Google",
              "MODEL": "Pixel 8 Pro",
              "FINGERPRINT": "google/husky_beta/husky:CANARY/ZP11.260515.009/15513807:user/release-keys",
              "PRODUCT": "husky_beta",
              "DEVICE": "husky",
              "SECURITY_PATCH": "2026-06-05"
            }
            """.trimIndent()
        )

        val resolved = PropSpoof.identityFrom(pif)!!
        assertEquals("Pixel 8 Pro", resolved.model)
        assertEquals("Google", resolved.manufacturer)
        assertEquals("husky_beta", resolved.product)
        assertEquals("husky", resolved.device)
        assertEquals("2026-06-05", resolved.securityPatch)
        assertEquals("CANARY", resolved.release)
        assertEquals("ZP11.260515.009", resolved.id)
        assertEquals("15513807", resolved.incremental)
        assertTrue(resolved.usable)
        assertTrue(resolved.description.contains("husky_beta-user"))
    }

    @Test
    fun fallsBackToTheFingerprintWhenFieldsAreMissing() {
        val pif = JSONObject(
            """{"FINGERPRINT":"google/husky_beta/husky:CANARY/ZP11/15513807:user/release-keys"}"""
        )

        val resolved = PropSpoof.identityFrom(pif)!!
        assertEquals("google", resolved.brand)
        assertEquals("husky_beta", resolved.product)
        assertEquals("husky", resolved.device)
        // manufacturer falls back to brand
        assertEquals("google", resolved.manufacturer)
        // MODEL has no fingerprint equivalent, so it stays empty and blocks usability
        assertEquals("", resolved.model)
        assertFalse(resolved.usable)
    }

    @Test
    fun theBundledProfileProducesAUsableIdentity() {
        // The shape shipped in Toolbox-data/Pif-props.json.
        val pif = JSONObject(
            """
            {
              "MANUFACTURER": "Google",
              "MODEL": "Pixel 8 Pro",
              "FINGERPRINT": "google/husky_beta/husky:CANARY/ZP11.260515.009/15513807:user/release-keys",
              "PRODUCT": "husky_beta",
              "DEVICE": "husky",
              "SECURITY_PATCH": "2026-06-05",
              "DEVICE_INITIAL_SDK_INT": "32"
            }
            """.trimIndent()
        )

        val resolved = PropSpoof.identityFrom(pif)!!
        assertTrue(resolved.usable)

        // And it reaches every partition that participates in property_source_order.
        for (partition in listOf(
            PropPartition.SYSTEM,
            PropPartition.PRODUCT,
            PropPartition.SYSTEM_EXT,
            PropPartition.VENDOR,
            PropPartition.ODM
        )) {
            assertEquals(
                "$partition must spoof the model",
                "Pixel 8 Pro",
                PropSpoof.propMapFor(partition, resolved)["ro.product." +
                    when (partition) {
                        PropPartition.SYSTEM -> "system"
                        PropPartition.PRODUCT -> "product"
                        PropPartition.SYSTEM_EXT -> "system_ext"
                        PropPartition.VENDOR -> "vendor"
                        else -> "odm"
                    } + ".model"]
            )
        }
    }
}
