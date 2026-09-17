package android.security.keystore2

import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * The on-device revocation gate lives in two classes that only meet at runtime:
 * [KeyboxEngine.chainForAlias] checks [KeyboxEngine]'s static [KeyboxRevocation]
 * before handing an app the spoofed keybox chain, and returns `null` (so the
 * caller falls back to the real device chain) when the serial is on Google's
 * revoked list. This test closes that loop without a device by wiring a
 * [KeyboxRevocation] backed by a disk cache file (no network) and a generated
 * alias entry, then asserting the gate behaves.
 *
 * The static collaborators ([KeyboxEngine.sRevocation] / [KeyboxEngine.sGenerated])
 * are private, so this drives them through reflection — the same seams
 * [KeyboxEngine.replaceChain] sets up from the real `Context`.
 */
class KeyboxEngineRevocationTest {

    companion object {
        private const val ALIAS = "farewell-test-key"
    }

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

    /** Replace KeyboxEngine's static revocation instance (the seam replaceChain sets). */
    private fun setRevocation(rev: KeyboxRevocation?) {
        val field = KeyboxEngine::class.java.getDeclaredField("sRevocation")
        field.isAccessible = true
        field.set(null, rev)
    }

    /** Insert an alias -> chain entry into KeyboxEngine's static generated map. */
    private fun putGenerated(alias: String, chain: Array<Certificate>) {
        val field = KeyboxEngine::class.java.getDeclaredField("sGenerated")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(null) as java.util.concurrent.ConcurrentHashMap<Any, Any>
        val entryClass = KeyboxEngine::class.java.declaredClasses.first { it.simpleName == "Entry" }
        val ctor = entryClass.getDeclaredConstructor(
            KeyPair::class.java, Class.forName("[Ljava.security.cert.Certificate;")
        )
        ctor.isAccessible = true
        map[alias] = ctor.newInstance(null, chain)
    }

    @After
    fun reset() {
        setRevocation(null)
        val field = KeyboxEngine::class.java.getDeclaredField("sGenerated")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as java.util.concurrent.ConcurrentHashMap<Any, Any>).clear()
    }

    @Test
    fun servesNothingWhenTheKeyboxSerialIsRevoked() {
        val leaf = buildLeaf()
        val serial = leaf.serialNumber.toString(16).lowercase()
        assertNotNull("AttestationBuilder.build returned null", leaf)

        // Back the revocation check with a disk cache that lists this serial as REVOKED.
        val cacheDir = createTempDirectory("farewell-revocation").toFile()
        File(cacheDir, "keybox-revocation.json").writeText(
            """{"entries":{"$serial":{"status":"REVOKED","reason":"compromised"}}}"""
        )
        val rev = KeyboxRevocation(cacheDir)
        rev.ensureFresh()
        assertTrue("revocation should have loaded the serial from disk", rev.isRevoked(arrayOf(leaf)))

        setRevocation(rev)
        putGenerated(ALIAS, arrayOf(leaf))

        // The gate must withhold the spoofed chain when Google has revoked it.
        assertNull(
            "chainForAlias must return null for a revoked keybox",
            KeyboxEngine.chainForAlias(ALIAS)
        )
    }

    @Test
    fun servesTheSpoofedChainWhenTheKeyboxIsNotRevoked() {
        val leaf = buildLeaf()

        // A revocation instance with an empty status list: nothing is revoked.
        val rev = KeyboxRevocation(createTempDirectory("farewell-revocation-empty").toFile())
        rev.ensureFresh()

        setRevocation(rev)
        val chain = arrayOf<Certificate>(leaf)
        putGenerated(ALIAS, chain)

        val served = KeyboxEngine.chainForAlias(ALIAS)
        assertNotNull("chainForAlias must serve the chain when not revoked", served)
        assertArrayEquals("the exact spoofed chain is served", chain, served)
    }
}
