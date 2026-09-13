package android.security.keystore2;

public final class HookCodec {

    private static final byte[] KEY = {
            0x4B, 0x53, 0x32, 0x7A, 0x11, (byte) 0x9C, 0x5E, 0x27,
            (byte) 0xA3, 0x6D, 0x38, (byte) 0xF1, 0x72, 0x0B, (byte) 0xD4, 0x67
    };

    private static final String PREFIX = "k2:";

    private HookCodec() {
    }

    public static String decode(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) {
            return raw;
        }
        try {
            byte[] data = android.util.Base64.decode(raw.substring(PREFIX.length()), android.util.Base64.DEFAULT);
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (data[i] ^ KEY[i % KEY.length]);
            }
            return new String(data, "UTF-8");
        } catch (Throwable throwable) {
            HookLog.e("codec decode", throwable);
            return raw;
        }
    }
}
