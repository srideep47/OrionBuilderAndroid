plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.legion.orion.infra.git"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":engine:agent"))
    implementation(libs.coroutines.core)
    implementation(libs.jgit)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.jgit)
}
