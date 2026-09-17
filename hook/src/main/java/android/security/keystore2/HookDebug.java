package android.security.keystore2;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * Structured diagnostics for ADB debugging.
 *
 * The question an engineer always asks on a device is "why did the spoof not take
 * effect?" — which needs the resolved config and the keybox state together. This
 * emits both as greppable `key=value` lines under the standard tag, so
 *
 *   adb shell setprop log.tag.KeyStoreHooks DEBUG
 *   adb logcat -s KeyStoreHooks
 *
 * shows the whole picture. [dump] is pure so it can be unit-tested without a
 * device; [fromConfig] gathers the live values on-device, and [logOnChange]
 * emits a dump only when the config blob actually changes (one dump per config
 * change, not one per call).
 */
final class HookDebug {

    /** What the hook is currently doing, pre-resolved so [dump] stays pure. */
    static final class Snapshot {
        boolean configPresent;
        boolean secureFlag;
        boolean hideDevStatus;
        boolean hideAppList;
        boolean keyboxSpoof;
        /** -1 when no keybox chain has been parsed yet. */
        int keyboxChainLength = -1;
        /** Lowercase hex of the leaf serial, or "" when unknown. */
        String keyboxLeafSerial = "";
        /** Whether the revocation status list has been loaded. */
        boolean revocationKnown;
        boolean keyboxRevoked;
    }

    private HookDebug() {
    }

    static String dump(Snapshot s) {
        StringBuilder b = new StringBuilder();
        line(b, "config.present", String.valueOf(s.configPresent));
        line(b, "flag.secureFlag", String.valueOf(s.secureFlag));
        line(b, "flag.hideDevStatus", String.valueOf(s.hideDevStatus));
        line(b, "flag.hideAppList", String.valueOf(s.hideAppList));
        line(b, "keybox.spoof", String.valueOf(s.keyboxSpoof));
        line(b, "keybox.chain", String.valueOf(s.keyboxChainLength));
        line(b, "keybox.leafSerial", s.keyboxLeafSerial.isEmpty() ? "unknown" : s.keyboxLeafSerial);
        line(b, "keybox.revocationKnown", String.valueOf(s.revocationKnown));
        line(b, "keybox.revoked", String.valueOf(s.keyboxRevoked));
        return b.toString();
    }

    private static void line(StringBuilder b, String key, String value) {
        if (b.length() > 0) {
            b.append('\n');
        }
        b.append("[farewell] ").append(key).append('=').append(value);
    }

    /** Gather the live values on-device. Diagnostics only; never on the key path. */
    static Snapshot fromConfig(HookConfig config) {
        Snapshot s = new Snapshot();
        if (config == null) {
            return s;
        }
        s.configPresent = true;
        s.secureFlag = config.isSecureFlag();
        s.hideDevStatus = config.isHideDevStatus();
        s.hideAppList = config.isHideAppList();
        s.keyboxSpoof = config.isKeyboxSpoof();

        Certificate[] chain = KeyboxEngine.cachedChain();
        if (chain != null && chain.length > 0) {
            s.keyboxChainLength = chain.length;
            if (chain[0] instanceof X509Certificate) {
                s.keyboxLeafSerial =
                        ((X509Certificate) chain[0]).getSerialNumber().toString(16).toLowerCase();
            }
        }
        KeyboxRevocation revocation = KeyboxEngine.revocation();
        s.revocationKnown = revocation != null && revocation.hasStatus();
        s.keyboxRevoked = KeyboxEngine.isCachedChainRevoked();
        return s;
    }

    private static volatile String sLastRaw;

    /** Emit a dump when the config blob changes. Verbose-gated, so quiet by default. */
    static void logOnChange(String raw, HookConfig config) {
        if (raw == null || raw.isEmpty() || raw.equals(sLastRaw)) {
            return;
        }
        sLastRaw = raw;
        HookLog.d("config changed, state dump:\n" + dump(fromConfig(config)));
    }
}
