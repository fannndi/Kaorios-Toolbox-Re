package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

class SystemServerInitRule : MethodRule {
    override val name = "services.systemserver.init"
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/SystemServer;") return false
        val targets = impl.instructions
            .withIndex()
            .filter { entry ->
                val reference = entry.value.methodReference() ?: return@filter false
                reference.name == "startOtherServices" && reference.definingClass == classDef.type
            }
            .map { it.index }
            .sortedDescending()
        if (targets.isEmpty()) return false
        val invoke = Asm.invokeStatic(
            intArrayOf(),
            Asm.methodRef(HOOK_CLASS, HookContract.INIT_SYSTEM_SERVER, emptyList(), "V")
        )
        for (index in targets) {
            impl.addAll(index, listOf(invoke))
        }
        return true
    }
}

/*
 * Surya-only scope.
 *
 * Three rules that used to live here targeted Android 13+ classes that do not
 * exist on any surya ROM, so they could never fire and have been removed:
 *
 *  - AppsFilterRule        AppsFilterBase / AppsFilterImpl are A13+. Android 11/12
 *                          use com.android.server.pm.AppsFilter
 *                          (see LegacyAppsFilterRule); Android 10 has no
 *                          AppsFilter class at all and filters through
 *                          PackageManagerService.filterAppAccess*
 *                          (see FilterAppAccessRule).
 *  - InstallerSourceRule   ComputerEngine.getInstallerPackageName is A13+. On
 *                          surya the method is declared on PackageManagerService
 *                          itself (see PackageManagerInstallerRule).
 *  - WindowManagerCaptureRule
 *                          WindowManagerService.notAllowCaptureDisplay has 0
 *                          occurrences in any surya services.jar — the stock jar
 *                          is R8-processed and the method is inlined away.
 *                          FLAG_SECURE is covered by WindowState.isSecureLocked,
 *                          WindowStateAnimator.setSecureLocked and the
 *                          DevicePolicyCache rules.
 *
 * The hook entry points they used (SHOULD_HIDE_APP_LIST_FOR_CALLER,
 * FILTER_INSTALLER, IS_SECURE_FLAG) are all still reached by the surya rules
 * below, so the generated hook identity is unchanged.
 */

/*
 * There is deliberately no SettingsProvider rule here.
 *
 * The 2.0.6.0 patch guide documents a server-side `filterSettingValue` /
 * `shouldRemoveSetting` patch on the SettingsProvider GET path, and the previous
 * implementation tried to match it inside services.jar. That could never work:
 *
 *  - `com.android.providers.settings.SettingsProvider` lives in
 *    /system/priv-app/SettingsProvider/SettingsProvider.apk, not in services.jar.
 *  - The provider has no `getStringForUser` and no `getString` method on surya
 *    MIUI 12, 13 or 14 (verified against the stock APK dex).
 *  - The only String-returning server-side entry point is
 *    `getSettingValue(Landroid/os/Bundle;)Ljava/lang/String;`, which is a
 *    one-liner (`request.getString("value")`) and carries no table name, so a
 *    per-app filter cannot be built on it.
 *
 * Per-app Settings spoofing is therefore implemented client-side on
 * `Settings$NameValueCache.getStringForUser`, which exists on all three ROMs and
 * covers both app and system_server reads (see HideDevStatusRule and
 * SettingsNameValueCacheRule). A true server-side variant would have to
 * post-process the Bundle returned by `SettingsProvider.call(String, String,
 * Bundle)` and would require patching the priv-app APK, which replaces a signed
 * system app with an unsigned one. See Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md.
 */

class DevicePolicySecureRule : MethodRule {
    override val name = "services.devicepolicy.screencapture"

    // Android 11+ exposes isScreenCaptureAllowed(int, boolean). Android 10 uses
    // the inverted getScreenCaptureDisabled(int) (see LegacyScreenCaptureRule).
    override val apiRange: IntRange = 31..Int.MAX_VALUE
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;") return false
        if (method.name != "isScreenCaptureAllowed" || method.returnType != "Z") return false
        if (!method.hasLocalRegister()) return false
        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(),
                Asm.methodRef(HOOK_CLASS, HookContract.IS_SECURE_FLAG, emptyList(), "Z")
            ),
            Asm.moveResult(scratch),
            Asm.ifEqz(scratch, label),
            Asm.constOne(scratch),
            Asm.returnInt(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class WindowSecureRule : MethodRule {
    override val name = "services.window.secureLocked"
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        val target = classDef.type == "Lcom/android/server/wm/WindowState;" ||
            classDef.type == "Lcom/android/server/wm/WindowStateAnimator;"
        if (!target) return false
        if (!method.hasLocalRegister()) return false
        return when {
            method.name == "isSecureLocked" && method.returnType == "Z" -> {
                patchBoolean(impl, method)
                true
            }
            method.name == "setSecureLocked" && method.returnType == "V" -> {
                patchVoid(impl, method)
                true
            }
            else -> false
        }
    }

    private fun patchBoolean(impl: MutableMethodImplementation, method: Method) {
        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(),
                Asm.methodRef(HOOK_CLASS, HookContract.IS_SECURE_FLAG, emptyList(), "Z")
            ),
            Asm.moveResult(scratch),
            Asm.ifEqz(scratch, label),
            Asm.constZero(scratch),
            Asm.returnInt(scratch)
        )
        impl.addAll(0, instructions)
    }

    private fun patchVoid(impl: MutableMethodImplementation, method: Method) {
        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(),
                Asm.methodRef(HOOK_CLASS, HookContract.IS_SECURE_FLAG, emptyList(), "Z")
            ),
            Asm.moveResult(scratch),
            Asm.ifEqz(scratch, label),
            Asm.returnVoid()
        )
        impl.addAll(0, instructions)
    }
}
