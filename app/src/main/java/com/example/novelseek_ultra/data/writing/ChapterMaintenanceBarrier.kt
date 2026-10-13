package com.example.novelseek_ultra.data.writing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for all chapter-derived writes, including cancellation cleanup, before another capture. */
internal object ChapterMaintenanceBarrier {
    suspend fun complete(
        timeoutMillis: Long,
        start: (CoroutineScope) -> List<Job>,
    ): Boolean {
        require(timeoutMillis > 0) { "Chapter maintenance timeout must be positive" }
        return withTimeoutOrNull(timeoutMillis) {
            // The factory must launch work in this supplied scope, not a process/view-model scope.
            // Scope completion also waits for any nested or unlisted child jobs.
            coroutineScope { start(this).joinAll() }
            true
        } ?: false
    }
}
