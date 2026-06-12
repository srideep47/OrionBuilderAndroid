// Root build file. Versions are pinned to what the on-device toolchain provides: Android platform-33
// + build-tools 34.0.4 + JDK 17 (AndroidIDE arm64 builds). AGP 8.2.2 / Kotlin 1.9.24 is a stable
// combo for that SDK without needing the Kotlin-2.0 Compose compiler plugin.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
