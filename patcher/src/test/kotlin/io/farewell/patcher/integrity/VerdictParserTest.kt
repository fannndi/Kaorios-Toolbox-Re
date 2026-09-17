package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VerdictParser] turns a decrypted `decodeIntegrityToken` response into the
 * "did my spoof pass?" answer. The fixtures use the real response shape from
 * the Play Integrity API v1 discovery surface.
 */
class VerdictParserTest {

    private fun strongJson() = """
        {
          "requestDetails": { "requestPackageName": "com.example.game" },
          "appIntegrity": {
            "appRecognitionVerdict": "PLAY_RECOGNIZED",
            "packageName": "com.example.game",
            "certificateSha256Digest": ["aabbcc"],
            "versionCode": "42"
          },
          "deviceIntegrity": {
            "deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"]
          },
          "accountDetails": { "appLicensingVerdict": "LICENSED" }
        }
    """.trimIndent()

    @Test
    fun strongVerdictParsesWithAllLevelsTrue() {
        val verdict = VerdictParser.parse(strongJson())!!
        assertTrue(verdict.meetsStrong)
        assertTrue(verdict.meetsDevice)
        assertTrue(verdict.meetsBasic)
        assertEquals("PLAY_RECOGNIZED", verdict.appVerdict)
        assertEquals("LICENSED", verdict.licensingVerdict)
        assertEquals("com.example.game", verdict.packageName)
    }

    @Test
    fun strongImpliesDeviceAndBasic() {
        // Google documents the verdicts as cumulative: STRONG implies the lower two.
        val verdict = VerdictParser.parse("""{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY"]}}""")!!
        assertTrue("STRONG implies DEVICE", verdict.meetsDevice)
        assertTrue("STRONG implies BASIC", verdict.meetsBasic)
    }

    @Test
    fun deviceWithoutStrongIsDistinguishedFromBasicOnly() {
        val verdict = VerdictParser.parse(
            """{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"]}}"""
        )!!
        assertFalse(verdict.meetsStrong)
        assertTrue(verdict.meetsDevice)
        assertTrue("DEVICE implies BASIC", verdict.meetsBasic)
    }

    @Test
    fun emptyVerdictListMeansUnevaluatedNotBasic() {
        val verdict = VerdictParser.parse("""{"deviceIntegrity": {"deviceRecognitionVerdict": []}}""")!!
        assertFalse(verdict.meetsStrong)
        assertFalse(verdict.meetsDevice)
        assertFalse("empty list must not read as BASIC", verdict.meetsBasic)
        assertTrue(VerdictParser.summarize(verdict).any { it.contains("No device verdict") })
    }

    @Test
    fun missingSectionsStillParse() {
        val verdict = VerdictParser.parse("""{"deviceIntegrity": {}}""")!!
        assertTrue(verdict.deviceVerdicts.isEmpty())
        assertNull(verdict.appVerdict)
        assertNull(verdict.licensingVerdict)
        assertNull(verdict.packageName)
    }

    @Test
    fun malformedInputYieldsNullInsteadOfThrowing() {
        assertNull(VerdictParser.parse(""))
        assertNull(VerdictParser.parse("   "))
        assertNull(VerdictParser.parse("not json at all"))
        assertNull(VerdictParser.parse("[1, 2, 3]"))
    }

    @Test
    fun summaryExplainsEachFailureMode() {
        val strong = VerdictParser.summarize(VerdictParser.parse(strongJson())!!).joinToString("\n")
        assertTrue(strong.contains("STRONG=true"))
        assertTrue(strong.contains("PLAY_RECOGNIZED"))
        assertTrue(strong.contains("LICENSED"))

        val basicOnly = VerdictParser.summarize(
            VerdictParser.parse("""{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY"]}}""")!!
        ).joinToString("\n")
        assertTrue(basicOnly.contains("STRONG=false"))
        assertTrue("BASIC-only must point at the keybox", basicOnly.contains("keybox"))

        val unlicensed = VerdictParser.summarize(
            VerdictParser.parse(
                """{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY"]},
                    "accountDetails": {"appLicensingVerdict": "UNLICENSED"}}"""
            )!!
        ).joinToString("\n")
        assertTrue(unlicensed.contains("UNLICENSED"))
    }

    @Test
    fun unrecognizedBuildIsCalledOut() {
        val summary = VerdictParser.summarize(
            VerdictParser.parse(
                """{"appIntegrity": {"appRecognitionVerdict": "UNRECOGNIZED_VERSION", "packageName": "com.example.game"}}"""
            )!!
        ).joinToString("\n")
        assertTrue(summary.contains("UNRECOGNIZED_VERSION"))
    }

    @Test
    fun environmentSignalsParseFromTheOfficialShape() {
        // Newer signals from the discovery document: Play Protect, app access
        // risk, location spoofing risk, SDK + activity level, testing flag.
        val verdict = VerdictParser.parse(
            """{
              "deviceIntegrity": {
                "deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY"],
                "deviceAttributes": { "sdkVersion": 31 },
                "recentDeviceActivity": { "deviceActivityLevel": "LEVEL_2" }
              },
              "environmentDetails": {
                "playProtectVerdict": "NO_ISSUES",
                "appAccessRiskVerdict": { "appsDetected": ["KNOWN_INSTALLED", "KNOWN_CAPTURING"] },
                "locationSpoofingRiskVerdict": ["LOW_RISK_DEVICE", "HIGH_RISK_NETWORK"]
              },
              "testingDetails": { "isTestingResponse": false }
            }"""
        )!!
        assertEquals(31, verdict.sdkVersion)
        assertEquals("LEVEL_2", verdict.activityLevel)
        assertEquals("NO_ISSUES", verdict.playProtectVerdict)
        assertFalse(verdict.isTestingResponse)
        val summary = VerdictParser.summarize(verdict).joinToString("\n")
        assertTrue(summary.contains("Token SDK: 31"))
        assertTrue("capturing apps must be called out", summary.contains("KNOWN_CAPTURING"))
        assertTrue("high-risk spoofing signal must be called out", summary.contains("HIGH_RISK_NETWORK"))
        assertFalse("low-risk entries stay quiet", summary.contains("LOW_RISK_DEVICE"))
    }

    @Test
    fun testingResponseIsFlaggedAsMeaningless() {
        val summary = VerdictParser.summarize(
            VerdictParser.parse(
                """{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_STRONG_INTEGRITY"]},
                    "testingDetails": {"isTestingResponse": true}}"""
            )!!
        ).joinToString("\n")
        assertTrue(summary.contains("TESTING response"))
    }

    @Test
    fun virtualAndUnknownVerdictsAreNamed() {
        val virtual = VerdictParser.summarize(
            VerdictParser.parse("""{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_VIRTUAL_INTEGRITY"]}}""")!!
        ).joinToString("\n")
        assertTrue(virtual.contains("emulator"))
        assertFalse("emulator verdict is not STRONG", VerdictParser.parse(
            """{"deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_VIRTUAL_INTEGRITY"]}}"""
        )!!.meetsStrong)

        val unknown = VerdictParser.summarize(
            VerdictParser.parse("""{"deviceIntegrity": {"deviceRecognitionVerdict": ["UNKNOWN"]}}""")!!
        ).joinToString("\n")
        assertTrue(unknown.contains("UNKNOWN"))
    }
}
