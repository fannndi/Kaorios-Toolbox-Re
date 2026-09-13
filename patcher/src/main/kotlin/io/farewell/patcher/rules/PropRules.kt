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
