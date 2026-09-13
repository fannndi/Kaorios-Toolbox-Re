package android.security.farewell;

import android.content.ContentResolver;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class SettingsSpoofer {

    private static final Set<String> DEV_KEYS = new HashSet<>(Arrays.asList(
            "adb_enabled",
            "development_settings_enabled"
    ));

    private static final Set<String> TABLES = new HashSet<>(Arrays.asList(
            "global",
            "secure",
            "system"
    ));

    private SettingsSpoofer() {
    }

    public static boolean shouldHideDevStatusFromNameValueCache(ContentResolver resolver, String name, int userId) {
        try {
            if (name == null || !DEV_KEYS.contains(name) || FarewellState.isInternalCall()) {
                return false;
            }
            FarewellConfig config = FarewellState.config();
            if (!config.isHideDevStatus()) {
                return false;
            }
            return !isExemptPackage(FarewellState.currentPackage());
        } catch (Throwable throwable) {
            FarewellLog.e("hideDevStatus", throwable);
            return false;
        }
    }

    public static boolean shouldRemoveSetting(ContentResolver resolver, String namespace, String name) {
        try {
            if (namespace == null || name == null) {
                return false;
            }
            FarewellProbe.noteRemove(namespace, name);
            if (FarewellProbe.isProbeName(name) || FarewellState.isInternalCall()) {
                return false;
            }
            if (!TABLES.contains(namespace)) {
                return false;
            }
            return FarewellState.config().shouldRemove(namespace, name);
        } catch (Throwable throwable) {
            FarewellLog.e("shouldRemoveSetting", throwable);
            return false;
        }
    }

    public static String filterSettingValue(ContentResolver resolver, String namespace, String name, String value) {
        try {
            if (namespace == null || name == null) {
                return value;
            }
            if (FarewellProbe.isProbeName(name)) {
                return FarewellProbe.answer(resolver, namespace, name);
            }
            if (FarewellState.isInternalCall() || !TABLES.contains(namespace)) {
                return value;
            }
            FarewellConfig config = FarewellState.config();
            if (config.shouldRemove(namespace, name)) {
                return value;
            }
            int uid = FarewellState.callingUid();
            if (FarewellState.isPrivilegedUid(uid)) {
                return value;
            }
            String packageName = FarewellState.packageForUid(uid);
            if (packageName == null || isExemptPackage(packageName)) {
                return value;
            }
            String spoofed = config.settingValue(packageName, namespace, name);
            return spoofed != null ? spoofed : value;
        } catch (Throwable throwable) {
            FarewellLog.e("filterSettingValue", throwable);
            return value;
        }
    }

    public static String filterSettingValueAuto(String namespace, String name, String value) {
        try {
            if (namespace == null || name == null) {
                return value;
            }
            if (FarewellProbe.isProbeName(name)) {
                android.content.Context context = FarewellState.context();
                ContentResolver resolver = context == null ? null : context.getContentResolver();
                return FarewellProbe.answer(resolver, namespace, name);
            }
            if (FarewellState.isInternalCall() || !TABLES.contains(namespace)) {
                return value;
            }
            FarewellConfig config = FarewellState.config();
            if (config.shouldRemove(namespace, name)) {
                return null;
            }
            int uid = FarewellState.callingUid();
            if (FarewellState.isPrivilegedUid(uid)) {
                return value;
            }
            String packageName = FarewellState.packageForUid(uid);
            if (packageName == null || isExemptPackage(packageName)) {
                return value;
            }
            String spoofed = config.settingValue(packageName, namespace, name);
            return spoofed != null ? spoofed : value;
        } catch (Throwable throwable) {
            FarewellLog.e("filterSettingValueAuto", throwable);
            return value;
        }
    }

    static boolean isExemptPackage(String packageName) {
        if (packageName == null) {
            return true;
        }
        if (packageName.equals("com.android.settings")
                || packageName.equals("com.android.providers.settings")) {
            return true;
        }
        String self = FarewellState.currentPackage();
        return self != null && self.equals(packageName);
    }
}
