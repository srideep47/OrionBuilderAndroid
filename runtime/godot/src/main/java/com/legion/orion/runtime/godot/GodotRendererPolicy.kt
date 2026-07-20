package com.legion.orion.runtime.godot

import android.os.Build

enum class RendererTier { FORWARD_PLUS_VULKAN, GLES3_COMPATIBILITY }

/**
 * SoC -> renderer tier, structurally cloned from `QnnModule.socToArch`
 * (engine/qnn/src/main/java/com/localai/qnn/QnnModule.kt:42-50).
 *
 * Godot's renderer choice is a build/export-time project setting, not per-device auto-detection —
 * the post-4.4 Vulkan-to-GLES3 auto-fallback is documented as unreliable in practice on Android
 * (godotengine/godot#111729), so this picks explicitly per SoC rather than trusting that fallback.
 *
 * [RendererTier.GLES3_COMPATIBILITY] (the Kirin-970-class tier: Mali-G72 MP12, Vulkan 1.0 only,
 * documented Mali/Vulkan driver bugs through 4.6) is implemented but NOT device-verified — the only
 * live test device is the Snapdragon 8 Elite Honor Magic 7 Pro. Surface this as an untested tier in
 * any UI that reports it, rather than presenting it as validated.
 */
object GodotRendererPolicy {
    private val socToRendererTier = mapOf(
        // Snapdragon 8 Elite (Honor Magic 7 Pro, etc.) — Adreno 830, Vulkan 1.3.
        "SM8750" to RendererTier.FORWARD_PLUS_VULKAN,
    )

    fun tierFor(): RendererTier = socToRendererTier[socModel()] ?: RendererTier.GLES3_COMPATIBILITY

    private fun socModel(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    /** Godot engine command-line args implementing [tier] as a project-setting override. */
    fun commandLineArgsFor(tier: RendererTier): List<String> = when (tier) {
        RendererTier.FORWARD_PLUS_VULKAN -> emptyList() // project default (Forward+/Mobile, Vulkan)
        RendererTier.GLES3_COMPATIBILITY -> listOf(
            "--rendering-method", "gl_compatibility",
            "--rendering-driver", "opengl3",
        )
    }
}
