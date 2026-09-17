package io.farewell.patcher.integrity

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * A tiny certificate authority for tests.
 *
 * The attestation audit needs chains that differ in ways real Google roots
 * cannot express: a leaf signed by a CA, a chain whose issuer is *not* a CA, a
 * tampered signature, a leaf carrying a chosen key-attestation extension. Those
 * are exactly the inputs PIF Detector builds, so the tests build them the same
 * way — a minimal DER encoder plus `java.security`, no BouncyCastle, mirroring
 * how the hook's own `AttestationBuilder` emits certificates.
 */
object TestCerts {

    class Authority(val keyPair: KeyPair, val name: String) {

        /** Signs a certificate for [subject] with this authority's key. */
        fun issueFor(
            subject: Authority,
            isCa: Boolean,
            attestationExtension: ByteArray? = null,
            corruptSignature: Boolean = false,
        ): X509Certificate {
            val extensions = mutableListOf(
                extension(BASIC_CONSTRAINTS_OID, DerFixture.sequence(DerFixture.boolean(isCa)))
            )
            if (attestationExtension != null) {
                // extnValue is an OCTET STRING wrapping the extension structure
                // (the KeyDescription SEQUENCE), which is exactly what
                // X509Certificate.getExtensionValue() hands back on the other side.
                extensions += extension(ATTESTATION_OID, attestationExtension)
            }
            val tbs = DerFixture.sequence(
                DerFixture.context(0, DerFixture.integer(2), constructed = true), // version [0] EXPLICIT v3
                DerFixture.integer(1),
                signatureAlgorithm(),
                name(name),                                   // issuer
                DerFixture.sequence(DerFixture.utcTime("250101000000Z"), DerFixture.utcTime("350101000000Z")),
                name(subject.name),                           // subject
                subjectPublicKeyInfo(subject.keyPair),
                DerFixture.context(3, DerFixture.sequence(*extensions.toTypedArray()), constructed = true)
            )
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(keyPair.private)
            signature.update(tbs)
            val bytes = signature.sign()
            if (corruptSignature) bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
            val certificate = DerFixture.sequence(tbs, signatureAlgorithm(), DerFixture.bitString(bytes))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        }

        fun selfSigned(isCa: Boolean = true): X509Certificate = issueFor(this, isCa)
    }

    fun newAuthority(name: String): Authority {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return Authority(generator.generateKeyPair(), name)
    }

    /** The normal shape: a CA that issued one leaf. Returns `[leaf, ca]`. */
    fun caAndLeaf(
        attestationExtension: ByteArray? = null,
        corruptSignature: Boolean = false,
    ): Pair<Authority, List<X509Certificate>> {
        val ca = newAuthority("Test CA")
        val leaf = newAuthority("Leaf")
        val caCert = ca.selfSigned(isCa = true)
        val leafCert = ca.issueFor(leaf, isCa = false, attestationExtension = attestationExtension, corruptSignature = corruptSignature)
        return ca to listOf(leafCert, caCert)
    }

    /** A two-level chain: root CA -> intermediate CA -> leaf. Returns `[leaf, intermediate, ca]`. */
    fun caIntermediateAndLeaf(attestationExtension: ByteArray? = null): List<X509Certificate> {
        val ca = newAuthority("Test CA")
        val intermediate = newAuthority("Intermediate")
        val leaf = newAuthority("Leaf")
        return listOf(
            intermediate.issueFor(leaf, isCa = false, attestationExtension = attestationExtension),
            ca.issueFor(intermediate, isCa = true),
            ca.selfSigned(isCa = true)
        )
    }

    /** A leaf signed by a certificate that is *not* a CA — the detector's nonCaIssuer case. */
    fun leafUnderNonCaIssuer(): List<X509Certificate> {
        val issuer = newAuthority("Not a CA")
        val leaf = newAuthority("Leaf")
        return listOf(
            issuer.issueFor(leaf, isCa = false),
            issuer.selfSigned(isCa = false)
        )
    }

    fun pem(certificate: X509Certificate): String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        append(java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded))
        append("\n-----END CERTIFICATE-----\n")
    }

    private fun signatureAlgorithm() = DerFixture.sequence(DerFixture.oid(1, 2, 840, 10045, 4, 3, 2))

    private fun name(value: String): ByteArray =
        DerFixture.sequence(
            DerFixture.setOf(
                DerFixture.sequence(DerFixture.oid(2, 5, 4, 3), DerFixture.utf8(value))
            )
        )

    private fun subjectPublicKeyInfo(keyPair: KeyPair): ByteArray {
        val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val point = byteArrayOf(0x04) + fixed(publicKey.w.affineX, 32) + fixed(publicKey.w.affineY, 32)
        return DerFixture.sequence(
            DerFixture.sequence(
                DerFixture.oid(1, 2, 840, 10045, 2, 1),
                DerFixture.oid(1, 2, 840, 10045, 3, 1, 7)
            ),
            DerFixture.bitString(point)
        )
    }

    private fun extension(oid: String, value: ByteArray): ByteArray {
        val arcs = oid.split('.').map { it.toInt() }.toIntArray()
        return DerFixture.sequence(DerFixture.oid(*arcs), DerFixture.octetString(value))
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(size)
        if (raw.size > size) raw.copyInto(out, 0, raw.size - size, raw.size)
        else raw.copyInto(out, size - raw.size)
        return out
    }

    private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"
    private const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
}
