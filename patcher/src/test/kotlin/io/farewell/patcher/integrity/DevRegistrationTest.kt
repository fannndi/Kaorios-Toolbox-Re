package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DevRegistration] answers "will this app still install once developer
 * verification reaches my country?" — Google's own endpoint, keyed with an API
 * key, no OAuth. The response shape and both error codes were confirmed against
 * the live service (403 without a key, 400 API_KEY_INVALID with a bad one), so
 * the states and the URL contract are pinned here.
 */
class DevRegistrationTest {

    private class FakeFetcher(private val code: Int, private val body: String) : DevRegistration.Fetcher {
        var lastUrl: String? = null
        var lastKey: String? = null
        override fun get(url: String, apiKey: String?): Pair<Int, String> {
            lastUrl = url
            lastKey = apiKey
            return code to body
        }
    }

    private fun state(state: String) =
        """{"name": "packages/com.example.app/packageRegistrationStatus", "state": "$state"}"""

    @Test
    fun theUrlFormatsThePackageTheWayGoogleDocuments() {
        val url = DevRegistration.urlFor("com.example.app")
        assertEquals(
            "https://androiddeveloperidstatus.googleapis.com/v1/packages/com-example-app/packageRegistrationStatus:check",
            url
        )
        val withCert = DevRegistration.urlFor("com.example.app", "D6:AC:89:ED")
        assertTrue("fingerprint is lowercased, colons stripped", withCert.endsWith("?certificateFingerprint=d6ac89ed"))
        assertTrue("dots become hyphens in the resource", withCert.contains("/com-example-app/"))
    }

    @Test
    fun allThreeDocumentedStatesAreRecognised() {
        val fetcher = FakeFetcher(200, state("REGISTERED"))
        val registered = DevRegistration.check("com.example.app", null, "key", fetcher)
        assertTrue(registered.registered)
        assertEquals("key", fetcher.lastKey)

        val other = DevRegistration.check("com.example.app", "aa", "key", FakeFetcher(200, state(DevRegistration.OTHER_FINGERPRINT)))
        assertEquals(DevRegistration.OTHER_FINGERPRINT, other.state)
        assertFalse(other.registered)
        assertTrue(other.summary().contains("DIFFERENT"))

        val none = DevRegistration.check("com.example.app", null, "key", FakeFetcher(200, state("NOT_REGISTERED")))
        assertEquals(DevRegistration.NOT_REGISTERED, none.state)
        assertTrue("must explain why that is fine here", none.summary().contains("ADB"))
    }

    @Test
    fun theLiveErrorCodesMapToActionableStates() {
        // Confirmed against the service: no key -> 403 PERMISSION_DENIED,
        // bad key -> 400 API_KEY_INVALID.
        val noKey = DevRegistration.check("com.example.app", null, null)
        assertEquals("NO_KEY", noKey.state)
        assertTrue(noKey.summary().contains("API key"))

        val badKey = DevRegistration.check(
            "com.example.app", null, "AIza-bad",
            FakeFetcher(400, """{"error": {"code": 400, "message": "API key not valid.", "status": "INVALID_ARGUMENT", "details": [{"reason": "API_KEY_INVALID"}]}}""")
        )
        assertEquals("BAD_KEY", badKey.state)

        val forbidden = DevRegistration.check(
            "com.example.app", null, "key",
            FakeFetcher(403, """{"error": {"code": 403, "message": "Method doesn't allow unregistered callers"}}""")
        )
        assertEquals("NO_KEY", forbidden.state)
        assertTrue("the Google message is surfaced", forbidden.detail.contains("unregistered callers"))
    }

    @Test
    fun unexpectedResponsesDoNotCrashAndAreNamed() {
        assertEquals("UNKNOWN", DevRegistration.check("com.example.app", null, "key", FakeFetcher(200, "{}")).state)
        assertEquals("UNKNOWN", DevRegistration.check("com.example.app", null, "key", FakeFetcher(200, "not json")).state)
        assertEquals("UNKNOWN", DevRegistration.check("com.example.app", null, "key", FakeFetcher(500, "boom")).state)
        val future = DevRegistration.check("com.example.app", null, "key", FakeFetcher(200, state("SOMETHING_NEW")))
        assertEquals("SOMETHING_NEW", future.state)
        assertEquals("State from Google: SOMETHING_NEW", future.detail)
    }
}
