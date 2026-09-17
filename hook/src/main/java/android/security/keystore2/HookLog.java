package android.security.keystore2;

import android.util.Log;

public final class HookLog {

    public static final String TAG = "KeyStoreHooks";

    /** Test seam: forces verbose on/off; null means ask the platform. */
    private static volatile Boolean sVerboseOverride;

    private HookLog() {
    }

    /**
     * Verbose logging is off unless it is switched on for a debug session:
     *
     *   adb shell setprop log.tag.KeyStoreHooks DEBUG
     *
     * That is the platform's own switch (Log.isLoggable), so it needs no rebuild
     * and survives until reboot. It used to be a hardcoded `false`, which meant
     * every debug line was silently discarded even with a device attached.
     */
    static boolean verbose() {
        Boolean override = sVerboseOverride;
        if (override != null) {
            return override.booleanValue();
        }
        try {
            return Log.isLoggable(TAG, Log.DEBUG);
        } catch (Throwable ignored) {
            // android.util.Log is a stub under the JVM test runtime.
            return false;
        }
    }

    /** Test-only: force the verbose switch without a device. */
    static void setVerboseForTest(Boolean value) {
        sVerboseOverride = value;
    }

    public static void d(String message) {
        if (!verbose()) {
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
