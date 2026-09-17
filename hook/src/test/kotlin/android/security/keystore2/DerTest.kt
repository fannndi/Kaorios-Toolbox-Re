package android.security.keystore2

import io.farewell.patcher.integrity.DerReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Der] is pure Java with no Android dependency, so it can run on the desktop JVM.
 * It is also the exact spot where a past DER bug lived: [Der.oid] wrote the first
 * subidentifier as a single byte, which silently truncates any OID whose leading
 * value (40 * arc0 + arc1) reaches 128. The decoder below is the tooling-side
 * [DerReader.oid], so a passing round trip proves the hook emits OIDs the parser
 * reads back identically.
 */
class DerTest {

    private fun decodeOid(der: ByteArray): String {
        val reader = DerReader(der)
        return reader.oid(reader.read())
    }

    @Test
    fun oidRoundTripsForStandardArcs() {
        for (oid in listOf(
            "1.2.840.113549.1.1.1",       // RSA
            "1.2.840.10045.2.1",           // EC public key
            "1.2.840.10045.3.1.7",         // P-256
            "1.3.6.1.4.1.11129.2.1.17"     // key attestation
        )) {
            assertEquals(oid, decodeOid(Der.oid(oid)))
        }
    }

    @Test
    fun oidEncodesHighFirstByteWithoutTruncation() {
        // 2.48 -> 2*40 + 48 = 128, which needs two content bytes. The old code
        // wrote a single 0x80 byte, which [DerReader.oid] reads back as arc0=2,
        // arc1=0 ("2.0"), silently corrupting the OID. The fix must emit 0x81 0x00.
        val der = Der.oid("2.48.1")
        assertEquals(0x06, der[0].toInt() and 0xFF) // OID tag
        assertEquals(0x03, der[1].toInt() and 0xFF)  // 3 content bytes
        assertEquals(0x81, der[2].toInt() and 0xFF)  // continuation bit set
        assertEquals(0x00, der[3].toInt() and 0xFF)  // 128 = 0x80 -> lower 7 bits 0
        assertEquals(0x01, der[4].toInt() and 0xFF)  // second arc = 1
        assertEquals("2.48.1", decodeOid(der))
    }

    @Test
    fun oidWithLargeArcIsNotTruncated() {
        // 2.999 -> 2*40 + 999 = 1079 -> base-128 0x88 0x27.
        assertEquals("2.999.5", decodeOid(Der.oid("2.999.5")))
    }
}
