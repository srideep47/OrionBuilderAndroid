package com.legion.orion.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The currently running dev server, shared between the agent tools and the preview screen. */
@Singleton
class DevServerState @Inject constructor() {
    data class Running(val port: Int, val projectDir: String) {
        val url: String get() = "http://127.0.0.1:$port/"
    }

    private val _current = MutableStateFlow<Running?>(null)
    val current = _current.asStateFlow()

    fun set(running: Running?) { _current.value = running }
}
