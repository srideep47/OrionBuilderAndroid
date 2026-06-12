package com.legion.orion.infra.git

import com.legion.orion.engine.agent.SnapshotRef
import com.legion.orion.engine.agent.Snapshotter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand

/**
 * Per-turn project snapshots backed by a JGit repo inside the project directory. Each snapshot is
 * a commit (HEAD advances) plus a lightweight ref under refs/orion/turns/<chatId>/<messageId>;
 * rollback is a hard reset to that commit. The repo is Orion-managed, so committing to HEAD per
 * turn is the snapshot history.
 *
 * gc.auto=0 disables JGit's implicit gc; an explicit prune/gc is exposed via [gc].
 */
class GitSnapshotter(private val projectDir: File) : Snapshotter {

    private fun openOrInit(): Git {
        val gitDir = File(projectDir, ".git")
        return if (gitDir.isDirectory) {
            Git.open(projectDir)
        } else {
            projectDir.mkdirs()
            ensureGitignore()
            Git.init().setDirectory(projectDir).call().also(::configure)
        }
    }

    private fun configure(git: Git) {
        git.repository.config.apply {
            setString("gc", null, "auto", "0")
            setString("user", null, "name", "Orion")
            setString("user", null, "email", "orion@localhost")
            setBoolean("commit", null, "gpgsign", false)
            save()
        }
    }

    private fun ensureGitignore() {
        val gi = File(projectDir, ".gitignore")
        if (!gi.exists()) {
            gi.writeText("node_modules/\n.gradle/\nbuild/\n*.log\n")
        }
    }

    override suspend fun snapshot(chatId: String, messageId: String): SnapshotRef? =
        withContext(Dispatchers.IO) {
            val git = openOrInit()
            git.use { g ->
                // Stage new/modified files and deletions of tracked files.
                g.add().addFilepattern(".").call()
                g.add().addFilepattern(".").setUpdate(true).call()
                val commit = g.commit()
                    .setMessage("orion snapshot $chatId/$messageId")
                    .setAllowEmpty(true)
                    .setSign(false)
                    .call()
                val refName = "refs/orion/turns/$chatId/$messageId"
                g.repository.updateRef(refName).apply {
                    setNewObjectId(commit.id)
                    setForceUpdate(true)
                    update()
                }
                SnapshotRef(refName, commit.name)
            }
        }

    override suspend fun restore(ref: SnapshotRef) = withContext(Dispatchers.IO) {
        Git.open(projectDir).use { g ->
            // Hard reset removes tracked changes; clean removes files created after the snapshot
            // that were never committed (respecting .gitignore, so node_modules/ is preserved).
            g.reset().setMode(ResetCommand.ResetType.HARD).setRef(ref.commit).call()
            g.clean().setCleanDirectories(true).setIgnore(true).call()
        }
        Unit
    }

    /** Explicit, off-the-hot-path garbage collection (gc.auto is disabled). */
    suspend fun gc() = withContext(Dispatchers.IO) {
        Git.open(projectDir).use { it.gc().call() }
        Unit
    }
}
