package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * [AttestationAudit] is the adversarial half of the keybox tooling: PIF
 * Detector's checks, run against a chain *we* served. The fixtures build the
 * exact shapes a detector uses to provoke a forger — a non-CA issuer, a broken
 * link, a single self-signed certificate, a mismatched challenge — so a
 * regression in the hook's forging shows up here first.
 */
class AttestationAuditTest {

    private fun resource(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    private fun googleRoots(): List<String> =
        listOf(resource("google-attestation-root-sample.pem"), resource("google-attestation-root-alt.pem"))

    private fun keyDescription(challenge: ByteArray): ByteArray =
        DerFixture.sequence(
            DerFixture.integer(3),
            DerFixture.enumerated(1),
            DerFixture.integer(4),
            DerFixture.enumerated(1),
            DerFixture.octetString(challenge),
            DerFixture.octetString(ByteArray(0)),
            DerFixture.sequence(),
            DerFixture.sequence()
        )

    @Test
    fun aCleanLocalChainHasGoodLinksAndCaIssuersButNoGoogleAnchor() {
        val (_, chain) = TestCerts.caAndLeaf()
        val report = AttestationAudit.audit(chain, googleRoots())
        assertEquals(2, report.certCount)
        assertTrue("links must verify", report.linksValid)
        assertTrue("issuers must be CAs", report.issuersAreCa)
        assertFalse("a local test CA is not a Google root", report.anchored)
        assertTrue(report.problems.any { it.contains("does not reach a current Google attestation root") })
        assertTrue(report.summary().contains("FAILS"))
    }

    @Test
    fun aTamperedSignatureBreaksTheLink() {
        val (_, chain) = TestCerts.caAndLeaf(corruptSignature = true)
        val report = AttestationAudit.audit(chain, googleRoots())
        assertFalse(report.linksValid)
        assertTrue(report.problems.any { it.contains("is not signed by certificate 1") })
    }

    @Test
    fun aNonCaIssuerIsAHardFailureNotAWarning() {
        // The detector's `chainHasNonCaIssuer`: an attacker can sign a forged
        // leaf with a genuine end-entity certificate, so the chain verifies link
        // by link unless the CA bit is required.
        val chain = TestCerts.leafUnderNonCaIssuer()
        val report = AttestationAudit.audit(chain, googleRoots())
        assertTrue("the link itself verifies", report.linksValid)
        assertFalse(report.issuersAreCa)
        assertTrue(report.problems.any { it.contains("not a CA") })
    }

    @Test
    fun aSingleCertificateChainIsFlaggedAsForged() {
        val lone = TestCerts.newAuthority("Lone").selfSigned(isCa = true)
        val report = AttestationAudit.audit(listOf(lone), googleRoots())
        assertTrue(report.problems.any { it.contains("Single certificate chain") })
    }

    @Test
    fun aRealGoogleRootAnchorsItselfAndReportsItsFingerprint() {
        val root = AttestationAudit.parsePemChain(resource("google-attestation-root-sample.pem"))
        val report = AttestationAudit.audit(root, googleRoots())
        assertTrue(report.anchored)
        val expected = MessageDigest.getInstance("SHA-256").digest(root[0].encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, report.rootFingerprint)
    }

    @Test
    fun theChallengeIsComparedByteForByte() {
        val challenge = ByteArray(32) { (it + 1).toByte() }
        val (_, chain) = TestCerts.caAndLeaf(attestationExtension = keyDescription(challenge))

        val matching = AttestationAudit.audit(chain, googleRoots(), challenge = challenge)
        assertEquals(true, matching.challengeEchoed)
        assertTrue(matching.problems.none { it.contains("challenge") })

        val replayed = AttestationAudit.audit(chain, googleRoots(), challenge = ByteArray(32) { 9 })
        assertEquals(false, replayed.challengeEchoed)
        assertTrue(replayed.problems.any { it.contains("does not echo the caller's challenge") })
    }

    @Test
    fun anAbsentChallengeIsNotCheckedAndNotGuessed() {
        val (_, chain) = TestCerts.caAndLeaf(attestationExtension = keyDescription(ByteArray(32) { 7 }))
        val report = AttestationAudit.audit(chain, googleRoots(), challenge = null)
        assertNull("no challenge asked, none compared", report.challengeEchoed)
    }

    @Test
    fun theExpectedPackageIsCheckedAgainstTheAttestedApplicationId() {
        // 709 = attestationApplicationId: SET OF SEQUENCE { name, version }.
        val appId = DerFixture.sequence(
            DerFixture.setOf(
                DerFixture.sequence(DerFixture.octetString("com.google.android.gms".toByteArray()), DerFixture.integer(1))
            ),
            DerFixture.setOf(DerFixture.octetString(ByteArray(32)))
        )
        val description = DerFixture.sequence(
            DerFixture.integer(3),
            DerFixture.enumerated(1),
            DerFixture.integer(4),
            DerFixture.enumerated(1),
            DerFixture.octetString(ByteArray(32) { 1 }),
            DerFixture.octetString(ByteArray(0)),
            DerFixture.sequence(),
            DerFixture.sequence(DerFixture.contextSequence(709, DerFixture.octetString(appId)))
        )
        val (_, chain) = TestCerts.caAndLeaf(attestationExtension = description)
        val match = AttestationAudit.audit(chain, googleRoots(), expectedPackage = "com.google.android.gms")
        assertTrue(match.problems.none { it.contains("application id") })
        val mismatch = AttestationAudit.audit(chain, googleRoots(), expectedPackage = "com.example.bank")
        assertTrue(mismatch.warnings.any { it.contains("does not include com.example.bank") })
    }

    @Test
    fun pemChainsParseLeafFirstAndInOrder() {
        val (_, chain) = TestCerts.caAndLeaf()
        val text = chain.joinToString("\n") { TestCerts.pem(it) }
        val parsed = AttestationAudit.parsePemChain(text)
        assertEquals(2, parsed.size)
        assertEquals(chain[0].subjectX500Principal.name, parsed[0].subjectX500Principal.name)
        assertEquals(chain[1].subjectX500Principal.name, parsed[1].subjectX500Principal.name)
        assertNotNull(parsed[0])
    }

    @Test
    fun anEmptyChainIsReportedNotCrashed() {
        val report = AttestationAudit.audit(emptyList(), googleRoots())
        assertEquals(0, report.certCount)
        assertTrue(report.problems.any { it.contains("No certificates") })
        assertTrue(report.lines().any { it.contains("Certificates: 0") })
    }
}
