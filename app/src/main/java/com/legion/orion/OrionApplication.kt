package com.legion.orion

import android.app.Application
import com.alibaba.mls.api.ApplicationProvider
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
    }

    override fun onCreate() {
        super.onCreate()
        // The ported GPTee engine reads the app context through this static provider.
        ApplicationProvider.set(this)

        // Only the main (UI) process owns the model + server bridge; the :node process skips this.
        if (Application.getProcessName() == packageName) {
            val bridge = EntryPointAccessors
                .fromApplication(this, BridgeEntryPoint::class.java)
                .serverBridge()
            ServiceLocator.setChatSessionProvider(bridge)
            ServiceLocator.setLlmRuntimeController(bridge)
        }
    }
}
