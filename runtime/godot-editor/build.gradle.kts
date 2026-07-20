plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.legion.orion.runtime.godoteditor"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The custom SCons build (disable_path_overrides=no, target=editor) vendored at
    // runtime/godot/repo/org/godotengine/godot-tools/ — see that dir's README.md for full
    // provenance. NOT the same coordinate as runtime:godot's `org.godotengine:godot` (that's the
    // template/game flavor); the two ship the same Java/Kotlin classes bound to different native
    // engine binaries and cannot be depended on from the same module (classpath collision), hence
    // this being a separate module rather than a second source set in runtime:godot.
    implementation("org.godotengine:godot-tools:4.7.1.stable")

    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
