package com.legion.orion.build

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidNativeToolchainInspectorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("orion-native-toolchain").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun rejectsX86HostToolsBeforeNativeBuildStarts() {
        val layout = nativeLayout("27.2.12479018")
        val sdk = File(root, "sdk")
        fakeElf(File(sdk, "ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"), 62)
        fakeElf(File(sdk, "cmake/3.22.1/bin/cmake"), 62)

        val report = AndroidNativeToolchainInspector.inspect(layout, sdk)

        assertFalse(report.ready)
        assertTrue(report.issues.any { it.contains("ARM64-host NDK") })
        assertTrue(report.issues.any { it.contains("SDK CMake") })
    }

    @Test
    fun acceptsVersionMatchedArm64HostTools() {
        val layout = nativeLayout("29.0.14206865")
        val sdk = File(root, "sdk")
        fakeElf(File(sdk, "ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"), 183)
        fakeElf(File(sdk, "cmake/3.22.1/bin/cmake"), 183)

        val report = AndroidNativeToolchainInspector.inspect(layout, sdk)

        assertTrue(report.describe(), report.ready)
    }

    private fun nativeLayout(ndkVersion: String): AndroidProjectLayout {
        File(root, "package.json").writeText("""{"dependencies":{"react-native":"0.84.0"}}""")
        val gradleRoot = File(root, "android").apply { mkdirs() }
        File(gradleRoot, "build.gradle").writeText(
            """
            buildscript { ext { ndkVersion = "$ndkVersion" } }
            """.trimIndent(),
        )
        val app = File(gradleRoot, "app").apply { mkdirs() }
        File(app, "build.gradle").writeText("apply plugin: \"com.android.application\"")
        return AndroidProjectLayout(root, gradleRoot, app)
    }

    private fun fakeElf(file: File, machine: Int) {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(20)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[5] = 1
        bytes[18] = (machine and 0xff).toByte()
        bytes[19] = ((machine ushr 8) and 0xff).toByte()
        file.writeBytes(bytes)
    }
}
