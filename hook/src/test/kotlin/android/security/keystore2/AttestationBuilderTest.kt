package android.security.keystore2

import io.farewell.patcher.integrity.AttestationParser
import io.farewell.patcher.integrity.DerReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * The hook builds the attestation certificate the keybox is wrapped in, and the
 * desktop tooling parses that same certificate to decide whether a spoofed device
 * looks legitimate. This test closes that loop: run the real [AttestationBuilder],
 * pull the keyDescription out of the cert, and feed it to [AttestationParser] — the
 * exact parser the patcher uses. If the hook emits a tag the parser misreads (the
 * old multi-byte [n] EXPLICIT bug), the identity here will not survive the round trip.
 */
class AttestationBuilderTest {

    /** A minimal but valid X.501 Name: SEQUENCE { SET { SEQUENCE { OID, UTF8String } } }. */
    private fun name(cn: String): ByteArray {
        val utf8 = byteArrayOf(0x0C, cn.length.toByte()) + cn.toByteArray()
        val attribute = Der.sequence(Der.oid("2.5.4.3"), utf8)
        val rdn = Der.set(attribute)
        return Der.sequence(rdn)
    }

    @Test
    fun buildsACertTheToolingCanParse() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val leaf = generator.generateKeyPair()
        val keybox = generator.generateKeyPair()

        val identity = AttestationBuilder.Identity().apply {
            brand = "google"
            device = "husky"
            product = "husky_beta"
            manufacturer = "Google"
            model = "Pixel 8 Pro"
            bootHash = ByteArray(32) { (it + 1).toByte() }
        }

        val challenge = byteArrayOf(1, 2, 3, 4)
        val issuer = name("keybox")
        val subject = name("leaf")

        val cert = AttestationBuilder.build(
            leaf, keybox.private, false, issuer, subject, challenge,
            "com.example.app", 202409, identity
        )
        assertNotNull("AttestationBuilder.build returned null (an exception was swallowed by HookLog)", cert)

        // The cert must verify against the keybox key that signed it.
        cert!!.verify(keybox.public)

        // Extract the keyDescription from the attestation extension and run it
        // through the precise parser the desktop tooling uses.
        val extension = cert.getExtensionValue(AttestationParser.ATTESTATION_OID)
        assertNotNull("attestation extension missing", extension)
        val reader = DerReader(extension)
        val keyDescription = reader.content(reader.read())
        val info = AttestationParser.parse(keyDescription)
        assertNotNull(info)

        assertEquals("google", info!!.brand)
        assertEquals("husky", info.device)
        assertEquals("husky_beta", info.product)
        assertEquals("Google", info.manufacturer)
        assertEquals("Pixel 8 Pro", info.model)
        assertEquals(202409, info.osPatchLevel)
        assertEquals(202409, info.vendorPatchLevel)
        assertEquals(202409, info.bootPatchLevel)
        assertEquals(challenge.size, info.challengeLength)
        assertEquals(true, info.deviceLocked)
        assertEquals(0, info.verifiedBootState)
        assertEquals(identity.bootHash!!.joinToString("") { "%02x".format(it) }, info.verifiedBootHashHex)
        assertEquals(listOf("com.example.app"), info.applicationPackages)
    }
}
