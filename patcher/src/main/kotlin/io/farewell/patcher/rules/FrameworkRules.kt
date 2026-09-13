package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

class InstrumentationInitRule : MethodRule {
    override val name = "framework.instrumentation.initContext"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/app/Instrumentation;") return false
        if (method.name != "newApplication" || method.returnType != HookContract.APPLICATION) return false
        val params = method.parameterTypesList()
        val contextIndex = when {
            params.size == 2 && params[0] == HookContract.CLASS && params[1] == HookContract.CONTEXT -> 1
            params.size == 3 && params[0] == HookContract.CLASS_LOADER &&
                params[1] == HookContract.STRING && params[2] == HookContract.CONTEXT -> 2
            else -> return false
        }
        val contextRegister = method.parameterRegister(contextIndex)
        if (contextRegister < 0) return false
        val invoke = Asm.invokeStatic(
            intArrayOf(contextRegister),
            Asm.methodRef(HOOK_CLASS, HookContract.INIT_CONTEXT, listOf(HookContract.CONTEXT), "V")
        )
        impl.insertBeforeReturns(listOf(invoke))
        return true
    }
}

class HasSystemFeatureRule : MethodRule {
    override val name = "framework.applicationpackagemanager.hasSystemFeature"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != "Landroid/app/ApplicationPackageManager;") return false
        if (method.name != "hasSystemFeature" || method.returnType != "Z") return false
        val params = method.parameterTypesList()
        if (params != listOf(HookContract.STRING, "I")) return false
        if (!method.hasLocalRegister()) return false
        val scratch = method.localRegister(0)
        val featureRegister = method.parameterRegister(0)
        val flagRegister = method.parameterRegister(1)
        if (featureRegister < 0 || flagRegister < 0) return false

        val label = impl.newLabelForIndex(0)
        val instructions = mutableListOf<BuilderInstruction>()
        instructions += Asm.invokeStatic(
            intArrayOf(featureRegister, flagRegister),
            Asm.methodRef(
                HOOK_CLASS, HookContract.HAS_SYSTEM_FEATURE,
                listOf(HookContract.STRING, "I"), HookContract.BOOLEAN_OBJ
            )
        )
        instructions += Asm.moveResultObject(scratch)
        instructions += Asm.ifEqz(scratch, label)
        instructions += Asm.invokeStatic(
            intArrayOf(scratch),
            Asm.methodRef("Ljava/lang/Boolean;", "booleanValue", emptyList(), "Z")
        )
        instructions += Asm.moveResult(scratch)
        instructions += Asm.returnInt(scratch)
        impl.addAll(0, instructions)
        return true
    }
}

