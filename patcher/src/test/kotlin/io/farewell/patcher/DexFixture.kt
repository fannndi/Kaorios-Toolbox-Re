package io.farewell.patcher

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds synthetic dex/jar fixtures so the rule engine can be tested without a
 * ROM. A rule only fires when the class descriptor, method name, parameter list
 * and return type all match exactly, so a fixture is a precise statement of "this
 * is the signature the rule claims to support".
 *
 * Bodies are deliberately trivial — `const/4 v0, 0; return v0` — because the tests
 * assert on the *patched* body, not the original one.
 */
object DexFixture {

    /** The trivial body a fixture method starts with. */
    enum class Body {
        /** `const/4 v0, 0` then `return v0` — a boolean/int method answering false/0. */
        FALSE,

        /** `const/4 v0, 1` then `return v0` — answering true/1. */
        TRUE,

        /** `const/4 v0, 0` then `return-object v0` — answering null. */
        NULL,

        /** `return-void`. */
        VOID
    }

    /** A method whose body is [body], with one local register plus its parameters. */
    fun method(
        owner: String,
        name: String,
        params: List<String>,
        returnType: String,
        body: Body = Body.FALSE,
        isStatic: Boolean = true
    ): ImmutableMethod {
        val access = AccessFlags.PUBLIC.value or if (isStatic) AccessFlags.STATIC.value else 0
        val instructions = when (body) {
            Body.FALSE -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            )
            Body.TRUE -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            )
            Body.NULL -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            )
            Body.VOID -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }
        return ImmutableMethod(
            owner,
            name,
            params.map { ImmutableMethodParameter(it, emptySet(), null) },
            returnType,
            access,
            emptySet(),
            null,
            ImmutableMethodImplementation(1 + params.size, instructions, null, null)
        )
    }

    /** A class with no superclass beyond Object and the given methods. */
    fun clazz(type: String, vararg methods: ImmutableMethod): ImmutableClassDef =
        ImmutableClassDef(
            type,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            methods.toList()
        )

    /** Write the classes to a dex file and return its bytes. */
    fun dex(directory: File, vararg classes: ClassDef): ByteArray {
        val target = File(directory, "fixture-${System.nanoTime()}.dex")
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { pool.internClass(it) }
        pool.writeTo(FileDataStore(target))
        return target.readBytes().also { target.delete() }
    }

    /**
     * Write a jar containing [dexes] as classes.dex, classes2.dex, … plus any
     * extra entries. Entries are stored uncompressed, like a real framework.jar.
     */
    fun jar(
        target: File,
        dexes: List<ByteArray>,
        extraEntries: Map<String, ByteArray> = emptyMap()
    ): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            dexes.forEachIndexed { index, bytes ->
                val name = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, bytes) in extraEntries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    /** Load the dex entries of a jar, keyed by entry name. */
    fun readDexEntries(jar: File): Map<String, com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile> =
        java.util.zip.ZipFile(jar).use { zip ->
            val entries = java.util.Collections.list(zip.entries())
            entries.filter { it.name.endsWith(".dex") }.associate { entry ->
                entry.name to DexSupport.load(zip.getInputStream(entry).use { it.readBytes() })
            }
        }
}
