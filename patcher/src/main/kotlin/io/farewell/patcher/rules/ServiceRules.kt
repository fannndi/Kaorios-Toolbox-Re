package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

class AppsFilterRule : MethodRule {
    override val name = "services.appsfilter.shouldFilterApplication"

    // AppsFilterBase / AppsFilterImpl only exist from Android 13 (SDK 33). On
    // Android 11/12 the same hook lives on com.android.server.pm.AppsFilter
    // (see LegacyAppsFilterRule); on Android 10 there is no AppsFilter at all.
    override val apiRange: IntRange = 33..Int.MAX_VALUE
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/AppsFilterBase;" &&
            classDef.type != "Lcom/android/server/pm/AppsFilterImpl;"
        ) {
            return false
        }
        if (method.name != "shouldFilterApplication" || method.returnType != "Z") return false
        val params = method.parameterTypesList()
        if (params.size != 5 || params[1] != "I" || params[4] != "I") return false
        if (method.localRegister(1) < 0) return false

        val callingUid = method.parameterRegister(1)
        val packageState = method.parameterRegister(3)
        val userId = method.parameterRegister(4)
        if (callingUid < 0 || packageState < 0 || userId < 0) return false

        val packageRegister = method.localRegister(0)
        val resultRegister = method.localRegister(1)

        val getPackageName = Asm.methodRef(params[3], "getPackageName", emptyList(), HookContract.STRING)
        val label = impl.newLabelForIndex(0)
        val instructions = mutableListOf<BuilderInstruction>()
        instructions += Asm.invokeInterface(intArrayOf(packageState), getPackageName)
        instructions += Asm.moveResultObject(packageRegister)
        instructions += Asm.constZero(resultRegister)
        instructions += Asm.invokeStatic(
            intArrayOf(callingUid, resultRegister, packageRegister, userId),
            Asm.methodRef(
                HOOK_CLASS, HookContract.SHOULD_HIDE_APP_LIST_FOR_CALLER,
                listOf("I", HookContract.CONTENT_RESOLVER, HookContract.STRING, "I"), "Z"
            )
        )
        instructions += Asm.moveResult(resultRegister)
        instructions += Asm.ifEqz(resultRegister, label)
        instructions += Asm.constOne(resultRegister)
        instructions += Asm.returnInt(resultRegister)

        impl.addAll(0, instructions)
        return true
    }
}

class InstallerSourceRule : MethodRule {
    override val name = "services.computerengine.installer"

    // ComputerEngine only carries getInstallerPackageName from Android 13 (SDK 33).
    // On surya MIUI 12/13/14 the method is declared on PackageManagerService itself
    // (see PackageManagerInstallerRule), so this rule stays dormant there.
    override val apiRange: IntRange = 33..Int.MAX_VALUE
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/ComputerEngine;") return false
        if (method.name != "getInstallerPackageName" || method.returnType != HookContract.STRING) return false
        val reference = Asm.methodRef(
            HOOK_CLASS, HookContract.FILTER_INSTALLER,
            listOf(HookContract.STRING), HookContract.STRING
        )
        var patched = false
        impl.replaceReturnsObject { register ->
            patched = true
            listOf(
                Asm.invokeStatic(intArrayOf(register), reference),
                Asm.moveResultObject(register)
            )
        }
        return patched
    }
}

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

class WindowManagerCaptureRule : MethodRule {
    override val name = "services.wm.captureDisplay"

    // WindowManagerService.notAllowCaptureDisplay does not exist on surya MIUI 12,
    // 13 or 14 — the stock services.jar is R8-processed and the method is inlined
    // away. FLAG_SECURE is covered there by WindowState.isSecureLocked,
    // WindowStateAnimator.setSecureLocked and the DevicePolicyCache rules. This
    // rule is kept for the generic Android 13+ profile, where the method is still
    // present upstream.
    override val apiRange: IntRange = 33..Int.MAX_VALUE
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/wm/WindowManagerService;") return false
        var patched = false
        val snapshot = impl.instructions
        for (index in snapshot.indices.reversed()) {
            val reference = snapshot[index].methodReference() ?: continue
            if (reference.name != "notAllowCaptureDisplay") continue
            if (index + 1 >= snapshot.size) continue
            val resultRegister = (snapshot[index + 1] as? OneRegisterInstruction)?.registerA ?: continue
            var scratch = method.localRegister(0)
            if (scratch == resultRegister) {
                scratch = method.localRegister(1)
            }
            if (scratch < 0) continue
            val label = impl.newLabelForIndex(index + 2)
            val injected = listOf<BuilderInstruction>(
                Asm.invokeStatic(
                    intArrayOf(),
                    Asm.methodRef(HOOK_CLASS, HookContract.IS_SECURE_FLAG, emptyList(), "Z")
                ),
                Asm.moveResult(scratch),
                Asm.ifEqz(scratch, label),
                Asm.constZero(resultRegister)
            )
            impl.addAll(index + 2, injected)
            patched = true
        }
        return patched
    }
}
