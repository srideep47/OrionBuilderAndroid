package com.legion.orion.runtime

import android.content.Context
import android.util.Log
import com.legion.orion.build.ApkBuildController
import com.legion.orion.build.DevelopmentWorkService
import com.legion.orion.claudecode.ClaudeCodeInstaller
import com.legion.orion.claudecode.ClaudeCodeToolchain
import com.legion.orion.claudecode.ensureOrionToolClient
import com.legion.orion.codex.CodexInstaller
import com.legion.orion.codex.CodexToolchain
import com.legion.orion.grok.GrokInstaller
import com.legion.orion.grok.GrokToolchain
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * First-launch (and every cold start) automatic provisioning of every on-device agent runtime.
 *
 * Order:
 * 1. Linux proot + Ubuntu rootfs + Claude Code CLI (bundled APK assets)
 * 2. `orion-tool` bridge client into the rootfs
 * 3. Codex CLI (GitHub download)
 * 4. Grok Build CLI (npm arm64 download)
 * 5. Android build toolchain (bundled JDK/SDK/build-tools)
 * 6. Node host assets (npm + orion-host.js)
 *
 * No user button is required. Progress is published on [state] for the first-run overlay and for
 * Chat banners. Sign-in to Claude/Codex/Grok remains user-driven (subscription accounts).
 *
 * Network failures for Codex/Grok leave Linux ready and mark those engines for retry next launch.
 */
