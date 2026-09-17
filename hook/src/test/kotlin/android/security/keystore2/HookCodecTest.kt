package android.security.keystore2

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * `HookCodec` is the entire app↔hook config channel (the `sys_keystore_cfg` blob):
 * the app encodes with `k2:` base64+XOR, the hook decodes it. A mismatch in the
 * key or the prefix silently breaks every spoof (invariant #6) — yet until now
 * this was only cross-checked by the Python `inspect_config.py` tool, never by a
 * unit test in the build. `decode()` calls `android.util.Base64`, which is a stub
 * that throws on the JVM, so this injects the real `java.util.Base64` via the
 * `Decoder` seam and pins the round trip, the no-prefix passthrough, and the
 * best-effort behaviour on garbage.
 */
class HookCodecTest {

    // Must match HookCodec.KEY exactly — if the key ever changes, this round trip
    // fails, which is the alert we want (the app and hook keys must stay identical).
    private val KEY = byteArrayOf(
        0x4B, 0x53, 0x32, 0x7A, 0x11, 0x9C.toByte(), 0x5E, 0x27,
        0xA3.toByte(), 0x6D, 0x38, 0xF1.toByte(), 0x72, 0x0B, 0xD4.toByte(), 0x67
    )

    @After
    fun reset() {
        // The decoder is static; don't leak the test decoder into other tests.
        HookCodec.setDecoderForTest(null)
    }

    private fun encode(plain: String): String {
        val xored = plain.toByteArray(StandardCharsets.UTF_8)
            .mapIndexed { i, b -> (b.toInt() xor KEY[i % KEY.size].toInt()).toByte() }
            .toByteArray()
        return "k2:" + java.util.Base64.getEncoder().encodeToString(xored)
    }

    @Test
    fun roundTripsTheK2ConfigBlob() {
        HookCodec.setDecoderForTest(object : HookCodec.Decoder {
            override fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
        })
        val plain = """{"flags":true,"build":"Pixel","props":{"ro.product.model":"Pixel 8 Pro"}}"""
        assertEquals(plain, HookCodec.decode(encode(plain)))
    }

    @Test
    fun passesThroughInputWithoutThePrefix() {
        // A value with no "k2:" prefix is config that wasn't encoded; decode must
        // hand it back untouched rather than corrupting it.
        assertEquals("plain settings string", HookCodec.decode("plain settings string"))
        assertEquals("k2abc-no-colon", HookCodec.decode("k2abc-no-colon"))
    }

    @Test
    fun garbageAfterThePrefixIsBestEffort() {
        HookCodec.setDecoderForTest(object : HookCodec.Decoder {
            override fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
        })
        // "k2:" present but the body is not valid Base64 -> decode throws, is swallowed,
        // and the raw value is returned so the key path is never broken.
        assertEquals("k2:!!!not-valid", HookCodec.decode("k2:!!!not-valid"))
    }
}
