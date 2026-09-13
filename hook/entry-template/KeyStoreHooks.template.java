package android.security.keystore2;

import android.content.ContentResolver;
import android.content.Context;

import java.security.KeyPair;
import java.security.cert.Certificate;

public final class __CLASS__ {

    private __CLASS__() {
    }

    public static void __M_INIT_CONTEXT__(Context context) {
        HookState.initApp(context);
    }

    public static void __M_INIT_SYSTEM_SERVER__() {
        HookState.initSystemServer();
    }

    public static Boolean __M_HAS_SYSTEM_FEATURE__(String name, int version) {
        return SystemFeatureSpoofer.check(name, version);
    }

    public static KeyPair __M_INIT_GENERATE_SOFTWARE_KEY_PAIR__(Object spi) {
        return KeyboxSpoofer.generateSoftwareKeyPair(spi);
    }

    public static Certificate[] __M_CERTIFICATE_CHAIN_IF_NEEDED__(Certificate[] chain) {
        return KeyboxSpoofer.maybeReplaceChain(chain);
    }

    public static boolean __M_SHOULD_HIDE_DEV_STATUS__(ContentResolver resolver, String name, int userId) {
        return SettingsSpoofer.shouldHideDevStatusFromNameValueCache(resolver, name, userId);
    }

    public static boolean __M_SHOULD_HIDE_APP_LIST_FOR_CALLER__(int callingUid, ContentResolver resolver,
                                                               String targetPackage, int userId) {
        return AppFilterSpoofer.shouldHideAppListForCaller(callingUid, resolver, targetPackage, userId);
    }

    public static boolean __M_SHOULD_HIDE_APP_LIST__(ContentResolver resolver, String targetPackage) {
        return AppFilterSpoofer.shouldHideAppList(resolver, targetPackage);
    }

    public static String __M_FILTER_INSTALLER__(ContentResolver resolver, int callingUid, int userId,
                                                String packageName, String installer) {
        return AppFilterSpoofer.filterInstallerPackageName(resolver, callingUid, userId, packageName, installer);
    }

    public static String __M_FILTER_INSTALLER__(String installer) {
        return AppFilterSpoofer.filterInstallerPackageNameAuto(installer);
    }

    public static boolean __M_SHOULD_REMOVE_SETTING__(ContentResolver resolver, String namespace, String name) {
        return SettingsSpoofer.shouldRemoveSetting(resolver, namespace, name);
    }

    public static String __M_FILTER_SETTING_VALUE__(ContentResolver resolver, String namespace,
                                                    String name, String value) {
        return SettingsSpoofer.filterSettingValue(resolver, namespace, name, value);
    }

    public static String __M_FILTER_SETTING_VALUE__(String namespace, String name, String value) {
        return SettingsSpoofer.filterSettingValueAuto(namespace, name, value);
    }

    public static boolean __M_HAS_SETTING_OVERRIDE__(Object nameValueCache, String name, int userId) {
        return SettingsSpoofer.hasSettingOverride(nameValueCache, name, userId);
    }

    public static String __M_SETTING_OVERRIDE_VALUE__(Object nameValueCache, String name) {
        return SettingsSpoofer.settingOverrideValue(nameValueCache, name);
    }

    public static boolean __M_COMBINE_APP_FILTER__(int callingUid, String targetPackage, int userId, boolean stock) {
        return AppFilterSpoofer.combineAppFilter(callingUid, targetPackage, userId, stock);
    }

    public static boolean __M_COMBINE_APP_FILTER_OBJECT__(int callingUid, Object target, int userId, boolean stock) {
        return AppFilterSpoofer.combineAppFilterForObject(callingUid, target, userId, stock);
    }

    public static String __M_FILTER_SYSTEM_PROPERTY__(String key, String value) {
        return PropSpoofer.filter(key, value);
    }

    public static String __M_PROP_OVERRIDE__(String key) {
        return PropSpoofer.override(key);
    }

    public static Certificate[] __M_CERTIFICATE_CHAIN_FOR_ALIAS__(String alias) {
        return KeyboxEngine.chainForAlias(alias);
    }

    public static Certificate __M_CERTIFICATE_FOR_ALIAS__(String alias) {
        return KeyboxEngine.certificateForAlias(alias);
    }

    public static boolean __M_IS_SECURE_FLAG__() {
        return SecureFlagSpoofer.isSecureFlag();
    }

    public static String __M_GET_FRAMEWORK_VERSION__() {
        return HookState.VERSION;
    }
}
