package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AttestationParser] turns a KeyDescription blob into the verdict inputs. The
 * fields it reads are context tags 704..719, which need multi-byte DER tags — the
 * exact area a past bug ("RootOfTrust/ID tags were malformed") broke. These tests
 * build a complete KeyDescription with [DerFixture] and assert every field.
 */
class AttestationParserTest {

    private val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    private val bootHash = ByteArray(32) { (it + 1).toByte() }
    private val digest = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

    /** The 704 RootOfTrust payload: key, locked, boot state, boot hash. */
    private fun rootOfTrust(
        deviceLocked: Boolean = true,
        bootState: Int = 0,
        hash: ByteArray? = bootHash
    ): ByteArray {
        val parts = mutableListOf(
            DerFixture.octetString(ByteArray(32) { 0x11 }),
            DerFixture.boolean(deviceLocked),
            DerFixture.enumerated(bootState)
        )
        if (hash != null) parts += DerFixture.octetString(hash)
        return DerFixture.contextSequence(704, *parts.toTypedArray())
    }

    private fun applicationId(): ByteArray {
        val packageInfo = DerFixture.sequence(
            DerFixture.octetString("com.example.one".toByteArray()),
            DerFixture.integer(1)
        )
        return DerFixture.octetString(
            DerFixture.sequence(
                DerFixture.setOf(packageInfo),
                DerFixture.setOf(DerFixture.octetString(digest))
            )
        )
    }

    private fun keyDescription(
        teeEnforced: ByteArray,
        softwareEnforced: ByteArray = DerFixture.sequence(),
        valueCount: Int = 8
    ): ByteArray {
        val values = listOf(
            DerFixture.integer(3),                      // attestationVersion
            DerFixture.enumerated(1),                   // attestationSecurityLevel
            DerFixture.integer(4),                      // keymasterVersion
            DerFixture.enumerated(1),                   // keymasterSecurityLevel
            DerFixture.octetString(challenge),          // attestationChallenge
            DerFixture.octetString(ByteArray(0)),       // uniqueId
            softwareEnforced,
            teeEnforced
        )
        return DerFixture.sequence(*values.take(valueCount).toTypedArray())
    }

    private fun fullTee(): ByteArray = DerFixture.sequence(
        rootOfTrust(),
        DerFixture.context(705, DerFixture.integer(202409)),
        DerFixture.context(706, DerFixture.integer(202409)),
        DerFixture.context(709, applicationId()),
        DerFixture.context(710, DerFixture.utf8("google")),
        DerFixture.context(711, DerFixture.utf8("husky")),
        DerFixture.context(712, DerFixture.utf8("husky_beta")),
        DerFixture.context(716, DerFixture.utf8("Google")),
        DerFixture.context(717, DerFixture.utf8("Pixel 8 Pro")),
        DerFixture.context(718, DerFixture.integer(202410)),
        DerFixture.context(719, DerFixture.integer(202411))
    )

    // --- happy path ----------------------------------------------------------

    @Test
    fun parsesTheHeaderFields() {
        val info = AttestationParser.parse(keyDescription(fullTee()))!!

        assertEquals(3, info.attestationVersion)
        assertEquals(1, info.attestationSecurityLevel)
        assertEquals(4, info.keymasterVersion)
        assertEquals(1, info.keymasterSecurityLevel)
        assertEquals(challenge.size, info.challengeLength)
    }

    @Test
    fun parsesRootOfTrust() {
        val info = AttestationParser.parse(keyDescription(fullTee()))!!

        assertEquals(0, info.verifiedBootState)
        assertEquals(true, info.deviceLocked)
        assertEquals(bootHash.size, info.verifiedBootHashHex!!.length / 2)
        assertEquals(bootHash.joinToString("") { "%02x".format(it) }, info.verifiedBootHashHex)
    }

    @Test
    fun parsesRootOfTrustWithAnUnlockedBootloader() {
        val tee = DerFixture.sequence(
            rootOfTrust(deviceLocked = false, bootState = 1),
            DerFixture.context(705, DerFixture.integer(202409))
        )
        val info = AttestationParser.parse(keyDescription(tee))!!

        assertEquals(false, info.deviceLocked)
        assertEquals(1, info.verifiedBootState)
    }

