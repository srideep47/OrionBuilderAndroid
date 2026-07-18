package com.legion.orion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * User-enabled automation surface for testing apps installed by Orion. Android deliberately blocks
 * one app from reading or capturing another app's UI; an AccessibilityService is the supported,
 * explicit-consent path. The service is not exported and accepts commands only through the in-app
 * controller/localhost bridge.
 */
class DeviceAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        instance.set(this)
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        instance.compareAndSet(this, null)
        _connected.value = false
    }

    fun uiTree(maxNodes: Int = 600): String {
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        if (roots.isEmpty()) return "(no active accessibility window)"
        val lines = ArrayList<String>()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count++ >= maxNodes) return
            val text = if (node.isPassword) "••••" else node.text?.toString().orEmpty()
            val desc = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
                if (!node.isEnabled) add("disabled")
                if (node.isChecked) add("checked")
            }
            if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || flags.isNotEmpty()) {
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                lines += buildString {
                    append("  ".repeat(depth.coerceAtMost(12))).append(cls.ifBlank { "node" })
                    if (text.isNotBlank()) append(" text=").append(text.take(120).quoted())
                    if (desc.isNotBlank()) append(" desc=").append(desc.take(120).quoted())
                    if (id.isNotBlank()) append(" id=").append(id)
                    if (flags.isNotEmpty()) append(" [").append(flags.joinToString()).append(']')
                    append(" bounds=").append(bounds.flattenToString())
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        if (count >= maxNodes) lines += "… truncated at $maxNodes nodes"
        return lines.joinToString("\n").ifBlank { "(active UI has no labelled nodes)" }
    }

    suspend fun click(selector: String): Boolean {
        val node = findNode(selector) ?: return false
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val target = clickable ?: node
        val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
        if (bounds.isEmpty) return false
        // A physical gesture is more reliable than ACTION_CLICK for Compose semantics nodes: some
        // OEM accessibility stacks report ACTION_CLICK success without dispatching the lambda.
        return tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun hasNode(selector: String): Boolean = findNode(selector) != null

    fun setText(selector: String, value: String): Boolean {
        val node = findNode(selector) ?: return false
        var editable: AccessibilityNodeInfo? = node
        while (editable != null && !editable.isEditable) editable = editable.parent
        val target = editable ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNode(selector: String): AccessibilityNodeInfo? {
        val query = selector.trim()
        if (query.isEmpty()) return null
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        roots.forEach { root ->
            if (query.contains(':')) {
                root.findAccessibilityNodeInfosByViewId(query).firstOrNull()?.let { return it }
            }
            root.findAccessibilityNodeInfosByText(query).firstOrNull { it.matchesSelector(query) }?.let { return it }
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 2000) {
                val node = queue.removeFirst()
                if (node.matchesSelector(query)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.matchesSelector(query: String): Boolean {
        val values = listOf(
            if (isPassword) "" else text?.toString().orEmpty(),
            contentDescription?.toString().orEmpty(),
            viewIdResourceName.orEmpty(),
        )
        return values.any { it.equals(query, ignoreCase = true) || it.contains(query, ignoreCase = true) }
    }

    suspend fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 80L)

    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean =
        gesture(x, y, x, y, durationMs.coerceIn(400L, 5_000L))

    suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean =
        gesture(fromX, fromY, toX, toY, durationMs.coerceIn(100L, 5_000L))

    private suspend fun gesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(fromX, fromY)
            if (fromX != toX || fromY != toY) lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    suspend fun screenshot(dest: File): Result<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(UnsupportedOperationException("Device screenshots require Android 11 or newer"))
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    buffer.close()
                    val result = runCatching {
                        requireNotNull(bitmap) { "Android returned an empty screenshot" }
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
                        }
                        dest
                    }.also {
                        bitmap?.recycle()
                    }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Screenshot failed (Android error $errorCode)")))
                }
            })
        }
    }

    companion object {
        private val instance = AtomicReference<DeviceAutomationService?>(null)
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()

        internal fun current(): DeviceAutomationService? = instance.get()
    }
}

private fun String.quoted(): String = "\"" + replace("\"", "\\\"").replace("\n", "\\n") + "\""
