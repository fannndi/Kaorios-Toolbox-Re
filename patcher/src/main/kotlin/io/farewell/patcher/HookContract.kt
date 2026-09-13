package io.farewell.patcher

object HookContract {

    const val HOOK_CLASS = HookIdentity.HOOK_CLASS
    const val HOOK_PACKAGE = "android.security.keystore2"
    const val HOOK_DEX_ENTRY = "hook.dex"
    const val MARKER_CLASS = HookIdentity.HOOK_CLASS

    const val INIT_CONTEXT = "initContext"
    const val INIT_SYSTEM_SERVER = "initSystemServer"
    const val HAS_SYSTEM_FEATURE = "hasSystemFeature"
    const val INIT_GENERATE_SOFTWARE_KEY_PAIR = "initGenerateSoftwareKeyPair"
    const val CERTIFICATE_CHAIN_IF_NEEDED = "CertificateChainIfNeeded"
    const val SHOULD_HIDE_DEV_STATUS = "shouldHideDevStatusFromNameValueCache"
    const val SHOULD_HIDE_APP_LIST_FOR_CALLER = "shouldHideAppListForCaller"
    const val SHOULD_HIDE_APP_LIST = "shouldHideAppList"
    const val FILTER_INSTALLER = "filterInstallerPackageName"
    const val SHOULD_REMOVE_SETTING = "shouldRemoveSetting"
    const val FILTER_SETTING_VALUE = "filterSettingValue"
    const val IS_SECURE_FLAG = "isSecureFlag"

    const val CONTEXT = "Landroid/content/Context;"
    const val CONTENT_RESOLVER = "Landroid/content/ContentResolver;"
    const val STRING = "Ljava/lang/String;"
    const val APPLICATION = "Landroid/app/Application;"
    const val CLASS = "Ljava/lang/Class;"
    const val CLASS_LOADER = "Ljava/lang/ClassLoader;"
    const val BOOLEAN_OBJ = "Ljava/lang/Boolean;"
    const val KEY_PAIR = "Ljava/security/KeyPair;"
    const val CERTIFICATE_ARRAY = "[Ljava/security/cert/Certificate;"
}
