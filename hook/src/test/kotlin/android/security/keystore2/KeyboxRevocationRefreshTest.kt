package android.security.keystore2

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

/**
 * `KeyboxRevocation.refresh()` is the one piece of the on-device revocation path
 * that previously needed a device: it fetches Google's status list on a background
 * thread, parses it, and caches it on disk — and must stay best-effort (a failed
 * fetch must never disable the user's setup). This test drives that lifecycle
 * without the network by injecting a [KeyboxRevocation.StatusFetcher], the seam
 * `refresh()` uses instead of the real HTTP call.
 */
class KeyboxRevocationRefreshTest {

    /** A minimal but valid X.501 Name: SEQUENCE { SET { SEQUENCE { OID, UTF8String } } }. */
    private fun name(cn: String): ByteArray {
        val utf8 = byteArrayOf(0x0C, cn.length.toByte()) + cn.toByteArray()
        val attribute = Der.sequence(Der.oid("2.5.4.3"), utf8)
        val rdn = Der.set(attribute)
        return Der.sequence(rdn)
    }

    private fun buildLeaf(): X509Certificate {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val pair = generator.generateKeyPair()
        val identity = AttestationBuilder.Identity().apply {
            brand = "google"
            device = "husky"
            model = "Pixel 8 Pro"
        }
        val dn = name("leaf")
        return AttestationBuilder.build(
            pair, pair.private, false, dn, dn, byteArrayOf(1, 2, 3, 4),
            "com.example.app", 202409, identity
        )!!
    }

    /** Spin until the predicate holds or the timeout elapses (the refresh runs off-thread). */
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Test
    fun backgroundRefreshLoadsRevokedStatusAndWritesCache() {
        val leaf = buildLeaf()
        val serial = leaf.serialNumber.toString(16).lowercase()
        assertNotNull("AttestationBuilder.build returned null", leaf)

        // No cache file yet -> ensureFresh() sees a stale (never-loaded) list and
        // fires the background refresh, which our injected source answers.
        val cacheDir = createTempDirectory("farewell-rev-refresh").toFile()
        val revokedJson = """{"entries":{"$serial":{"status":"REVOKED","reason":"compromised"}}}"""
        val rev = KeyboxRevocation(cacheDir)
        rev.setFetcherForTest(object : KeyboxRevocation.StatusFetcher {
            override fun fetch(url: String): String = revokedJson
        })

        rev.ensureFresh()

        // The background thread must flip the synchronous check to true and persist the list.
        assertTrue(
            "background refresh should load the revoked status into the map",
            waitUntil(2000) { rev.isRevoked(arrayOf(leaf)) }
        )
        val cacheFile = File(cacheDir, "keybox-revocation.json")
        assertTrue("a successful refresh must write the cache file", cacheFile.exists())
        assertTrue("cached payload must contain the revoked serial", cacheFile.readText().contains(serial))
    }

    @Test
    fun refreshFailureStaysBestEffort() {
        val leaf = buildLeaf()
        val cacheDir = createTempDirectory("farewell-rev-fail").toFile()

        // A source that always throws: the refresh must swallow it and keep serving the spoof.
        val rev = KeyboxRevocation(cacheDir)
        rev.setFetcherForTest(object : KeyboxRevocation.StatusFetcher {
            override fun fetch(url: String): String = throw RuntimeException("no network")
        })

        rev.ensureFresh()

        // Give the background thread time to run (and prove it does not crash the caller).
        Thread.sleep(300)

        assertFalse(
            "a failed refresh must NOT flag the chain as revoked (best-effort, key path intact)",
            rev.isRevoked(arrayOf(leaf))
        )
        val cacheFile = File(cacheDir, "keybox-revocation.json")
        assertFalse("a failed refresh must not write a cache file", cacheFile.exists())
    }
}
