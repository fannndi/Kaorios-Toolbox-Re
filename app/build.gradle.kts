plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.farewell.toolbox"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.farewell.toolbox"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "DATA_BASE_URL",
            "\"https://raw.githubusercontent.com/fannndi/Farewell-Toolbox/main/Toolbox-data\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        res.srcDirs("src/main/res", rootProject.file("Toolbox-languages"))
        assets.srcDir(rootProject.layout.buildDirectory.dir("hook").get().asFile)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(":hook:makeHookDex")
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(":hook:makeHookDex")
}

dependencies {
    implementation(project(":patcher"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
