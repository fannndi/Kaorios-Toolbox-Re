package io.farewell.patcher

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import io.farewell.patcher.integrity.AttestationAudit
import io.farewell.patcher.integrity.IntegrityData
import io.farewell.patcher.integrity.KeyboxVerifier
import io.farewell.patcher.integrity.VerdictParser
import java.io.File

private val INTEGRITY_DIR = File("integrity-data")

fun main(args: Array<String>) {
    var input: File? = null
    var output: File? = null
    var hook: File? = null
    var kind = JarKind.FRAMEWORK
    var scan = false
    var profile: PlatformProfile? = null
    var grep: Regex? = null
    var verifyKeybox: File? = null
    var fetchIntegrity = false
    var decodeVerdict: File? = null
    var auditAttestation: File? = null
    var auditChallenge: String? = null
    var auditPackage: String? = null
    var patchProp: File? = null
    var propsFile: File? = null
    var dumpPropMaps: File? = null

    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--input" -> input = File(args[index + 1]).also { index++ }
            "--output" -> output = File(args[index + 1]).also { index++ }
            "--hook" -> hook = File(args[index + 1]).also { index++ }
            "--kind" -> kind = JarKind.valueOf(args[index + 1].uppercase()).also { index++ }
            "--profile" -> profile = PlatformProfiles.byId(args[index + 1]).also { index++ }
            "--grep" -> grep = Regex(args[index + 1], RegexOption.IGNORE_CASE).also { index++ }
            "--verify-keybox" -> verifyKeybox = File(args[index + 1]).also { index++ }
            "--fetch-integrity" -> fetchIntegrity = true
            "--decode-verdict" -> decodeVerdict = File(args[index + 1]).also { index++ }
            "--audit-attestation" -> auditAttestation = File(args[index + 1]).also { index++ }
            "--challenge" -> auditChallenge = args[index + 1].also { index++ }
            "--package" -> auditPackage = args[index + 1].also { index++ }
            "--patch-prop" -> patchProp = File(args[index + 1]).also { index++ }
            "--props" -> propsFile = File(args[index + 1]).also { index++ }
            "--dump-prop-maps" -> dumpPropMaps = File(args[index + 1]).also { index++ }
            "--scan" -> scan = true
            else -> error("Unknown argument: ${args[index]}")
        }
        index++
    }

    if (patchProp != null) {
        val props = propsFile?.takeIf { it.exists() } ?: error("--props <json> is required")
        val map = LinkedHashMap<String, String>()
        val json = org.json.JSONObject(props.readText())
        for (key in json.keys()) {
            map[key] = json.optString(key, "")
        }
        val result = PropPatcher.apply(patchProp.readText(), map)
        val target = output ?: File(patchProp.parentFile, patchProp.name + ".patched")
        target.writeText(result.content)
        println("Prop patch: ${result.replaced} replaced, ${result.appended} appended -> ${target.absolutePath}")
        return
    }

    if (dumpPropMaps != null) {
        val pifFile = propsFile?.takeIf { it.exists() }
            ?: error("--dump-prop-maps requires --props <Pif-props.json>")
        val identity = PropSpoof.identityFrom(org.json.JSONObject(pifFile.readText()))
            ?: error("Could not build a spoof identity from ${pifFile.absolutePath}")
        dumpPropMaps.mkdirs()
        println("Spoof identity: ${identity.brand}/${identity.device} ${identity.model} (${identity.product})")
        println("Fingerprint: ${identity.fingerprint}")
        for (partition in PropPartition.entries) {
            val map = PropSpoof.propMapFor(partition, identity)
            if (map.isEmpty()) continue
            val json = org.json.JSONObject()
            for ((key, value) in map) json.put(key, value)
            val target = File(dumpPropMaps, "${partition.name}.json")
            target.writeText(json.toString(2))
            println("  ${partition.name.padEnd(11)} ${map.size} keys -> ${target.absolutePath}")
        }
        return
    }

    if (fetchIntegrity) {
        val snapshot = IntegrityData.download(INTEGRITY_DIR, force = true)
        println("Google roots: ${snapshot.rootPems.size}, status entries: ${snapshot.statuses.size}")
        println("root file: ${snapshot.rootFile.absolutePath}")
        println("status file: ${snapshot.statusFile.absolutePath}")
        return
    }

    if (verifyKeybox != null) {
        val snapshot = IntegrityData.download(INTEGRITY_DIR)
        val report = KeyboxVerifier.verify(verifyKeybox.readText(), snapshot.rootPems, snapshot.statuses)
        for (line in report.lines()) {
            println(line)
        }
        println(report.summary())
        return
    }

    if (decodeVerdict != null) {
        // The raw integrity token is encrypted (JWE compact serialization) and
        // only Google's server can open it (Cloud project + OAuth, POST
        // https://playintegrity.googleapis.com/v1/PACKAGE:decodeIntegrityToken).
        // This reads the *decrypted* verdict JSON and answers "did my spoof pass?".
        val pasted = decodeVerdict.readText()
        if (VerdictParser.looksLikeRawToken(pasted)) {
            println("That is a RAW encrypted token, not the decrypted verdict - only Google's server can open it:")
            println("  1. Create a service account with the playintegrity scope on your Google Cloud project.")
            println("  2. Decrypt it: POST https://playintegrity.googleapis.com/v1/PACKAGE_NAME:decodeIntegrityToken")
            println("     body { \"integrityToken\": \"<the token>\" } with an OAuth access token.")
            println("  3. Save the tokenPayloadExternal JSON and feed THAT file back to --decode-verdict.")
            println("Decrypt each token exactly once: re-decrypting the same token clears every verdict.")
            return
        }
        val verdict = VerdictParser.parse(pasted)
            ?: error("Could not parse ${decodeVerdict.absolutePath} as a verdict JSON")
        for (line in VerdictParser.summarize(verdict)) {
            println(line)
        }
        return
    }

    if (auditAttestation != null) {
        // Audits an attestation chain we served (PEM, leaf first) against PIF
        // Detector's checks: link signatures, CA issuers, Google-root anchoring,
        // challenge echo. Run it on a chain dumped from a device so a forgery
        // failure is seen here before a detector sees it.
        val snapshot = IntegrityData.download(INTEGRITY_DIR)
        val chain = AttestationAudit.parsePemChain(auditAttestation.readText())
        if (chain.isEmpty()) {
            error("No PEM certificates found in ${auditAttestation.absolutePath}")
        }
        val challenge = auditChallenge?.let { hexToBytes(it) }
        val report = AttestationAudit.audit(chain, snapshot.rootPems, challenge, auditPackage)
        for (line in report.lines()) {
            println(line)
        }
        println(report.summary())
        return
    }

    val source = input ?: error("--input is required")
    if (grep != null) {
        grepClasses(source, grep)
        return
    }
    if (scan) {
        scanJar(source)
        return
    }

    val target = output ?: error("--output is required")
    val hookDex = hook?.takeIf { it.exists() }?.readBytes()
    val activeProfile = profile
        ?: error("--profile is required (${PlatformProfiles.ALL.joinToString { it.id }})")

    println("Patching ${source.absolutePath} ($kind, ${activeProfile.id})")
    val report = JarPatcher.patch(source, target, kind, hookDex, activeProfile) { message -> println("  $message") }
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
    println("Verify: $dexCount dex files, $hookCallCount contain hook calls")
    check(dexCount > 0) { "Output jar has no dex files" }
}

