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
