package io.farewell.patcher.rules

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.Label
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

object Asm {

    fun methodRef(
        definingClass: String,
        name: String,
        parameters: List<String>,
        returnType: String
    ): ImmutableMethodReference = ImmutableMethodReference(definingClass, name, parameters, returnType)

    fun invokeStatic(registers: IntArray, reference: MethodReference): BuilderInstruction {
        return if (canUse35c(registers)) {
            val padded = IntArray(5)
            registers.copyInto(padded)
            BuilderInstruction35c(
                Opcode.INVOKE_STATIC,
                registers.size,
                padded[0], padded[1], padded[2], padded[3], padded[4],
                reference
            )
        } else {
            BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, registers.first(), registers.size, reference)
        }
    }

    fun invokeInterface(registers: IntArray, reference: MethodReference): BuilderInstruction {
        return if (canUse35c(registers)) {
            val padded = IntArray(5)
            registers.copyInto(padded)
            BuilderInstruction35c(
                Opcode.INVOKE_INTERFACE,
                registers.size,
                padded[0], padded[1], padded[2], padded[3], padded[4],
                reference
            )
        } else {
            BuilderInstruction3rc(Opcode.INVOKE_INTERFACE_RANGE, registers.first(), registers.size, reference)
        }
    }

    private fun canUse35c(registers: IntArray): Boolean {
        if (registers.size > 5) return false
        return registers.all { it in 0..15 }
    }

    fun moveResultObject(register: Int) = BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)

    fun moveResult(register: Int) = BuilderInstruction11x(Opcode.MOVE_RESULT, register)

    fun returnObject(register: Int) = BuilderInstruction11x(Opcode.RETURN_OBJECT, register)

    fun returnInt(register: Int) = BuilderInstruction11x(Opcode.RETURN, register)

    fun returnVoid() = BuilderInstruction10x(Opcode.RETURN_VOID)

    fun ifEqz(register: Int, label: Label) = BuilderInstruction21t(Opcode.IF_EQZ, register, label)

    fun ifNez(register: Int, label: Label) = BuilderInstruction21t(Opcode.IF_NEZ, register, label)

    fun constString(register: Int, value: String) =
        BuilderInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

    fun constZero(register: Int): BuilderInstruction =
        if (register < 16) BuilderInstruction11n(Opcode.CONST_4, register, 0)
        else BuilderInstruction21s(Opcode.CONST_16, register, 0)

    fun constOne(register: Int): BuilderInstruction =
        if (register < 16) BuilderInstruction11n(Opcode.CONST_4, register, 1)
        else BuilderInstruction21s(Opcode.CONST_16, register, 1)

    fun replaceBody(implementation: MutableMethodImplementation, instructions: List<BuilderInstruction>) {
        implementation.removeRange(0, implementation.size)
        implementation.addAll(0, instructions)
    }

    fun returnTrue(implementation: MutableMethodImplementation) {
        replaceBody(implementation, listOf(constOne(0), returnInt(0)))
    }

    fun returnZero(implementation: MutableMethodImplementation) {
        replaceBody(implementation, listOf(constZero(0), returnInt(0)))
    }

    fun returnVoidBody(implementation: MutableMethodImplementation) {
        replaceBody(implementation, listOf(returnVoid()))
    }
}

val MutableMethodImplementation.size: Int
    get() = instructions.size

fun MutableMethodImplementation.addAll(index: Int, items: List<BuilderInstruction>) {
    items.forEachIndexed { offset, instruction -> addInstruction(index + offset, instruction) }
}

fun MutableMethodImplementation.removeRange(index: Int, count: Int) {
    repeat(count) { removeInstruction(index) }
}

fun MethodImplementation.toMutable(): MutableMethodImplementation = MutableMethodImplementation(this)
