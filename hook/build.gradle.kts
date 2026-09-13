import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

plugins {
    `java-library`
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

dependencies {
    compileOnly(files(androidJar))
}

val d8: Configuration = configurations.create("d8")

dependencies {
    d8(libs.r8)
}

val hookOutputDir = rootProject.layout.buildDirectory.dir("hook")

val makeHookDex by tasks.registering(JavaExec::class) {
    group = "farewell"
    description = "Compiles the framework hook library into hook.dex using D8"

    val jarTask = tasks.named<Jar>("jar")
    val jarFile = jarTask.flatMap { it.archiveFile }
    val outDir = hookOutputDir

    dependsOn(jarTask)
    inputs.file(jarFile)
    inputs.file(androidJar)
    outputs.dir(outDir)

    classpath = d8
    mainClass.set("com.android.tools.r8.D8")

    argumentProviders.add(object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf(
            "--min-api", "29",
            "--output", outDir.get().asFile.absolutePath,
            "--lib", androidJar.absolutePath,
            jarFile.get().asFile.absolutePath
        )
    })

    doFirst {
        outDir.get().asFile.deleteRecursively()
        outDir.get().asFile.mkdirs()
    }

    doLast {
        val produced = File(outDir.get().asFile, "classes.dex")
        check(produced.exists()) { "D8 did not produce a dex file" }
        val target = File(outDir.get().asFile, "hook.dex")
        if (target.exists()) target.delete()
        check(produced.renameTo(target)) { "Could not rename hook dex" }
    }
}
