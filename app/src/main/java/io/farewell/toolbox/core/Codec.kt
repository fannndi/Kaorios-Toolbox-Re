package io.farewell.toolbox.core

import android.util.Base64

object Codec {

    private val KEY = byteArrayOf(
        0x4B, 0x53, 0x32, 0x7A, 0x11, 0x9C.toByte(), 0x5E, 0x27,
        0xA3.toByte(), 0x6D, 0x38, 0xF1.toByte(), 0x72, 0x0B, 0xD4.toByte(), 0x67
    )

    fun encode(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        for (index in bytes.indices) {
            bytes[index] = (bytes[index].toInt() xor KEY[index % KEY.size].toInt()).toByte()
        }
        return "k2:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
