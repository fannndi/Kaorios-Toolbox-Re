package android.security.keystore2;

public final class PropSpoofer {

    private PropSpoofer() {
    }

    public static String filter(String key, String value) {
        try {
            if (key == null || HookState.isInternalCall()) {
                return value;
            }
            String override = HookState.config().propOverride(HookState.currentPackage(), key);
            return override != null ? override : value;
        } catch (Throwable throwable) {
            HookLog.e("filterSystemProperty", throwable);
            return value;
        }
    }
}
