plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("io.farewell.patcher.PatchCliKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.smali.dexlib2)
    // `api`, not `implementation`: SpoofRules exposes JSONObject in its public API,
    // so consumers (the app, the CLI) need the type on their compile classpath.
    api(libs.json)
    testImplementation(libs.junit)
}

tasks.named("compileKotlin") {
    dependsOn(":generateHookIdentity")
}

sourceSets {
    main {
        java.srcDir(rootProject.layout.buildDirectory.dir("generated/patcher").get().asFile)
    }
}
