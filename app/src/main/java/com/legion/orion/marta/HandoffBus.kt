package com.legion.orion.marta

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Second-brain request Marta posts for the Code tab to pick up. */
data class BrainHandoff(
    val brain: String,
    val brief: String,
)

@Singleton
class HandoffBus @Inject constructor() {
    private val _pending = MutableStateFlow<BrainHandoff?>(null)
    val pending = _pending.asStateFlow()

    fun post(brain: String, brief: String) {
        _pending.value = BrainHandoff(brain.trim().lowercase(), brief.trim())
    }

    fun consume(): BrainHandoff? {
        val v = _pending.value
        _pending.value = null
        return v
    }
}
