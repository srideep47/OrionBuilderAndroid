package com.legion.orion.data.media

import android.content.Context
import android.util.Log
import com.legion.orion.core.common.Dispatcher
import com.legion.orion.core.common.OrionDispatchers
import com.legion.orion.core.database.JobsDao
import com.legion.orion.core.database.MediaJobEntity
import com.legion.orion.infra.p2p.P2pController
import com.legion.orion.infra.p2p.P2pEvent
import com.legion.orion.infra.p2p.SharedMediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A trusted peer's advertised shared-media catalog (the "media shared on the network"). */
data class RemoteCatalog(
    val peerKey: String,
    val displayName: String,
    val items: List<SharedMediaItem>,
)

/** Progress of a shared-media download we initiated. status: downloading | done | error. */
data class ShareDownloadState(
    val fileName: String,
    val peerKey: String,
    val received: Long,
    val total: Long,
    val status: String,
)

/**
 * Owns the media lifecycle on the phone.
 *
 * Generation NEVER runs locally (non-negotiable). [queue] dispatches an inline one-shot
 * generation request to a trusted compute peer (desktop MEDIA_GEN_REQUEST) and reassembles the
 * streamed MEDIA_GEN_CHUNK bytes into the gallery.
 *
 * Shared media: this device both advertises its gallery to trusted peers (MEDIA_ANNOUNCE) and
 * tracks peers' advertised catalogs in [catalogs]. Files transfer chunked via the desktop
 * media-share protocol (MEDIA_DOWNLOAD_*). Downloaded items become normal gallery library items.
 */
