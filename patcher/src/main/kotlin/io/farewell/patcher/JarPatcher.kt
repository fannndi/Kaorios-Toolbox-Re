package io.farewell.patcher

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object JarPatcher {

    private val DEX_PATTERN = Regex("^classes(\\d*)\\.dex$")

    fun patch(
        input: File,
        output: File,
        kind: JarKind,
        hookDex: ByteArray?,
        profile: PlatformProfile = PlatformProfiles.MODERN,
        log: (String) -> Unit = {}
    ): PatchReport {
        val engine = DexPatchEngine(kind, profile)
        val patchedDex = LinkedHashMap<String, ByteArray>()
        var dexCount = 0
        var alreadyPatched = false

        ZipFile(input).use { zip ->
            val entries = Collections.list(zip.entries())
            val dexNames = entries.map { it.name }.filter { DEX_PATTERN.matches(it) }

            for (name in dexNames) {
                val entry = zip.getEntry(name) ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val dexFile = DexSupport.load(bytes)
                if (dexFile.hasHookCalls()) {
                    alreadyPatched = true
                    continue
                }
                val temp = File.createTempFile("farewell", ".dex", output.parentFile)
                try {
                    engine.patchDexTo(dexFile, temp)
                    patchedDex[name] = temp.readBytes()
                } finally {
                    temp.delete()
                }
                dexCount++
            }

            if (alreadyPatched) {
                log("$kind already contains hook calls, keeping original dex files")
            }

            val maxIndex = dexNames.map { dexIndex(it) }.maxOrNull() ?: 0
            val hookName = if (hookDex != null && !alreadyPatched) "classes${maxIndex + 1}.dex" else null

            AlignedZipWriter(FileOutputStream(output)).use { writer ->
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val patched = patchedDex[entry.name]
                    if (patched != null) {
                        writer.write(entry.name, patched, store = true)
                    } else {
                        zip.getInputStream(entry).use { writer.writeStream(entry.name, it) }
                    }
                }
                if (hookName != null && hookDex != null) {
                    writer.write(hookName, hookDex, store = true)
                    log("injected $hookName")
                }
            }
        }

        return PatchReport(kind, dexCount, patchedDex.size, engine.outcomes())
    }

    private fun dexIndex(name: String): Int {
        val digits = DEX_PATTERN.matchEntire(name)?.groupValues?.get(1).orEmpty()
        return if (digits.isEmpty()) 1 else digits.toIntOrNull() ?: 1
    }
}

class AlignedZipWriter(output: OutputStream) : Closeable {

    private val counter = CountingOutputStream(output)
    private val zip = ZipOutputStream(counter)

    fun write(name: String, bytes: ByteArray, store: Boolean = false) {
        val entry = ZipEntry(name)
        if (store) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.crc = crc(bytes)
            alignmentExtra(name)?.let { entry.extra = it }
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun writeStream(name: String, source: InputStream) {
        zip.putNextEntry(ZipEntry(name))
        source.copyTo(zip)
        zip.closeEntry()
    }

    private fun alignmentExtra(name: String): ByteArray? {
        val nameLength = name.toByteArray(Charsets.UTF_8).size
        var extraLength = ((4 - ((counter.count + 30 + nameLength) % 4)) % 4).toInt()
        if (extraLength in 1..3) {
            extraLength += 4
        }
        if (extraLength == 0) {
            return null
        }
        val extra = ByteArray(extraLength)
        extra[0] = 0xFE.toByte()
        extra[1] = 0xCA.toByte()
        extra[2] = ((extraLength - 4) and 0xFF).toByte()
        extra[3] = (((extraLength - 4) shr 8) and 0xFF).toByte()
        return extra
    }

    override fun close() {
        zip.close()
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private fun crc(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
