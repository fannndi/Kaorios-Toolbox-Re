package android.security.farewell;

public final class SystemFeatureSpoofer {

    private SystemFeatureSpoofer() {
    }

    public static Boolean check(String feature, int version) {
        try {
            if (feature == null) {
                return null;
            }
            return FarewellState.config().featureState(feature);
        } catch (Throwable throwable) {
            FarewellLog.e("hasSystemFeature", throwable);
            return null;
        }
    }
}
