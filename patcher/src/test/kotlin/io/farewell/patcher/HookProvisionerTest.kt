package io.farewell.patcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * [HookProvisioner] is the transport for a hook dex provisioned over
 * `adb shell settings put` — the no-root update path. A bug here is silent: a
 * truncated or tampered provision that loads anyway, or a dex that never matches
 * its recorded hash, would leave the device serving a half hook with no error
 * anywhere. The tests pin the round trip, every rejection path, and the fact
 * that the payload is not the dex in disguise.
 */
class HookProvisionerTest {

    private fun dex(size: Int = 150_000): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }.also {
            // A recognizable needle to prove chunks are not plaintext dex.
            val needle = "FAREWELL-NEEDLE".toByteArray()
            needle.copyInto(it, 0, 0, minOf(needle.size, it.size))
        }

    private fun values(provision: HookProvisioner.Provision): Map<String, String?> =
        provision.chunks.withIndex().associate { (index, chunk) -> HookProvisioner.chunkKey(index) to chunk }

    @Test
    fun roundTripsADexAcrossSeveralChunks() {
        val original = dex()
        val provision = HookProvisioner.encode(original)
        assertEquals("150000 bytes at 60000 per chunk", 3, provision.chunkCount)
        val decoded = HookProvisioner.decode(provision.meta, values(provision))
        assertNotNull(decoded)
        assertTrue(original.contentEquals(decoded))
    }

    @Test
    fun theMetaRecordsTheCountAndTheRealHash() {
        val original = dex(1000)
        val provision = HookProvisioner.encode(original)
        val parts = provision.meta.split(':')
        assertEquals(3, parts.size)
        assertEquals(1, parts[0].toInt())
        val expected = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        assertEquals(expected, provision.sha256)
    }

    @Test
    fun aTamperedChunkIsRejectedNotLoaded() {
        val provision = HookProvisioner.encode(dex(1000))
        val tampered = values(provision).toMutableMap()
        val chunk = tampered[HookProvisioner.chunkKey(0)]!!
        tampered[HookProvisioner.chunkKey(0)] = chunk.replaceFirst(chunk[10], if (chunk[10] == 'A') 'B' else 'A')
        assertNull("a changed byte must not load", HookProvisioner.decode(provision.meta, tampered))
    }

    @Test
    fun aMissingChunkIsRejected() {
        val provision = HookProvisioner.encode(dex())
        val incomplete = values(provision).filterKeys { it != HookProvisioner.chunkKey(1) }
        assertNull(HookProvisioner.decode(provision.meta, incomplete))
    }

    @Test
    fun malformedMetaIsRejected() {
        assertNull(HookProvisioner.decode(null, emptyMap()))
        assertNull(HookProvisioner.decode("", emptyMap()))
        assertNull(HookProvisioner.decode("not-a-meta", emptyMap()))
        assertNull(HookProvisioner.decode("0:${"0".repeat(64)}:AAAA", emptyMap()))
        assertNull(HookProvisioner.decode("1:short:AAAA", emptyMap()))
    }

    @Test
    fun thePayloadIsNotTheDexInDisguise() {
        val original = dex(70_000)
        val provision = HookProvisioner.encode(original)
        val needle = String(original.copyOfRange(0, 15), Charsets.US_ASCII)
        for (chunk in provision.chunks) {
            assertFalse("chunk must not contain plaintext dex", chunk.contains(needle))
        }
        // And two runs of the same dex differ: the XOR key is random per provision.
        assertFalse(provision.chunks[0] == HookProvisioner.encode(original).chunks[0])
    }

    @Test
    fun settingsCommandsAreShellSafeAndOrdered() {
        val provision = HookProvisioner.encode(dex(10))
        val commands = HookProvisioner.settingsCommands(provision)
        assertEquals(2, commands.size)
        assertTrue(commands[0].startsWith("settings put global ${HookProvisioner.META_KEY} '"))
        assertTrue(commands[1].contains(HookProvisioner.chunkKey(0)))
        assertTrue("value wrapped in single quotes", commands[0].endsWith("'"))

        // A value containing a quote must be escaped the shell way.
        val evil = HookProvisioner.Provision("1:${"a".repeat(64)}:AAAA", listOf("it's"))
        val evilCommand = HookProvisioner.settingsCommands(evil).last()
        assertTrue(evilCommand.contains("'it'\\''s'"))
    }

    @Test
    fun cleanupRemovesEveryRowTheProvisionWrote() {
        val provision = HookProvisioner.encode(dex())
        val cleanup = HookProvisioner.cleanupCommands(provision.chunkCount)
        assertEquals(provision.chunkCount + 1, cleanup.size)
        assertTrue(cleanup.any { it.contains(HookProvisioner.META_KEY) })
        for (index in 0 until provision.chunkCount) {
            assertTrue(cleanup.any { it.contains(HookProvisioner.chunkKey(index)) })
        }
        assertTrue(cleanup.all { it.startsWith("settings delete global ") })
    }
}
