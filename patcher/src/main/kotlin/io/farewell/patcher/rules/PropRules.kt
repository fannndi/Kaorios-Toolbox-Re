package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

class SystemPropertiesRule : MethodRule {
    override val name = "framework.systemproperties.props"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/os/SystemProperties;") return false
        if (method.name != "get" || method.returnType != HookContract.STRING) return false
        val params = method.parameterTypesList()
        if (params != listOf(HookContract.STRING) && params != listOf(HookContract.STRING, HookContract.STRING)) {
            return false
        }
        val keyRegister = method.parameterRegister(0)
        if (keyRegister < 0) return false
        val reference = Asm.methodRef(
            HOOK_CLASS, "filterSystemProperty",
            listOf(HookContract.STRING, HookContract.STRING), HookContract.STRING
        )
        var patched = false
        impl.replaceReturnsObject { register ->
            patched = true
            listOf<BuilderInstruction>(
                Asm.invokeStatic(intArrayOf(keyRegister, register), reference),
                Asm.moveResultObject(register)
            )
        }
        return patched
    }
}

class SystemPropertiesPrimitiveRule : MethodRule {
    override val name = "framework.systemproperties.primitives"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/os/SystemProperties;") return false
        val params = method.parameterTypesList()
        val parse: Pair<String, String> = when {
            method.name == "getInt" && method.returnType == "I" && params == listOf(HookContract.STRING, "I") ->
                "Ljava/lang/Integer;" to "parseInt"
            method.name == "getLong" && method.returnType == "J" && params == listOf(HookContract.STRING, "J") ->
                "Ljava/lang/Long;" to "parseLong"
            method.name == "getBoolean" && method.returnType == "Z" && params == listOf(HookContract.STRING, "Z") ->
                "Ljava/lang/Boolean;" to "parseBoolean"
            else -> return false
        }

        val keyRegister = method.parameterRegister(0)
        if (keyRegister < 0) return false
        val resultRegister = method.parameterRegister(1)
        if (resultRegister < 0) return false
        val wide = method.returnType == "J"
        val label = impl.newLabelForIndex(0)

        val instructions = mutableListOf<BuilderInstruction>()
        instructions += Asm.invokeStatic(
            intArrayOf(keyRegister),
            Asm.methodRef(HOOK_CLASS, "propOverride", listOf(HookContract.STRING), HookContract.STRING)
        )
        instructions += Asm.moveResultObject(keyRegister)
        instructions += Asm.ifEqz(keyRegister, label)
        instructions += Asm.invokeStatic(
            intArrayOf(keyRegister),
            Asm.methodRef(parse.first, parse.second, listOf(HookContract.STRING), method.returnType)
        )
        if (wide) {
            instructions += Asm.moveResultWide(resultRegister)
            instructions += Asm.returnWide(resultRegister)
        } else {
            instructions += Asm.moveResult(resultRegister)
            instructions += Asm.returnInt(resultRegister)
        }
        impl.addAll(0, instructions)
        return true
    }
}
