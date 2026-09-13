package android.security.farewell;

import android.content.ContentResolver;
import android.content.Context;

import java.security.KeyPair;
import java.security.cert.Certificate;

public final class FarewellHook {

    private FarewellHook() {
    }

    public static void initContext(Context context) {
        FarewellState.initApp(context);
    }

    public static void initSystemServer() {
        FarewellState.initSystemServer();
    }

    public static Boolean hasSystemFeature(String name, int version) {
        return SystemFeatureSpoofer.check(name, version);
    }

    public static KeyPair initGenerateSoftwareKeyPair(Object spi) {
        return KeyboxSpoofer.generateSoftwareKeyPair(spi);
    }

    public static Certificate[] CertificateChainIfNeeded(Certificate[] chain) {
        return KeyboxSpoofer.maybeReplaceChain(chain);
    }

    public static boolean shouldHideDevStatusFromNameValueCache(ContentResolver resolver, String name, int userId) {
        return SettingsSpoofer.shouldHideDevStatusFromNameValueCache(resolver, name, userId);
    }

    public static boolean shouldHideAppListForCaller(int callingUid, ContentResolver resolver,
                                                     String targetPackage, int userId) {
        return AppFilterSpoofer.shouldHideAppListForCaller(callingUid, resolver, targetPackage, userId);
    }

    public static boolean shouldHideAppList(ContentResolver resolver, String targetPackage) {
        return AppFilterSpoofer.shouldHideAppList(resolver, targetPackage);
    }

    public static String filterInstallerPackageName(ContentResolver resolver, int callingUid, int userId,
                                                    String packageName, String installer) {
        return AppFilterSpoofer.filterInstallerPackageName(resolver, callingUid, userId, packageName, installer);
    }

    public static String filterInstallerPackageName(String installer) {
        return AppFilterSpoofer.filterInstallerPackageNameAuto(installer);
    }

    public static boolean shouldRemoveSetting(ContentResolver resolver, String namespace, String name) {
        return SettingsSpoofer.shouldRemoveSetting(resolver, namespace, name);
    }

    public static String filterSettingValue(ContentResolver resolver, String namespace, String name, String value) {
        return SettingsSpoofer.filterSettingValue(resolver, namespace, name, value);
    }

    public static String filterSettingValue(String namespace, String name, String value) {
        return SettingsSpoofer.filterSettingValueAuto(namespace, name, value);
    }

    public static boolean hasSettingOverride(Object nameValueCache, String name, int userId) {
        return SettingsSpoofer.hasSettingOverride(nameValueCache, name, userId);
    }

    public static String settingOverrideValue(Object nameValueCache, String name) {
        return SettingsSpoofer.settingOverrideValue(nameValueCache, name);
    }

    public static boolean isSecureFlag() {
        return SecureFlagSpoofer.isSecureFlag();
    }

    public static String getFrameworkVersion() {
        return FarewellState.VERSION;
    }
}
