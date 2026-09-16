package io.farewell.patcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app is the only writer of the per-app spoof sections, and the hook
 * (`HookConfig.java`) is the only reader. These tests pin the serialised shape to
 * the exact paths the hook walks, so a rename on either side fails here instead of
 * silently doing nothing on a device.
 *
 * The same shape is also enforced from the other direction by
 * `tools/config-inspect/inspect_config.py`, which re-implements the hook's readers.
 */
class SpoofRulesTest {

    private val sample = SpoofRules(
        installer = mapOf("com.example.app" to "com.android.vending"),
        settings = mapOf(
            "com.example.app" to mapOf(
                "secure" to mapOf("android_id" to "0123456789abcdef"),
                "global" to mapOf("example_key" to "1")
            )
        ),
        remove = mapOf("secure" to listOf("example_key")),
        features = mapOf("android.hardware.keystore" to true)
    )

    // --- the contract with HookConfig ---------------------------------------

    @Test
    fun installerIsAFlatPackageMap() {
        val installer = sample.toJson().getJSONObject("installer")
        // HookConfig.installerOverride: installer.optString(packageName, "")
        assertEquals("com.android.vending", installer.getString("com.example.app"))
    }

    @Test
    fun settingsAreNestedUnderSettingsDotApps() {
        val json = sample.toJson()
        // HookConfig.settingValue: root.settings.apps[pkg][namespace][name]
        val value = json
            .getJSONObject("settings")
            .getJSONObject("apps")
            .getJSONObject("com.example.app")
            .getJSONObject("secure")
            .getString("android_id")
        assertEquals("0123456789abcdef", value)
    }

    @Test
    fun removeIsAnArrayPerNamespace() {
        val json = sample.toJson()
        // HookConfig.shouldRemove -> namespaceSet: root.remove.getJSONArray(namespace)
        val names = json.getJSONObject("remove").getJSONArray("secure")
        assertEquals(1, names.length())
        assertEquals("example_key", names.getString(0))
    }

    @Test
    fun featuresIsAFlatBooleanMap() {
        val json = sample.toJson()
        // HookConfig.featureState: features.has(feature) / features.optBoolean(feature)
        assertTrue(json.getJSONObject("features").getBoolean("android.hardware.keystore"))
    }

    @Test
    fun emptyRulesEmitNothingSoExistingConfigIsUnchanged() {
        val json = SpoofRules().toJson()
        assertEquals(0, json.length())
        assertFalse(json.has("installer"))
        assertFalse(json.has("settings"))
        assertFalse(json.has("remove"))
        assertFalse(json.has("features"))
    }

    @Test
    fun applyToLeavesOtherSectionsAlone() {
        val config = JSONObject()
            .put("flags", JSONObject().put("hide_dev_status", true))
            .put("props", JSONObject().put("*", JSONObject().put("ro.product.model", "Pixel 8 Pro")))

        sample.applyTo(config)

        // Untouched sections survive.
        assertTrue(config.getJSONObject("flags").getBoolean("hide_dev_status"))
        assertEquals(
            "Pixel 8 Pro",
            config.getJSONObject("props").getJSONObject("*").getString("ro.product.model")
        )
        // And the rules landed.
        assertEquals(
            "com.android.vending",
            config.getJSONObject("installer").getString("com.example.app")
        )
    }

    @Test
    fun applyToWithNoRulesDoesNotAddEmptySections() {
        val config = JSONObject().put("flags", JSONObject())
        SpoofRules().applyTo(config)

        assertEquals(1, config.length())
        assertFalse(config.has("installer"))
    }

    // --- validation ----------------------------------------------------------

    @Test
    fun invalidPackageNamesAreDropped() {
        val rules = SpoofRules(
            installer = mapOf(
                "com.example.app" to "com.android.vending",
                "notapackage" to "com.android.vending",
                "1com.bad" to "com.android.vending",
                "" to "com.android.vending"
            )
        )
        val installer = rules.toJson().getJSONObject("installer")

        assertEquals(1, installer.length())
        assertTrue(installer.has("com.example.app"))
    }

    @Test
    fun invalidNamespacesAreDropped() {
        val rules = SpoofRules(
            settings = mapOf("com.example.app" to mapOf("bogus_table" to mapOf("k" to "v"))),
            remove = mapOf("bogus_table" to listOf("k"))
        )
        val json = rules.toJson()

        assertFalse("unknown tables must not reach the hook", json.has("settings"))
        assertFalse(json.has("remove"))
    }

    @Test
    fun onlyTheThreeRealNamespacesAreAccepted() {
        assertEquals(listOf("global", "secure", "system"), SpoofRules.NAMESPACES)
    }

    @Test
    fun blankValuesAreDropped() {
        val rules = SpoofRules(
            installer = mapOf("com.example.app" to "  "),
            features = mapOf("  " to true)
        )
        val json = rules.toJson()

        assertFalse(json.has("installer"))
        assertFalse(json.has("features"))
    }

    @Test
    fun packageNameValidation() {
        assertTrue(SpoofRules.isPackageName("com.example.app"))
        assertTrue(SpoofRules.isPackageName("com.android.vending"))
        assertTrue(SpoofRules.isPackageName("a.b_c.d"))
        assertFalse(SpoofRules.isPackageName("single"))
        assertFalse(SpoofRules.isPackageName(".leading"))
        assertFalse(SpoofRules.isPackageName("trailing."))
        assertFalse(SpoofRules.isPackageName("com..empty"))
        assertFalse(SpoofRules.isPackageName("com.9digit"))
        assertFalse(SpoofRules.isPackageName(""))
    }

    // --- round trip and counting --------------------------------------------

    @Test
    fun roundTripsThroughJson() {
        assertEquals(sample, SpoofRules.fromJson(sample.toJson()))
    }

    @Test
    fun roundTripSurvivesASecondPass() {
        val once = SpoofRules.fromJson(sample.toJson())
        val twice = SpoofRules.fromJson(once.toJson())
        assertEquals(once, twice)
    }

    @Test
    fun fromJsonToleratesMissingSections() {
        assertEquals(SpoofRules(), SpoofRules.fromJson(JSONObject()))
        assertEquals(SpoofRules(), SpoofRules.fromJson(JSONObject("""{"flags":{"x":true}}""")))
    }

    @Test
    fun ruleCountAddsUpEverySection() {
        // 1 installer + 2 settings + 1 removal + 1 feature
        assertEquals(5, sample.ruleCount)
        assertEquals(0, SpoofRules().ruleCount)
    }

    @Test
    fun isEmptyReflectsWhetherAnyRuleExists() {
        assertTrue(SpoofRules().isEmpty)
        assertFalse(sample.isEmpty)
        assertFalse(
            SpoofRules(installer = mapOf("com.example.app" to "com.android.vending")).isEmpty
        )
    }
}
