package io.farewell.patcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the parts of the profile layer that the surya audit corrected: the
 * per-SKU property targets, the per-partition split, and the surya-only scope
 * (no Android 13+ profile, no unreachable targets).
 */
class PlatformProfileTest {

    // --- scope ---------------------------------------------------------------

    @Test
    fun onlySuryaProfilesExist() {
        assertEquals(3, PlatformProfiles.ALL.size)
        assertEquals(
            listOf("surya-miui12", "surya-miui13", "surya-miui14"),
            PlatformProfiles.ALL.map { it.id }
        )
        assertTrue(
            "every profile must target surya",
            PlatformProfiles.ALL.all { it.deviceCodename == "surya" }
        )
    }

    @Test
    fun noProfileTargetsTheNewerApiLevels() {
        // No surya ROM is A13+, so nothing should claim API 33+.
        assertTrue(
            "no profile may require API 33+",
            PlatformProfiles.ALL.all { it.androidApi <= 31 }
        )
    }

    @Test
    fun byIdIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(PlatformProfiles.SURYA_MIUI13, PlatformProfiles.byId("SURYA-MIUI13"))
        assertEquals(PlatformProfiles.SURYA_MIUI12, PlatformProfiles.byId("surya-miui12"))

        val failure = runCatching { PlatformProfiles.byId("modern-a13plus") }.exceptionOrNull()
        assertTrue("the removed A13+ profile must not resolve", failure is IllegalStateException)
    }

    @Test
    fun resolvePicksTheProfileFromMiuiVersion() {
        assertEquals(MiuiMajor.MIUI12, PlatformProfiles.resolve("surya", 29, MiuiMajor.MIUI12).miui)
        assertEquals(MiuiMajor.MIUI13, PlatformProfiles.resolve("surya", 31, MiuiMajor.MIUI13).miui)
        assertEquals(MiuiMajor.MIUI14, PlatformProfiles.resolve("surya", 31, MiuiMajor.MIUI14).miui)
    }

    @Test
    fun resolveFallsBackByApiLevelWhenMiuiIsUnknown() {
        assertEquals(PlatformProfiles.SURYA_MIUI12, PlatformProfiles.resolve(null, 29, MiuiMajor.NONE))
        assertEquals(PlatformProfiles.SURYA_MIUI14, PlatformProfiles.resolve(null, 31, MiuiMajor.NONE))
        assertEquals(PlatformProfiles.SURYA_MIUI14, PlatformProfiles.resolve(null, 33, MiuiMajor.NONE))
    }

    @Test
    fun supportsDeviceOnlySurya() {
        assertTrue(PlatformProfiles.supportsDevice("surya"))
        assertTrue(PlatformProfiles.supportsDevice("SURYA"))
        assertFalse(PlatformProfiles.supportsDevice("karna"))
        assertFalse(PlatformProfiles.supportsDevice(null))
    }

    // --- per-SKU property targets -------------------------------------------

    @Test
    fun skuTargetsCoverBothFilesForTheDetectedSkuFirst() {
        val targets = PlatformProfiles.skuPropTargets("surya")

        assertEquals(4, targets.size)
        assertEquals("vendor/build_surya.prop", targets[0].systemPath)
        assertEquals("vendor/odm/etc/build_surya.prop", targets[1].systemPath)
        assertTrue(targets.any { it.systemPath == "vendor/build_karna.prop" })
        assertTrue(targets.any { it.systemPath == "vendor/odm/etc/build_karna.prop" })
    }

    @Test
    fun skuTargetsPutTheDetectedSkuAheadOfTheKnownOnes() {
        val targets = PlatformProfiles.skuPropTargets("karna")
        assertEquals("vendor/build_karna.prop", targets.first().systemPath)
    }

    @Test
    fun skuTargetsNeverDuplicateAPath() {
        val paths = PlatformProfiles.skuPropTargets("surya").map { it.systemPath }
        assertEquals(paths.size, paths.distinct().size)
    }

    @Test
    fun skuTargetsAreOptionalAndArePropertyTargets() {
        for (target in PlatformProfiles.skuPropTargets("surya")) {
            assertEquals(JarKind.PROPS, target.kind)
            assertFalse("a missing SKU file must not fail the build", target.required)
        }
    }

    @Test
    fun skuTargetsMapToTheRightPartition() {
        val byPath = PlatformProfiles.skuPropTargets("surya").associateBy { it.systemPath }

        assertEquals(PropPartition.VENDOR, byPath.getValue("vendor/build_surya.prop").propPartition)
        assertEquals(
            PropPartition.ODM,
            byPath.getValue("vendor/odm/etc/build_surya.prop").propPartition
        )
    }

    @Test
    fun skuTargetsWorkWithoutADetectedSku() {
        val targets = PlatformProfiles.skuPropTargets(null)
        assertEquals(4, targets.size)
        assertEquals("vendor/build_surya.prop", targets.first().systemPath)
    }

    // --- partition + zip path derivation ------------------------------------

    @Test
    fun propPartitionIsDerivedFromTheSystemPath() {
        fun partition(path: String) = PatchTarget(JarKind.PROPS, path).propPartition

        assertEquals(PropPartition.SYSTEM, partition("system/build.prop"))
        assertEquals(PropPartition.SYSTEM, partition("system/default.prop"))
        assertEquals(PropPartition.PRODUCT, partition("product/build.prop"))
        assertEquals(PropPartition.PRODUCT, partition("product/etc/build.prop"))
        assertEquals(PropPartition.SYSTEM_EXT, partition("system_ext/etc/build.prop"))
        assertEquals(PropPartition.VENDOR, partition("vendor/build.prop"))
        assertEquals(PropPartition.VENDOR, partition("vendor/default.prop"))
        assertEquals(PropPartition.ODM, partition("vendor/odm/etc/build.prop"))
    }

    @Test
    fun systemFilesGoUnderSystemRootInTheZip() {
        assertEquals(
            "system_root/system/framework/framework.jar",
            PatchTarget(JarKind.FRAMEWORK, "system/framework/framework.jar").zipPath
        )
    }

    @Test
    fun otherPartitionsKeepTheirNativeZipPath() {
        assertEquals(
            "vendor/odm/etc/build.prop",
            PatchTarget(JarKind.PROPS, "vendor/odm/etc/build.prop").zipPath
        )
        assertEquals(
            "product/etc/build.prop",
            PatchTarget(JarKind.PROPS, "product/etc/build.prop").zipPath
        )
    }

    // --- per-ROM property layout --------------------------------------------

    private fun propPaths(profile: PlatformProfile) =
        profile.targets.filter { it.kind == JarKind.PROPS }.map { it.systemPath }

    @Test
    fun miui12UsesTheLegacyProductLayoutAndHasNoSystemExt() {
        val paths = propPaths(PlatformProfiles.SURYA_MIUI12)

        assertTrue(paths.contains("product/build.prop"))
        assertFalse("MIUI 12 has no system_ext partition", paths.any { it.startsWith("system_ext/") })
        assertTrue(paths.contains("vendor/odm/etc/build.prop"))
    }

    @Test
    fun miui13And14UseTheModernProductLayout() {
        for (profile in listOf(PlatformProfiles.SURYA_MIUI13, PlatformProfiles.SURYA_MIUI14)) {
            val paths = propPaths(profile)
            assertTrue(paths.contains("product/etc/build.prop"))
            assertTrue(paths.contains("system_ext/etc/build.prop"))
            assertFalse(paths.contains("product/build.prop"))
        }
    }

    @Test
    fun noProfileTargetsADeadPropertyFile() {
        // system/default.prop does not exist on any surya ROM; it stays as an
        // optional target for older layouts, so it must never be required.
        for (profile in PlatformProfiles.ALL) {
            val target = profile.targets.firstOrNull { it.systemPath == "system/default.prop" }
            if (target != null) {
                assertFalse("system/default.prop is absent on surya", target.required)
            }
        }
    }

    // --- invariants from the README -----------------------------------------

    @Test
    fun noProfileTouchesTheForbiddenTargets() {
        val forbidden = listOf(
            "SettingsProvider.apk",
            "miui-framework.jar",
            "miui-services.jar",
            "miuix.jar"
        )
        for (profile in PlatformProfiles.ALL) {
            for (target in profile.targets) {
                for (name in forbidden) {
                    assertFalse(
                        "${profile.id} must not patch $name",
                        target.systemPath.contains(name)
                    )
                }
            }
        }
    }

    @Test
    fun everyProfilePatchesBothCoreJars() {
        for (profile in PlatformProfiles.ALL) {
            val kinds = profile.targets.map { it.kind }.toSet()
            assertTrue("${profile.id} needs framework.jar", JarKind.FRAMEWORK in kinds)
            assertTrue("${profile.id} needs services.jar", JarKind.SERVICES in kinds)
        }
    }

    @Test
    fun theCoreJarsAreRequiredButPropertyFilesAreNot() {
        for (profile in PlatformProfiles.ALL) {
            for (target in profile.targets) {
                if (target.kind == JarKind.FRAMEWORK || target.kind == JarKind.SERVICES) {
                    assertTrue("${target.systemPath} must be required", target.required)
                } else {
                    assertFalse("${target.systemPath} must stay optional", target.required)
                }
            }
        }
    }

    @Test
    fun labelDescribesTheMiuiVersion() {
        assertNotEquals(
            PlatformProfiles.SURYA_MIUI12.label,
            PlatformProfiles.SURYA_MIUI14.label
        )
        assertTrue(PlatformProfiles.SURYA_MIUI13.label.contains("MIUI 13"))
    }
}
