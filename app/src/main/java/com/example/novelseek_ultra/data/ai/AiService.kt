package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    }
}
