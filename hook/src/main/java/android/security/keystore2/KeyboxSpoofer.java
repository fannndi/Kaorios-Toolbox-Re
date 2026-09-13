package android.security.keystore2;

import java.security.KeyPair;
import java.security.cert.Certificate;

public final class KeyboxSpoofer {

    private KeyboxSpoofer() {
    }

    public static KeyPair generateSoftwareKeyPair(Object spi) {
        try {
            HookConfig config = HookState.config();
            if (config == null) {
                return null;
            }
            return KeyboxEngine.generateKeyPair(spi, config);
        } catch (Throwable throwable) {
            HookLog.e("generateSoftwareKeyPair", throwable);
            return null;
        }
    }

    public static Certificate[] maybeReplaceChain(Certificate[] chain) {
        try {
            HookConfig config = HookState.config();
            if (config == null) {
                return chain;
            }
            return KeyboxEngine.replaceChain(chain, config);
        } catch (Throwable throwable) {
            HookLog.e("maybeReplaceChain", throwable);
            return chain;
        }
    }
}
