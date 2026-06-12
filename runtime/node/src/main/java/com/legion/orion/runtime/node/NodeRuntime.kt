package com.legion.orion.runtime.node

import android.util.Log

/**
 * Thin Kotlin wrapper over the embedded Node 18 runtime (nodejs-mobile). [start] launches Node on a
 * dedicated thread (node::Start blocks the calling thread for the lifetime of the process), running
 * the given host script. Designed to live in the isolated :node process so a Node crash/OOM cannot
 * take down the UI or the loaded MNN model.
 */
object NodeRuntime {
    private const val TAG = "NodeRuntime"

    init {
        // libnode first (libnodebridge depends on it).
        System.loadLibrary("node")
        System.loadLibrary("nodebridge")
    }

    @Volatile
    var running: Boolean = false
        private set

    private external fun nativeStart(args: Array<String>): Int
    private external fun nativeRedirectOutput(stderrFile: String)

    /**
     * Starts Node with `node <scriptPath> <extraArgs...>`. Blocks the current thread until Node
     * exits — call from a dedicated thread.
     */
    fun startBlocking(scriptPath: String, extraArgs: List<String> = emptyList()): Int {
        // stderr goes to a file (not the logcat pipe) so Node's dying fatal message survives an
        // in-process exit(1) — the pipe pump thread dies with the process and loses buffered data.
        val stderrLog = java.io.File(java.io.File(scriptPath).parentFile, "stderr.log").absolutePath
        nativeRedirectOutput(stderrLog)
        running = true
        return try {
            nativeStart(arrayOf("node", scriptPath, *extraArgs.toTypedArray()))
        } finally {
            running = false
            Log.i(TAG, "node runtime stopped")
        }
    }
}
