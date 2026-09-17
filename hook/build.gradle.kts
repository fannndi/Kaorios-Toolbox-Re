import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    `java-library`
    kotlin("jvm")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val sdkDir: String = localProps.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("Android SDK not found. Set sdk.dir in local.properties")

val androidJar: File = run {
    val platforms = File(sdkDir, "platforms")
    val platform = platforms.listFiles { f -> f.isDirectory && f.name.startsWith("android-") }
        ?.maxByOrNull { it.name }
        ?: error("No Android platform found in ${platforms.absolutePath}")
    File(platform, "android.jar").takeIf { it.exists() }
        ?: error("android.jar missing in ${platform.absolutePath}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// The device code stays at Java 11 (compileOnly android.jar, no lambdas/streams),
// but the test source set consumes :patcher, which is JVM 17. Compile only the
// tests at 17 so the cross-module dependency resolves; runtime is JDK 26.
tasks.withType<JavaCompile>().configureEach {
    if (name.startsWith("compileTest")) {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
tasks.withType<KotlinCompile>().configureEach {
    if (name.startsWith("compileTest")) {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(files(androidJar))

    // Unit tests run on the JVM. Der and AttestationBuilder are plain Java, but the
    // module's classes reference android types, so android.jar is needed on the test
    // compile classpath as well. :patcher is needed to round-trip what the hook
    // writes back through the parser the tooling reads it with.
    testImplementation(libs.junit)
    testImplementation(files(androidJar))
    testImplementation(project(":patcher"))
}

tasks.named("compileJava") {
    dependsOn(":generateHookIdentity")
}

tasks.named("compileKotlin") {
    dependsOn(":generateHookIdentity")
}

sourceSets {
    main {
        java.srcDir(rootProject.layout.buildDirectory.dir("generated/hook").get().asFile)
    }
}

val d8: Configuration = configurations.create("d8")

dependencies {
    d8(libs.r8)
}

val obfuscate = providers.gradleProperty("hookObfuscate").map { it.toBoolean() }.getOrElse(true)

val hookOutputDir = rootProject.layout.buildDirectory.dir("hook")
val identityFile = rootProject.layout.buildDirectory.file("hook-identity.txt")

val makeHookDex by tasks.registering(JavaExec::class) {
    group = "farewell"
    description = "Compiles the framework hook into hook.dex using R8 (obfuscated) or D8"

    val jarTask = tasks.named<Jar>("jar")
    val jarFile = jarTask.flatMap { it.archiveFile }
    val outDir = hookOutputDir

    dependsOn(jarTask, ":generateHookIdentity")
    inputs.file(jarFile)
    inputs.file(androidJar)
    inputs.file(identityFile)
    inputs.property("obfuscate", obfuscate)
    outputs.dir(outDir)

    classpath = d8
    mainClass.set(if (obfuscate) "com.android.tools.r8.R8" else "com.android.tools.r8.D8")

    argumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> {
            val arguments = mutableListOf<String>()
            if (obfuscate) {
                val className = identityFile.get().asFile.readText().trim()
                    .split(";").first().substringBefore(":")
                val rulesFile = File(outDir.get().asFile, "r8-rules.pro")
                rulesFile.parentFile.mkdirs()
                rulesFile.writeText(
                    buildString {
                        appendLine("-keep public class android.security.keystore2.$className { public *; }")
                        appendLine("-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*")
                        appendLine("-repackageclasses 'o'")
                        appendLine("-allowaccessmodification")
                        appendLine("-dontwarn **")
                        appendLine("-dontoptimize")
                    }
                )
                arguments += listOf("--release", "--pg-conf", rulesFile.absolutePath)
            }
            arguments += listOf(
                "--min-api", "29",
                "--output", outDir.get().asFile.absolutePath,
                "--lib", androidJar.absolutePath,
                jarFile.get().asFile.absolutePath
            )
            return arguments
        }
    })

    doFirst {
        outDir.get().asFile.deleteRecursively()
        outDir.get().asFile.mkdirs()
    }

    doLast {
        val produced = File(outDir.get().asFile, "classes.dex")
        check(produced.exists()) { "Dex compiler did not produce a dex file" }
        val target = File(outDir.get().asFile, "hook.dex")
        if (target.exists()) target.delete()
        check(produced.renameTo(target)) { "Could not rename hook dex" }
        File(outDir.get().asFile, "r8-rules.pro").delete()
    }
}
