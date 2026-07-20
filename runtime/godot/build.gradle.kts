plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.legion.orion.runtime.godot"
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
    // Custom-built from source (godotengine/godot, tag 4.7.1-stable) with
    // disable_path_overrides=no — NOT the stock GitHub release. Resolved through the local Maven
    // repo declared in settings.gradle.kts (repo/org/godotengine/godot/...) rather than files(...):
    // AGP rejects a direct local .aar file dependency inside a module that itself produces an AAR.
    // Non-mono build (this app is Kotlin/Java, not C#/.NET). See repo/README.md for the full build
    // provenance and the on-device verification that proves --path/--main-pack now work
    // (byte-level diff: the custom .so has zero occurrences of the "compiled without support for
    // path overrides" string that the old stock .so had 5 of).
    //
    // This single prerequisite (a custom-built engine, not two separate problems) unblocks both the
    // Editor UI port (needs to open more than one fixed project) and AI-generated dynamic Godot
    // projects (G4+, each needs its own project directory) — see godot-tools/ below for the
    // editor-flavor equivalent.
    // One editor-capable binary serves both modes: `--editor` opens the full editor, while the
    // normal launch runs a project. This avoids packaging two AARs that both contain a native
    // library named libgodot_android.so.
    implementation("org.godotengine:godot-tools:4.7.1.stable")

    // A raw `files()` AAR dependency carries no POM, so Godot's own transitive deps (confirmed from
    // platform/android/java/lib/build.gradle at the same tag) must be declared explicitly here.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
