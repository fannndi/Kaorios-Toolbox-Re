package android.security.farewell;

import android.content.ContentResolver;

public final class AppFilterSpoofer {

    private AppFilterSpoofer() {
    }

    public static boolean shouldHideAppListForCaller(int callingUid, ContentResolver resolver,
                                                     String targetPackage, int userId) {
        try {
            if (targetPackage == null || FarewellState.isInternalCall()) {
                return false;
            }
            if (FarewellState.isPrivilegedUid(callingUid)) {
                return false;
            }
            String caller = FarewellState.packageForUid(callingUid);
            if (SettingsSpoofer.isExemptPackage(caller)) {
                return false;
            }
            return FarewellState.config().isHideAppList();
        } catch (Throwable throwable) {
            FarewellLog.e("shouldHideAppListForCaller", throwable);
            return false;
        }
    }

    public static boolean shouldHideAppList(ContentResolver resolver, String targetPackage) {
        try {
            if (targetPackage == null || FarewellState.isInternalCall()) {
                return false;
            }
            return FarewellState.config().isHideAppList();
        } catch (Throwable throwable) {
            FarewellLog.e("shouldHideAppList", throwable);
            return false;
        }
    }

    public static String filterInstallerPackageName(ContentResolver resolver, int callingUid, int userId,
                                                    String packageName, String installer) {
        try {
            if (packageName == null || FarewellState.isInternalCall()) {
                return installer;
            }
            String override = FarewellState.config().installerOverride(packageName);
            return override != null ? override : installer;
        } catch (Throwable throwable) {
            FarewellLog.e("filterInstallerPackageName", throwable);
            return installer;
        }
    }

    public static boolean combineAppFilter(int callingUid, String targetPackage, int userId, boolean stock) {
        try {
            if (stock) {
                return true;
            }
            return shouldHideAppListForCallerAuto(callingUid, targetPackage, userId);
        } catch (Throwable throwable) {
            FarewellLog.e("combineAppFilter", throwable);
            return stock;
        }
    }

    public static boolean combineAppFilterForObject(int callingUid, Object target, int userId, boolean stock) {
        try {
            if (stock) {
                return true;
            }
            return shouldHideAppListForCallerAuto(callingUid, packageNameOf(target), userId);
        } catch (Throwable throwable) {
            FarewellLog.e("combineAppFilterForObject", throwable);
            return stock;
        }
    }

    private static boolean shouldHideAppListForCallerAuto(int callingUid, String targetPackage, int userId) {
        if (targetPackage == null || FarewellState.isInternalCall()) {
            return false;
        }
        if (FarewellState.isPrivilegedUid(callingUid)) {
            return false;
        }
        String caller = FarewellState.packageForUid(callingUid);
        if (SettingsSpoofer.isExemptPackage(caller)) {
            return false;
        }
        return FarewellState.config().isHideAppList();
    }

    private static String packageNameOf(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod("getPackageName").invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static String filterInstallerPackageNameAuto(String installer) {
        try {
            if (installer == null || FarewellState.isInternalCall()) {
                return installer;
            }
            int uid = FarewellState.callingUid();
            String packageName = FarewellState.packageForUid(uid);
            if (packageName == null) {
                return installer;
            }
            String override = FarewellState.config().installerOverride(packageName);
            return override != null ? override : installer;
        } catch (Throwable throwable) {
            FarewellLog.e("filterInstallerPackageNameAuto", throwable);
            return installer;
        }
    }
}