class GenerateSoftwareKeyPairRule(
    private val targetClass: String = "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
    override val apiRange: IntRange = 31..Int.MAX_VALUE
) : MethodRule {
    override val name = "framework.keystore.generateKeyPair"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != targetClass) return false
        if (method.name != "generateKeyPair" || method.returnType != HookContract.KEY_PAIR) return false
        if (!method.isStaticMethod() && method.parameterTypesList().isNotEmpty()) return false
        if (method.isStaticMethod()) return false
        if (!method.hasLocalRegister()) return false
        val scratch = method.localRegister(0)
        val thisRegister = method.parameterRegister(0)
        if (thisRegister < 0) return false

        val label = impl.newLabelForIndex(0)
        val invoke = Asm.invokeStatic(
            intArrayOf(thisRegister),
            Asm.methodRef(
                HOOK_CLASS, HookContract.INIT_GENERATE_SOFTWARE_KEY_PAIR,
                listOf("Ljava/lang/Object;"), HookContract.KEY_PAIR
            )
        )
        val instructions = listOf<BuilderInstruction>(
            invoke,
            Asm.moveResultObject(scratch),
            Asm.ifEqz(scratch, label),
            Asm.returnObject(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class CertificateChainRule(
    private val targetClass: String = "Landroid/security/keystore2/AndroidKeyStoreSpi;",
    override val apiRange: IntRange = 31..Int.MAX_VALUE
) : MethodRule {
    override val name = "framework.keystore.certificateChain"
    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    override fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean {
        if (classDef.type != targetClass) return false
        if (method.name != "engineGetCertificateChain" || method.returnType != HookContract.CERTIFICATE_ARRAY) return false
        val reference = Asm.methodRef(
            HOOK_CLASS, HookContract.CERTIFICATE_CHAIN_IF_NEEDED,
            listOf(HookContract.CERTIFICATE_ARRAY), HookContract.CERTIFICATE_ARRAY
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

class HideDevStatusRule : MethodRule {
    override val name = "framework.settings.nvchide"
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
        val resolverRegister = method.parameterRegister(0)
        val nameRegister = method.parameterRegister(1)
        val userRegister = method.parameterRegister(2)
        if (resolverRegister < 0 || nameRegister < 0 || userRegister < 0) return false

        val scratch = method.localRegister(0)
        val label = impl.newLabelForIndex(0)
        val instructions = listOf<BuilderInstruction>(
            Asm.invokeStatic(
                intArrayOf(resolverRegister, nameRegister, userRegister),
                Asm.methodRef(
                    HOOK_CLASS, HookContract.SHOULD_HIDE_DEV_STATUS,
                    listOf(HookContract.CONTENT_RESOLVER, HookContract.STRING, "I"), "Z"
                )
            ),
            Asm.moveResult(scratch),
            Asm.ifEqz(scratch, label),
            Asm.constString(scratch, "0"),
            Asm.returnObject(scratch)
        )
        impl.addAll(0, instructions)
        return true
    }
}

class BuildFieldClassRule : ClassRule {
    override val name = "framework.build.unfinal"

    override fun enabledFor(kind: JarKind) = kind == JarKind.FRAMEWORK

    private val buildFields = setOf(
        "BRAND", "BRAND_FOR_ATTESTATION", "DEVICE", "DEVICE_FOR_ATTESTATION", "FINGERPRINT",
        "HARDWARE", "ID", "MANUFACTURER", "MANUFACTURER_FOR_ATTESTATION", "MODEL",
        "MODEL_FOR_ATTESTATION", "PRODUCT", "PRODUCT_FOR_ATTESTATION", "TAGS", "TIME", "TYPE", "USER"
    )

    private val versionFields = setOf(
        "RELEASE", "RELEASE_OR_CODENAME", "RELEASE_OR_PREVIEW_DISPLAY",
        "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT"
    )

    override fun applyClass(classDef: ClassDef): ClassDef? {
        val target = when (classDef.type) {
            "Landroid/os/Build;" -> buildFields
            "Landroid/os/Build\$VERSION;" -> versionFields
            else -> return null
        }
        var changed = false
        val staticFields = classDef.staticFields.map { field ->
            if (field.name in target && AccessFlags.FINAL.isSet(field.accessFlags)) {
                changed = true
                ImmutableField(
                    field.definingClass,
                    field.name,
                    field.type,
                    field.accessFlags and AccessFlags.FINAL.value.inv(),
                    field.initialValue,
                    field.annotations,
                    field.hiddenApiRestrictions
                )
            } else {
                field
            }
        }
        val instanceFields = classDef.instanceFields.map { field ->
            if (field.name in target && AccessFlags.FINAL.isSet(field.accessFlags)) {
                changed = true
                ImmutableField(
                    field.definingClass,
                    field.name,
                    field.type,
                    field.accessFlags and AccessFlags.FINAL.value.inv(),
                    field.initialValue,
                    field.annotations,
                    field.hiddenApiRestrictions
                )
            } else {
                field
            }
        }
        if (!changed) return null
        return ImmutableClassDefBuilder.build(classDef, staticFields, instanceFields)
    }
}

object ImmutableClassDefBuilder {

    fun build(
        classDef: ClassDef,
        staticFields: List<com.android.tools.smali.dexlib2.iface.Field> = classDef.staticFields.toList(),
        instanceFields: List<com.android.tools.smali.dexlib2.iface.Field> = classDef.instanceFields.toList(),
        directMethods: List<Method> = classDef.directMethods.toList(),
        virtualMethods: List<Method> = classDef.virtualMethods.toList()
    ): ClassDef {
        return ImmutableClassDef(
            classDef.type,
            classDef.accessFlags,
            classDef.superclass,
            classDef.interfaces,
            classDef.sourceFile,
            classDef.annotations,
            staticFields.map { it.ensureImmutable() },
            instanceFields.map { it.ensureImmutable() },
            directMethods.map { it.ensureImmutable() },
            virtualMethods.map { it.ensureImmutable() }
        )
    }

    fun replaceMethods(
        classDef: ClassDef,
        directMethods: List<Method>,
        virtualMethods: List<Method>
    ): ClassDef = build(classDef, directMethods = directMethods, virtualMethods = virtualMethods)
}

private fun com.android.tools.smali.dexlib2.iface.Field.ensureImmutable(): ImmutableField {
    if (this is ImmutableField) return this
    return ImmutableField(
        definingClass,
        name,
        type,
        accessFlags,
        initialValue,
        annotations,
        emptySet()
    )
}

private fun Method.ensureImmutable(): ImmutableMethod {
    if (this is ImmutableMethod) return this
    return ImmutableMethod(
        definingClass,
        name,
        parameters,
        returnType,
        accessFlags,
        annotations,
        emptySet(),
        implementation
    )
}

fun Method.withImplementation(impl: MutableMethodImplementation): Method {
    return ImmutableMethod(
        definingClass,
        name,
        parameters,
        returnType,
        accessFlags,
        annotations,
        emptySet(),
        impl
    )
}
