package io.farewell.patcher.integrity

import java.math.BigInteger

class DerReader(private val data: ByteArray, private var offset: Int = 0, private val end: Int = data.size) {

    data class Tlv(
        val tagClass: Int,
        val constructed: Boolean,
        val tagNumber: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val next: Int
    )

    fun hasMore(): Boolean = offset < end

    fun read(): Tlv {
        require(offset < end) { "DER: out of bounds" }
        val first = data[offset].toInt() and 0xFF
        val tagClass = first shr 6
        val constructed = (first and 0x20) != 0
        var tagNumber = first and 0x1F
        offset++
        if (tagNumber == 0x1F) {
            tagNumber = 0
            while (true) {
                val b = data[offset].toInt() and 0xFF
                offset++
                tagNumber = (tagNumber shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
        }
        var length = data[offset].toInt() and 0xFF
        offset++
        if ((length and 0x80) != 0) {
            val count = length and 0x7F
            length = 0
            repeat(count) {
                length = (length shl 8) or (data[offset].toInt() and 0xFF)
                offset++
            }
        }
        val contentStart = offset
        val contentEnd = contentStart + length
        require(contentEnd <= end) { "DER: truncated content" }
        return Tlv(tagClass, constructed, tagNumber, contentStart, contentEnd, contentEnd)
    }

    fun readerFor(tlv: Tlv): DerReader = DerReader(data, tlv.contentStart, tlv.contentEnd)

    fun content(tlv: Tlv): ByteArray = data.copyOfRange(tlv.contentStart, tlv.contentEnd)

    fun integer(tlv: Tlv): BigInteger = BigInteger(content(tlv))

    fun intValue(tlv: Tlv): Int = integer(tlv).toInt()

    fun enumerated(tlv: Tlv): Int = integer(tlv).toInt()

    fun boolean(tlv: Tlv): Boolean = content(tlv).any { it.toInt() != 0 }

    fun oid(tlv: Tlv): String {
        val bytes = content(tlv)
        if (bytes.isEmpty()) return ""

        // The first subidentifier is itself a base-128 value and may span several
        // bytes; it encodes `40 * arc0 + arc1`. arc1 only fits in `value % 40` while
        // arc0 is 0 or 1 (DER caps arc1 at 39 there), so the split has to be done on
        // the decoded value rather than on the raw first byte.
        var index = 0
        var first = 0L
        while (index < bytes.size) {
            val b = bytes[index].toInt() and 0xFF
            index++
            first = (first shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) == 0) break
        }
        val arc0 = when {
            first < 40 -> 0L
            first < 80 -> 1L
            else -> 2L
        }

        val builder = StringBuilder()
        builder.append(arc0).append('.').append(first - arc0 * 40)
        var value = 0L
        while (index < bytes.size) {
            val b = bytes[index].toInt() and 0xFF
            index++
            value = (value shl 7) or (b and 0x7F).toLong()
            if ((b and 0x80) == 0) {
                builder.append('.').append(value)
                value = 0
            }
        }
        return builder.toString()
    }

    fun advance(tlv: Tlv) {
        offset = tlv.next
    }
}
