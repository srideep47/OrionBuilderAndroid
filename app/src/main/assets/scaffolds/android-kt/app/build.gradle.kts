plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.orionapp"
    // compileSdk 34 (required by Compose material3 1.2.x); build-tools 34.0.4 arm64. Both bundled.
    // Proven building to a signed APK on-device (Snapdragon 8 Elite) on 2026-06-12.
    compileSdk = 34
    buildToolsVersion = "34.0.4"

    defaultConfig {
        applicationId = "com.example.orionapp"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        // Compose compiler 1.5.14 is matched to Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.12.0")
}
