package android.security.farewell;

import java.security.KeyPair;
import java.security.cert.Certificate;

public final class KeyboxEngine {

    private KeyboxEngine() {
    }

    public static KeyPair generateKeyPair(Object spi, FarewellConfig config) {
        return null;
    }

    public static Certificate[] replaceChain(Certificate[] chain, FarewellConfig config) {
        return chain;
    }
}
