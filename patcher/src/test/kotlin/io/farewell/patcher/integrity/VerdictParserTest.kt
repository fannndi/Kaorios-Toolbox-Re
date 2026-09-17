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
}