@Singleton
class RuntimeBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkBuilder: ApkBuildController,
    private val node: NodeController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val claudeToolchain = ClaudeCodeToolchain(context)
    private val claudeInstaller = ClaudeCodeInstaller(claudeToolchain, context.assets)
    private val codexToolchain = CodexToolchain(claudeToolchain)
    private val codexInstaller = CodexInstaller(codexToolchain)
    private val grokToolchain = GrokToolchain(claudeToolchain)
    private val grokInstaller = GrokInstaller(grokToolchain)

    enum class Phase { Idle, Running, Ready, Partial, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        val message: String = "",
        val detail: String = "",
        val linuxReady: Boolean = false,
        val claudeReady: Boolean = false,
        val codexReady: Boolean = false,
        val grokReady: Boolean = false,
        val buildToolsReady: Boolean = false,
        val nodeReady: Boolean = false,
        val error: String? = null,
        val finishedPass: Boolean = false,
    ) {
        val busy: Boolean get() = phase == Phase.Running
        val allCoreReady: Boolean get() = linuxReady && claudeReady && codexReady && grokReady
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Fire-and-forget from Application.onCreate — safe to call multiple times. */
    fun startIfNeeded() {
        scope.launch { ensureReady() }
    }

    /**
     * Idempotent full provision. Concurrent callers share one pass via [mutex].
     */
    suspend fun ensureReady(): State = mutex.withLock {
        val current = _state.value
        if (current.phase == Phase.Ready &&
            current.allCoreReady &&
            current.buildToolsReady &&
            current.nodeReady
        ) {
            return@withLock current
        }

        // Cold start after a successful first provision: sentinels on disk are enough.
        // Do not flip to Running (overlay + FGS) or hit GitHub/npm just to confirm versions.
        snapshotIfAlreadyProvisioned()?.let { ready ->
            _state.value = ready
            return@withLock ready
        }

        val errors = mutableListOf<String>()
        emit(
            phase = Phase.Running,
            message = "Setting up Orion…",
            detail = "Preparing on-device development runtimes",
            finishedPass = false,
            clearError = true,
        )

        DevelopmentWorkService.acquire(context, JOB_ID, "Installing runtimes…")
        try {
            // ── 1–2. Linux + Claude + orion-tool ──
            val linuxOk = runCatching {
                emit(message = "Installing Linux runtime…", detail = "proot + Ubuntu + Claude Code")
                claudeInstaller.install { line ->
                    emit(message = "Installing Linux runtime…", detail = line)
                    DevelopmentWorkService.acquire(context, JOB_ID, line.take(80))
                }
                ensureOrionToolClient(claudeToolchain)
                val after = claudeToolchain.status()
                val baseReady = isLinuxBaseReady(after)
                emit(
                    linuxReady = baseReady,
                    claudeReady = after.provisioned,
                    message = if (after.provisioned) "Linux + Claude ready" else "Linux runtime installed",
                    detail = after.describe(),
                )
                if (!baseReady) error("Linux runtime incomplete: ${after.missing.joinToString()}")
                Log.i(TAG, "Claude/Linux status: ${after.describe()}")
                baseReady
            }.getOrElse {
                errors += "Linux/Claude: ${it.message}"
                Log.e(TAG, "Linux install failed", it)
                emit(message = "Linux install failed", detail = it.message.orEmpty(), error = it.message)
                false
            }

            // ── 3. Codex ──
            if (linuxOk || isLinuxBaseReady(claudeToolchain.status())) {
                runCatching {
                    emit(message = "Installing Codex…", detail = "Downloading official CLI")
                    val status = codexInstaller.install { line ->
                        emit(message = "Installing Codex…", detail = line)
                        DevelopmentWorkService.acquire(context, JOB_ID, line.take(80))
                    }
                    emit(
                        codexReady = status.provisioned,
                        message = if (status.provisioned) "Codex ready" else "Codex incomplete",
                        detail = status.describe(),
                    )
                    if (!status.provisioned) errors += "Codex: ${status.missing.joinToString()}"
                }.onFailure {
                    errors += "Codex: ${it.message}"
                    Log.e(TAG, "Codex install failed", it)
                    emit(message = "Codex install failed", detail = it.message.orEmpty())
                }
            } else {
                errors += "Codex: skipped (Linux runtime not ready)"
            }

            // ── 4. Grok ──
            if (linuxOk || isLinuxBaseReady(claudeToolchain.status())) {
                runCatching {
                    emit(message = "Installing Grok Build…", detail = "Downloading official CLI")
                    val status = grokInstaller.install { line ->
                        emit(message = "Installing Grok Build…", detail = line)
                        DevelopmentWorkService.acquire(context, JOB_ID, line.take(80))
                    }
                    emit(
                        grokReady = status.provisioned,
                        message = if (status.provisioned) "Grok Build ready" else "Grok incomplete",
                        detail = status.describe(),
                    )
                    if (!status.provisioned) errors += "Grok: ${status.missing.joinToString()}"
                }.onFailure {
                    errors += "Grok: ${it.message}"
                    Log.e(TAG, "Grok install failed", it)
                    emit(message = "Grok install failed", detail = it.message.orEmpty())
                }
            } else {
                errors += "Grok: skipped (Linux runtime not ready)"
            }

            // ── 5. Android build toolchain ──
            runCatching {
                emit(message = "Installing Android build tools…", detail = "JDK + SDK + build-tools")
                val status = apkBuilder.installToolchain(sourceDir = null, jdkUrl = null) { line ->
                    emit(message = "Installing Android build tools…", detail = line)
                    DevelopmentWorkService.acquire(context, JOB_ID, line.take(80))
                }
                val ok = status.provisioned || apkBuilder.toolchainStatus().provisioned
                emit(
                    buildToolsReady = ok,
                    message = if (ok) "Build tools ready" else "Build tools incomplete",
                    detail = status.describe(),
                )
                if (!ok) errors += "Build tools: ${status.missing.joinToString()}"
            }.onFailure {
                errors += "Build tools: ${it.message}"
                Log.e(TAG, "Build toolchain install failed", it)
            }

            // ── 6. Node assets ──
            runCatching {
                emit(message = "Installing Node tooling…", detail = "npm + preview host")
                node.ensureAssetsExtracted()
                emit(nodeReady = true, message = "Node assets ready", detail = "Extracted")
            }.onFailure {
                errors += "Node: ${it.message}"
                Log.e(TAG, "Node asset extract failed", it)
            }

            val claude = claudeToolchain.status()
            val codex = codexToolchain.status()
            val grok = grokToolchain.status()
            val build = apkBuilder.toolchainStatus()
            val baseReady = isLinuxBaseReady(claude)

            val phase = when {
                errors.isEmpty() && codex.provisioned && grok.provisioned && baseReady -> Phase.Ready
                baseReady -> Phase.Partial
                else -> Phase.Failed
            }
            val summary = when (phase) {
                Phase.Ready -> "All runtimes ready"
                Phase.Partial -> "Core ready — some installs need a network retry"
                Phase.Failed -> "Setup failed"
                else -> "Setup finished"
            }
            emit(
                phase = phase,
                message = summary,
                detail = if (errors.isEmpty()) {
                    "Claude, Codex, Grok, build tools, Node"
                } else {
                    errors.joinToString(" · ")
                },
                linuxReady = baseReady,
                claudeReady = claude.provisioned,
                codexReady = codex.provisioned,
                grokReady = grok.provisioned,
                buildToolsReady = build.provisioned,
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                finishedPass = true,
                clearError = errors.isEmpty(),
            )
            Log.i(TAG, "Bootstrap finished phase=$phase errors=$errors")
        } finally {
            DevelopmentWorkService.release(context, JOB_ID)
        }
        _state.value
    }

    private fun snapshotIfAlreadyProvisioned(): State? {
        val claude = claudeToolchain.status()
        if (!isLinuxBaseReady(claude) || !claude.provisioned) return null
        val codex = codexToolchain.status()
        val grok = grokToolchain.status()
        if (!codex.provisioned || !grok.provisioned) return null
        val build = apkBuilder.toolchainStatus()
        if (!build.provisioned) return null
        if (!node.assetsReady()) return null
        return State(
            phase = Phase.Ready,
            message = "All runtimes ready",
            detail = "Claude, Codex, Grok, build tools, Node",
            linuxReady = true,
            claudeReady = true,
            codexReady = true,
            grokReady = true,
            buildToolsReady = true,
            nodeReady = true,
            finishedPass = true,
        )
    }

    private fun isLinuxBaseReady(status: ClaudeCodeToolchain.Status): Boolean {
        if (status.provisioned) return true
        // Codex/Grok only need proot + rootfs; claude binary is optional for those engines.
        val basePieces = setOf("proot", "proot loader", "libtalloc", "libandroid-shmem", "Ubuntu rootfs")
        return status.missing.none { it in basePieces }
    }

    private fun emit(
        phase: Phase? = null,
        message: String? = null,
        detail: String? = null,
        linuxReady: Boolean? = null,
        claudeReady: Boolean? = null,
        codexReady: Boolean? = null,
        grokReady: Boolean? = null,
        buildToolsReady: Boolean? = null,
        nodeReady: Boolean? = null,
        error: String? = null,
        finishedPass: Boolean? = null,
        clearError: Boolean = false,
    ) {
        val cur = _state.value
        _state.value = cur.copy(
            phase = phase ?: cur.phase,
            message = message ?: cur.message,
            detail = detail ?: cur.detail,
            linuxReady = linuxReady ?: cur.linuxReady,
            claudeReady = claudeReady ?: cur.claudeReady,
            codexReady = codexReady ?: cur.codexReady,
            grokReady = grokReady ?: cur.grokReady,
            buildToolsReady = buildToolsReady ?: cur.buildToolsReady,
            nodeReady = nodeReady ?: cur.nodeReady,
            error = when {
                clearError -> null
                error != null -> error
                else -> cur.error
            },
            finishedPass = finishedPass ?: cur.finishedPass,
        )
    }

    companion object {
        private const val TAG = "RuntimeBootstrap"
        private const val JOB_ID = "runtime-bootstrap"
    }
}
