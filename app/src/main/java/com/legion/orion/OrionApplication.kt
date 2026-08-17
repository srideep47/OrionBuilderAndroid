package com.legion.orion

import android.app.Application
import com.alibaba.mls.api.ApplicationProvider
import com.legion.orion.claudecode.ClaudeToolBridge
import com.legion.orion.runtime.RuntimeBootstrap
import com.legion.orion.server.OrionServerBridge
import com.localai.api.openai.di.ServiceLocator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class OrionApplication : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BridgeEntryPoint {
        fun serverBridge(): OrionServerBridge
        fun claudeToolBridge(): ClaudeToolBridge
        fun runtimeBootstrap(): RuntimeBootstrap
    }

    override fun onCreate() {
        super.onCreate()
        // The ported GPTee engine reads the app context through this static provider.
        ApplicationProvider.set(this)

        // Only the main (UI) process owns the model + server bridge; the :node process skips this.
        if (Application.getProcessName() == packageName) {
            val entryPoint = EntryPointAccessors.fromApplication(this, BridgeEntryPoint::class.java)
            val bridge = entryPoint.serverBridge()
            ServiceLocator.setChatSessionProvider(bridge)
            ServiceLocator.setLlmRuntimeController(bridge)
            // Loopback-only, random-token bridge used by Claude Code's `orion-tool` command.
            entryPoint.claudeToolBridge().start()
            // Auto-install Linux/Claude/Codex/Grok/build tools/Node — no Install button required.
            entryPoint.runtimeBootstrap().startIfNeeded()
        }
    }
}
