package io.farewell.patcher.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * [DerReader] is the only thing between a KeyDescription blob and the attestation
 * verdict, and a decoding slip shows up as a wrong-but-plausible verdict rather
 * than a crash. Inputs are built with [DerFixture] so each test states the
 * structure it means.
 */
class DerReaderTest {

    // --- tags and lengths ----------------------------------------------------

    @Test
    fun readsAShortTagWithAShortLength() {
        val bytes = DerFixture.tlv(DerFixture.TAG_OCTET_STRING, byteArrayOf(1, 2, 3))
        val tlv = DerReader(bytes).read()

        assertEquals(DerFixture.CLASS_UNIVERSAL, tlv.tagClass)
        assertEquals(DerFixture.TAG_OCTET_STRING, tlv.tagNumber)
        assertFalse(tlv.constructed)
        assertEquals(3, tlv.contentEnd - tlv.contentStart)
        assertEquals(bytes.size, tlv.next)
    }

    @Test
    fun readsAMultiByteLength() {
        // 200 bytes forces the long form: 0x81 0xC8.
        val content = ByteArray(200) { it.toByte() }
        val bytes = DerFixture.tlv(DerFixture.TAG_OCTET_STRING, content)
        val reader = DerReader(bytes)
        val tlv = reader.read()

        assertEquals(200, tlv.contentEnd - tlv.contentStart)
        assertTrue(reader.content(tlv).contentEquals(content))
        assertEquals(bytes.size, tlv.next)
    }

    @Test
    fun readsAVeryLongLength() {
        val content = ByteArray(300) { 7 }
        val bytes = DerFixture.tlv(DerFixture.TAG_OCTET_STRING, content)
        val tlv = DerReader(bytes).read()

        assertEquals(300, tlv.contentEnd - tlv.contentStart)
    }

    @Test
    fun readsAHighTagNumber() {
        // The attestation fields are 704..719, which need the base-128 form.
        val bytes = DerFixture.tlv(704, byteArrayOf(9), tagClass = DerFixture.CLASS_CONTEXT)
        val tlv = DerReader(bytes).read()

        assertEquals(704, tlv.tagNumber)
        assertEquals(DerFixture.CLASS_CONTEXT, tlv.tagClass)
    }

    @Test
    fun readsEveryAttestationTagNumber() {
        for (tag in listOf(704, 705, 706, 709, 710, 711, 712, 716, 717, 718, 719)) {
            val bytes = DerFixture.tlv(tag, byteArrayOf(1), tagClass = DerFixture.CLASS_CONTEXT)
            assertEquals("tag $tag must round-trip", tag, DerReader(bytes).read().tagNumber)
        }
    }

    @Test
    fun tracksTheConstructedBit() {
        val sequence = DerFixture.sequence(DerFixture.integer(1))
        assertTrue(DerReader(sequence).read().constructed)

        val primitive = DerFixture.integer(1)
        assertFalse(DerReader(primitive).read().constructed)
    }

    // --- primitive values ----------------------------------------------------

    @Test
    fun decodesIntegers() {
        assertEquals(BigInteger.valueOf(3), DerReader(DerFixture.integer(3)).let {
            val tlv = it.read(); it.integer(tlv)
        })
        assertEquals(300, DerReader(DerFixture.integer(300)).let {
            val tlv = it.read(); it.intValue(tlv)
        })
    }

    @Test
    fun keepsLargeIntegersPositive() {
        // 0x80 would read as negative without the leading zero octet.
        val reader = DerReader(DerFixture.integer(128))
        assertEquals(128, reader.intValue(reader.read()))
    }

    @Test
    fun decodesEnumeratedValues() {
        val reader = DerReader(DerFixture.enumerated(2))
        assertEquals(2, reader.enumerated(reader.read()))
    }

    @Test
    fun decodesBooleans() {
        val truthy = DerReader(DerFixture.boolean(true))
        assertTrue(truthy.boolean(truthy.read()))

        val falsy = DerReader(DerFixture.boolean(false))
        assertFalse(falsy.boolean(falsy.read()))
    }

    @Test
    fun returnsRawContentBytes() {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val reader = DerReader(DerFixture.octetString(payload))
        assertTrue(reader.content(reader.read()).contentEquals(payload))
    }

    @Test
    fun readsUtf8Strings() {
        val reader = DerReader(DerFixture.utf8("google"))
        assertEquals("google", String(reader.content(reader.read())))
    }

    // --- OIDs ----------------------------------------------------------------

    @Test
    fun decodesTheAttestationOid() {
        val bytes = DerFixture.oid(1, 3, 6, 1, 4, 1, 11129, 2, 1, 17)
        val reader = DerReader(bytes)

        assertEquals(AttestationParser.ATTESTATION_OID, reader.oid(reader.read()))
    }

