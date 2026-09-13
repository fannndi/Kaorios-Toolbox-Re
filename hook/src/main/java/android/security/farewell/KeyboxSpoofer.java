package android.security.farewell;

import java.security.KeyPair;
import java.security.cert.Certificate;

public final class KeyboxSpoofer {

    private KeyboxSpoofer() {
    }

    public static KeyPair generateSoftwareKeyPair(Object spi) {
        try {
            FarewellConfig config = FarewellState.config();
            if (config == null) {
                return null;
            }
            return KeyboxEngine.generateKeyPair(spi, config);
        } catch (Throwable throwable) {
            FarewellLog.e("generateSoftwareKeyPair", throwable);
            return null;
        }
    }

    public static Certificate[] maybeReplaceChain(Certificate[] chain) {
        try {
            FarewellConfig config = FarewellState.config();
            if (config == null) {
                return chain;
            }
            return KeyboxEngine.replaceChain(chain, config);
        } catch (Throwable throwable) {
            FarewellLog.e("maybeReplaceChain", throwable);
            return chain;
        }
    }
}
