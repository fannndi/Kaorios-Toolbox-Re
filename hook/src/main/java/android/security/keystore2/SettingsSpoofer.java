package android.security.keystore2;

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
            if (name == null || !DEV_KEYS.contains(name) || HookState.isInternalCall()) {
                return false;
            }
            HookConfig config = HookState.config();
            if (!config.isHideDevStatus()) {
                return false;
            }
            return !isExemptPackage(HookState.currentPackage());
        } catch (Throwable throwable) {
            HookLog.e("hideDevStatus", throwable);
            return false;
        }
    }

    public static boolean shouldRemoveSetting(ContentResolver resolver, String namespace, String name) {
        try {
            if (namespace == null || name == null) {
                return false;
            }
            HookProbe.noteRemove(namespace, name);
            if (HookProbe.isProbeName(name) || HookState.isInternalCall()) {
                return false;
            }
            if (!TABLES.contains(namespace)) {
                return false;
            }
            return HookState.config().shouldRemove(namespace, name);
        } catch (Throwable throwable) {
            HookLog.e("shouldRemoveSetting", throwable);
            return false;
        }
    }

    public static String filterSettingValue(ContentResolver resolver, String namespace, String name, String value) {
        try {
            if (namespace == null || name == null) {
                return value;
            }
            if (HookProbe.isProbeName(name)) {
                return HookProbe.answer(resolver, namespace, name);
            }
            if (HookState.isInternalCall() || !TABLES.contains(namespace)) {
                return value;
            }
            HookConfig config = HookState.config();
            if (config.shouldRemove(namespace, name)) {
                return value;
            }
            int uid = HookState.callingUid();
            if (HookState.isPrivilegedUid(uid)) {
                return value;
            }
            String packageName = HookState.packageForUid(uid);
            if (packageName == null || isExemptPackage(packageName)) {
                return value;
            }
            String spoofed = config.settingValue(packageName, namespace, name);
            return spoofed != null ? spoofed : value;
        } catch (Throwable throwable) {
            HookLog.e("filterSettingValue", throwable);
            return value;
        }
    }

    public static String filterSettingValueAuto(String namespace, String name, String value) {
        try {
            if (namespace == null || name == null) {
                return value;
            }
            if (HookProbe.isProbeName(name)) {
                android.content.Context context = HookState.context();
                ContentResolver resolver = context == null ? null : context.getContentResolver();
                return HookProbe.answer(resolver, namespace, name);
            }
            if (HookState.isInternalCall() || !TABLES.contains(namespace)) {
                return value;
            }
            HookConfig config = HookState.config();
            if (config.shouldRemove(namespace, name)) {
                return null;
            }
            int uid = HookState.callingUid();
            if (HookState.isPrivilegedUid(uid)) {
                return value;
            }
            String packageName = HookState.packageForUid(uid);
            if (packageName == null || isExemptPackage(packageName)) {
                return value;
            }
            String spoofed = config.settingValue(packageName, namespace, name);
            return spoofed != null ? spoofed : value;
        } catch (Throwable throwable) {
            HookLog.e("filterSettingValueAuto", throwable);
            return value;
        }
    }

    public static boolean hasSettingOverride(Object nameValueCache, String name, int userId) {
        try {
            if (name == null || HookState.isInternalCall()) {
                return false;
            }
            String namespace = namespaceOf(nameValueCache);
            if (namespace == null || !TABLES.contains(namespace)) {
                return false;
            }
            if (HookProbe.isProbeName(name)) {
                HookProbe.noteRemove(namespace, name);
                return true;
            }
            HookConfig config = HookState.config();
            if (config.shouldRemove(namespace, name)) {
                return true;
            }
            String packageName = HookState.currentPackage();
            if (packageName == null || isExemptPackage(packageName)) {
                return false;
            }
            return config.settingValue(packageName, namespace, name) != null;
        } catch (Throwable throwable) {
            HookLog.e("hasSettingOverride", throwable);
            return false;
        }
    }

    public static String settingOverrideValue(Object nameValueCache, String name) {
        try {
            if (name == null) {
                return null;
            }
            String namespace = namespaceOf(nameValueCache);
            if (namespace == null || !TABLES.contains(namespace)) {
                return null;
            }
            if (HookProbe.isProbeName(name)) {
                android.content.Context context = HookState.context();
                ContentResolver resolver = context == null ? null : context.getContentResolver();
                return HookProbe.answer(resolver, namespace, name);
            }
            HookConfig config = HookState.config();
            if (config.shouldRemove(namespace, name)) {
                return null;
            }
            return config.settingValue(HookState.currentPackage(), namespace, name);
        } catch (Throwable throwable) {
            HookLog.e("settingOverrideValue", throwable);
            return null;
        }
    }

    private static String namespaceOf(Object nameValueCache) {
        if (nameValueCache == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = nameValueCache.getClass().getDeclaredField("mUri");
            field.setAccessible(true);
            Object value = field.get(nameValueCache);
            if (value instanceof android.net.Uri) {
                java.util.List<String> segments = ((android.net.Uri) value).getPathSegments();
                if (!segments.isEmpty()) {
                    return segments.get(0);
                }
            }
        } catch (Throwable throwable) {
            HookLog.w("namespace lookup failed: " + throwable);
        }
        return null;
    }

    static boolean isExemptPackage(String packageName) {
        if (packageName == null) {
            return true;
        }
        if (packageName.equals("com.android.settings")
                || packageName.equals("com.android.providers.settings")) {
            return true;
        }
        String self = HookState.currentPackage();
        return self != null && self.equals(packageName);
    }
}
