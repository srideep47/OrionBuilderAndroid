package com.legion.orion.data.marketplace

/** A model source the marketplace can browse. */
enum class ModelSource(val label: String) { HUGGINGFACE("HuggingFace"), MODELSCOPE("ModelScope") }

/** A capability badge inferred from a repo id / tags. */
enum class Capability(val label: String) { CHAT("chat"), VISION("vision"), THINKING("thinking"), OMNI("omni"), AUDIO("audio") }

/** A model listing returned from a marketplace search. */
data class RemoteModel(
    val source: ModelSource,
    val repoId: String,
    val org: String,
    val name: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val sizeBytes: Long = 0,
    val quant: String? = null,
    val capabilities: List<Capability> = listOf(Capability.CHAT),
    val mnnReady: Boolean = false,
)

/** A file inside a model repo. */
data class RemoteFile(val path: String, val size: Long)

object ModelHeuristics {
    fun org(repoId: String): String = repoId.substringBefore('/', "")
    fun name(repoId: String): String = repoId.substringAfter('/', repoId)

    fun isMnn(repoId: String, tags: List<String> = emptyList()): Boolean {
        val lc = repoId.lowercase()
        return lc.contains("mnn") || tags.any { it.equals("MNN", true) || it.contains("mnn", true) }
    }

    fun capabilities(repoId: String, tags: List<String> = emptyList()): List<Capability> {
        val s = (repoId + " " + tags.joinToString(" ")).lowercase()
        val caps = mutableListOf<Capability>()
        if (Regex("\\bvl\\b|vision|qwen-?vl|llava").containsMatchIn(s)) caps += Capability.VISION
        if (Regex("r1|qwq|reasoning|thinking|distill|deepseek-r").containsMatchIn(s)) caps += Capability.THINKING
        if (s.contains("omni")) caps += Capability.OMNI
        if (Regex("audio|whisper|asr|sense-?voice").containsMatchIn(s)) caps += Capability.AUDIO
        if (caps.isEmpty()) caps += Capability.CHAT
        return caps
    }

    fun quant(text: String): String? {
        val m = Regex("(?i)(q4_k_m|q4_0|q8_0|q4|q8|int4|int8|fp16|bf16|4bit|8bit|w4a16|gptq|awq)").find(text)
        return m?.value?.uppercase()
    }
}
