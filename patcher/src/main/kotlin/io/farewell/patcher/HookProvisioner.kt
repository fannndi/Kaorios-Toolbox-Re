package io.farewell.patcher

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Transport for a hook dex that never touches the flashable zip.
 *
 * The patched framework can bootstrap a hook out of `Settings.Global` — the
 * channel `adb shell settings put` can write with no root — so a hook update
 * becomes one `adb` command instead of a reflash (the technique Farewell-PIF
 * proved with its hot-install path). This is the tooling half: split the dex
 * into chunks, hash them, and hand back the exact commands to provision, plus
 * the decoder and its integrity check.
 *
 * Layout, camouflaged as an ordinary system key family:
 *
 * ```
 * sys_perf_dex_meta   <count>:<sha256 of the dex>:<base64 XOR key>
 * sys_perf_dex_0      base64( chunk XOR key )
 * sys_perf_dex_1      ...
 * ```
 *
 * The XOR key travels in the meta row, so the transport is self-contained (no
 * shared constant to drift between the app, the hook and the CLI) and a
 * `settings list global` never shows raw dex bytes. [decode] verifies the chunk
 * count and the SHA-256 before returning anything, so a partial or tampered
 * provision can never be loaded.
 *
 * The on-device loader that reads these keys back and feeds an
 * `InMemoryDexClassLoader` is the remaining half; until it ships, the bootstrap
 * in `framework.jar` still loads the dex injected as `classesN.dex`.
 */
object HookProvisioner {

    const val META_KEY = "sys_perf_dex_meta"

    /** Kept well under the Settings row size limit (~91 KB on AOSP). */
    const val CHUNK_SIZE = 60_000

    fun chunkKey(index: Int) = "sys_perf_dex_$index"

    data class Provision(
        /** `<count>:<sha256hex>:<base64 xor key>` — written to [META_KEY]. */
        val meta: String,
        /** Base64 payloads in order; chunk `i` belongs to [chunkKey]`(i)`. */
        val chunks: List<String>,
    ) {
        val chunkCount: Int get() = chunks.size
        val sha256: String get() = meta.split(':')[1]
    }

    fun encode(dex: ByteArray, chunkSize: Int = CHUNK_SIZE): Provision {
        require(dex.isNotEmpty()) { "refusing to provision an empty dex" }
        require(chunkSize > 0) { "chunk size must be positive" }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < dex.size) {
            val length = minOf(chunkSize, dex.size - offset)
            val chunk = ByteArray(length) { index -> (dex[offset + index].toInt() xor key[index % key.size].toInt()).toByte() }
            chunks += Base64.getEncoder().encodeToString(chunk)
            offset += length
        }
        val meta = "${chunks.size}:${sha256(dex)}:${Base64.getEncoder().encodeToString(key)}"
        return Provision(meta, chunks)
    }

    /**
     * Rebuild the dex from provisioned values.
     *
     * Returns null — never a partial dex — when the meta is malformed, a chunk
     * is missing or not base64, or the reassembled bytes do not match the
     * recorded SHA-256.
     */
    fun decode(meta: String?, values: Map<String, String?>): ByteArray? {
        if (meta.isNullOrBlank()) return null
        return try {
            val parts = meta.split(':')
            if (parts.size != 3) return null
            val count = parts[0].toIntOrNull() ?: return null
            val expectedHash = parts[1].lowercase()
            if (count <= 0 || parts[1].length != 64) return null
            val key = Base64.getDecoder().decode(parts[2])
            if (key.isEmpty()) return null
            val out = java.io.ByteArrayOutputStream()
            for (index in 0 until count) {
                val value = values[chunkKey(index)] ?: return null
                val chunk = Base64.getDecoder().decode(value)
                out.write(ByteArray(chunk.size) { i -> (chunk[i].toInt() xor key[i % key.size].toInt()).toByte() })
            }
            val dex = out.toByteArray()
            if (sha256(dex) != expectedHash) return null
            dex
        } catch (throwable: Throwable) {
            null
        }
    }

    /** `settings put global …` lines that provision [provision] over adb. */
    fun settingsCommands(provision: Provision): List<String> {
        val commands = mutableListOf<String>()
        commands += settingsPut(META_KEY, provision.meta)
        for ((index, chunk) in provision.chunks.withIndex()) {
            commands += settingsPut(chunkKey(index), chunk)
        }
        return commands
    }

    /** Removes every provisioned row, for a clean handoff back to the zip dex. */
    fun cleanupCommands(chunkCount: Int): List<String> {
        val commands = mutableListOf<String>()
        for (index in 0 until chunkCount) {
            commands += "settings delete global ${chunkKey(index)}"
        }
        commands += "settings delete global $META_KEY"
        return commands
    }

    private fun settingsPut(key: String, value: String): String =
        "settings put global $key '${value.replace("'", "'\\''")}'"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
