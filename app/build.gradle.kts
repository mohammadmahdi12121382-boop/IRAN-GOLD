plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ir.irangold.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.irangold.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Xray's official Android binary is only vendored for arm64 (nearly all current phones).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly (sideload). Not for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        // Engine binaries (libxray.so, libtor.so) are executed as processes, so they must be extracted to disk.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/hev-classes.jar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // QR import (Apache-2.0)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Tor daemon binary (libtor.so) from the Guardian Project repo
    implementation("info.guardianproject:tor-android:0.4.8.16")
    // Snowflake pluggable transport (MIT)
    implementation("com.netzarchitekten:IPtProxy:5.7.0")
}
