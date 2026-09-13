package io.farewell.patcher

object HookContract {

    const val HOOK_CLASS = HookIdentity.HOOK_CLASS
    const val HOOK_PACKAGE = "android.security.keystore2"
    const val HOOK_DEX_ENTRY = "hook.dex"
    const val MARKER_CLASS = HookIdentity.HOOK_CLASS

    const val INIT_CONTEXT = HookIdentity.M_INIT_CONTEXT
    const val INIT_SYSTEM_SERVER = HookIdentity.M_INIT_SYSTEM_SERVER
    const val HAS_SYSTEM_FEATURE = HookIdentity.M_HAS_SYSTEM_FEATURE
    const val INIT_GENERATE_SOFTWARE_KEY_PAIR = HookIdentity.M_INIT_GENERATE_SOFTWARE_KEY_PAIR
    const val CERTIFICATE_CHAIN_IF_NEEDED = HookIdentity.M_CERTIFICATE_CHAIN_IF_NEEDED
    const val SHOULD_HIDE_DEV_STATUS = HookIdentity.M_SHOULD_HIDE_DEV_STATUS
    const val SHOULD_HIDE_APP_LIST_FOR_CALLER = HookIdentity.M_SHOULD_HIDE_APP_LIST_FOR_CALLER
    const val SHOULD_HIDE_APP_LIST = HookIdentity.M_SHOULD_HIDE_APP_LIST
    const val FILTER_INSTALLER = HookIdentity.M_FILTER_INSTALLER
    const val SHOULD_REMOVE_SETTING = HookIdentity.M_SHOULD_REMOVE_SETTING
    const val FILTER_SETTING_VALUE = HookIdentity.M_FILTER_SETTING_VALUE
    const val HAS_SETTING_OVERRIDE = HookIdentity.M_HAS_SETTING_OVERRIDE
    const val SETTING_OVERRIDE_VALUE = HookIdentity.M_SETTING_OVERRIDE_VALUE
    const val COMBINE_APP_FILTER = HookIdentity.M_COMBINE_APP_FILTER
    const val COMBINE_APP_FILTER_OBJECT = HookIdentity.M_COMBINE_APP_FILTER_OBJECT
    const val FILTER_SYSTEM_PROPERTY = HookIdentity.M_FILTER_SYSTEM_PROPERTY
    const val PROP_OVERRIDE = HookIdentity.M_PROP_OVERRIDE
    const val CERTIFICATE_CHAIN_FOR_ALIAS = HookIdentity.M_CERTIFICATE_CHAIN_FOR_ALIAS
    const val CERTIFICATE_FOR_ALIAS = HookIdentity.M_CERTIFICATE_FOR_ALIAS
    const val IS_SECURE_FLAG = HookIdentity.M_IS_SECURE_FLAG
    const val GET_FRAMEWORK_VERSION = HookIdentity.M_GET_FRAMEWORK_VERSION

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
