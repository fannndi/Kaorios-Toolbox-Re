package io.farewell.patcher

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File

fun main(args: Array<String>) {
    var input: File? = null
    var output: File? = null
    var hook: File? = null
    var kind = JarKind.FRAMEWORK
    var scan = false
    var profile = PlatformProfiles.MODERN

    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--input" -> input = File(args[index + 1]).also { index++ }
            "--output" -> output = File(args[index + 1]).also { index++ }
            "--hook" -> hook = File(args[index + 1]).also { index++ }
            "--kind" -> kind = JarKind.valueOf(args[index + 1].uppercase()).also { index++ }
            "--profile" -> profile = PlatformProfiles.byId(args[index + 1]).also { index++ }
            "--scan" -> scan = true
            else -> error("Unknown argument: ${args[index]}")
        }
        index++
    }

    val source = input ?: error("--input is required")
    if (scan) {
        scanJar(source)
        return
    }

    val target = output ?: error("--output is required")
    val hookDex = hook?.takeIf { it.exists() }?.readBytes()

    println("Patching ${source.absolutePath} ($kind, ${profile.id})")
    val report = JarPatcher.patch(source, target, kind, hookDex, profile) { message -> println("  $message") }
    println("Report: ${report.summary()}")
    for (outcome in report.outcomes) {
        println("  ${if (outcome.applied) "+" else "-"} ${outcome.rule} ${outcome.detail} ${outcome.target}")
    }

    val container = DexFileFactory.loadDexContainer(target, Opcodes.getDefault())
    var dexCount = 0
    var hookCallCount = 0
    for (entryName in container.dexEntryNames) {
        val entry = container.getEntry(entryName) ?: continue
        val dexFile = entry.dexFile
        dexCount++
        if (dexFile.hasHookCalls()) {
            hookCallCount++
        }
    }
    println("Verify: $dexCount dex files, $hookCallCount contain FarewellHook calls")
    check(dexCount > 0) { "Output jar has no dex files" }
}

private fun scanJar(source: File) {
    println("Scanning ${source.absolutePath}")
    val container = DexFileFactory.loadDexContainer(source, Opcodes.getDefault())
    val targets = setOf(
        "Lcom/android/server/pm/AppsFilterBase;",
        "Lcom/android/server/pm/AppsFilterImpl;",
        "Lcom/android/server/pm/AppsFilter;",
        "Lcom/android/server/pm/ComputerEngine;",
        "Lcom/android/server/SystemServer;",
        "Landroid/os/Build;",
        "Landroid/os/Build\$VERSION;",
        "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
        "Landroid/security/keystore2/AndroidKeyStoreSpi;",
        "Landroid/security/keystore/AndroidKeyStoreKeyPairGeneratorSpi;",
        "Landroid/security/keystore/AndroidKeyStoreSpi;",
        "Landroid/provider/Settings\$NameValueCache;",
        "Landroid/provider/Settings_NameValueCache;",
        "Lcom/android/providers/settings/SettingsProvider;",
        "Landroid/app/Instrumentation;",
        "Landroid/app/ApplicationPackageManager;",
        "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;",
        "Lcom/android/server/wm/WindowState;",
        "Lcom/android/server/wm/WindowStateAnimator;",
        "Lcom/android/server/wm/WindowManagerService;",
        "Landroid/util/apk/ApkSignatureSchemeV2Verifier;",
        "Landroid/util/apk/ApkSignatureSchemeV3Verifier;",
        "Landroid/util/apk/ApkSigningBlockUtils;",
        "Landroid/util/apk/ApkSignatureVerifier;",
        "Landroid/content/pm/SigningDetails;",
        "Landroid/content/pm/PackageParser\$SigningDetails;",
        "Landroid/util/jar/StrictJarVerifier;",
        "Lcom/android/server/pm/PackageManagerServiceUtils;",
        "Lcom/android/server/pm/KeySetManagerService;"
    )
    val settingsProviderHint = Regex("SettingsProvider")
    var classes = 0
    for (entryName in container.dexEntryNames) {
        val entry = container.getEntry(entryName) ?: continue
        val dexFile = entry.dexFile
        for (classDef in dexFile.classes) {
            classes++
            val interesting = classDef.type in targets || settingsProviderHint.containsMatchIn(classDef.type)
            if (!interesting) continue
            println("CLASS ${classDef.type}  [${entryName}]")
            val finalFields = classDef.fields.filter { (it.accessFlags and 0x10) != 0 }.map { it.name }
            if (finalFields.isNotEmpty()) {
                println("  final fields: ${finalFields.joinToString(", ")}")
            }
            for (method in classDef.methods) {
                println("  ${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
            }
        }
    }
    println("Scanned $classes classes")
}
