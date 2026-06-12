plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// MNN framework checkout providing C++ headers for the JNI layer.
val mnnSourceRoot: String = (project.findProperty("mnnSourceRoot") as? String)
    ?: error("Set mnnSourceRoot in gradle.properties (path to the MNN framework checkout)")

android {
    namespace = "com.legion.orion.engine.mnn"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DMNN_SOURCE_ROOT=$mnnSourceRoot",
                    "-DANDROID_STL=c++_shared",
                )
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    implementation(project(":engine:qnn"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.coroutines.core)
}
