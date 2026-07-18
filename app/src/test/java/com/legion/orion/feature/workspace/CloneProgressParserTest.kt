package com.legion.orion.feature.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloneProgressParserTest {

    @Test
    fun receivingObjectsReportsLatestCarriageReturnProgress() {
        val initial = CloneProgressState("https://example.com/repo.git", "repo")
        val parsed = parseCloneProgress(
            "Cloning into 'repo'...\rReceiving objects: 12% (12/100)\rReceiving objects: 47% (47/100)",
            initial,
        )

        assertEquals("Receiving objects", parsed.phase)
        assertEquals(47, parsed.percent)
        assertFalse(parsed.failed)
    }

    @Test
    fun resolvingDeltasReplacesThePreviousClonePhase() {
        val receiving = CloneProgressState(
            url = "https://example.com/repo.git",
            name = "repo",
            phase = "Receiving objects",
            percent = 100,
        )
        val parsed = parseCloneProgress("Resolving deltas: 63% (63/100), done.", receiving)

        assertEquals("Resolving deltas", parsed.phase)
        assertEquals(63, parsed.percent)
    }

    @Test
    fun remoteEnumerationHasAReadablePhaseAndDetail() {
        val initial = CloneProgressState("git@example.com:team/repo.git", "repo")
        val parsed = parseCloneProgress("remote: Enumerating objects: 250, done.", initial)

        assertEquals("Enumerating objects", parsed.phase)
        assertEquals("Enumerating objects: 250, done.", parsed.detail)
    }
}
