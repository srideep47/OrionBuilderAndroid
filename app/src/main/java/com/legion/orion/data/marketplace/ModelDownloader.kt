package com.legion.orion.data.marketplace

import android.content.Context
import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import com.legion.orion.core.database.DownloadDao
import com.legion.orion.core.database.DownloadEntity
import com.legion.orion.core.database.LocalModelDao
import com.legion.orion.core.database.LocalModelEntity
import com.localai.utils.FileSplitter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resumable, multi-file model downloader. MNN models are directories (config.json
 * + .mnn + weights + tokenizer); each file is fetched with HTTP Range so a paused
 * or interrupted download continues from the last byte. Split weights are merged
 * via [FileSplitter]. Progress is mirrored to [DownloadDao] for the UI; on success
 * a [LocalModelEntity] is registered.
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val api: MarketplaceApi,
    private val localModels: LocalModelDao,
    private val downloads: DownloadDao,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val modelsRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun localId(model: RemoteModel): String = "${model.source.name}:${model.repoId}"

    /** Absolute path of the models folder (shown in the UI so users know where to drop models). */
    fun modelsFolderPath(): String = modelsRoot.absolutePath

    /**
     * Scan the models folder for any model directory (one containing config.json)
     * that isn't registered yet, and register it. Lets manually-pushed models —
     * or models from an interrupted session — show up in the Local tab so they can
     * be loaded. Returns the number newly registered.
     */
    suspend fun scanLocalModels(): Int = withContext(io) {
        val known = localModels.all().map { it.localPath }.toSet()
        var added = 0
        modelsRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val cfg = dir.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" } ?: return@forEach
            val loadDir = cfg.parentFile ?: dir
            if (loadDir.absolutePath in known) return@forEach
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            localModels.upsert(
                LocalModelEntity(
                    id = "file:${loadDir.absolutePath}",
                    source = "imported",
                    repoId = dir.name,
                    localPath = loadDir.absolutePath,
                    format = "mnn",
                    quant = ModelHeuristics.quant(dir.name),
                    sizeBytes = size,
                    capabilities = ModelHeuristics.capabilities(dir.name).joinToString(",") { it.label },
                    lastUsedBackend = null,
                ),
            )
            added++
        }
        added
    }

    /** Register an arbitrary model directory (must contain config.json) by absolute path. */
    suspend fun registerPath(path: String): LocalModelEntity? = withContext(io) {
        val dir = File(path)
        val cfg = dir.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" } ?: return@withContext null
        val loadDir = cfg.parentFile ?: dir
        val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val entity = LocalModelEntity(
            id = "file:${loadDir.absolutePath}",
            source = "imported",
            repoId = dir.name,
            localPath = loadDir.absolutePath,
            format = "mnn",
            quant = ModelHeuristics.quant(dir.name),
            sizeBytes = size,
            capabilities = ModelHeuristics.capabilities(dir.name).joinToString(",") { it.label },
            lastUsedBackend = null,
        )
        localModels.upsert(entity)
        entity
    }

    fun modelDir(model: RemoteModel): File =
        File(modelsRoot, model.repoId.replace('/', '_').replace(':', '_'))

    /** Download every relevant file in the repo. Cancel the coroutine to pause
     * (partial `.part` files remain and resume on the next call). */
    suspend fun download(
        model: RemoteModel,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<LocalModelEntity> = withContext(io) {
        runCatching {
            val id = localId(model)
            val files = api.listFiles(model.source, model.repoId).filter { keep(it.path) }
            require(files.isNotEmpty()) { "No downloadable files found in ${model.repoId}" }

            val total = files.sumOf { it.size }.coerceAtLeast(1)
            val dir = modelDir(model).apply { mkdirs() }
            var done = files.sumOf { existingBytes(File(dir, it.path)) }

            downloads.upsert(DownloadEntity(id, id, "running", total, done, null, null))
            onProgress(done, total)

            var lastPersist = 0L
            for (f in files) {
                coroutineContext.ensureActive()
                val target = File(dir, f.path).apply { parentFile?.mkdirs() }
                fetch(api.fileUrl(model.source, model.repoId, f.path), target, f.size) { delta ->
                    done += delta
                    onProgress(done, total)
                    // Persist progress at ~4MB granularity (the callback fires per 64KB buffer).
                    if (done - lastPersist >= 4L * 1024 * 1024) {
                        lastPersist = done
                        downloads.upsert(DownloadEntity(id, id, "running", total, done.coerceAtMost(total), null, null))
                    }
                }
            }

            // Merge any split weight files produced by FileSplitter conventions.
            runCatching { if (FileSplitter.needsMerging(dir)) FileSplitter.mergeAllSplitFiles(dir) }

            val loadDir = findModelRoot(dir)
            val sizeOnDisk = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val entity = LocalModelEntity(
                id = id,
                source = if (model.source == ModelSource.HUGGINGFACE) "hf" else "modelscope",
                repoId = model.repoId,
                localPath = loadDir.absolutePath,
                format = "mnn",
                quant = model.quant,
                sizeBytes = sizeOnDisk,
                capabilities = model.capabilities.joinToString(",") { it.label },
                lastUsedBackend = null,
            )
            localModels.upsert(entity)
            downloads.upsert(DownloadEntity(id, id, "done", total, total, null, null))
            entity
        }.onFailure { e ->
            val id = localId(model)
            downloads.upsert(DownloadEntity(id, id, "failed", 0, 0, null, e.message))
        }
    }

    /** Returns bytes newly written. Resumes via Range if a partial file exists. */
    private suspend fun fetch(
        url: String,
        target: File,
        expected: Long,
        onDelta: suspend (Long) -> Unit,
    ): Long {
        val already = existingBytes(target)
        if (expected > 0 && already >= expected) return 0

        val reqB = Request.Builder().url(url).header("User-Agent", "OrionBuilderAndroid/1.0")
        if (already > 0) reqB.header("Range", "bytes=$already-")
        val resp = http.newCall(reqB.build()).execute()
        resp.use { r ->
            require(r.isSuccessful) { "HTTP ${r.code} for $url" }
            val resuming = r.code == 206
            val raf = RandomAccessFile(target, "rw")
            if (resuming) raf.seek(already) else raf.setLength(0)
            var written = 0L
            r.body?.byteStream()?.use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    written += n
                    onDelta(n.toLong())
                }
            }
            raf.close()
            return written
        }
    }

    private fun existingBytes(f: File): Long = if (f.isFile) f.length() else 0

    /** Skip repo cruft; keep model weights, configs, tokenizers. */
    private fun keep(path: String): Boolean {
        val lc = path.lowercase()
        if (lc.endsWith(".gitattributes") || lc.endsWith(".md")) return false
        if (lc.endsWith(".png") || lc.endsWith(".jpg") || lc.endsWith(".jpeg") || lc.endsWith(".gif")) return false
        return true
    }

    /** The directory containing config.json is what the engine loads. */
    private fun findModelRoot(dir: File): File {
        val cfg = dir.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" }
        return cfg?.parentFile ?: dir
    }
}
