package io.farewell.patcher

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile

object DexSupport {

    fun load(bytes: ByteArray): DexBackedDexFile {
        return DexBackedDexFile(Opcodes.getDefault(), normalizeVersion(bytes))
    }

    fun normalizeVersion(bytes: ByteArray): ByteArray {
        if (bytes.size < 8) return bytes
        if (bytes[0] != 'd'.code.toByte() || bytes[1] != 'e'.code.toByte() || bytes[2] != 'x'.code.toByte()) {
            return bytes
        }
        val version = String(bytes, 4, 3, Charsets.US_ASCII)
        if (version <= "039") return bytes
        val copy = bytes.copyOf()
        copy[4] = '0'.code.toByte()
        copy[5] = '3'.code.toByte()
        copy[6] = '9'.code.toByte()
        FarewellLog("normalized dex version $version to 039")
        return copy
    }

    private fun FarewellLog(message: String) {
        println("  [dex] $message")
    }
}
