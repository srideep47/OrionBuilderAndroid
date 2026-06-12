package com.legion.orion.infra.git

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitSnapshotterTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("orion-git-test").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun snapshotThenRollbackRestoresContentAndRemovesNewFiles() = runBlocking {
        val snapshotter = GitSnapshotter(dir)
        val a = File(dir, "a.txt").apply { writeText("v1") }

        val ref = snapshotter.snapshot("chat1", "m1")
        assertNotNull("snapshot must produce a ref", ref)

        // Mutate after the snapshot: change a.txt and add b.txt.
        a.writeText("v2-modified")
        File(dir, "b.txt").writeText("created after snapshot")

        snapshotter.restore(ref!!)

        assertEquals("a.txt must be restored to its snapshot content", "v1", File(dir, "a.txt").readText())
        assertFalse("b.txt created after the snapshot must be removed", File(dir, "b.txt").exists())
    }

    @Test fun secondSnapshotRollbackToFirst() = runBlocking {
        val snapshotter = GitSnapshotter(dir)
        File(dir, "code.kt").writeText("fun v1() {}")
        val first = snapshotter.snapshot("chat1", "m1")!!

        File(dir, "code.kt").writeText("fun v2() {}")
        snapshotter.snapshot("chat1", "m2")

        File(dir, "code.kt").writeText("fun v3() {}")

        snapshotter.restore(first)
        assertEquals("fun v1() {}", File(dir, "code.kt").readText())
        assertTrue(File(dir, ".git").isDirectory)
    }
}
