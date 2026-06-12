package com.legion.orion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legion.orion.runtime.buildtools.BuildToolchain
import com.legion.orion.runtime.buildtools.ToolchainInstaller
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Faithful test of the M4 W^X resolution: at targetSdk 28, the APP PROCESS must be able to extract
 * the bundled JDK into its own filesDir and execve it (the adb-shell proof ran as shell-user from
 * /data/local/tmp, a different SELinux domain). This runs in the app's UID/domain, so a green result
 * confirms on-device APK builds can actually launch java from app storage.
 */
@RunWith(AndroidJUnit4::class)
class ToolchainExecTest {

    @Test
    fun appCanExecBundledJdk() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val toolchain = BuildToolchain(ctx)
        val installer = ToolchainInstaller(toolchain, ctx.assets)

        // Extract only the JDK component from bundled assets (sufficient to test exec).
        val jdkComponent = ToolchainInstaller.Component(
            name = "OpenJDK 17 (arm64)",
            source = "asset:toolchain/jdk17-android-arm64.tar.xz",
            extractInto = ".",
            sentinel = "jdk/bin/java",
        )
        installer.install(listOf(jdkComponent)) { /* progress */ }

        val javaBin = toolchain.javaBin
        assertTrue("java launcher missing at $javaBin", javaBin.exists())

        val jdkLib = File(toolchain.javaHome, "lib")
        val proc = ProcessBuilder(javaBin.absolutePath, "-version")
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] =
                    listOf(File(jdkLib, "server"), jdkLib).joinToString(File.pathSeparator) { it.absolutePath }
                environment()["HOME"] = toolchain.root.absolutePath
                environment()["JAVA_HOME"] = toolchain.javaHome.absolutePath
            }
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()

        android.util.Log.i("ToolchainExecTest", "java -version (exit=$code):\n$output")
        assertEquals("java exited non-zero; output:\n$output", 0, code)
        assertTrue("unexpected java -version output:\n$output", output.contains("openjdk version"))
    }
}
