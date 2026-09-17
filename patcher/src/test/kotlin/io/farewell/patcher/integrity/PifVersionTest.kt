package io.farewell.patcher.integrity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PifVersion] guards a real failure mode seen on a device: the configured data
 * source served a `Pif-props.json` a year older than the bundled one, and a
 * plain sync would have applied it. The gate is one comparison, so the tests
 * pin exactly when it blocks.
 */
class PifVersionTest {

    private fun pif(patch: String, model: String = "Pixel 8 Pro") = """
        {
          "MANUFACTURER": "Google",
          "MODEL": "$model",
          "FINGERPRINT": "google/husky_beta/husky:CANARY/ZP11.260515.009/15513807:user/release-keys",
          "PRODUCT": "husky_beta",
          "DEVICE": "husky",
          "SECURITY_PATCH": "$patch",
          "DEVICE_INITIAL_SDK_INT": "32"
        }
    """.trimIndent()

    @Test
    fun anOlderPatchIsStale() {
        assertTrue(PifVersion.isCandidateStale(pif("2026-06-05"), pif("2025-07-05")))
    }

    @Test
    fun equalOrNewerIsAccepted() {
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), pif("2026-06-05")))
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), pif("2026-07-05")))
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), pif("2027-01-05")))
    }

    @Test
    fun unreadableInputsNeverBlockTheSync() {
        // Neither side is parseable, or one of them is missing entirely: the
        // guard must let the candidate through rather than break first-run and
        // offline flows.
        assertFalse(PifVersion.isCandidateStale(null, pif("2025-01-05")))
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), null))
        assertFalse(PifVersion.isCandidateStale("not json", pif("2025-01-05")))
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), "not json"))
        assertFalse(PifVersion.isCandidateStale(pif("2026-06-05"), """{"MODEL": "Pixel 6"}"""))
        assertFalse(PifVersion.isCandidateStale("", ""))
    }

    @Test
    fun patchIsReadAsADateOnly() {
        assertNull(PifVersion.securityPatch(null))
        assertNull(PifVersion.securityPatch(""))
        assertNull(PifVersion.securityPatch("not json"))
        assertNull(PifVersion.securityPatch("""{"MODEL": "Pixel"}"""))
        assertNull(PifVersion.securityPatch("""{"SECURITY_PATCH": "short"}"""))
        assertTrue(PifVersion.securityPatch(pif("2026-06-05")) == "2026-06-05")
        // A longer value (e.g. a timestamp) still compares on its date prefix.
        assertTrue(PifVersion.securityPatch("""{"SECURITY_PATCH": "2026-06-05T00:00:00Z"}""") == "2026-06-05")
    }
}
