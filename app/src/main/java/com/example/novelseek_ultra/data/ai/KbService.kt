package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.EmbeddingConfig
import com.example.novelseek_ultra.data.model.KbChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Knowledge-base service: OpenAI-compatible embedding HTTP, chunking, and cosine retrieval.
 *
 * PC parity notes:
 *   - PC uses sqlite-vec (Rust) for the vector store. We brute-force cosine sim in memory — fine
 *     for the realistic scale of a single novel project (hundreds, not millions, of chunks).
 *   - Default endpoint is Aliyun Bailian (DashScope) `text-embedding-v3` at 1024 dims, exactly
 *     matching the PC default.
 *   - The dimensions field is forwarded to the API (DashScope supports truncation; OpenAI v3
 *     models do too). Custom providers that don't support it will ignore it.
 */
class KbService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // KB has no streaming endpoints, so a finite client-wide call timeout is fine and saves
        // us from per-call boilerplate. 90s covers DashScope's slowest embedding response while
        // still failing fast on hangs.
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Embed a single text string. Returns the raw vector. */
    suspend fun embed(text: String, cfg: EmbeddingConfig): FloatArray =
        embedBatch(listOf(text), cfg).first()

    /**
     * Embed a batch of texts. Automatically splits into mini-batches of [MAX_BATCH] per HTTP
     * request, because DashScope (the recommended default provider) caps `input` arrays at 10
     * per call — sending more triggers "InvalidParameter.BatchSize" and zero chunks get indexed.
     * Other providers (OpenAI) tolerate larger batches; the cap is conservative and safe for all.
     */
    suspend fun embedBatch(texts: List<String>, cfg: EmbeddingConfig): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>(texts.size)
        var i = 0
        while (i < texts.size) {
            val end = (i + MAX_BATCH).coerceAtMost(texts.size)
            out += embedBatchSingle(texts.subList(i, end), cfg)
            i = end
        }
        return out
    }

    private suspend fun embedBatchSingle(texts: List<String>, cfg: EmbeddingConfig): List<FloatArray> {
        val payload = buildJsonObject {
            put("model", cfg.model)
            // `input` can be string or array per OpenAI spec; we always send an array.
            put("input", JsonArray(texts.map { JsonPrimitive(it) }))
            put("encoding_format", "float")
            cfg.dimensions?.let { put("dimensions", it) }
        }.toString()

        val request = Request.Builder()
            .url("${cfg.apiUrl.trimEnd('/')}/embeddings")
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            // Surface the provider's actual error body — DashScope returns
                            // {"error": {"code": "InvalidParameter.BatchSize", "message": "..."}}
                            // which is the only signal a user has to fix their config.
                            cont.resumeWith(Result.failure(IOException(
                                "HTTP ${resp.code} (${texts.size} inputs): ${body.take(500)}"
                            )))
                            return
                        }
                        runCatching {
                            val root = Json.parseToJsonElement(body).jsonObject
                            val data = root["data"]?.jsonArray
                                ?: error("missing 'data' in embedding response: ${body.take(200)}")
                            data.map { entry ->
                                val vec = entry.jsonObject["embedding"]?.jsonArray
                                    ?: error("missing 'embedding' field in response entry")
                                FloatArray(vec.size) { i -> vec[i].jsonPrimitive.float }
                            }
                        }.onSuccess { cont.resumeWith(Result.success(it)) }
                            .onFailure { cont.resumeWith(Result.failure(it)) }
                    }
                }
            })
        }
    }

    private companion object {
        // DashScope hard limit; OpenAI/SiliconFlow allow more but this cap is safe for all.
        const val MAX_BATCH = 10
    }

    /** Quick credential check: try embedding one tiny string. */
    suspend fun testConnection(cfg: EmbeddingConfig): Boolean = runCatching {
        embed("ping", cfg).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Split [text] into overlapping chunks. Uses character-based chunking sized for CJK + Latin
     * mixed content — exact token count varies per tokenizer but this is the same heuristic PC
     * uses (chars ≈ tokens for CJK).
     */
    fun chunkText(text: String, chunkSize: Int = 600, overlap: Int = 100): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= chunkSize) return listOf(cleaned)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            val end = (start + chunkSize).coerceAtMost(cleaned.length)
            val isLast = end == cleaned.length
            // Last chunk: take everything remaining. Otherwise: prefer a paragraph/sentence
            // boundary, but enforce a minimum chunk size so we don't end up with sliver chunks
            // — AND cap at cleaned.length to avoid a substring-OOB when boundary < min and the
            // text tail is shorter than chunkSize/2 (e.g. start=3831, chunkSize=600, length=4030).
            val actualEnd = if (isLast) {
                cleaned.length
            } else {
                val boundary = findBoundary(cleaned, start, end)
                boundary.coerceAtLeast(start + chunkSize / 2).coerceAtMost(cleaned.length)
            }
            chunks += cleaned.substring(start, actualEnd).trim()
            if (actualEnd >= cleaned.length) break
            // Forward-progress guarantee: even if actualEnd - overlap doesn't advance past start,
            // step by at least 1 so the loop terminates on pathological inputs.
            val nextStart = (actualEnd - overlap).coerceAtLeast(start + 1)
            start = nextStart.coerceAtLeast(0)
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun findBoundary(text: String, start: Int, end: Int): Int {
        // Look back from `end` for the last paragraph break (\n\n) or sentence terminator.
        val windowStart = (end - 120).coerceAtLeast(start)
        // 1) paragraph
        val para = text.lastIndexOf("\n\n", end - 1)
        if (para in windowStart until end) return para + 2
        // 2) sentence (Chinese 。！？ + Latin .!?)
        val sentenceChars = charArrayOf('。', '！', '？', '.', '!', '?', '\n')
        var best = -1
        for (c in sentenceChars) {
            val idx = text.lastIndexOf(c, end - 1)
            if (idx in windowStart until end && idx > best) best = idx
        }
        return if (best > 0) best + 1 else end
    }

    /** Index one chapter: chunk → embed → return list of [KbChunk] ready to persist. */
    suspend fun indexChapter(
        chapterId: String,
        chapterTitle: String,
        text: String,
        cfg: EmbeddingConfig,
    ): List<KbChunk> {
        val pieces = chunkText(text)
        if (pieces.isEmpty()) return emptyList()
        val vectors = embedBatch(pieces, cfg)
        return pieces.mapIndexed { i, t ->
            KbChunk(
                id = "kbc-${chapterId}-$i",
                sourceType = "chapter",
                sourceId = chapterId,
                sourceTitle = chapterTitle,
                chunkIndex = i,
                text = t,
                embeddingModel = cfg.model,
                embedding = vectors[i].toList(),
            )
        }
    }

    /**
     * Retrieve the top [topK] chunks most similar to [query] from [pool] (excluding chunks whose
     * sourceId is in [excludeSourceIds]). Returns the raw chunks in descending similarity order.
     */
    suspend fun retrieveTopK(
        query: String,
        pool: List<KbChunk>,
        topK: Int,
        excludeSourceIds: Set<String>,
        cfg: EmbeddingConfig,
    ): List<KbChunk> {
        if (pool.isEmpty() || query.isBlank()) return emptyList()
        val queryVec = embed(query, cfg)
        val qNorm = norm(queryVec)
        if (qNorm == 0f) return emptyList()
        val ranked = pool.asSequence()
            .filter { it.sourceId !in excludeSourceIds }
            .map { chunk -> chunk to cosine(queryVec, qNorm, chunk.embedding) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
            .toList()
        return ranked
    }

    private fun norm(v: FloatArray): Float {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s).toFloat()
    }

    private fun cosine(a: FloatArray, normA: Float, b: List<Float>): Float {
        if (b.size != a.size) return 0f
        var dot = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val ai = a[i]; val bi = b[i]
            dot += ai * bi
            normB += bi * bi
        }
        val nb = sqrt(normB).toFloat()
        if (nb == 0f) return 0f
        return (dot / (normA * nb)).toFloat()
    }
}

/** Convenience: render a list of retrieved chunks as a single text block for prompt injection. */
fun List<KbChunk>.toPromptContext(language: String): String {
    if (isEmpty()) return ""
    val header = if (language == "en") "[Long-range relevant memory]" else "【长程相关记忆】"
    // Pre-render the per-chunk fragments OUTSIDE the buildString {} receiver so that
    // `forEachIndexed` resolves to List<KbChunk>.forEachIndexed rather than
    // CharSequence.forEachIndexed (which would iterate the StringBuilder's chars and shadow `c`
    // as Char, hiding sourceTitle / chunkIndex / text — that bit us at compile time once).
    val chunks: List<KbChunk> = this
    val rendered = chunks.mapIndexed { _, c ->
        val tag = if (language == "en")
            "—— from chapter \"${c.sourceTitle}\" (chunk ${c.chunkIndex + 1}) ——"
        else
            "—— 摘自章节《${c.sourceTitle}》（片段 ${c.chunkIndex + 1}）——"
        "$tag\n${c.text.take(800)}"
    }
    return buildString {
        appendLine(header)
        rendered.forEachIndexed { i, block ->
            appendLine(block)
            if (i < rendered.size - 1) appendLine()
        }
    }.trim()
}
