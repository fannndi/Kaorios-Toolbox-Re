import java.io.File
import java.security.SecureRandom

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val hookIdentityFile = layout.buildDirectory.file("hook-identity.txt")
val generatedRoot = layout.buildDirectory.dir("generated")

val generateHookIdentity = tasks.register("generateHookIdentity") {
    group = "farewell"
    description = "Generates a per-build random hook class name and the shared identity constants"

    val templateFile = file("hook/entry-template/KeyStoreHooks.template.java")
    inputs.file(templateFile)
    outputs.file(hookIdentityFile)
    outputs.dir(generatedRoot)

    doLast {
        val identityFile = hookIdentityFile.get().asFile
        identityFile.parentFile.mkdirs()
        val className = if (identityFile.exists()) {
            identityFile.readText().trim()
        } else {
            val random = SecureRandom()
            val suffix = (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
            ("KeyStoreCompat$suffix").also { identityFile.writeText(it) }
        }

        val root = generatedRoot.get().asFile

        val javaFile = File(root, "hook/android/security/keystore2/$className.java")
        javaFile.parentFile.mkdirs()
        javaFile.writeText(templateFile.readText().replace("__CLASS__", className))

        val kotlinFile = File(root, "patcher/io/farewell/patcher/HookIdentity.kt")
        kotlinFile.parentFile.mkdirs()
        kotlinFile.writeText(
            "package io.farewell.patcher\n\n" +
                "object HookIdentity {\n" +
                "    const val HOOK_CLASS = \"Landroid/security/keystore2/$className;\"\n" +
                "}\n"
        )
    }
}

tasks.matching { it.name == "clean" }.configureEach {
    doLast {
        hookIdentityFile.get().asFile.delete()
    }
}
