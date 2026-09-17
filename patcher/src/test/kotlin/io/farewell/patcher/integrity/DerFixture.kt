package io.farewell.patcher.integrity

import java.io.ByteArrayOutputStream

/**
 * Minimal DER *encoder*, used only by tests.
 *
 * [DerReader] is a decoder, so testing it against hand-written byte literals is
 * both unreadable and easy to get wrong in the same way as the code under test.
 * Building the input from the same structural rules lets a test say what it means:
 * "a SEQUENCE containing a BOOLEAN and an ENUMERATED".
 *
 * Encodes only what the attestation parser needs — definite lengths, single-byte
 * and multi-byte tags, and the universal types it reads.
 */
object DerFixture {

    const val CLASS_UNIVERSAL = 0
    const val CLASS_CONTEXT = 2

    const val TAG_BOOLEAN = 1
    const val TAG_INTEGER = 2
    const val TAG_BIT_STRING = 3
    const val TAG_OCTET_STRING = 4
    const val TAG_OID = 6
    const val TAG_ENUMERATED = 10
    const val TAG_UTF8_STRING = 12
    const val TAG_UTC_TIME = 23
    const val TAG_SEQUENCE = 16
    const val TAG_SET = 17

    /** One identifier octet plus, for tags >= 31, base-128 continuation octets. */
    fun tlv(
        tagNumber: Int,
        content: ByteArray,
        tagClass: Int = CLASS_UNIVERSAL,
        constructed: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            (tagClass shl 6) or
                (if (constructed) 0x20 else 0) or
                (if (tagNumber < 0x1F) tagNumber else 0x1F)
        )
        if (tagNumber >= 0x1F) {
            val octets = mutableListOf<Int>()
            var value = tagNumber
            octets.add(0, value and 0x7F)
            value = value shr 7
            while (value > 0) {
                octets.add(0, (value and 0x7F) or 0x80)
                value = value shr 7
            }
            octets.forEach { out.write(it) }
        }
        val length = content.size
        if (length < 0x80) {
            out.write(length)
        } else {
            val octets = mutableListOf<Int>()
            var value = length
            while (value > 0) {
                octets.add(0, value and 0xFF)
                value = value shr 8
            }
            out.write(0x80 or octets.size)
            octets.forEach { out.write(it) }
        }
        out.write(content)
        return out.toByteArray()
    }

    /** Minimal two's-complement content for a non-negative value. */
    private fun unsignedContent(value: Int): ByteArray {
        require(value >= 0) { "DER fixture only encodes non-negative integers" }
        val octets = mutableListOf<Int>()
        var remaining = value
        do {
            octets.add(0, remaining and 0xFF)
            remaining = remaining shr 8
        } while (remaining > 0)
        if ((octets[0] and 0x80) != 0) octets.add(0, 0)
        return ByteArray(octets.size) { octets[it].toByte() }
    }

    fun boolean(value: Boolean): ByteArray =
        tlv(TAG_BOOLEAN, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun integer(value: Int): ByteArray = tlv(TAG_INTEGER, unsignedContent(value))

    fun enumerated(value: Int): ByteArray = tlv(TAG_ENUMERATED, unsignedContent(value))

    fun octetString(bytes: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, bytes)

    /** A BIT STRING with zero unused bits — how certificates carry keys and signatures. */
    fun bitString(bytes: ByteArray): ByteArray =
        tlv(TAG_BIT_STRING, byteArrayOf(0) + bytes)

    /** UTCTime as `YYMMDDHHMMSSZ`. */
    fun utcTime(value: String): ByteArray = tlv(TAG_UTC_TIME, value.toByteArray(Charsets.US_ASCII))

    fun utf8(value: String): ByteArray = tlv(TAG_UTF8_STRING, value.toByteArray(Charsets.UTF_8))

    fun sequence(vararg parts: ByteArray): ByteArray =
        tlv(TAG_SEQUENCE, concat(*parts), constructed = true)

    fun setOf(vararg parts: ByteArray): ByteArray =
        tlv(TAG_SET, concat(*parts), constructed = true)

    /** A context-specific tag such as the attestation fields 704..719. */
    fun context(number: Int, content: ByteArray, constructed: Boolean = false): ByteArray =
        tlv(number, content, tagClass = CLASS_CONTEXT, constructed = constructed)

    /**
     * An EXPLICIT context tag around a SEQUENCE, which is how the attestation
     * schema tags its fields: `rootOfTrust [704] EXPLICIT RootOfTrust`. The tag's
     * content is the whole SEQUENCE TLV, not just its contents.
     */
    fun contextSequence(number: Int, vararg parts: ByteArray): ByteArray =
        context(number, sequence(*parts), constructed = true)

    /** OID from dotted arcs, e.g. oid(1, 3, 6, 1, 4, 1, 11129, 2, 1, 17). */
    fun oid(vararg arcs: Int): ByteArray {
        require(arcs.size >= 2) { "an OID needs at least two arcs" }
        require(arcs[0] in 0..2) { "the first arc is 0, 1 or 2" }
        require(arcs[1] < 40 || arcs[0] == 2) { "arcs[1] must be < 40 unless arcs[0] == 2" }
        val out = ByteArrayOutputStream()
        writeBase128(out, arcs[0] * 40 + arcs[1])
        for (index in 2 until arcs.size) writeBase128(out, arcs[index])
        return tlv(TAG_OID, out.toByteArray())
    }

    private fun writeBase128(out: ByteArrayOutputStream, value: Int) {
        val octets = mutableListOf<Int>()
        var remaining = value
        octets.add(0, remaining and 0x7F)
        remaining = remaining shr 7
        while (remaining > 0) {
            octets.add(0, (remaining and 0x7F) or 0x80)
            remaining = remaining shr 7
        }
        octets.forEach { out.write(it) }
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