@Singleton
class MediaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: P2pController,
    private val jobs: JobsDao,
    @Dispatcher(OrionDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val json = Json { ignoreUnknownKeys = true }

    val galleryDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }

    // Remote peers' shared catalogs — the network shared-media folder.
    private val _catalogs = MutableStateFlow<List<RemoteCatalog>>(emptyList())
    val catalogs: StateFlow<List<RemoteCatalog>> = _catalogs.asStateFlow()

    // Active shared-media downloads, keyed by "peerKey/fileName".
    private val _downloads = MutableStateFlow<Map<String, ShareDownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, ShareDownloadState>> = _downloads.asStateFlow()

    private val genJobs = ConcurrentHashMap<String, GenAccum>()       // requestId(=jobId) -> generation
    private val shareDownloads = ConcurrentHashMap<String, DlAccum>() // requestId -> shared download

    private class GenAccum(val type: String) { val chunks = ArrayList<ByteArray>(); var count = 0 }
    private class DlAccum(val peerKey: String, val item: SharedMediaItem) {
        val chunks = ArrayList<ByteArray>(); var received = 0L
    }

    init {
        scope.launch { controller.mediaEvents.collect { handle(it) } }
        // When a trusted peer comes online, publish our catalog and pull theirs; drop stale ones.
        scope.launch {
            var known = emptySet<String>()
            controller.peers.collect { peers ->
                val online = peers.filter { it.isTrusted && it.status == "online" }.map { it.publicKey }.toSet()
                val fresh = online - known
                known = online
                if (fresh.isNotEmpty()) {
                    val mine = localCatalog()
                    fresh.forEach { pk ->
                        runCatching { controller.announceMedia(pk, mine); controller.requestMediaList(pk) }
                    }
                }
                val present = peers.map { it.publicKey }.toSet()
                if (_catalogs.value.any { it.peerKey !in present }) {
                    _catalogs.value = _catalogs.value.filter { it.peerKey in present }
                }
            }
        }
    }

    /** True when at least one trusted peer is advertising spare compute. */
    fun hasComputePeer(): Boolean = controller.peers.value.any { it.isTrusted && it.computeAvailable }

    // ── Generation (inline one-shot) ──────────────────────────────────────────

    /**
     * Generate one media asset on a trusted compute peer and stream it back inline. Returns the job
     * id immediately; the result lands in the gallery (media_jobs) when the peer finishes.
     */
    suspend fun queue(type: String, prompt: String, paramsJson: String? = null): String {
        val jobId = UUID.randomUUID().toString()
        val peer = controller.peers.value.firstOrNull { it.isTrusted && it.computeAvailable }
            ?: controller.peers.value.firstOrNull { it.isTrusted && it.status == "online" }
        jobs.upsertMediaJob(
            MediaJobEntity(
                id = jobId, type = type, prompt = prompt, paramsJson = paramsJson,
                state = if (peer != null) "running" else "queued",
                assignedPeer = peer?.publicKey, resultPath = null, progress = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        if (peer != null) {
            genJobs[jobId] = GenAccum(type)
            val options: JsonElement? = paramsJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            runCatching {
                controller.requestMediaGen(peer.publicKey, jobId, modelTypeFor(type), prompt, extFor(type), options)
            }.onFailure { genJobs.remove(jobId); update(jobId) { it.copy(state = "failed") } }
        }
        return jobId
    }

    /** Local cancel — there is no remote cancel in the one-shot protocol; we just stop assembling. */
    suspend fun cancel(jobId: String) {
        genJobs.remove(jobId)
        update(jobId) { it.copy(state = "failed") }
    }

    suspend fun delete(jobId: String) {
        jobs.mediaJob(jobId)?.resultPath?.let { runCatching { File(it).delete() } }
        jobs.deleteMediaJob(jobId)
        announceCatalog()
    }

    // ── Shared-media folder (network) ─────────────────────────────────────────

    /** Pull the latest catalogs from all trusted online peers and (re)publish ours. */
    suspend fun refreshCatalogs() {
        val mine = localCatalog()
        controller.peers.value.filter { it.isTrusted && it.status == "online" }.forEach { p ->
            runCatching { controller.requestMediaList(p.publicKey); controller.announceMedia(p.publicKey, mine) }
        }
    }

    /** Download a peer's shared item into our gallery (it becomes a normal library item). */
    fun download(peerKey: String, item: SharedMediaItem) {
        val requestId = "dl_" + UUID.randomUUID()
        shareDownloads[requestId] = DlAccum(peerKey, item)
        setDownload(peerKey, item.fileName, 0, item.sizeBytes, "downloading")
        scope.launch {
            runCatching { controller.requestMediaDownload(peerKey, requestId, item.fileName) }
                .onFailure {
                    shareDownloads.remove(requestId)
                    setDownload(peerKey, item.fileName, 0, item.sizeBytes, "error")
                }
        }
    }

    // ── Event handling ────────────────────────────────────────────────────────

    private suspend fun handle(event: P2pEvent) {
        when (event) {
            is P2pEvent.MediaGenChunk -> onGenChunk(event)
            is P2pEvent.MediaGenError -> { genJobs.remove(event.requestId); update(event.requestId) { it.copy(state = "failed") } }
            is P2pEvent.MediaAnnounce -> onAnnounce(event.peerId, event.items)
            is P2pEvent.MediaListRequest -> runCatching { controller.announceMedia(event.peerId, localCatalog()) }
            is P2pEvent.MediaDownloadRequest -> serveFile(event.peerId, event.requestId, event.fileName)
            is P2pEvent.MediaDownloadChunk -> onDownloadChunk(event)
            is P2pEvent.MediaDownloadError -> onDownloadError(event)
            else -> Unit
        }
    }

    private suspend fun onGenChunk(event: P2pEvent.MediaGenChunk) {
        val acc = genJobs[event.requestId] ?: return
        if (event.data.isNotEmpty()) runCatching { acc.chunks.add(Base64.getDecoder().decode(event.data)) }
        acc.count++
        if (event.eof) {
            genJobs.remove(event.requestId)
            val path = saveBytes("${event.requestId}.${extFor(acc.type)}", acc.chunks)
            if (path != null) {
                update(event.requestId) { it.copy(state = "done", progress = 100, resultPath = path) }
                announceCatalog()
            } else {
                update(event.requestId) { it.copy(state = "failed") }
            }
        } else {
            val pct = minOf(95, 8 + acc.count * 6)
            update(event.requestId) { it.copy(state = "running", progress = pct) }
        }
    }

    private fun onAnnounce(peerKey: String, items: List<SharedMediaItem>) {
        val name = controller.peers.value.firstOrNull { it.publicKey == peerKey }
            ?.displayName?.takeIf { it.isNotBlank() } ?: peerKey.take(8)
        val others = _catalogs.value.filterNot { it.peerKey == peerKey }
        _catalogs.value = if (items.isEmpty()) others else others + RemoteCatalog(peerKey, name, items)
    }

    /** Serve a gallery file a trusted peer requested, chunked (trust is gated in the bridge). */
    private fun serveFile(peerKey: String, requestId: String, fileName: String) {
        scope.launch {
            val f = File(galleryDir, File(fileName).name)
            if (!f.exists() || !f.isFile) {
                runCatching { controller.respondMediaDownloadError(peerKey, requestId, "File not found") }
                return@launch
            }
            val bytes = runCatching { f.readBytes() }.getOrElse {
                runCatching { controller.respondMediaDownloadError(peerKey, requestId, "Read failed") }
                return@launch
            }
            if (bytes.isEmpty()) {
                runCatching { controller.respondMediaDownloadChunk(peerKey, requestId, "", true) }
                return@launch
            }
            var off = 0
            while (off < bytes.size) {
                val end = minOf(off + CHUNK, bytes.size)
                val eof = end >= bytes.size
                val b64 = Base64.getEncoder().encodeToString(bytes.copyOfRange(off, end))
                val ok = runCatching { controller.respondMediaDownloadChunk(peerKey, requestId, b64, eof); true }.getOrDefault(false)
                if (!ok) return@launch
                off = end
            }
        }
    }

    private suspend fun onDownloadChunk(event: P2pEvent.MediaDownloadChunk) {
        val acc = shareDownloads[event.requestId] ?: return
        if (event.data.isNotEmpty()) runCatching {
            val b = Base64.getDecoder().decode(event.data); acc.chunks.add(b); acc.received += b.size
        }
        if (event.eof) {
            shareDownloads.remove(event.requestId)
            val path = saveSharedItem(acc)
            setDownload(acc.peerKey, acc.item.fileName, acc.received, acc.item.sizeBytes, if (path != null) "done" else "error")
        } else {
            setDownload(acc.peerKey, acc.item.fileName, acc.received, acc.item.sizeBytes, "downloading")
        }
    }

    private fun onDownloadError(event: P2pEvent.MediaDownloadError) {
        val acc = shareDownloads.remove(event.requestId) ?: return
        setDownload(acc.peerKey, acc.item.fileName, acc.received, acc.item.sizeBytes, "error")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun saveSharedItem(acc: DlAccum): String? {
        val safe = File(acc.item.fileName).name.ifBlank { "shared_${UUID.randomUUID()}" }
        val path = saveBytes(safe, acc.chunks) ?: return null
        jobs.upsertMediaJob(
            MediaJobEntity(
                id = "shared_" + UUID.randomUUID(),
                type = acc.item.kind,
                prompt = acc.item.prompt ?: "Shared from peer",
                paramsJson = null, state = "done", assignedPeer = acc.peerKey,
                resultPath = path, progress = 100, createdAt = System.currentTimeMillis(),
            ),
        )
        return path
    }

    private fun saveBytes(fileName: String, chunks: List<ByteArray>): String? = runCatching {
        val out = File(galleryDir, fileName)
        out.writeBytes(concat(chunks))
        out.absolutePath
    }.getOrElse { Log.w(TAG, "saveBytes failed", it); null }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { System.arraycopy(c, 0, out, pos, c.size); pos += c.size }
        return out
    }

    /** Build our sharable catalog from finished gallery items (generated + downloaded). */
    private suspend fun localCatalog(): List<SharedMediaItem> =
        jobs.observeMediaJobs().first().mapNotNull { j ->
            val f = j.resultPath?.let { File(it) } ?: return@mapNotNull null
            if (!f.exists() || !f.isFile) return@mapNotNull null
            SharedMediaItem(
                fileName = f.name,
                kind = shareKind(j.type),
                mimeType = mimeForExt(f.extension),
                sizeBytes = f.length(),
                prompt = j.prompt,
                thumbnail = null,
            )
        }

    private fun announceCatalog() {
        scope.launch { runCatching { controller.announceMedia(null, localCatalog()) } }
    }

    private fun setDownload(peerKey: String, fileName: String, received: Long, total: Long, status: String) {
        _downloads.value = _downloads.value + ("$peerKey/$fileName" to ShareDownloadState(fileName, peerKey, received, total, status))
    }

    private suspend fun update(jobId: String, transform: (MediaJobEntity) -> MediaJobEntity) {
        val current = jobs.mediaJob(jobId) ?: return
        jobs.upsertMediaJob(transform(current))
    }

    private fun modelTypeFor(type: String) = when (type.lowercase()) {
        "video" -> "video"; "audio", "music" -> "audio"; else -> "image"
    }
    private fun extFor(type: String) = when (type.lowercase()) {
        "video" -> "mp4"; "audio", "music" -> "mp3"; else -> "png"
    }
    private fun shareKind(type: String) = when (type.lowercase()) {
        "video" -> "video"; "audio", "music" -> "audio"; "model" -> "model"; else -> "image"
    }
    private fun mimeForExt(ext: String) = when (ext.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"; "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private companion object { const val TAG = "MediaManager"; const val CHUNK = 48 * 1024 }
}
