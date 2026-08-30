package com.example.novelseek_ultra.data.ai

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** A streamed result is usable only after the producer emits an explicit [AiService.StreamEvent.Done]. */
internal class AiStreamCompletionException(message: String) : IOException(message)

/**
 * Collects streamed deltas while enforcing the terminal-event contract.
 *
 * [AiService.StreamEvent.Error] fails immediately, a normal EOF without
 * [AiService.StreamEvent.Done] is rejected, and coroutine cancellation is deliberately not caught.
 */
internal suspend fun Flow<AiService.StreamEvent>.collectCompleted(
    onDelta: suspend (String) -> Unit,
    onFailure: suspend (String) -> Unit = {},
) {
    var done = false
    collect { event ->
        currentCoroutineContext().ensureActive()
        when (event) {
            is AiService.StreamEvent.Delta -> {
                if (done) failCompletion("流式响应在 Done 后仍返回了内容", onFailure)
                onDelta(event.text)
            }
            AiService.StreamEvent.Done -> {
                if (done) failCompletion("流式响应重复返回 Done", onFailure)
                done = true
            }
            is AiService.StreamEvent.Error -> failCompletion(event.message, onFailure)
        }
    }
    currentCoroutineContext().ensureActive()
    if (!done) {
        failCompletion("流式响应结束但未收到 Done", onFailure)
    }
}

private suspend fun failCompletion(
    message: String,
    onFailure: suspend (String) -> Unit,
): Nothing {
    onFailure(message)
    throw AiStreamCompletionException(message)
}
