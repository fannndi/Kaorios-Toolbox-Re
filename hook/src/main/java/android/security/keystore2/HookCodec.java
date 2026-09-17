package android.security.keystore2;

public final class HookCodec {

    private static final byte[] KEY = {
            0x4B, 0x53, 0x32, 0x7A, 0x11, (byte) 0x9C, 0x5E, 0x27,
            (byte) 0xA3, 0x6D, 0x38, (byte) 0xF1, 0x72, 0x0B, (byte) 0xD4, 0x67
    };

    private static final String PREFIX = "k2:";

    /**
     * Test seam: supplies the Base64 decoder. The production path leaves this null
     * and uses android.util.Base64 (which is a stub that throws under the JVM test
     * runtime), so tests inject the real java.util.Base64 to exercise decode()
     * off-device. Declared as an interface, not a lambda, to honour the hook's
     * Java 11 / no-lambda rule.
     */
    interface Decoder {
        byte[] decode(String s) throws Exception;
    }

    private static Decoder sDecoder;

    private HookCodec() {
    }

    /** Test-only: replace the Base64 decoder with a canned one. */
    static void setDecoderForTest(Decoder decoder) {
        sDecoder = decoder;
    }

    public static String decode(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) {
            return raw;
        }
        try {
            byte[] data = decodeBase64(raw.substring(PREFIX.length()));
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
            }
            return new String(data, "UTF-8");
        } catch (Throwable throwable) {
            HookLog.e("codec decode", throwable);
            return raw;
        }
    }

    /** The Base64 decoder. Uses the injected decoder when present, else android.util.Base64. */
    private static byte[] decodeBase64(String s) throws Exception {
        return sDecoder != null ? sDecoder.decode(s) : android.util.Base64.decode(s, android.util.Base64.DEFAULT);
    }
}
