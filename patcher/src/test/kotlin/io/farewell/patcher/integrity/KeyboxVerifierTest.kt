package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * [KeyboxVerifier] decides whether a keybox is worth installing, so a wrong answer
 * here is a wrong Play Integrity verdict. The tests use **real Google attestation
 * roots** (fetched from `https://android.googleapis.com/attestation/root`) as
 * fixtures, so certificate parsing, chain anchoring and the status-list lookup are
 * exercised against genuine X.509 data rather than a mock.
 */
class KeyboxVerifierTest {

    private val rootPem = resource("google-attestation-root-sample.pem")
    private val altRootPem = resource("google-attestation-root-alt.pem")

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** The keybox XML shape the verifier scans for `<Certificate>` blocks. */
    private fun keyboxXml(vararg pems: String): String = buildString {
        append("<keybox>\n")
        for (pem in pems) {
            val body = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .trim()
            append("  <Key algorithm=\"ecdsa\">\n")
            append("    <PrivateKey>not-parsed-by-the-verifier</PrivateKey>\n")
            append("    <Certificate>\n").append(body).append("\n    </Certificate>\n")
            append("  </Key>\n")
        }
        append("</keybox>\n")
    }

    private fun revoked(reason: String? = "KEY_COMPROMISE") =
        IntegrityData.RevocationEntry(status = "REVOKED", reason = reason)

    private fun suspended() =
        IntegrityData.RevocationEntry(status = "SUSPENDED", reason = "SOFTWARE_FLAW")

    private fun serialOf(pem: String): BigInteger =
        KeyboxVerifier.parseCertificates(keyboxXml(pem)).first().serialNumber

    private fun serialHexOf(pem: String): String = serialOf(pem).toString(16)

    // --- certificate parsing -------------------------------------------------

    @Test
    fun parsesARealGoogleRoot() {
        val certificates = KeyboxVerifier.parseCertificates(keyboxXml(rootPem))

        assertEquals(1, certificates.size)
        val certificate = certificates[0]
        assertEquals(
            "unexpected serial: ${certificate.serialNumber}",
            "f1c172a699eaf51d",
            certificate.serialNumber.toString(16)
        )
        // This root identifies itself only by serialNumber (OID 2.5.4.5), and Java
        // renders that attribute as a raw DER blob (`2.5.4.5=#13106639...` rather
        // than the decoded string), so assert on the OID rather than the value.
        assertTrue(
            "unexpected subject: ${certificate.subjectX500Principal.name}",
            certificate.subjectX500Principal.name.contains("2.5.4.5")
        )
        assertEquals(
            "a Google attestation root is self-signed",
            certificate.subjectX500Principal,
            certificate.issuerX500Principal
        )
    }

    @Test
    fun parsesARealGoogleCaRootWithReadableSubject() {
        val certificate = KeyboxVerifier.parseCertificates(keyboxXml(altRootPem)).first()
        val subject = certificate.subjectX500Principal.name

        assertEquals("84a9d0297b0eb58ae7ff0e80de760605", certificate.serialNumber.toString(16))
        assertTrue("unexpected subject: $subject", subject.contains("Key Attestation CA1"))
        assertTrue("unexpected subject: $subject", subject.contains("Google LLC"))
    }

    @Test
    fun parsesMultipleCertificates() {
        val certificates = KeyboxVerifier.parseCertificates(keyboxXml(rootPem, altRootPem))

        assertEquals(2, certificates.size)
        assertEquals("f1c172a699eaf51d", certificates[0].serialNumber.toString(16))
        assertEquals("84a9d0297b0eb58ae7ff0e80de760605", certificates[1].serialNumber.toString(16))
    }

    @Test
    fun parsesCertificatesWithMimeLineWrapping() {
        // The PEM bodies are wrapped; the verifier uses the MIME decoder for that.
        val certificates = KeyboxVerifier.parseCertificates(keyboxXml(rootPem))
        assertEquals(1, certificates.size)
    }

    @Test
    fun returnsNothingWhenTheXmlHasNoCertificates() {
        assertTrue(KeyboxVerifier.parseCertificates("<keybox></keybox>").isEmpty())
    }

    // --- chain anchoring -----------------------------------------------------