    @Test
    fun parsesPatchLevels() {
        val info = AttestationParser.parse(keyDescription(fullTee()))!!

        assertEquals(202409, info.osVersion)
        assertEquals(202409, info.osPatchLevel)
        assertEquals(202410, info.vendorPatchLevel)
        assertEquals(202411, info.bootPatchLevel)
    }

    @Test
    fun parsesTheDeviceIdentity() {
        val info = AttestationParser.parse(keyDescription(fullTee()))!!

        assertEquals("google", info.brand)
        assertEquals("husky", info.device)
        assertEquals("husky_beta", info.product)
        assertEquals("Google", info.manufacturer)
        assertEquals("Pixel 8 Pro", info.model)
    }

    @Test
    fun parsesTheApplicationId() {
        val info = AttestationParser.parse(keyDescription(fullTee()))!!

        assertEquals(listOf("com.example.one"), info.applicationPackages)
        assertEquals(listOf("aabb"), info.applicationDigests)
    }

    // --- shape handling ------------------------------------------------------

    @Test
    fun rejectsABlobWithTooFewValues() {
        // teeEnforced is dropped, leaving 7 of the 8 required elements.
        assertNull(AttestationParser.parse(keyDescription(fullTee(), valueCount = 7)))
    }

    @Test
    fun rejectsGarbageWithoutThrowing() {
        assertNull(AttestationParser.parse(byteArrayOf(0x30, 0x7F)))
        assertNull(AttestationParser.parse(ByteArray(0)))
    }

    @Test
    fun missingTeeFieldsStayNull() {
        val tee = DerFixture.sequence(
            rootOfTrust(),
            DerFixture.context(705, DerFixture.integer(202409))
        )
        val info = AttestationParser.parse(keyDescription(tee))!!

        assertEquals(202409, info.osVersion)
        assertNull("osPatchLevel was not sent", info.osPatchLevel)
        assertNull("vendorPatchLevel was not sent", info.vendorPatchLevel)
        assertNull("bootPatchLevel was not sent", info.bootPatchLevel)
        assertNull("no identity was sent", info.brand)
        assertNull(info.device)
        assertNull(info.model)
        assertTrue("no applicationId was sent", info.applicationPackages.isEmpty())
        assertTrue(info.applicationDigests.isEmpty())
    }

    @Test
    fun rootOfTrustWithoutAHashLeavesTheHashNull() {
        val tee = DerFixture.sequence(rootOfTrust(hash = null))
        val info = AttestationParser.parse(keyDescription(tee))!!

        assertEquals(0, info.verifiedBootState)
        assertEquals(true, info.deviceLocked)
        assertNull("a 3-element RootOfTrust carries no boot hash", info.verifiedBootHashHex)
    }

    @Test
    fun anEmptyTeeEnforcedYieldsNoVerdictInputs() {
        val info = AttestationParser.parse(keyDescription(DerFixture.sequence()))!!

        assertNull(info.verifiedBootState)
        assertNull(info.deviceLocked)
        assertNull(info.verifiedBootHashHex)
        assertNull(info.osVersion)
    }

    @Test
    fun fieldsInTheSoftwareEnforcedSectionAreIgnored() {
        val software = DerFixture.sequence(
            DerFixture.context(710, DerFixture.utf8("wrong")),
            DerFixture.context(705, DerFixture.integer(1))
        )
        val tee = DerFixture.sequence(DerFixture.context(705, DerFixture.integer(202409)))

        val info = AttestationParser.parse(keyDescription(tee, softwareEnforced = software))!!

        assertEquals("only teeEnforced is read", 202409, info.osVersion)
        assertNull("brand must not come from softwareEnforced", info.brand)
    }

    @Test
    fun anEmptyChallengeIsReportedAsLengthZero() {
        val values = listOf(
            DerFixture.integer(3),
            DerFixture.enumerated(1),
            DerFixture.integer(4),
            DerFixture.enumerated(1),
            DerFixture.octetString(ByteArray(0)),
            DerFixture.octetString(ByteArray(0)),
            DerFixture.sequence(),
            DerFixture.sequence()
        )
        val info = AttestationParser.parse(DerFixture.sequence(*values.toTypedArray()))!!

        assertEquals(0, info.challengeLength)
        assertFalse(info.applicationPackages.isNotEmpty())
    }

    // --- the OID the parser is anchored to -----------------------------------

    @Test
    fun theAttestationOidIsTheStandardOne() {
        assertEquals("1.3.6.1.4.1.11129.2.1.17", AttestationParser.ATTESTATION_OID)
    }
}
