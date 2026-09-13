package android.security.keystore2;

public final class PropSpoofer {

    private PropSpoofer() {
    }

    public static String override(String key) {
        try {
            if (key == null || HookState.isInternalCall()) {
                return null;
            }
            return HookState.config().propOverride(HookState.currentPackage(), key);
        } catch (Throwable throwable) {
            HookLog.e("propOverride", throwable);
            return null;
        }
    }

    public static String filter(String key, String value) {
        try {
            String override = override(key);
            return override != null ? override : value;
        } catch (Throwable throwable) {
            HookLog.e("filterSystemProperty", throwable);
            return value;
        }
    }
}