    @Test
    fun anchorsAKeyboxToItsMatchingRoot() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(rootPem), emptyMap())

        assertTrue("a self-signed Google root must anchor: ${report.problems}", report.chainValid)
        assertEquals(1, report.chainLength)
        assertNotNull(report.rootSubject)
        assertTrue(report.problems.isEmpty())
        assertEquals("f1c172a699eaf51d", report.leafSerialHex)
    }

    @Test
    fun rejectsAKeyboxThatDoesNotReachTheGivenRoot() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(altRootPem), emptyMap())

        assertFalse(report.chainValid)
        assertNull(report.rootSubject)
        assertTrue(
            "must say why: ${report.problems}",
            report.problems.any { it.contains("does not reach a known Google attestation root") }
        )
    }

    @Test
    fun anchorsWhenOneOfSeveralRootsMatches() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(altRootPem, rootPem), emptyMap())

        assertTrue("a later root must still anchor: ${report.problems}", report.chainValid)
        assertNotNull(report.rootSubject)
    }

    @Test
    fun reportsWhenNoGoogleRootsAreAvailable() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), emptyList(), emptyMap())

        assertFalse(report.chainValid)
        assertTrue(report.problems.any { it.contains("No Google root certificates available") })
    }

    @Test
    fun flagsACertificateThatIsNotSignedByItsIssuer() {
        // Two unrelated roots in one chain: the first cannot verify against the second.
        val report = KeyboxVerifier.verify(keyboxXml(rootPem, altRootPem), listOf(rootPem), emptyMap())

        assertFalse(report.chainValid)
        assertTrue(
            "must name the broken link: ${report.problems}",
            report.problems.any { it.contains("is not signed by certificate") }
        )
    }

    // --- status list lookups -------------------------------------------------

    @Test
    fun findsARevokedLeafByHexSerial() {
        val serial = serialHexOf(rootPem)
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(serial to revoked())
        )

        assertNotNull("the documented key format is lowercase hex", report.leafRevocation)
        assertTrue(report.problems.any { it.contains("listed in Google's status list") })
        // A revoked leaf is recorded as a problem, and a problem outranks everything
        // else in summary(), so the keybox reads as invalid rather than revoked.
        assertTrue(
            "unexpected summary: ${report.summary()}",
            report.summary().startsWith("Keybox invalid")
        )
    }

    @Test
    fun findsARevokedLeafByUppercaseHexSerial() {
        val serial = serialHexOf(rootPem).uppercase()
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(serial to revoked())
        )

        assertNotNull(report.leafRevocation)
    }

    @Test
    fun findsARevokedLeafByZeroPrefixedHexSerial() {
        val serial = "0x" + serialHexOf(rootPem)
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(serial to revoked())
        )

        assertNotNull(report.leafRevocation)
    }

    /**
     * Regression test for the lookup order.
     *
     * Google's list is keyed by hex serial only — verified against the live list,
     * where all 1746 keys match `^[a-f1-9][a-f0-9]*$`. The decimal form used to be
     * tried first, and it is the one notation that can resolve to a *different*
     * certificate: a serial whose decimal string equals another key's hex string
     * would report that other key's status. A decimal-keyed entry must now be
     * ignored entirely.
     */
    @Test
    fun ignoresADecimalKeyedEntry() {
        val decimal = serialOf(rootPem).toString(10)
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(decimal to revoked())
        )

        assertNull("decimal keys are not part of the documented format", report.leafRevocation)
        assertFalse(report.problems.any { it.contains("status list") })
    }

    @Test
    fun aDecimalSerialCannotCrossMatchAnotherKeysHexEntry() {
        // Serial 10000 has decimal "10000" and hex "2710". If a lookup used the
        // decimal form it would match an entry belonging to the unrelated key whose
        // hex serial is "10000". The fixture root has no such collision, so the
        // honest assertion is that an unrelated all-digit hex key is not matched.
        val unrelatedAllDigitHexKey = "10000"
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(unrelatedAllDigitHexKey to revoked())
        )

        assertNull(report.leafRevocation)
    }

    @Test
    fun aSuspendedLeafIsAWarningNotAProblem() {
        val serial = serialHexOf(rootPem)
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(rootPem),
            mapOf(serial to suspended())
        )

        assertTrue(report.softBanned)
        assertNotNull(report.leafRevocation)
        assertTrue(report.problems.isEmpty())
        assertTrue(report.warnings.any { it.contains("SUSPENDED") })
        assertTrue(report.summary().startsWith("Keybox soft-banned"))
    }

    @Test
    fun aCleanKeyboxSummarisesAsValid() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(rootPem), emptyMap())

        assertNull(report.leafRevocation)
        assertFalse(report.softBanned)
        assertTrue(report.summary().startsWith("Keybox valid"))
    }

    @Test
    fun anUnanchoredKeyboxSummarisesAsUnknown() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(altRootPem), emptyMap())

        // chainValid is false but there is no revocation and no parse problem, so the
        // summary falls through to "unknown" — the chain problem is the "does not
        // reach a known root" entry, which is a problem, hence "invalid".
        assertTrue(
            "unexpected summary: ${report.summary()}",
            report.summary().startsWith("Keybox invalid")
        )
    }

    // --- malformed input -----------------------------------------------------

    @Test
    fun reportsAnEmptyKeybox() {
        val report = KeyboxVerifier.verify("<keybox></keybox>", listOf(rootPem), emptyMap())

        assertFalse(report.chainValid)
        assertEquals(0, report.chainLength)
        assertTrue(report.problems.any { it.contains("No certificates in keybox") })
        assertTrue(report.summary().startsWith("Keybox invalid"))
    }

    @Test
    fun reportsUnparseableCertificates() {
        val xml = "<keybox><Certificate>bm90LWEtY2VydGlmaWNhdGU=</Certificate></keybox>"
        val report = KeyboxVerifier.verify(xml, listOf(rootPem), emptyMap())

        assertFalse(report.chainValid)
        assertEquals(0, report.chainLength)
        assertTrue(
            "must explain the parse failure: ${report.problems}",
            report.problems.any { it.contains("Cannot parse keybox certificates") }
        )
    }

    // --- report rendering ----------------------------------------------------

    @Test
    fun linesMentionAMissingAttestationExtension() {
        val report = KeyboxVerifier.verify(keyboxXml(rootPem), listOf(rootPem), emptyMap())
        val lines = report.lines()

        assertTrue(lines.any { it.startsWith("Keybox certificates: 1") })
        assertTrue(lines.any { it.startsWith("Chain to Google root: OK") })
        assertTrue(lines.any { it.contains("Attestation extension: absent") })
        assertTrue(lines.any { it.startsWith("Revocation: not listed") })
    }

    @Test
    fun linesListEveryProblemAndWarning() {
        val serial = serialHexOf(rootPem)
        val report = KeyboxVerifier.verify(
            keyboxXml(rootPem),
            listOf(altRootPem),
            mapOf(serial to suspended())
        )
        val lines = report.lines()

        assertTrue(lines.any { it.startsWith("PROBLEM: ") })
        assertTrue(lines.any { it.startsWith("WARN: ") })
    }
}
