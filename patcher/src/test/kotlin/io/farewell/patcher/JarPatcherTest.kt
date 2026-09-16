package io.farewell.patcher

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.farewell.patcher.rules.methodReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises [JarPatcher] and the rule engine against synthetic jars, so the dex
 * side is covered without depending on a ROM extraction.
 *
 * The fixtures state a signature exactly — descriptor, name, parameters, return
 * type — because that is the contract a rule matches on. A test that fails here
 * means either the rule's expectations or the engine changed.
 */
class JarPatcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var hookDex: ByteArray

    @Before
    fun setUp() {
        // A realistic hook dex: it must at least contain the generated hook class.
        hookDex = DexFixture.dex(
            temp.root,
            DexFixture.clazz(
                HookIdentity.HOOK_CLASS,
                DexFixture.method(HookIdentity.HOOK_CLASS, HookIdentity.M_GET_FRAMEWORK_VERSION, emptyList(), "I")
            )
        )
    }

    @After
    fun tearDown() {
        // TemporaryFolder cleans up; nothing to do.
    }

    // --- helpers -------------------------------------------------------------

    private fun dexOf(vararg classes: com.android.tools.smali.dexlib2.iface.ClassDef): ByteArray =
        DexFixture.dex(temp.root, *classes)

    private fun bodyOf(dex: DexBackedDexFile, type: String, name: String): List<Instruction> {
        val method: Method = dex.classes.first { it.type == type }.methods.first { it.name == name }
        assertNotNull("$type->$name must have a body", method.implementation)
        return method.implementation!!.instructions.toList()
    }

    private fun constants(body: List<Instruction>): List<Int> =
        body.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }

    private fun entryBytes(jar: File, name: String): ByteArray =
        java.util.zip.ZipFile(jar).use { zip ->
            zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
        }

    private fun callsHook(body: List<Instruction>): Boolean =
        body.any { instruction ->
            instruction.methodReference()?.definingClass == HookIdentity.HOOK_CLASS
        }

    private fun strictJarVerifierClass() = DexFixture.clazz(
        "Landroid/util/jar/StrictJarVerifier;",
        DexFixture.method(
            "Landroid/util/jar/StrictJarVerifier;",
            "verifyMessageDigest",
            listOf("[B", "[B"),
            "Z",
            DexFixture.Body.FALSE
        )
    )

    private fun strictJarVerifier(): ByteArray = dexOf(strictJarVerifierClass())

    // --- patching ------------------------------------------------------------

    @Test
    fun patchesAMatchingMethodAndReportsTheRule() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val output = File(temp.root, "framework-patched.jar")

        val report = JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        assertTrue(
            "StrictJarVerifierRule must fire: ${report.outcomes.map { it.rule }}",
            report.outcomes.any { it.rule == "corepatch.strictjarverifier.verifydigest" && it.applied }
        )
        assertEquals(1, report.dexFiles)
    }

    @Test
    fun thePatchedMethodReturnsTrue() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val output = File(temp.root, "framework-patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val body = bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Landroid/util/jar/StrictJarVerifier;",
            "verifyMessageDigest"
        )
        // Asm.returnTrue -> const/4 v0, 1 ; return v0
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), body.map { it.opcode })
        assertEquals(listOf(1), constants(body))
    }

    @Test
    fun leavesClassesNoRuleTargetsAlone() {
        val untouched = DexFixture.clazz(
            "Lcom/example/Unrelated;",
            DexFixture.method("Lcom/example/Unrelated;", "flag", emptyList(), "Z", DexFixture.Body.TRUE)
        )
        val input = DexFixture.jar(
            File(temp.root, "framework.jar"),
            listOf(dexOf(strictJarVerifierClass(), untouched))
        )
        val output = File(temp.root, "framework-patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val body = bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Lcom/example/Unrelated;",
            "flag"
        )
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), body.map { it.opcode })
        assertEquals("an unrelated class must keep its body", listOf(1), constants(body))
    }

    @Test
    fun injectsTheHookDexAsTheNextClassesEntry() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val output = File(temp.root, "framework-patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val entries = DexFixture.readDexEntries(output)
        assertEquals(setOf("classes.dex", "classes2.dex"), entries.keys)
        // The injected dex is the hook, byte for byte.
        val injected = java.util.zip.ZipFile(output).use { zip ->
            zip.getInputStream(zip.getEntry("classes2.dex")).use { it.readBytes() }
        }
        assertTrue("the hook dex must be injected verbatim", injected.contentEquals(hookDex))
    }

    @Test
    fun injectsAfterTheHighestExistingDexIndex() {
        val input = DexFixture.jar(
            File(temp.root, "framework.jar"),
            listOf(strictJarVerifier(), strictJarVerifier(), strictJarVerifier())
        )
        val output = File(temp.root, "framework-patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        assertEquals(
            setOf("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex"),
            DexFixture.readDexEntries(output).keys
        )
    }

    @Test
    fun keepsNonDexEntries() {
        val input = DexFixture.jar(
            File(temp.root, "framework.jar"),
            listOf(strictJarVerifier()),
            mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray())
        )
        val output = File(temp.root, "framework-patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val names = java.util.zip.ZipFile(output).use { zip ->
            java.util.Collections.list(zip.entries()).map { it.name }
        }
        assertTrue("non-dex entries must survive", names.contains("META-INF/MANIFEST.MF"))
    }

    @Test
    fun doesNotPatchTwice() {
        val input = installerJar()
        val once = File(temp.root, "once.jar")
        JarPatcher.patch(input, once, JarKind.SERVICES, hookDex, PlatformProfiles.SURYA_MIUI13)

        // Sanity: the first pass must have wired a hook call, which is what makes
        // the jar recognisable as already patched.
        assertTrue(
            callsHook(
                bodyOf(
                    DexFixture.readDexEntries(once).getValue("classes.dex"),
                    "Lcom/android/server/pm/PackageManagerService;",
                    "getInstallerPackageName"
                )
            )
        )

        val twice = File(temp.root, "twice.jar")
        val messages = mutableListOf<String>()
        JarPatcher.patch(once, twice, JarKind.SERVICES, hookDex, PlatformProfiles.SURYA_MIUI13) {
            messages += it
        }

        assertTrue(
            "a re-patch must be detected: $messages",
            messages.any { it.contains("already contains hook calls") }
        )
        assertEquals(
            "no second hook dex may be injected",
            setOf("classes.dex", "classes2.dex"),
            DexFixture.readDexEntries(twice).keys
        )
        // The strongest check: the already-patched dex is copied through verbatim,
        // so re-patching cannot compound the rewrites.
        assertTrue(
            "an already-patched dex must not be rewritten",
            entryBytes(once, "classes.dex").contentEquals(entryBytes(twice, "classes.dex"))
        )
    }

    /**
     * Boundary of the re-patch detection: it looks for a reference to the hook class,
     * not for a rewritten body. A jar patched only by rules that merely change a
     * return value (`StrictJarVerifierRule`, `MinimumSignatureSchemeRule`) carries no
     * such reference and is not detected. In practice framework.jar always also gets
     * `InstrumentationInit` and the `SystemProperties` rules, which do call the hook,
     * so the guard works where it matters.
     */
    @Test
    fun rePatchDetectionNeedsAHookCallNotJustARewrittenBody() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val once = File(temp.root, "once.jar")
        JarPatcher.patch(input, once, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val twice = File(temp.root, "twice.jar")
        val messages = mutableListOf<String>()
        JarPatcher.patch(once, twice, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13) {
            messages += it
        }

        assertTrue(
            "no hook call was introduced, so the jar is not recognised: $messages",
            messages.any { it.contains("injected") }
        )
    }

    @Test
    fun worksWithoutAHookDex() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val output = File(temp.root, "framework-patched.jar")

        val report = JarPatcher.patch(input, output, JarKind.FRAMEWORK, null, PlatformProfiles.SURYA_MIUI13)

        assertTrue(report.outcomes.any { it.applied })
        assertEquals("no hook dex means no extra entry", setOf("classes.dex"), DexFixture.readDexEntries(output).keys)
    }

    // --- apiRange gating -----------------------------------------------------

    private fun minimumScheme(): ByteArray = dexOf(
        DexFixture.clazz(
            "Landroid/util/apk/ApkSignatureVerifier;",
            DexFixture.method(
                "Landroid/util/apk/ApkSignatureVerifier;",
                "getMinimumSignatureSchemeVersionForTargetSdk",
                listOf("I"),
                "I",
                // Starts at 1 so "patched to 0" is observable.
                DexFixture.Body.TRUE
            )
        )
    )

    @Test
    fun apiGatedRuleFiresOnTheNewerRom() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(minimumScheme()))
        val output = File(temp.root, "patched.jar")

        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val body = bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Landroid/util/apk/ApkSignatureVerifier;",
            "getMinimumSignatureSchemeVersionForTargetSdk"
        )
        // Asm.returnZero -> const/4 v0, 0 ; return v0
        assertEquals(listOf(0), constants(body))
    }

    @Test
    fun apiGatedRuleStaysDormantOnTheOlderRom() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(minimumScheme()))
        val output = File(temp.root, "patched.jar")

        // MinimumSignatureSchemeRule is gated to API 31+, and MIUI 12 is API 29.
        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI12)

        val body = bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Landroid/util/apk/ApkSignatureVerifier;",
            "getMinimumSignatureSchemeVersionForTargetSdk"
        )
        assertEquals("the rule must not run below its apiRange", listOf(1), constants(body))
    }

    // --- hook wiring ---------------------------------------------------------

    private fun installerClass() = DexFixture.clazz(
        "Lcom/android/server/pm/PackageManagerService;",
        DexFixture.method(
            "Lcom/android/server/pm/PackageManagerService;",
            "getInstallerPackageName",
            listOf("Ljava/lang/String;"),
            "Ljava/lang/String;",
            DexFixture.Body.NULL,
            isStatic = false
        )
    )

    private fun installerJar(): File =
        DexFixture.jar(File(temp.root, "services.jar"), listOf(dexOf(installerClass())))

    @Test
    fun servicesRuleWiresACallToTheHook() {
        val input = installerJar()
        val output = File(temp.root, "services-patched.jar")

        val report = JarPatcher.patch(input, output, JarKind.SERVICES, hookDex, PlatformProfiles.SURYA_MIUI13)

        assertTrue(
            "PackageManagerInstallerRule must fire: ${report.outcomes.map { it.rule }}",
            report.outcomes.any { it.applied }
        )
        val body = bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Lcom/android/server/pm/PackageManagerService;",
            "getInstallerPackageName"
        )
        assertTrue("the patched body must call the generated hook class", callsHook(body))
        assertEquals("and must still return an object", Opcode.RETURN_OBJECT, body.last().opcode)
    }

    @Test
    fun theHookClassIsNotPresentBeforePatching() {
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val before = DexFixture.readDexEntries(input).getValue("classes.dex")

        assertFalse(
            "the fixture must not already look patched",
            before.classes.any { it.type == HookIdentity.HOOK_CLASS }
        )
    }

    @Test
    fun registerCountIsRespectedWhenRewritingABody() {
        // A body rewrite needs a local register; the fixture reserves one per method.
        val input = DexFixture.jar(File(temp.root, "framework.jar"), listOf(strictJarVerifier()))
        val output = File(temp.root, "patched.jar")
        JarPatcher.patch(input, output, JarKind.FRAMEWORK, hookDex, PlatformProfiles.SURYA_MIUI13)

        val method: Method = DexFixture.readDexEntries(output).getValue("classes.dex")
            .classes.first { it.type == "Landroid/util/jar/StrictJarVerifier;" }
            .methods.first { it.name == "verifyMessageDigest" }
        val registers = method.implementation!!.registerCount
        val written = (bodyOf(
            DexFixture.readDexEntries(output).getValue("classes.dex"),
            "Landroid/util/jar/StrictJarVerifier;",
            "verifyMessageDigest"
        ).first() as OneRegisterInstruction).registerA

        assertTrue("register $written must be inside $registers", written < registers)
    }
}
