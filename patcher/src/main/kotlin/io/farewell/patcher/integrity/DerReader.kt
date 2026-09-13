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
        val builder = StringBuilder()
        val first = bytes[0].toInt() and 0xFF
        builder.append(first / 40).append('.').append(first % 40)
        var value = 0L
        for (index in 1 until bytes.size) {
            val b = bytes[index].toInt() and 0xFF
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
