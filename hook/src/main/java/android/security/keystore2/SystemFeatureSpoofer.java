package android.security.keystore2;

public final class SystemFeatureSpoofer {

    private SystemFeatureSpoofer() {
    }

    public static Boolean check(String feature, int version) {
        try {
            if (feature == null) {
                return null;
            }
            return HookState.config().featureState(feature);
        } catch (Throwable throwable) {
            HookLog.e("hasSystemFeature", throwable);
            return null;
        }
    }
}
