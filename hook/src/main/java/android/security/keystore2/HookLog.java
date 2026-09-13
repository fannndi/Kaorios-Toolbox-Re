package android.security.keystore2;

import android.util.Log;

public final class HookLog {

    public static final String TAG = "KeyStoreHooks";

    private static final boolean VERBOSE = false;

    private HookLog() {
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
