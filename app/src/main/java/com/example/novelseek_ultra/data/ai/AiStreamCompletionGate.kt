package com.example.novelseek_ultra.data.ai

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** A streamed result is usable only after the producer emits an explicit [AiService.StreamEvent.Done]. */
internal class AiStreamCompletionException(
    message: String,
    val kind: AiService.StreamFailureKind = AiService.StreamFailureKind.OTHER,
) : IOException(message)

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
                if (done) failCompletion(
                    "流式响应在 Done 后仍返回了内容",
                    AiService.StreamFailureKind.OTHER,
                    onFailure,
                )
                onDelta(event.text)
            }
            AiService.StreamEvent.Done -> {
                if (done) failCompletion(
                    "流式响应重复返回 Done",
                    AiService.StreamFailureKind.OTHER,
                    onFailure,
                )
                done = true
            }
            is AiService.StreamEvent.Error -> failCompletion(event.message, event.kind, onFailure)
        }
    }
    currentCoroutineContext().ensureActive()
    if (!done) {
        failCompletion(
            "流式响应结束但未收到 Done",
            AiService.StreamFailureKind.OTHER,
            onFailure,
        )
    }
}

private suspend fun failCompletion(
    message: String,
    kind: AiService.StreamFailureKind,
    onFailure: suspend (String) -> Unit,
): Nothing {
    onFailure(message)
    throw AiStreamCompletionException(message, kind)
}
