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

    private val hookRef = Asm.methodRef(
        HOOK_CLASS, HookContract.SHOULD_HIDE_APP_LIST_FOR_CALLER,
        listOf("I", HookContract.CONTENT_RESOLVER, HookContract.STRING, "I"), "Z"
    )

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Lcom/android/server/pm/PackageManagerService;") return false
        if (method.name != "filterAppAccess" && method.name != "filterAppAccessLPr") return false
        if (method.returnType != "Z") return false
        val params = method.parameterTypesList()
        if (params.size != 3 || params[1] != "I" || params[2] != "I") return false
        if (method.localRegister(1) < 0) return false

        val callingUid = method.parameterRegister(1)
        val userId = method.parameterRegister(2)
        if (callingUid < 0 || userId < 0) return false

        val label = impl.newLabelForIndex(0)
        val instructions = mutableListOf<BuilderInstruction>()

        if (params[0] == HookContract.STRING) {
            val packageParam = method.parameterRegister(0)
            if (packageParam < 0) return false
            val scratch = method.localRegister(0)
            instructions += Asm.constZero(scratch)
            instructions += Asm.invokeStatic(intArrayOf(callingUid, scratch, packageParam, userId), hookRef)
            instructions += Asm.moveResult(scratch)
            instructions += Asm.ifEqz(scratch, label)
            instructions += Asm.constOne(scratch)
            instructions += Asm.returnInt(scratch)
        } else {
            val packageParam = method.parameterRegister(0)
            if (packageParam < 0) return false
            val packageRegister = method.localRegister(0)
            val scratch = method.localRegister(1)
            val getPackageName = Asm.methodRef(params[0], "getPackageName", emptyList(), HookContract.STRING)
            val invoke = if (params[0].contains("AndroidPackage")) {
                Asm.invokeInterface(intArrayOf(packageParam), getPackageName)
            } else {
                Asm.invokeVirtual(intArrayOf(packageParam), getPackageName)
            }
            instructions += invoke
            instructions += Asm.moveResultObject(packageRegister)
            instructions += Asm.constZero(scratch)
            instructions += Asm.invokeStatic(intArrayOf(callingUid, scratch, packageRegister, userId), hookRef)
            instructions += Asm.moveResult(scratch)
            instructions += Asm.ifEqz(scratch, label)
            instructions += Asm.constOne(scratch)
            instructions += Asm.returnInt(scratch)
        }

        impl.addAll(0, instructions)
        return true
    }
}
