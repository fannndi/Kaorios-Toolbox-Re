package android.security.keystore2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * [KeyboxAnchor] recognises Google's current attestation roots on-device, which
 * is what catches the retired-root trap: the 2019 RSA root shares its subject
 * with the current one, so every offline chain check passes while the server
 * rejects the attestation. The fixture is a genuine root published at
 * `/attestation/root`, reused from the patcher test resources so the embedded
 * fingerprints are validated against real bytes rather than a copy.
 */
class KeyboxAnchorTest {

    private fun patcherResource(name: String): String {
        val candidates = listOf(
            File("../patcher/src/test/resources/$name"),
            File("patcher/src/test/resources/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("fixture $name not found (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    private fun certificateFromPem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    @Test
    fun theEmbeddedFingerprintsMatchTheRealGoogleRoots() {
        val rsa = certificateFromPem(patcherResource("google-attestation-root-sample.pem"))
        val ec = certificateFromPem(patcherResource("google-attestation-root-alt.pem"))

        assertEquals(KeyboxAnchor.ROOT_RSA_SHA256, KeyboxAnchor.fingerprint(rsa))
        assertEquals(KeyboxAnchor.ROOT_EC_SHA256, KeyboxAnchor.fingerprint(ec))
        assertTrue(KeyboxAnchor.isCurrentRoot(rsa))
        assertTrue(KeyboxAnchor.isCurrentRoot(ec))
        assertEquals("current", KeyboxAnchor.label(arrayOf(rsa)))
        assertEquals("current", KeyboxAnchor.label(arrayOf(ec)))
    }

    @Test
    fun aForgedChainIsLabelledRetiredNotSilentlyAccepted() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val leaf = generator.generateKeyPair()
        val keybox = generator.generateKeyPair()
        val forged = AttestationBuilder.build(
            leaf, keybox.private, false, name("keybox"), name("leaf"),
            byteArrayOf(1, 2, 3, 4), "com.example.app", 202409, AttestationBuilder.Identity()
        )!!

        assertFalse(KeyboxAnchor.isCurrentRoot(forged))
        assertEquals("retired", KeyboxAnchor.label(arrayOf(forged)))
        // The root is the last certificate, not the leaf.
        val realRoot = certificateFromPem(patcherResource("google-attestation-root-sample.pem"))
        assertEquals("current", KeyboxAnchor.label(arrayOf(forged, realRoot)))
    }

    @Test
    fun nothingToInspectIsUnknownNeverCurrent() {
        assertEquals("unknown", KeyboxAnchor.label(null))
        assertEquals("unknown", KeyboxAnchor.label(emptyArray()))
        assertNull(KeyboxAnchor.fingerprint(null))
    }

    private fun name(cn: String): ByteArray {
        val utf8 = byteArrayOf(0x0C, cn.length.toByte()) + cn.toByteArray()
        return Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), utf8)))
    }
}
