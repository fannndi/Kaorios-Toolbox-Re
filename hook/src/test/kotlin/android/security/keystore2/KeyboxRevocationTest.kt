package android.security.keystore2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate

/**
 * `KeyboxRevocation` is the on-device half of `--verify-keybox`: it asks Google's
 * status list whether the spoofed keybox is revoked. The list is parsed by hand
 * (no `org.json`) so this runs on the JVM instead of hitting android.jar's stub,
 * and the synchronous check is just a map lookup — the network fetch is off the
 * critical path. These tests cover the parts that do not need a device: the parse,
 * the hex-serial lookup, and that a cert's serial formats the same way Google keys
 * the list (lowercase hex, no leading zeros).
 */
class KeyboxRevocationTest {

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

    @Test
    fun parsesTheStatusListShape() {
        val json = """{"entries":{
            "f1c172a699eaf51d":{"status":"REVOKED","reason":"compromised"},
            "2710":{"status":"REVOKED"},
            "abc":{"noStatus":true}
        }}"""
        val map = KeyboxRevocation.parseStatus(json)
        assertEquals(2, map.size)
        assertEquals("REVOKED", map["f1c172a699eaf51d"])
        assertEquals("REVOKED", map["2710"])
        assertFalse("an entry without a status key is not a revocation", map.containsKey("abc"))
    }

    @Test
    fun ignoresMalformedJson() {
        assertTrue(KeyboxRevocation.parseStatus("").isEmpty())
        assertTrue(KeyboxRevocation.parseStatus("{not json").isEmpty())
        assertTrue(KeyboxRevocation.parseStatus("{}").isEmpty())
    }

    @Test
    fun flagsAChainWhoseSerialIsListed() {
        val leaf = buildLeaf()
        val serial = leaf.serialNumber.toString(16).lowercase()
        assertTrue(KeyboxRevocation.isRevoked(arrayOf(leaf), mapOf(serial to "REVOKED")))
    }

    @Test
    fun doesNotFlagAnUnknownChain() {
        val leaf = buildLeaf()
        assertFalse(KeyboxRevocation.isRevoked(arrayOf(leaf), emptyMap()))
    }

    @Test
    fun serialFormatMatchesGooglesLowercaseHexKeys() {
        // Google keys the list by lowercase hex with no leading zeros, so the hook
        // must derive the same string from a cert or the lookup never hits.
        val leaf = buildLeaf()
        val serial = leaf.serialNumber.toString(16).lowercase()
        assertTrue("serial must be plain lowercase hex: $serial", serial.matches(Regex("^[0-9a-f]+$")))

        // parseStatus must lowercase the keys, so an uppercase key in the payload
        // still resolves to the cert's lowercase serial.
        val alt = KeyboxRevocation.parseStatus("""{"entries":{"${serial.uppercase()}":{"status":"REVOKED"}}}""")
        assertTrue("parseStatus should normalise key case", alt.containsKey(serial))
    }
}
