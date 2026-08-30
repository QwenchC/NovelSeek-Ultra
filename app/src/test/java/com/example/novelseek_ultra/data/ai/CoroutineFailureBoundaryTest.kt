package com.example.novelseek_ultra.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineFailureBoundaryTest {
    @Test
    fun `standalone launch reports failure locally and preserves job cause`() = runBlocking {
        val expected = IOException("finish_reason=length")
        val reported = CompletableDeferred<Throwable>()
        val completed = CompletableDeferred<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launchWithFailureBoundary(
            start = CoroutineStart.LAZY,
            onFailure = { reported.complete(it) },
        ) {
            throw expected
        }
        job.invokeOnCompletion { completed.complete(it) }
        job.start()
        job.join()

        assertSame(expected, reported.await())
        assertSame(expected, completed.await())
        assertTrue(job.isCancelled)
        scope.cancel()
    }

    @Test
    fun `cancellation stays cancellation and is not reported as failure`() = runBlocking {
        val cancellation = CancellationException("用户停止")
        var failureReported = false
        val completed = CompletableDeferred<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launchWithFailureBoundary(
            onFailure = { failureReported = true },
        ) {
            throw cancellation
        }
        job.invokeOnCompletion { completed.complete(it) }
        job.join()

        assertSame(cancellation, completed.await())
        assertFalse(failureReported)
        assertTrue(job.isCancelled)
        scope.cancel()
    }

    @Test
    fun `truncated stream is rejected but contained by standalone launch boundary`() = runBlocking {
        val gateFailures = mutableListOf<String>()
        val boundaryFailure = CompletableDeferred<Throwable>()
        val completionCause = CompletableDeferred<Throwable?>()
        val text = StringBuilder()
        var committed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launchWithFailureBoundary(
            onFailure = { boundaryFailure.complete(it) },
        ) {
            flowOf(
                AiService.StreamEvent.Delta("不完整角色 JSON"),
                AiService.StreamEvent.Error("生成内容被截断（finish_reason=length）"),
            ).collectCompleted(
                onDelta = { text.append(it) },
                onFailure = { gateFailures += it },
            )
            committed = true
        }
        job.invokeOnCompletion { completionCause.complete(it) }
        job.join()

        val reported = boundaryFailure.await()
        assertTrue(reported is AiStreamCompletionException)
        assertSame(reported, completionCause.await())
        assertEquals("不完整角色 JSON", text.toString())
        assertEquals(listOf("生成内容被截断（finish_reason=length）"), gateFailures)
        assertFalse(committed)
        scope.cancel()
    }
}
