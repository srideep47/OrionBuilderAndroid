package com.legion.orion.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLanguageCatalogTest {
    @Test
    fun detectsCommonAndCompoundLanguageFiles() {
        assertEquals("kotlin", EditorLanguageCatalog.detect("app/src/MainActivity.kt").id)
        assertEquals("javascript", EditorLanguageCatalog.detect("src/App.tsx").id)
        assertEquals("html", EditorLanguageCatalog.detect("src/Page.svelte").id)
        assertEquals("yaml", EditorLanguageCatalog.detect(".github/workflows/build.yml").id)
        assertEquals("c_cpp", EditorLanguageCatalog.detect("native/include/orion.hpp").id)
        assertEquals("powershell", EditorLanguageCatalog.detect("scripts/build.ps1").id)
    }

    @Test
    fun detectsExtensionlessBuildAndToolFiles() {
        assertEquals("build", EditorLanguageCatalog.detect("Dockerfile").id)
        assertEquals("build", EditorLanguageCatalog.detect("native/CMakeLists.txt").id)
        assertEquals("shell", EditorLanguageCatalog.detect("gradlew").id)
        assertEquals("git", EditorLanguageCatalog.detect(".gitignore").id)
    }

    @Test
    fun unknownExtensionsStillReceiveGenericHighlighting() {
        assertEquals("generic", EditorLanguageCatalog.detect("module/source.orionlang").id)
        assertEquals("plain", EditorLanguageCatalog.detect("LICENSE").id)
    }
}
