package com.legion.orion

import androidx.test.platform.app.InstrumentationRegistry
import com.alibaba.mls.api.ApplicationProvider
import com.legion.orion.engine.mnn.MnnBackend
import com.legion.orion.engine.mnn.OrionMnnEngine
import java.io.File
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * M0 gate: stream 50 tokens from a local Qwen3 0.6B on CPU and OpenCL.
 *
 * Push a model to the device and pass its directory:
 *   adb push qwen3-0.6b /data/local/tmp/orion-model
 *   adb shell am instrument -w \
 *     -e modelDir /data/local/tmp/orion-model \
 *     com.legion.orion.debug.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Skipped (not failed) when no modelDir is supplied, so CI without a model stays green.
 */
class EngineSmokeTest {

    private val modelDir: String? by lazy {
        InstrumentationRegistry.getArguments().getString("modelDir")
    }

    @Test
    fun streams50TokensOnCpu() = runStreamTest(MnnBackend.CPU)

    @Test
    fun streams50TokensOnOpenCl() = runStreamTest(MnnBackend.OPENCL)

    private fun runStreamTest(backend: MnnBackend) {
        val dir = modelDir
        assumeTrue("No -e modelDir supplied; skipping on-device stream test", dir != null)
        assumeTrue("config.json missing in $dir", File(dir, "config.json").exists())

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ApplicationProvider.set(app as android.app.Application)

        val engine = OrionMnnEngine()
        runBlocking {
            engine.load(dir!!, backend)
            assertTrue("model failed to load on $backend", engine.isLoaded)

            val chunks = engine.generate("Count slowly: one, two, three, four")
                .take(50)
                .toList()

            val text = chunks.joinToString("") { it.text }
            assertTrue("expected streamed tokens on $backend", text.isNotBlank())
            assertTrue("expected >=50 chunks on $backend, got ${chunks.size}", chunks.size >= 50)
            engine.unload()
        }
    }
}