    @Test
    fun decodesAnOidWithATwoByteFirstSubidentifier() {
        // 2.999 encodes as 40 * 2 + 999 = 1079, which does not fit in one octet.
        // Splitting the raw first byte with /40 and %40 yields "3.16" instead.
        val bytes = DerFixture.oid(2, 999)
        val reader = DerReader(bytes)

        assertEquals("2.999", reader.oid(reader.read()))
    }

    @Test
    fun decodesTheLargestFirstArcCorrectly() {
        // 2.100 = 180 = 0xB4: the naive split would report arc 4.
        val bytes = DerFixture.oid(2, 100)
        val reader = DerReader(bytes)

        assertEquals("2.100", reader.oid(reader.read()))
    }

    @Test
    fun decodesSingleArcPairOids() {
        val zero = DerReader(DerFixture.oid(0, 0))
        assertEquals("0.0", zero.oid(zero.read()))

        val one = DerReader(DerFixture.oid(1, 39))
        assertEquals("1.39", one.oid(one.read()))
    }

    @Test
    fun emptyOidContentIsEmpty() {
        val reader = DerReader(DerFixture.tlv(DerFixture.TAG_OID, ByteArray(0)))
        assertEquals("", reader.oid(reader.read()))
    }

    // --- nesting and iteration ----------------------------------------------

    @Test
    fun readsNestedContentWithAScopedReader() {
        val bytes = DerFixture.sequence(
            DerFixture.integer(1),
            DerFixture.integer(2)
        )
        val outer = DerReader(bytes)
        val sequence = outer.read()
        val inner = outer.readerFor(sequence)

        val values = inner.readAll()
        assertEquals(2, values.size)
        assertEquals(1, inner.intValue(values[0]))
        assertEquals(2, inner.intValue(values[1]))
    }

    @Test
    fun readAllWalksEveryElement() {
        val bytes = DerFixture.sequence(
            DerFixture.integer(1),
            DerFixture.utf8("two"),
            DerFixture.boolean(true)
        )
        val reader = DerReader(bytes)
        val items = reader.readerFor(reader.read()).readAll()

        assertEquals(
            listOf(DerFixture.TAG_INTEGER, DerFixture.TAG_UTF8_STRING, DerFixture.TAG_BOOLEAN),
            items.map { it.tagNumber }
        )
    }

    @Test
    fun hasMoreReflectsTheRemainingInput() {
        val reader = DerReader(DerFixture.concat(DerFixture.integer(1), DerFixture.integer(2)))
        assertTrue(reader.hasMore())
        reader.advance(reader.read())
        assertTrue(reader.hasMore())
        reader.advance(reader.read())
        assertFalse(reader.hasMore())
    }

    @Test
    fun aScopedReaderStopsAtItsOwnBoundary() {
        val bytes = DerFixture.sequence(DerFixture.integer(1), DerFixture.integer(2))
        val outer = DerReader(bytes)
        val sequence = outer.read()
        val scoped = outer.readerFor(sequence)

        assertEquals(2, scoped.readAll().size)
        // read() does not consume the content, so the outer reader still has the
        // sequence body ahead of it until advance() is called.
        assertTrue(outer.hasMore())
        outer.advance(sequence)
        assertFalse(outer.hasMore())
    }

    // --- malformed input -----------------------------------------------------

    @Test
    fun rejectsReadingPastTheEnd() {
        val reader = DerReader(ByteArray(0))
        val failure = runCatching { reader.read() }.exceptionOrNull()
        assertTrue("empty input must fail cleanly", failure is IllegalArgumentException)
    }

    @Test
    fun rejectsATruncatedContent() {
        // Claims 10 content bytes but supplies 2.
        val bytes = byteArrayOf(DerFixture.TAG_OCTET_STRING.toByte(), 0x0A, 1, 2)
        val failure = runCatching { DerReader(bytes).read() }.exceptionOrNull()
        assertTrue("truncated content must fail cleanly", failure is IllegalArgumentException)
    }

    @Test
    fun aTruncatedTopLevelSequenceIsRejected() {
        val full = DerFixture.sequence(DerFixture.integer(1), DerFixture.integer(2))
        val truncated = full.copyOf(full.size - 1)

        val failure = runCatching {
            val reader = DerReader(truncated)
            reader.readerFor(reader.read()).readAll()
        }.exceptionOrNull()

        assertTrue("truncation must not be swallowed", failure is IllegalArgumentException)
    }

    @Test
    fun aNestedElementOverrunningItsParentIsRejected() {
        // SEQUENCE declaring 4 content bytes, holding an INTEGER that claims 5 but
        // only has 2 available. The outer length is satisfiable; the inner one is not.
        val bytes = byteArrayOf(
            0x30, 0x04,
            0x02, 0x05, 0x01, 0x02
        )
        val reader = DerReader(bytes)
        val sequence = reader.read()
        assertEquals(6, sequence.contentEnd)

        val failure = runCatching { reader.readerFor(sequence).readAll() }.exceptionOrNull()
        assertTrue(
            "an element overrunning its parent must fail, not read past it",
            failure is IllegalArgumentException
        )
    }
}
