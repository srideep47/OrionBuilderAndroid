package com.localai.qnn

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * QnnModule — loads QNN (Qualcomm Neural Network) HTP backend libraries at runtime.
 *
 * Modelled after MNN Chat's QnnModule.kt (com.alibaba.mnnllm.android.qnn).
 *
 * KEY INSIGHT: libQnnHtpV79Stub.so has a static DT_NEEDED on libcdsprpc.so (the
 * Qualcomm FastRPC client). For the linker to resolve it, libcdsprpc.so must either:
 *   a) Already be loaded in the app's namespace (we load it explicitly first), OR
 *   b) Be accessible via <uses-library android:name="libcdsprpc.so"> in the manifest
 *      (Qualcomm devices register it as a shared library in /vendor/etc/permissions/).
 *
 * We do both: the manifest entry bridges the vendor namespace on supported devices,
 * and the explicit load() call handles devices where bundling the .so is needed.
 *
 * LOAD ORDER (order matters — each lib is a dependency of the next):
 *   1. libcdsprpc.so  — Qualcomm FastRPC client. Best-effort; manifest may cover it.
 *   2. libQnnHtp.so   — Main HTP backend driver. CRITICAL.
 *   3. libQnnSystem.so — Device enumeration + context lifecycle. CRITICAL.
 *   4. libQnnHtp{V}Skel.so — DSP firmware; best-effort (DSP sideloader finds via ADSP_LIBRARY_PATH).
 *   5. libQnnHtp{V}Stub.so — ARM↔DSP communication stub. CRITICAL.
 */
object QnnModule {
    private const val TAG = "QnnModule"

    @Volatile private var qnnInit = false

    /** Populated with the exact error on failure — surfaced in MnnLlmModule's rejection. */
    @Volatile var lastInitError: String = ""; private set

    // SOC_MODEL → Hexagon architecture version
    // Source: https://docs.qualcomm.com/bundle/publicresource/topics/80-63442-50/overview.html
    private val socToArch = mapOf(
        "SM8750" to "V79",   // Snapdragon 8 Elite  (Honor Magic 7 Pro, etc.)
        "SM8650" to "V75",   // Snapdragon 8 Gen 3
        "SM8550" to "V73",   // Snapdragon 8 Gen 2
        "SM8475" to "V69",   // Snapdragon 8+ Gen 1
        "SM8450" to "V69",   // Snapdragon 8 Gen 1
        "SM8350P" to "V68",  // Snapdragon 888+
        "SM8350" to "V68",   // Snapdragon 888
    )

    // SOC_MODEL → visual encoder filename middle segment (for multimodal QNN models)
    // e.g. visual_qnn_69_v79.mnn on SM8750
    private val socToModelName = mapOf(
        "SM8750" to "69_v79",
        "SM8650" to "57_v75",
        "SM8550" to "43_v73",
        "SM8475" to "42_v69",
        "SM8450" to "36_v69",
        "SM8350P" to "30_v68",
        "SM8350" to "30_v68",
    )

    // ── SOC detection ─────────────────────────────────────────────────────────

    private fun getSoc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    private fun getArch(): String? = socToArch[getSoc()]

    fun deviceSupported(): Boolean = getArch() != null

    /** Middle segment of the visual encoder filename for multimodal QNN models.
     *  Used by LlmSession: visual_qnn_${modelMiddleName()}.mnn */
    fun modelMiddleName(): String? = socToModelName[getSoc()]

    // ── Library loading ────────────────────────────────────────────────────────

    private fun load(name: String, dir: String): Boolean {
        val path = "$dir/lib$name.so"
        return try {
            System.load(path)
            Log.i(TAG, "✓ lib$name.so loaded from $path")
            true
        } catch (e: UnsatisfiedLinkError) {
            lastInitError = "lib$name.so: ${e.message?.take(300)}"
            Log.e(TAG, "✗ lib$name.so failed: ${e.message}")
            false
        }
    }

    // ── Init ───────────────────────────────────────────────────────────────────

