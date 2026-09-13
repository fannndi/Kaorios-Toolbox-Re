package android.security.keystore2;

public final class SecureFlagSpoofer {

    private SecureFlagSpoofer() {
    }

    public static boolean isSecureFlag() {
        try {
            return HookState.config().isSecureFlag();
        } catch (Throwable throwable) {
            HookLog.e("isSecureFlag", throwable);
            return false;
        }
    }
}
