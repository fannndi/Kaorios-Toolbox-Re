package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

class SettingsNameValueCacheRule : MethodRule {
    override val name = "common.settings.nvcOverride"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/provider/Settings\$NameValueCache;" &&
            classDef.type != "Landroid/provider/Settings_NameValueCache;"
        ) {
            return false
        }
        if (method.name != "getStringForUser" || method.returnType != HookContract.STRING) return false
        val params = method.parameterTypesList()
        if (params != listOf(HookContract.CONTENT_RESOLVER, HookContract.STRING, "I")) return false
        if (!method.hasLocalRegister()) return false

        val cacheRegister = method.parameterRegister(0)
        val nameRegister = method.parameterRegister(1)
        val userRegister = method.parameterRegister(2)
        if (cacheRegister < 0 || nameRegister < 0 || userRegister < 0) return false

        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(cacheRegister, nameRegister, userRegister),
                Asm.methodRef(
                    HOOK_CLASS, "hasSettingOverride",
                    listOf("Ljava/lang/Object;", HookContract.STRING, "I"), "Z"
                )
            ),
            Asm.moveResult(scratch),
            Asm.ifEqz(scratch, label),
            Asm.invokeStatic(
                intArrayOf(cacheRegister, nameRegister),
                Asm.methodRef(
                    HOOK_CLASS, "settingOverrideValue",
                    listOf("Ljava/lang/Object;", HookContract.STRING), HookContract.STRING
                )
            ),
            Asm.moveResultObject(scratch),
            Asm.returnObject(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class LegacyAppsFilterRule : MethodRule {
    override val name = "legacy.appsfilter.shouldFilterApplication"
    override val apiRange: IntRange = 0..32
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/AppsFilter;") return false
        if (method.name != "shouldFilterApplication" || method.returnType != "Z") return false
        val params = method.parameterTypesList()
        if (params.size != 4 || params[0] != "I" || params[3] != "I") return false
        if (method.localRegister(1) < 0) return false

        val callingUid = method.parameterRegister(0)
        val packageSetting = method.parameterRegister(2)
        val userId = method.parameterRegister(3)
        if (callingUid < 0 || packageSetting < 0 || userId < 0) return false

        val packageRegister = method.localRegister(0)
        val resultRegister = method.localRegister(1)
        val label = impl.newLabelForIndex(0)

        val instructions = mutableListOf<BuilderInstruction>()
        instructions += Asm.invokeVirtual(
            intArrayOf(packageSetting),
            Asm.methodRef(params[2], "getPackageName", emptyList(), HookContract.STRING)
        )
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

class LegacyScreenCaptureRule : MethodRule {
    override val name = "legacy.devicepolicy.getScreenCaptureDisabled"
    override val apiRange: IntRange = 0..30
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;") return false
        if (method.name != "getScreenCaptureDisabled" || method.returnType != "Z") return false
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
            Asm.constZero(scratch),
            Asm.returnInt(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class LegacyWindowManagerSecureRule : MethodRule {
    override val name = "legacy.wm.isSecureLocked"
    override val apiRange: IntRange = 0..32
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/wm/WindowManagerService;") return false
        if (method.name != "isSecureLocked" || method.returnType != "Z") return false
        if (!method.hasParameterOfType("Lcom/android/server/wm/WindowState;")) return false
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
            Asm.constZero(scratch),
            Asm.returnInt(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }

    private fun Method.hasParameterOfType(type: String): Boolean = parameterTypesList().contains(type)
}
