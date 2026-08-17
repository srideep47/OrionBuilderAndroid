plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.legion.orion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.legion.orion"
        minSdk = 29
        // targetSdk 28: the on-device build pipeline must execve java/aapt2/d8 from app storage,
        // which W^X blocks on targetSdk ≥ 29 (Termux/AndroidIDE ship 28 for the same reason).
        // Sideload-only distribution, so the Play targetSdk floor does not apply.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a only — high-end phones, no compatibility builds.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The bundled toolchain archives are already xz-compressed; don't let aapt re-deflate them.
        noCompress += "xz"
        // AAPT's default ignoreAssetsPattern excludes any dot-prefixed path (the general ".*" rule),
        // which would silently drop the bundled Godot project's .godot/ cache — specifically
        // extension_list.cfg, which is how the engine discovers orion_bridge.gdextension at all
        // (normally auto-maintained by the editor; this project is hand-authored, never opened in
        // one). Same default list minus the blanket ".*" clause, rather than relying on a "!"
        // override's precedence against it.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
    packaging {
        resources {
            // Ktor + Netty + slf4j bundle overlapping META-INF entries; pick/drop the dupes.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            pickFirsts += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
        jniLibs {
            // 16KB page alignment is required for Android 15+; keep .so uncompressed-friendly.
            useLegacyPackaging = false
            // engine:mnn and the vendored godot-lib AAR each bundle their own libc++_shared.so for
            // arm64-v8a; both are standard, ABI-stable NDK toolchain runtimes, so picking either is
            // safe — this just resolves the otherwise-fatal duplicate-path merge conflict.
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

// AGP's asset-merge step (mergeXAssets) drops dot-prefixed paths regardless of
// androidResources.ignoreAssetsPattern (that only governs the later aapt2 packaging step, which
// never sees a path the merge step already dropped) — confirmed by inspecting the merge task's own
// output directory. This silently drops runtime/godot's bundled Godot project's `.godot/` cache
// (specifically extension_list.cfg, how the engine discovers orion_bridge.gdextension at all,
// since this project is hand-authored and never opened in the real editor, which normally
// maintains that file). Copy it back in after the merge task's own copy logic has run.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doLast {
        val dotGodotSrc = file("$rootDir/runtime/godot/src/main/assets/.godot")
        if (!dotGodotSrc.exists()) return@doLast
        outputs.files.files.forEach { outDir ->
            if (outDir.isDirectory) {
                dotGodotSrc.copyRecursively(File(outDir, ".godot"), overwrite = true)
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:design"))
    implementation(project(":engine:mnn"))
    implementation(project(":engine:qnn"))
    implementation(project(":engine:providers"))
    implementation(project(":engine:server"))
    implementation(project(":engine:agent"))
    implementation(project(":infra:git"))
    implementation(project(":infra:p2p"))
    implementation(project(":runtime:node"))
    implementation(project(":runtime:godot"))
    // runtime:godot uses one editor-capable tools AAR for both editor and game-preview modes, so
    // this APK contains exactly one libgodot_android.so. Do not add runtime:godot-editor too.
    implementation(project(":runtime:buildtools"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.jgit)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.brotli.dec)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.core)
}
