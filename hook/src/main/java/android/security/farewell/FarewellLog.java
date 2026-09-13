package android.security.farewell;

import android.util.Log;

public final class FarewellLog {

    public static final String TAG = "FarewellHook";

    private static final boolean VERBOSE = false;

    private FarewellLog() {
    }

    public static void d(String message) {
        if (!VERBOSE) {
            return;
        }
        try {
            Log.d(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    public static void w(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String message, Throwable throwable) {
        try {
            Log.e(TAG, message, throwable);
        } catch (Throwable ignored) {
        }
    }
}
