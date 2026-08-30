import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Third-party API keys live in the (git-ignored) .env at the repo root, or in the environment.
val secrets = Properties().apply {
    rootProject.file(".env").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String): String = (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim().trim('"')

android {
    namespace = "com.cortinadev.dogmatix"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.cortinadev.dogmatix"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "RAWG_API_KEY", "\"${secret("RAWG_API_KEY")}\"")
        buildConfigField("String", "THEGAMESDB_API_KEY", "\"${secret("THEGAMESDB_API_KEY")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Release key lives outside the repo (keystore/, git-ignored); credentials come from .env
        // or the environment. Without them the release falls back to the debug key.
        val storeFile = secret("RELEASE_STORE_FILE").takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
        if (storeFile != null && storeFile.exists()) {
            create("release") {
                this.storeFile = storeFile
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs alongside the release build (com.cortinadev.dogmatix.debug).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    android.bundle {
        abi {
            enableSplit = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }

    packaging {
        jniLibs {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.room.common.jvm)
    implementation(libs.androidx.room.ktx)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.jsoup)
    implementation(libs.seven.zip.jbinding)
    implementation(libs.kotlinx.coroutines.core)

    // libtorrent4j
    implementation(libs.libtorrent4j.android.arm64.v8a)
    implementation(libs.libtorrent4j.android.armeabi.v7a)
    implementation(libs.libtorrent4j.android.x86.x4)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
