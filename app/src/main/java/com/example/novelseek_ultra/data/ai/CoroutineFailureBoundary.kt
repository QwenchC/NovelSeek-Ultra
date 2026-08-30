package com.example.novelseek_ultra.data.ai

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches a standalone UI/background task with a local exception boundary.
 *
 * A regular root [launch] reports an unhandled exception to Android even when another coroutine
 * observes its [Job] completion. When used for a direct child of a supervisor-style app scope
 * such as viewModelScope, this helper keeps the original failure as the Job's completion cause
 * (so callback bridges can report it) while preventing it from reaching the process-wide
 * uncaught-exception handler. Cancellation remains normal coroutine control flow.
 */
internal fun CoroutineScope.launchWithFailureBoundary(
    context: CoroutineContext = EmptyCoroutineContext,
    start: CoroutineStart = CoroutineStart.DEFAULT,
    onFailure: (Throwable) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val boundary = CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) {
            // An exception handler must never become a second process-level failure.
            runCatching { onFailure(error) }
        }
    }
    return launch(context + boundary, start = start, block = block)
}
