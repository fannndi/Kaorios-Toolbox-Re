package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

class PackageManagerInstallerRule : MethodRule {
    override val name = "legacy.pmservice.installer"
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/PackageManagerService;") return false
        if (method.name != "getInstallerPackageName" || method.returnType != HookContract.STRING) return false
        if (method.parameterTypesList() != listOf(HookContract.STRING)) return false
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

class FilterAppAccessRule : MethodRule {
    override val name = "legacy.pmservice.filterAppAccess"
    override val apiRange: IntRange = 29..32
    override fun enabledFor(kind: JarKind) = kind == JarKind.SERVICES

    private val stringHook = Asm.methodRef(
        HOOK_CLASS, "combineAppFilter",
        listOf("I", HookContract.STRING, "I", "Z"), "Z"
    )

    private val objectHook = Asm.methodRef(
        HOOK_CLASS, "combineAppFilterForObject",
        listOf("I", "Ljava/lang/Object;", "I", "Z"), "Z"
    )

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/PackageManagerService;") return false
        if (method.name != "filterAppAccess" && method.name != "filterAppAccessLPr") return false
        if (method.returnType != "Z") return false
        val params = method.parameterTypesList()
        if (params.size != 3 || params[1] != "I" || params[2] != "I") return false

        val callingUid = method.parameterRegister(1)
        val userId = method.parameterRegister(2)
        val targetRegister = method.parameterRegister(0)
        if (callingUid < 0 || userId < 0 || targetRegister < 0) return false

        val stringVariant = params[0] == HookContract.STRING
        return impl.replaceReturnsInt { resultRegister ->
            val reference = if (stringVariant) stringHook else objectHook
            listOf<BuilderInstruction>(
                Asm.invokeStatic(intArrayOf(callingUid, targetRegister, userId, resultRegister), reference),
                Asm.moveResult(resultRegister)
            )
        }
    }
}
