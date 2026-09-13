package android.security.farewell;

public final class SecureFlagSpoofer {

    private SecureFlagSpoofer() {
    }

    public static boolean isSecureFlag() {
        try {
            return FarewellState.config().isSecureFlag();
        } catch (Throwable throwable) {
            FarewellLog.e("isSecureFlag", throwable);
            return false;
        }
    }
}
