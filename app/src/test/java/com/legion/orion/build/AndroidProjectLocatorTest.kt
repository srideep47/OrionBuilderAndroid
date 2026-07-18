package com.legion.orion.build

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidProjectLocatorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("orion-android-locator").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun prefersNestedWrapperOverRootFacade() {
        writeApplication(File(root, "app"))
        File(root, "settings.gradle").writeText("include ':app'")

        val android = File(root, "android")
        writeApplication(File(android, "app"))
        File(android, "settings.gradle").writeText("include ':app'")
        File(android, "gradle/wrapper").mkdirs()
        File(android, "gradle/wrapper/gradle-wrapper.jar").writeBytes(byteArrayOf(1))

        val layout = AndroidProjectLocator.locate(root)
        assertNotNull(layout)
        assertEquals(android.canonicalFile, layout!!.gradleRoot)
        assertEquals(":app:assembleDebug", layout.assembleDebugTask)
        assertTrue(layout.hasWrapper)
    }

    @Test
    fun detectsNonAppApplicationModuleInMonorepo() {
        val gradleRoot = File(root, "packages/mobile/android")
        File(gradleRoot, "settings.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("include(\":mobile\")")
        }
        writeApplication(File(gradleRoot, "mobile"))

        val layout = AndroidProjectLocator.locate(root)
        assertNotNull(layout)
        assertEquals("packages/mobile/android", layout!!.relativeGradleRoot)
        assertEquals(":mobile:assembleDebug", layout.assembleDebugTask)
        assertEquals("com.example.mobile", AndroidProjectLocator.applicationId(layout))
    }

    private fun writeApplication(module: File) {
        module.mkdirs()
        File(module, "build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }
            android { namespace = "com.example.mobile" }
            """.trimIndent(),
        )
    }
}
