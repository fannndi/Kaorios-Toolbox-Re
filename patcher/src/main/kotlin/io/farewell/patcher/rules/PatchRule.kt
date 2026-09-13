package io.farewell.patcher.rules

import io.farewell.patcher.HookContract
import io.farewell.patcher.JarKind
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

interface PatchRule {
    val name: String
    val apiRange: IntRange get() = 0..Int.MAX_VALUE
    fun enabledFor(kind: JarKind): Boolean
}

interface ClassRule : PatchRule {
    fun applyClass(classDef: ClassDef): ClassDef?
}

interface MethodRule : PatchRule {
    fun applyMethod(classDef: ClassDef, method: Method, impl: MutableMethodImplementation): Boolean
}

const val HOOK_CLASS: String = HookContract.HOOK_CLASS

fun Method.isStaticMethod(): Boolean = AccessFlags.STATIC.isSet(accessFlags)

fun typeWidth(type: String): Int = if (type == "J" || type == "D") 2 else 1

fun Method.parameterWordCount(): Int {
    var words = if (isStaticMethod()) 0 else 1
    for (type in parameterTypes) {
        words += typeWidth(type.toString())
    }
    return words
}

fun Method.hasLocalRegister(): Boolean {
    val registers = implementation?.registerCount ?: return false
    return registers - parameterWordCount() > 0
}

fun Method.localRegister(index: Int = 0): Int {
    val registers = implementation?.registerCount ?: return -1
    val locals = registers - parameterWordCount()
    return if (index < locals) index else -1
}

fun Method.parameterRegister(index: Int): Int {
    val impl = implementation ?: return -1
    var register = impl.registerCount - parameterWordCount()
    if (!isStaticMethod()) {
        register += 1
    }
    for (i in 0 until index) {
        register += typeWidth(parameterTypes[i].toString())
    }
    return register
}

fun Method.parameterTypesList(): List<String> = parameterTypes.map { it.toString() }

fun MutableMethodImplementation.insertBeforeReturns(items: List<BuilderInstruction>) {
    val targets = instructions
        .withIndex()
        .filter { it.value.opcode.name.startsWith("RETURN") }
        .map { it.index }
        .sortedDescending()
    for (index in targets) {
        addAll(index, items)
    }
}

fun MutableMethodImplementation.replaceReturnsObject(
    transform: (register: Int) -> List<BuilderInstruction>
) {
    val targets = instructions
        .withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .sortedByDescending { it.first }
    for ((index, register) in targets) {
        addAll(index, transform(register))
    }
}

fun MutableMethodImplementation.replaceReturnsInt(
    transform: (register: Int) -> List<BuilderInstruction>
): Boolean {
    val targets = instructions
        .withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .sortedByDescending { it.first }
    for ((index, register) in targets) {
        addAll(index, transform(register))
    }
    return targets.isNotEmpty()
}

fun Instruction.methodReference(): MethodReference? {
    val reference = (this as? ReferenceInstruction)?.reference
    return reference as? MethodReference
}

fun Instruction.isInvokeTo(methodName: String): Boolean {
    val reference = methodReference() ?: return false
    return reference.name == methodName
}
