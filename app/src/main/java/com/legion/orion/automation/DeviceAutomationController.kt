package com.legion.orion.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@Singleton
class DeviceAutomationController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val connected: StateFlow<Boolean> = DeviceAutomationService.connected

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun screenshot(dest: File): Result<File> = onService { screenshot(dest).getOrThrow() }

    suspend fun uiTree(): Result<String> = withContext(Dispatchers.Main) {
        runCatching { requireService().uiTree() }
    }

    suspend fun click(selector: String): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching { check(requireService().click(selector)) { "No clickable UI matched '$selector'" } }
    }

    suspend fun type(selector: String, text: String): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching { check(requireService().setText(selector, text)) { "No editable UI matched '$selector'" } }
    }

    suspend fun tap(x: Float, y: Float): Result<Unit> = onService {
        check(tap(x, y)) { "Tap gesture was rejected" }
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long): Result<Unit> = onService {
        check(longPress(x, y, durationMs)) { "Long-press gesture was rejected" }
    }

    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Result<Unit> = onService {
        check(swipe(fromX, fromY, toX, toY, durationMs)) { "Swipe gesture was rejected" }
    }

    suspend fun waitFor(selector: String, timeoutMs: Long): Result<Unit> = onService {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100L, 120_000L)
        while (System.currentTimeMillis() < deadline) {
            if (hasNode(selector)) return@onService
            delay(200L)
        }
        error("Timed out waiting for UI matching '$selector'")
    }

    suspend fun global(action: String): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val code = when (action.lowercase()) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick-settings", "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> error("Unknown global action '$action'")
            }
            check(requireService().performGlobalAction(code)) { "Global action '$action' was rejected" }
        }
    }

    private suspend fun <T> onService(block: suspend DeviceAutomationService.() -> T): Result<T> =
        runCatching { requireService().block() }

    private fun requireService(): DeviceAutomationService =
        DeviceAutomationService.current()
            ?: error("Device verification is disabled. Enable Orion in Android Accessibility settings first.")
}
