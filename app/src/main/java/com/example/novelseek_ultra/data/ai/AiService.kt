package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val content: String)

/**
 * OkHttp-based replacement for the PC Tauri AI invoke commands. Supports OpenAI-compatible
 * `/chat/completions` endpoints (DeepSeek / OpenAI / OpenRouter / Gemini-OpenAI-compat / custom).
 */
class AiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val sseFactory = EventSources.createFactory(client)

    /** Streamed chat completion: each emission is a delta `String` (a token or token chunk). */
    fun streamChat(
        config: TextModelConfig,
        messages: List<ChatMessage>,
    ): Flow<StreamEvent> = callbackFlow {
        val payload = buildChatPayload(config, messages, stream = true).toString()
        val request = Request.Builder()
            .url("${config.apiUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val source = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.Done)
                    close()
                    return
                }
                val delta = parseSseDelta(data) ?: return
                trySend(StreamEvent.Delta(delta))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message ?: response?.message ?: "unknown SSE error"
                trySend(StreamEvent.Error(msg))
                close(t ?: IOException(msg))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Done)
                close()
            }
        })

        awaitClose { source.cancel() }
    }

    /** Non-streaming chat completion — full text reply. */
    suspend fun chat(config: TextModelConfig, messages: List<ChatMessage>): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val payload = buildChatPayload(config, messages, stream = false).toString()
            val request = Request.Builder()
                .url("${config.apiUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            // Per-call hard deadline — client.callTimeout stays at 0 to keep SSE streaming
            // long-lived, so the bound has to be applied here per-request. Without this the
            // call could hang forever if the provider queues / rate-limits us (Pollinations
            // does this, which is exactly how the "always generating…" bug surfaced).
            call.timeout().timeout(CHAT_ONESHOT_TIMEOUT_SEC, TimeUnit.SECONDS)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            cont.resumeWith(Result.failure(IOException("HTTP ${resp.code}: $body")))
                            return
                        }
                        runCatching {
                            val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                            root["choices"]?.jsonArray?.get(0)?.jsonObject
                                ?.get("message")?.jsonObject
                                ?.get("content")?.jsonPrimitive?.contentOrNull
                                ?: ""
                        }.onSuccess { cont.resumeWith(Result.success(it)) }
                            .onFailure { cont.resumeWith(Result.failure(it)) }
                    }
                }
            })
        }

    /**
     * Pollinations image generation — uses the NEW unified gateway at `gen.pollinations.ai`
     * which:
     *   - defaults to `zimage` (the high-quality model — old anonymous `image.pollinations.ai`
     *     defaulted to `flux` which produced visibly worse output)
     *   - requires `Authorization: Bearer <key>` for proper quotas (sk_/pk_ from
     *     https://auth.pollinations.ai/). Anonymous calls still get a response on this host
     *     but are heavily throttled
     *   - supports `enhance=true` which has the platform rewrite the prompt before generation
     *     — usually noticeably better composition / detail for short prompts
     *
     * Returns raw PNG/JPEG bytes.
     */
    suspend fun generateImage(
        prompt: String,
        width: Int,
        height: Int,
        model: String = "zimage",
        seed: Int? = null,
        nologo: Boolean = true,
        enhance: Boolean = true,
        transparent: Boolean = false,
        safe: Boolean = false,
        pollinationsKey: String? = null,
    ): ByteArray = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val encoded = java.net.URLEncoder.encode(prompt, "UTF-8")
        val params = buildList {
            add("width=$width")
            add("height=$height")
            add("model=$model")
            if (nologo) add("nologo=true")
            if (enhance) add("enhance=true")
            if (transparent) add("transparent=true")
            if (safe) add("safe=true")
            seed?.let { add("seed=$it") }
        }.joinToString("&")
        val url = "https://gen.pollinations.ai/image/$encoded?$params"
        val req = Request.Builder().url(url).apply {
            if (!pollinationsKey.isNullOrBlank()) addHeader("Authorization", "Bearer $pollinationsKey")
        }.build()
        val call = client.newCall(req)
        // Per-call deadline — see chat(): the client-wide callTimeout must stay at 0 so SSE
        // streaming works, but image gen MUST have a hard bound or Pollinations' queue can
        // wedge the request forever. 120s is generous (free tier usually responds in 5-30s).
        call.timeout().timeout(IMAGE_TIMEOUT_SEC, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Translate generic IO/timeout into something the user can actually act on.
                val msg = when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Pollinations 超时（${IMAGE_TIMEOUT_SEC}s）。可能是限流或队列拥堵，请稍后再试。"
                    else -> e.message.orEmpty().ifBlank { "网络异常" }
                }
                cont.resumeWith(Result.failure(IOException(msg, e)))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        // Surface Pollinations' actual body (e.g. "Rate limit exceeded") so the
                        // user sees the real reason instead of just a status code.
                        val body = runCatching { r.body?.string().orEmpty() }.getOrNull().orEmpty()
                        val detail = if (body.isNotBlank()) "：${body.take(200)}" else ""
                        cont.resumeWith(Result.failure(IOException("Pollinations HTTP ${r.code}$detail")))
                        return
                    }
                    val bytes = r.body?.bytes()
                    if (bytes == null) cont.resumeWith(Result.failure(IOException("empty image body")))
                    else cont.resumeWith(Result.success(bytes))
                }
            }
        })
    }

    // ── ComfyUI image generation ──────────────────────────────────────────────────────────
    //
    // Port of the PC `src-tauri/src/api/comfyui.rs` client. Submits the hardcoded z-image-turbo
    // workflow (`t2i-lumicreate.json`) in ComfyUI API/`prompt` format — the LoRA node (48) is
    // skipped, so UNETLoader (46) feeds ModelSamplingAuraFlow (47) directly. Flow:
    //   1. POST /prompt           → returns a prompt_id
    //   2. poll GET /history/{id} → until status.completed, reading SaveImage (node 9) outputs
    //   3. GET /view              → download the PNG bytes
    // ComfyUI runs on the local network with no auth, so there's no API key here — only a base URL
    // (default http://localhost:8188, configurable in Settings for a LAN host like 192.168.x.x).

    /** Health check — ComfyUI exposes GET /system_stats. */
    suspend fun testComfyUIConnection(baseUrl: String): Boolean = try {
        val url = "${baseUrl.trimEnd('/')}/system_stats"
        val req = Request.Builder().url(url).get().build()
        executeForString(req, COMFY_REQUEST_TIMEOUT_SEC).let { true }
    } catch (_: Throwable) {
        false
    }

    /**
     * Generate an image via ComfyUI and return the raw PNG/JPEG bytes (parity with
     * [generateImage]'s return type so callers can stay engine-agnostic).
     */
    suspend fun generateImageComfyUI(
        prompt: String,
        width: Int,
        height: Int,
        baseUrl: String,
        negativePrompt: String = COMFY_DEFAULT_NEGATIVE,
    ): ByteArray {
        val base = baseUrl.trimEnd('/')
        val promptId = submitComfyPrompt(base, prompt, negativePrompt, width, height)
        val image = pollComfyHistory(base, promptId)
        return downloadComfyImage(base, image)
    }

    private data class ComfyImageRef(val filename: String, val subfolder: String, val type: String)

    /** Build the ComfyUI API-format prompt. LoRA node (48) omitted — 46 → 47 directly. */
    private fun buildComfyPrompt(
        positive: String,
        negative: String,
        width: Int,
        height: Int,
    ): JsonObject = buildJsonObject {
        // Step 1 – Load models
        put("46", buildJsonObject {
            put("class_type", "UNETLoader")
            put("inputs", buildJsonObject {
                put("unet_name", "z_image_turbo_bf16.safetensors")
                put("weight_dtype", "default")
            })
        })
        put("39", buildJsonObject {
            put("class_type", "CLIPLoader")
            put("inputs", buildJsonObject {
                put("clip_name", "qwen_3_4b.safetensors")
                put("type", "lumina2")
                put("device", "default")
            })
        })
        put("40", buildJsonObject {
            put("class_type", "VAELoader")
            put("inputs", buildJsonObject { put("vae_name", "ae.safetensors") })
        })
        // Step 2 – Sampling config (shift for AuraFlow-style scheduling)
        put("47", buildJsonObject {
            put("class_type", "ModelSamplingAuraFlow")
            put("inputs", buildJsonObject {
                put("model", nodeRef("46", 0))
                put("shift", 3.0)
            })
        })
        // Step 3 – Prompts
        put("45", buildJsonObject {
            put("class_type", "CLIPTextEncode")
            put("inputs", buildJsonObject {
                put("clip", nodeRef("39", 0))
                put("text", positive)
            })
        })
        put("61", buildJsonObject {
            put("class_type", "CLIPTextEncode")
            put("inputs", buildJsonObject {
                put("clip", nodeRef("39", 0))
                put("text", negative)
            })
        })
        // Step 4 – Latent canvas
        put("41", buildJsonObject {
            put("class_type", "EmptySD3LatentImage")
            put("inputs", buildJsonObject {
                put("width", width)
                put("height", height)
                put("batch_size", 1)
            })
        })
        // KSampler
        put("44", buildJsonObject {
            put("class_type", "KSampler")
            put("inputs", buildJsonObject {
                put("model", nodeRef("47", 0))
                put("positive", nodeRef("45", 0))
                put("negative", nodeRef("61", 0))
                put("latent_image", nodeRef("41", 0))
                put("seed", kotlin.random.Random.nextLong(0, Long.MAX_VALUE))
                put("steps", 6)
                put("cfg", 1.0)
                put("sampler_name", "dpmpp_2m_sde_gpu")
                put("scheduler", "simple")
                put("denoise", 1.0)
            })
        })
        // Decode + Save
        put("43", buildJsonObject {
            put("class_type", "VAEDecode")
            put("inputs", buildJsonObject {
                put("samples", nodeRef("44", 0))
                put("vae", nodeRef("40", 0))
            })
        })
        put("9", buildJsonObject {
            put("class_type", "SaveImage")
            put("inputs", buildJsonObject {
                put("images", nodeRef("43", 0))
                put("filename_prefix", "novelseek")
            })
        })
    }

    private fun nodeRef(nodeId: String, slot: Int): JsonArray =
        JsonArray(listOf(JsonPrimitive(nodeId), JsonPrimitive(slot)))

    /** POST /prompt → prompt_id (surfaces ComfyUI's node-validation error if the submit fails). */
    private suspend fun submitComfyPrompt(
        base: String,
        positive: String,
        negative: String,
        width: Int,
        height: Int,
    ): String {
        val payload = buildJsonObject {
            put("prompt", buildComfyPrompt(positive, negative, width, height))
        }.toString()
        val req = Request.Builder()
            .url("$base/prompt")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = executeForString(req, COMFY_REQUEST_TIMEOUT_SEC, friendlyComfyError = true)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        return root["prompt_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("ComfyUI 未返回 prompt_id：${body.take(200)}")
    }

    /** Poll GET /history/{id} until the job completes; returns the first SaveImage output. */
    private suspend fun pollComfyHistory(base: String, promptId: String): ComfyImageRef {
        val deadline = System.currentTimeMillis() + COMFY_JOB_TIMEOUT_SEC * 1000
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw IOException("ComfyUI 任务超时（${COMFY_JOB_TIMEOUT_SEC / 60} 分钟），请检查 ComfyUI 是否在生成。")
            }
            val req = Request.Builder().url("$base/history/$promptId").get().build()
            val body = executeForString(req, COMFY_REQUEST_TIMEOUT_SEC)
            val history = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            val entry = history[promptId]?.jsonObject
            if (entry != null) {
                val status = entry["status"]?.jsonObject
                val completed = status?.get("completed")?.jsonPrimitive?.contentOrNull == "true"
                if (completed) {
                    val statusStr = status?.get("status_str")?.jsonPrimitive?.contentOrNull ?: "success"
                    if (statusStr == "error") {
                        throw IOException(extractComfyExecError(status) ?: "ComfyUI 任务执行失败")
                    }
                    val images = entry["outputs"]?.jsonObject
                        ?.get("9")?.jsonObject
                        ?.get("images")?.jsonArray
                        ?.mapNotNull { it.jsonObject.toComfyImageRef() }
                        .orEmpty()
                    if (images.isEmpty()) {
                        throw IOException("ComfyUI 任务完成但无输出图片，请检查 SaveImage 节点（id=9）")
                    }
                    return images.first()
                }
            }
            kotlinx.coroutines.delay(COMFY_POLL_INTERVAL_MS)
        }
    }

    private fun JsonObject.toComfyImageRef(): ComfyImageRef? {
        val filename = this["filename"]?.jsonPrimitive?.contentOrNull ?: return null
        val subfolder = this["subfolder"]?.jsonPrimitive?.contentOrNull ?: ""
        val type = this["type"]?.jsonPrimitive?.contentOrNull ?: "output"
        return ComfyImageRef(filename, subfolder, type)
    }

    /** Pull the `execution_error` exception message out of the history `status.messages` array. */
    private fun extractComfyExecError(status: JsonObject?): String? {
        val messages = status?.get("messages")?.jsonArray ?: return null
        for (msg in messages) {
            val arr = (msg as? JsonArray) ?: continue
            if (arr.firstOrNull()?.jsonPrimitive?.contentOrNull != "execution_error") continue
            val details = arr.getOrNull(1)?.jsonObject ?: continue
            val nodeType = details["node_type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val exc = details["exception_message"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
            return "节点 $nodeType 执行失败：$exc"
        }
        return null
    }

    /** GET /view → image bytes. Retries to ride out stale keep-alive connections. */
    private suspend fun downloadComfyImage(base: String, img: ComfyImageRef): ByteArray {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val url = "$base/view?filename=${enc(img.filename)}&subfolder=${enc(img.subfolder)}&type=${enc(img.type)}"
        val req = Request.Builder().url(url).get().build()
        var lastErr: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(800L * attempt)
            try {
                return executeForBytes(req, COMFY_REQUEST_TIMEOUT_SEC)
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        throw IOException("ComfyUI /view 下载失败：${lastErr?.message}", lastErr)
    }

    /** Run a request with a per-call deadline and return the body as a String (throws on non-2xx). */
    private suspend fun executeForString(
        req: Request,
        timeoutSec: Long,
        friendlyComfyError: Boolean = false,
    ): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                    if (!r.isSuccessful) {
                        val msg = if (friendlyComfyError) comfyValidationError(body)
                            else "ComfyUI HTTP ${r.code}：${body.take(200)}"
                        cont.resumeWith(Result.failure(IOException(msg)))
                    } else {
                        cont.resumeWith(Result.success(body))
                    }
                }
            }
        })
    }

    private suspend fun executeForBytes(
        req: Request,
        timeoutSec: Long,
    ): ByteArray = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        cont.resumeWith(Result.failure(IOException("ComfyUI HTTP ${r.code}")))
                        return
                    }
                    val bytes = r.body?.bytes()
                    if (bytes == null) cont.resumeWith(Result.failure(IOException("empty image body")))
                    else cont.resumeWith(Result.success(bytes))
                }
            }
        })
    }

    /** Turn ComfyUI's node-validation JSON into a readable message; fall back to the raw body. */
    private fun comfyValidationError(body: String): String {
        val parsed = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        }.getOrNull() ?: return "ComfyUI /prompt 错误：${body.take(200)}"
        val mainMsg = parsed["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        val nodeDetail = parsed["node_errors"]?.jsonObject?.values?.firstOrNull()
            ?.jsonObject?.get("errors")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("details")?.jsonPrimitive?.contentOrNull
        return when {
            mainMsg.isNullOrBlank() -> "ComfyUI /prompt 错误：${body.take(200)}"
            nodeDetail.isNullOrBlank() -> "ComfyUI 节点验证失败：$mainMsg"
            else -> "ComfyUI 节点验证失败：$mainMsg（$nodeDetail）"
        }
    }

    /** Verify model credentials by issuing a tiny `models` GET or 1-token completion. */
    suspend fun testConnection(config: TextModelConfig): Boolean = try {
        chat(
            config = config.copy(temperature = 0.0),
            messages = listOf(ChatMessage("user", "ping")),
        ).isNotEmpty()
    } catch (_: Throwable) {
        false
    }

    private fun buildChatPayload(
        config: TextModelConfig,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("temperature", config.temperature)
        put("stream", stream)
        put("messages", JsonArray(messages.map {
            buildJsonObject {
                put("role", it.role)
                put("content", it.content)
            }
        }))
    }

    private fun parseSseDelta(data: String): String? = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(data).jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
        choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    sealed class StreamEvent {
        data class Delta(val text: String) : StreamEvent()
        object Done : StreamEvent()
        data class Error(val message: String) : StreamEvent()
    }

    private companion object {
        // Per-call hard deadlines applied via `call.timeout()` (NOT client.callTimeout — that
        // would also kill long-lived SSE streams). Generous enough for slow providers, short
        // enough that a hung request surfaces an error instead of an infinite spinner.
        const val CHAT_ONESHOT_TIMEOUT_SEC = 120L
        const val IMAGE_TIMEOUT_SEC = 120L
        const val EMBED_TIMEOUT_SEC = 60L

        // ComfyUI: each individual HTTP call (submit / poll / view) gets this bound; the overall
        // job can take much longer, so it's gated by COMFY_JOB_TIMEOUT_SEC + a 1.5s poll interval.
        const val COMFY_REQUEST_TIMEOUT_SEC = 60L
        const val COMFY_JOB_TIMEOUT_SEC = 300L
        const val COMFY_POLL_INTERVAL_MS = 1500L
        const val COMFY_DEFAULT_NEGATIVE =
            "low quality, worst quality, deformed, mutated hands, mutated fingers, " +
                "extra limbs, missing arms, signature, watermark, username, logo"
    }
}
