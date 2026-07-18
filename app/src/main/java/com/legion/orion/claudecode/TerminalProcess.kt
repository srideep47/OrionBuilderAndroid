package com.legion.orion.claudecode

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * A persistent interactive `bash` shell inside the bundled proot Ubuntu environment, bound to a
 * project directory at `/root/project`. Unlike [ClaudeCodeProcess] this is a plain scrollback-log
 * terminal, not a full VT100/ANSI emulator — raw stdout/stderr text is appended to [output] as it
 * arrives and the user types commands via [send]. Fullscreen TUI programs (vim, htop, an interactive
 * `less`) won't render correctly since cursor-positioning escape codes are shown as literal text
 * rather than interpreted; ordinary command-line workflows (running builds, git, npm, python, etc.)
 * work fine. Good enough for a first version — a real terminal emulator is a much bigger, separate
 * project if ever needed.
 */
class TerminalProcess(
    private val toolchain: ClaudeCodeToolchain,
    private val projectDir: File?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guestProjectDir = "/root/project"
    private val cpuWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var process: Process? = null
    private var writer: java.io.Writer? = null

    private fun command(): List<String> = buildList {
        add(toolchain.prootBin.absolutePath)
        add("-r"); add(toolchain.rootfsDir.absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        if (projectDir != null) {
            add("-b"); add("${projectDir.absolutePath}:$guestProjectDir")
        }
        add("-w"); add(if (projectDir != null) guestProjectDir else "/root")
        add("/bin/bash"); add("-c")
        add(
            "export PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "export HOME=/root; export TERM=xterm-256color; " +
                "export MAKEFLAGS=-j$cpuWorkers CMAKE_BUILD_PARALLEL_LEVEL=$cpuWorkers CARGO_BUILD_JOBS=$cpuWorkers; " +
                "export GOMAXPROCS=$cpuWorkers RAYON_NUM_THREADS=$cpuWorkers OMP_NUM_THREADS=$cpuWorkers OPENBLAS_NUM_THREADS=$cpuWorkers; " +
                "export UV_THREADPOOL_SIZE=${(cpuWorkers * 4).coerceAtMost(128)}; " +
                "export PS1='\\w \\$ '; exec /bin/bash --norc -i",
        )
    }

    fun start() {
        if (process?.isAlive == true) return
        val proc = runCatching {
            ProcessBuilder(command())
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_LOADER"] = toolchain.loaderBin.absolutePath
                    environment()["PROOT_LOADER_32"] = toolchain.loader32Bin.absolutePath
                    environment()["PROOT_TMP_DIR"] = toolchain.prootTmpDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = toolchain.libDir.absolutePath
                }
                .start()
        }.getOrElse {
            _running.value = false
            _output.value = "Terminal failed to start: ${it.message}"
            return
        }
        process = proc
        writer = proc.outputStream.bufferedWriter()
        _running.value = true
        _output.value = ""

        scope.launch {
            val buf = CharArray(4096)
            val reader = proc.inputStream.bufferedReader()
            while (true) {
                val n = runCatching { reader.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                _output.value = (_output.value + chunk).takeLast(MAX_BUFFER_CHARS)
            }
            _running.value = false
        }
    }

    suspend fun send(command: String) = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext
        runCatching {
            w.write(command)
            w.write("\n")
            w.flush()
        }
    }

    fun clear() {
        _output.value = ""
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        _running.value = false
    }

    companion object {
        private const val MAX_BUFFER_CHARS = 200_000
    }
}
