package com.legion.orion.server

import android.content.Context
import com.localai.api.openai.service.OpenAIService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Starts/stops the OpenAI-compatible foreground server and tracks its on/off state for the UI. */
@Singleton
class ServerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    fun start() {
        OpenAIService.startService(context)
        _running.value = true
    }

    fun stop() {
        OpenAIService.releaseService(context, force = true)
        _running.value = false
    }

    fun port(): Int? = OpenAIService.getInstance()?.getServerPort()
}
