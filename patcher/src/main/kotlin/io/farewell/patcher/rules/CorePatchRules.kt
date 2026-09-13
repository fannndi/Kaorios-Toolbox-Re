package io.farewell.patcher.rules

import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

class SigningDetailsRule : MethodRule {
    override val name = "corepatch.signingdetails.checkcapability"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK || kind == JarKind.SERVICES

    private val targets = setOf(
        "Landroid/content/pm/SigningDetails;",
        "Landroid/content/pm/PackageParser\$SigningDetails;"
    )

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type !in targets) return false
        if (method.returnType != "Z") return false
        if (method.name != "hasAncestorOrSelf" && !method.name.startsWith("checkCapability")) return false
        if (impl.registerCount < 1) return false
        Asm.returnTrue(impl)
        return true
    }
}

class MessageDigestForceRule : MethodRule {
    override val name = "corepatch.messagedigest.isequal"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK || kind == JarKind.SERVICES

    private val targets = setOf(
        "Landroid/util/apk/ApkSignatureSchemeV2Verifier;",
        "Landroid/util/apk/ApkSignatureSchemeV3Verifier;",
        "Landroid/util/apk/ApkSigningBlockUtils;"
    )

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type !in targets) return false
        var patched = false
        for (index in (0 until impl.size).reversed()) {
            val instruction = impl.instructions[index]
            val reference = instruction.methodReference() ?: continue
            if (reference.definingClass != "Ljava/security/MessageDigest;" || reference.name != "isEqual") continue
            val nextIndex = index + 1
            if (nextIndex >= impl.size) continue
            val next = impl.instructions[nextIndex]
            if (next.opcode != Opcode.MOVE_RESULT && next.opcode != Opcode.MOVE_RESULT_OBJECT) continue
            val register = (next as OneRegisterInstruction).registerA
            impl.replaceInstruction(nextIndex, Asm.constOne(register))
            patched = true
        }
        return patched
    }
}

class MinimumSignatureSchemeRule : MethodRule {
    override val name = "corepatch.apksignatureverifier.minimumscheme"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK || kind == JarKind.SERVICES

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/util/apk/ApkSignatureVerifier;") return false
        if (method.name != "getMinimumSignatureSchemeVersionForTargetSdk" || method.returnType != "I") return false
        if (impl.registerCount < 1) return false
        Asm.returnZero(impl)
        return true
    }
}

class StrictJarVerifierRule : MethodRule {
    override val name = "corepatch.strictjarverifier.verifydigest"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/util/jar/StrictJarVerifier;") return false
        if (method.name != "verifyMessageDigest" || method.returnType != "Z") return false
        if (impl.registerCount < 1) return false
        Asm.returnTrue(impl)
        return true
    }
}