    /**
     * Loads all QNN HTP libraries from the app's native library directory.
     *
     * Pre-conditions:
     *   - The APK must contain libQnnHtp.so, libQnnHtp{V}Skel.so,
     *     libQnnHtp{V}Stub.so in jniLibs/arm64-v8a/.
     *   - For OFFLINE QNN: libQnnSystem.so must also be present.
     *   - For ONLINE QNN: libQnnHtpPrepare.so must also be present.
     *   - libcdsprpc.so must be accessible — either bundled (same jniLibs dir) or
     *     declared via <uses-library android:name="libcdsprpc.so"> in the manifest.
     *
     * Idempotent — returns true immediately on subsequent calls.
     */
    suspend fun loadQnnLibs(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (qnnInit) {
            Log.d(TAG, "QNN already initialised")
            return@withContext true
        }

        lastInitError = ""

        val arch = getArch() ?: run {
            lastInitError = "SOC '${getSoc()}' not in supported list"
            Log.e(TAG, "QNN not supported: $lastInitError")
            return@withContext false
        }

        val dir = context.applicationInfo.nativeLibraryDir
        Log.i(TAG, "QNN init — SOC=${getSoc()}  arch=$arch  dir=$dir")

        // Set ADSP_LIBRARY_PATH so the HTP backend can locate the DSP Skel binary.
        // Set LD_LIBRARY_PATH so the linker finds transitive deps (libcdsprpc etc.)
        // in our native lib dir when loading the Stub.
        try {
            Os.setenv("ADSP_LIBRARY_PATH", dir, true)
            Os.setenv("LD_LIBRARY_PATH", dir, true)
            Log.i(TAG, "ADSP_LIBRARY_PATH=$dir  LD_LIBRARY_PATH=$dir")
        } catch (e: Throwable) {
            lastInitError = "Os.setenv failed: ${e.message}"
            Log.e(TAG, lastInitError)
            return@withContext false
        }

        // ── Load order: cdsprpc → QnnHtp → QnnSystem → QnnHtpPrepare → Skel → Stub
        //
        // cdsprpc is best-effort: if the manifest <uses-library> bridged the vendor
        // namespace, the linker already knows about it and this may log "already loaded".
        // If the library is physically bundled in our APK, it succeeds here and the
        // Stub's DT_NEEDED is satisfied when the Stub is loaded below.
        load("cdsprpc", dir)  // best-effort — non-fatal

        if (!load("QnnHtp", dir)) {
            Log.e(TAG, "CRITICAL: libQnnHtp.so failed — $lastInitError")
            return@withContext false
        }

        // QnnSystem is needed for OFFLINE QNN (AOT models). Best-effort for online.
        if (!load("QnnSystem", dir)) {
            Log.w(TAG, "libQnnSystem.so not loaded — offline QNN models may not work")
        }

        // QnnHtpPrepare is needed for ONLINE QNN (runtime compilation).
        // Best-effort: if not bundled, online NPU mode won't work but offline will.
        if (!load("QnnHtpPrepare", dir)) {
            Log.w(TAG, "libQnnHtpPrepare.so not loaded — online NPU (backend_type: \"npu\") will not work. " +
                "Offline QNN models (qnn/ directory) are unaffected.")
        }

        // Skel is the DSP firmware binary. The HTP backend also loads it via
        // ADSP_LIBRARY_PATH at HTP context creation time, so JVM load failure here
        // is non-fatal.
        if (!load("QnnHtp${arch}Skel", dir)) {
            Log.w(TAG, "libQnnHtp${arch}Skel.so not loaded by JVM — DSP sideloader will handle it")
        }

        if (!load("QnnHtp${arch}Stub", dir)) {
            Log.e(TAG, "CRITICAL: libQnnHtp${arch}Stub.so failed — $lastInitError")
            return@withContext false
        }

        qnnInit = true
        Log.i(TAG, "QNN init complete ✓  SOC=${getSoc()}  arch=$arch")
        return@withContext true
    }
}
