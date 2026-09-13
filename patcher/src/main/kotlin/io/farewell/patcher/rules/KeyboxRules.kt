package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

private val KEYSTORE_SPI_TARGETS = setOf(
    "Landroid/security/keystore2/AndroidKeyStoreSpi;",
    "Landroid/security/keystore/AndroidKeyStoreSpi;"
)

class CertificateChainAliasRule : MethodRule {
    override val name = "framework.keystore.certificateChainAlias"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type !in KEYSTORE_SPI_TARGETS) return false
        if (method.name != "engineGetCertificateChain" || method.returnType != HookContract.CERTIFICATE_ARRAY) return false
        if (method.parameterTypesList() != listOf(HookContract.STRING)) return false
        if (!method.hasLocalRegister()) return false

        val aliasRegister = method.parameterRegister(0)
        if (aliasRegister < 0) return false
        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(aliasRegister),
                Asm.methodRef(
                    HOOK_CLASS, HookContract.CERTIFICATE_CHAIN_FOR_ALIAS,
                    listOf(HookContract.STRING), HookContract.CERTIFICATE_ARRAY
                )
            ),
            Asm.moveResultObject(scratch),
            Asm.ifEqz(scratch, label),
            Asm.returnObject(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class CertificateAliasRule : MethodRule {
    override val name = "framework.keystore.certificateAlias"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type !in KEYSTORE_SPI_TARGETS) return false
        if (method.name != "engineGetCertificate" || method.returnType != "Ljava/security/cert/Certificate;") return false
        if (method.parameterTypesList() != listOf(HookContract.STRING)) return false
        if (!method.hasLocalRegister()) return false

        val aliasRegister = method.parameterRegister(0)
        if (aliasRegister < 0) return false
        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(aliasRegister),
                Asm.methodRef(
                    HOOK_CLASS, HookContract.CERTIFICATE_FOR_ALIAS,
                    listOf(HookContract.STRING), "Ljava/security/cert/Certificate;"
                )
            ),
            Asm.moveResultObject(scratch),
            Asm.ifEqz(scratch, label),
            Asm.returnObject(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}
