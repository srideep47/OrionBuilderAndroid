package com.legion.orion.engine.agent

/** Opaque reference to a per-turn snapshot (e.g. a git ref + commit hash). */
data class SnapshotRef(val ref: String, val commit: String)

/**
 * Captures and restores project state around mutating tool batches. Implemented by infra:git
 * (JGit shadow refs). Kept as an interface so the agent loop stays testable without git.
 */
interface Snapshotter {
    /** Snapshot the project before a mutating batch; null if nothing to snapshot. */
    suspend fun snapshot(chatId: String, messageId: String): SnapshotRef?

    /** Restore the project to a prior snapshot. */
    suspend fun restore(ref: SnapshotRef)
}

/** No-op snapshotter for cloud-only/agent-less contexts or tests. */
object NoopSnapshotter : Snapshotter {
    override suspend fun snapshot(chatId: String, messageId: String): SnapshotRef? = null
    override suspend fun restore(ref: SnapshotRef) {}
}