private fun hexToBytes(text: String): ByteArray {
    val clean = text.trim().replace(":", "").replace(" ", "")
    require(clean.length % 2 == 0) { "--challenge must be hex with an even number of digits" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun grepClasses(source: File, pattern: Regex) {
    println("Grepping ${source.absolutePath} for /${pattern.pattern}/i")
    val container = DexFileFactory.loadDexContainer(source, Opcodes.getDefault())
    var matches = 0
    for (entryName in container.dexEntryNames) {
        val entry = container.getEntry(entryName) ?: continue
        for (classDef in entry.dexFile.classes) {
            if (pattern.containsMatchIn(classDef.type)) {
                matches++
                println("CLASS ${classDef.type}  [$entryName]")
            }
        }
    }
    println("Matches: $matches")
}

private fun scanJar(source: File) {
    println("Scanning ${source.absolutePath}")
    val container = DexFileFactory.loadDexContainer(source, Opcodes.getDefault())
    // Exactly the classes the rules look for, surya scope only. Both keystore
    // generations are listed because MIUI 12 uses android.security.keystore and
    // MIUI 13/14 use android.security.keystore2. SettingsProvider is deliberately
    // absent: it lives in /system/priv-app/SettingsProvider/SettingsProvider.apk
    // and never in services.jar, so no SERVICES-kind rule can reach it.
    val targets = setOf(
        // framework.jar
        "Landroid/app/Instrumentation;",
        "Landroid/app/ApplicationPackageManager;",
        "Landroid/os/Build;",
        "Landroid/os/Build\$VERSION;",
        "Landroid/os/SystemProperties;",
        "Landroid/provider/Settings\$NameValueCache;",
        "Landroid/provider/Settings_NameValueCache;",
        "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
        "Landroid/security/keystore2/AndroidKeyStoreSpi;",
        "Landroid/security/keystore/AndroidKeyStoreKeyPairGeneratorSpi;",
        "Landroid/security/keystore/AndroidKeyStoreSpi;",
        "Landroid/content/pm/PackageParser\$SigningDetails;",
        "Landroid/content/pm/SigningDetails;",
        "Landroid/util/apk/ApkSignatureSchemeV2Verifier;",
        "Landroid/util/apk/ApkSignatureSchemeV3Verifier;",
        "Landroid/util/apk/ApkSigningBlockUtils;",
        "Landroid/util/apk/ApkSignatureVerifier;",
        "Landroid/util/jar/StrictJarVerifier;",
        // services.jar
        "Lcom/android/server/SystemServer;",
        "Lcom/android/server/pm/AppsFilter;",
        "Lcom/android/server/pm/PackageManagerService;",
        "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;",
        "Lcom/android/server/wm/WindowState;",
        "Lcom/android/server/wm/WindowStateAnimator;",
        "Lcom/android/server/wm/WindowManagerService;"
    )
    var classes = 0
    for (entryName in container.dexEntryNames) {
        val entry = container.getEntry(entryName) ?: continue
        val dexFile = entry.dexFile
        for (classDef in dexFile.classes) {
            classes++
            if (classDef.type !in targets) continue
            println("CLASS ${classDef.type}  [${entryName}]")
            val finalFields = classDef.fields.filter { (it.accessFlags and 0x10) != 0 }.map { it.name }
            if (finalFields.isNotEmpty()) {
                println("  final fields: ${finalFields.joinToString(", ")}")
            }
            for (method in classDef.methods) {
                val registers = method.implementation?.registerCount ?: -1
                val instructions = method.implementation?.instructions?.count() ?: 0
                println("  ${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}  [regs=$registers, insns=$instructions]")
            }
        }
    }
    println("Scanned $classes classes")
}
