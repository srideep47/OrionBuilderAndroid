package com.legion.orion.engine.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Per-project mutexes shared by the agent loop and (later) the editor, so concurrent writes to
 * the same project serialize. Keyed by the project's canonical path.
 */
object ProjectLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun forPath(path: String): Mutex = locks.getOrPut(path) { Mutex() }
}
