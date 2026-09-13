package android.security.farewell;

public final class PropSpoofer {

    private PropSpoofer() {
    }

    public static String filter(String key, String value) {
        try {
            if (key == null || FarewellState.isInternalCall()) {
                return value;
            }
            String override = FarewellState.config().propOverride(FarewellState.currentPackage(), key);
            return override != null ? override : value;
        } catch (Throwable throwable) {
            FarewellLog.e("filterSystemProperty", throwable);
            return value;
        }
    }
}
