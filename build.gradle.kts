import java.io.File
import java.security.SecureRandom

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val hookMethodNames = listOf(
    "INIT_CONTEXT",
    "INIT_SYSTEM_SERVER",
    "HAS_SYSTEM_FEATURE",
    "INIT_GENERATE_SOFTWARE_KEY_PAIR",
    "CERTIFICATE_CHAIN_IF_NEEDED",
    "SHOULD_HIDE_DEV_STATUS",
    "SHOULD_HIDE_APP_LIST_FOR_CALLER",
    "SHOULD_HIDE_APP_LIST",
    "FILTER_INSTALLER",
    "SHOULD_REMOVE_SETTING",
    "FILTER_SETTING_VALUE",
    "HAS_SETTING_OVERRIDE",
    "SETTING_OVERRIDE_VALUE",
    "COMBINE_APP_FILTER",
    "COMBINE_APP_FILTER_OBJECT",
    "FILTER_SYSTEM_PROPERTY",
    "PROP_OVERRIDE",
    "CERTIFICATE_CHAIN_FOR_ALIAS",
    "CERTIFICATE_FOR_ALIAS",
    "IS_SECURE_FLAG",
    "GET_FRAMEWORK_VERSION"
)

val hookIdentityFile = layout.buildDirectory.file("hook-identity.txt")
val generatedRoot = layout.buildDirectory.dir("generated")
val renewHookIdentity = providers.gradleProperty("renewHookIdentity").isPresent

val generateHookIdentity = tasks.register("generateHookIdentity") {
    group = "farewell"
    description = "Generates a per-build random hook class/method identity (use -PrenewHookIdentity to rotate)"

    val templateFile = file("hook/entry-template/KeyStoreHooks.template.java")
    val random = SecureRandom()

    fun randomName(prefix: String): String =
        prefix + (1..6).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    inputs.file(templateFile)
    outputs.file(hookIdentityFile)
    outputs.dir(generatedRoot)
    outputs.upToDateWhen { !renewHookIdentity }

    doLast {
        val identityFile = hookIdentityFile.get().asFile
        identityFile.parentFile.mkdirs()
        val className = if (identityFile.exists()) {
            identityFile.readText().trim().split(";").first().substringBefore(":")
        } else {
            randomName("KeyStoreCompat")
        }
        val methodNames = if (identityFile.exists() && identityFile.readText().contains(":")) {
            identityFile.readText().trim().split(";").drop(1).map { it.substringAfter(":") }
        } else {
            emptyList()
        }
        val names = if (methodNames.size == hookMethodNames.size) {
            methodNames
        } else {
            hookMethodNames.map { randomName("h") }
        }
        identityFile.writeText(
            (listOf(className) + hookMethodNames.zip(names).map { (key, value) -> "$key:$value" })
                .joinToString(";")
        )

        val identity = hookMethodNames.zip(names).toMap()

        val root = generatedRoot.get().asFile

        val javaText = templateFile.readText()
            .replace("__CLASS__", className)
        var java = javaText
        for ((key, value) in identity) {
            java = java.replace("__M_${key}__", value)
        }
        val javaFile = File(root, "hook/android/security/keystore2/$className.java")
        javaFile.parentFile.mkdirs()
        javaFile.writeText(java)

        val kotlinFile = File(root, "patcher/io/farewell/patcher/HookIdentity.kt")
        kotlinFile.parentFile.mkdirs()
        val builder = StringBuilder()
        builder.append("package io.farewell.patcher\n\n")
        builder.append("object HookIdentity {\n")
        builder.append("    const val HOOK_CLASS = \"Landroid/security/keystore2/").append(className).append(";\"\n")
        for ((key, value) in identity) {
            builder.append("    const val M_").append(key).append(" = \"").append(value).append("\"\n")
        }
        builder.append("}\n")
        kotlinFile.writeText(builder.toString())
    }
}

tasks.matching { it.name == "clean" }.configureEach {
    doLast {
        hookIdentityFile.get().asFile.delete()
    }
}
