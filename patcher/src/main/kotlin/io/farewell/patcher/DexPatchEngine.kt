package io.farewell.patcher

import io.farewell.patcher.rules.AppsFilterRule
import io.farewell.patcher.rules.BuildFieldClassRule
import io.farewell.patcher.rules.CertificateChainRule
import io.farewell.patcher.rules.ClassRule
import io.farewell.patcher.rules.DevicePolicySecureRule
import io.farewell.patcher.rules.GenerateSoftwareKeyPairRule
import io.farewell.patcher.rules.HasSystemFeatureRule
import io.farewell.patcher.rules.HideDevStatusRule
import io.farewell.patcher.rules.HOOK_CLASS
import io.farewell.patcher.rules.ImmutableClassDefBuilder
import io.farewell.patcher.rules.InstrumentationInitRule
import io.farewell.patcher.rules.InstallerSourceRule
import io.farewell.patcher.rules.MessageDigestForceRule
import io.farewell.patcher.rules.MethodRule
import io.farewell.patcher.rules.MinimumSignatureSchemeRule
import io.farewell.patcher.rules.SettingsProviderRule
import io.farewell.patcher.rules.SigningDetailsRule
import io.farewell.patcher.rules.StrictJarVerifierRule
import io.farewell.patcher.rules.SystemServerInitRule
import io.farewell.patcher.rules.WindowManagerCaptureRule
import io.farewell.patcher.rules.WindowSecureRule
import io.farewell.patcher.rules.methodReference
import io.farewell.patcher.rules.withImplementation
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File

class DexPatchEngine(private val kind: JarKind) {

    private data class RuleStat(var count: Int = 0, val samples: MutableList<String> = mutableListOf())

    private val stats = LinkedHashMap<String, RuleStat>()
    private var failedMethods = 0

    private val classRules: List<ClassRule> = listOf(
        BuildFieldClassRule()
    ).filter { it.enabledFor(kind) }

    private val methodRules: List<MethodRule> = listOf(
        InstrumentationInitRule(),
        HasSystemFeatureRule(),
        GenerateSoftwareKeyPairRule(),
        CertificateChainRule(),
        HideDevStatusRule(),
        SystemServerInitRule(),
        AppsFilterRule(),
        InstallerSourceRule(),
        SettingsProviderRule(),
        DevicePolicySecureRule(),
        WindowSecureRule(),
        WindowManagerCaptureRule(),
        SigningDetailsRule(),
        MessageDigestForceRule(),
        MinimumSignatureSchemeRule(),
        StrictJarVerifierRule()
    ).filter { it.enabledFor(kind) }

    fun outcomes(): List<RuleOutcome> {
        val result = mutableListOf<RuleOutcome>()
        for ((rule, stat) in stats) {
            result += RuleOutcome(rule, stat.samples.joinToString(", "), !rule.startsWith("engine.error:"), "x${stat.count}")
        }
        if (failedMethods > 0) {
            result += RuleOutcome("engine.errors", "methods", false, "failed=$failedMethods")
        }
        return result
    }

    fun patchDexTo(dex: DexFile, output: File) {
        val pool = DexPool(Opcodes.getDefault())
        for (classDef in dex.classes) {
            var current = classDef
            for (rule in classRules) {
                val rewritten = try {
                    rule.applyClass(current)
                } catch (throwable: Throwable) {
                    null
                }
                if (rewritten != null) {
                    record(rule.name, current.type)
                    current = rewritten
                }
            }
            val (direct, directChanged) = patchMethods(current, current.directMethods.toList())
            val (virtual, virtualChanged) = patchMethods(current, current.virtualMethods.toList())
            if (directChanged || virtualChanged) {
                current = ImmutableClassDefBuilder.replaceMethods(current, direct, virtual)
            }
            pool.internClass(current)
        }
        pool.writeTo(FileDataStore(output))
    }

    private fun patchMethods(classDef: ClassDef, methods: List<Method>): Pair<List<Method>, Boolean> {
        var changed = false
        val output = methods.map { method ->
            val implementation = method.implementation ?: return@map method
            val mutable = MutableMethodImplementation(implementation)
            var methodChanged = false
            for (rule in methodRules) {
                val applied = try {
                    rule.applyMethod(classDef, method, mutable)
                } catch (throwable: Throwable) {
                    failedMethods++
                    record("engine.error:" + rule.name, classDef.type + "->" + method.name + " " + throwable)
                    false
                }
                if (applied) {
                    methodChanged = true
                    record(rule.name, classDef.type + "->" + method.name)
                }
            }
            if (methodChanged) {
                changed = true
                method.withImplementation(mutable)
            } else {
                method
            }
        }
        return output to changed
    }

    private fun record(rule: String, target: String) {
        val stat = stats.getOrPut(rule) { RuleStat() }
        stat.count++
        if (stat.samples.size < 4) {
            stat.samples += target
        }
    }
}

fun DexFile.hasHookCalls(): Boolean {
    for (classDef in classes) {
        for (method in classDef.methods) {
            val implementation = method.implementation ?: continue
            for (instruction in implementation.instructions) {
                val reference = instruction.methodReference() ?: continue
                if (reference.definingClass == HOOK_CLASS) {
                    return true
                }
            }
        }
    }
    return false
}
