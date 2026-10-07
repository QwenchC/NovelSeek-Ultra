package com.example.novelseek_ultra.data.ai

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStreamCompletionGateTest {

    @Test
    fun explicitDoneAllowsCallerToCommitCompleteText() = runBlocking {
        val text = StringBuilder()
        var committed = false

        flowOf(
            AiService.StreamEvent.Delta("完整"),
            AiService.StreamEvent.Delta("正文"),
            AiService.StreamEvent.Done,
        ).collectCompleted(onDelta = { text.append(it) })
        committed = true

        assertEquals("完整正文", text.toString())
        assertTrue(committed)
    }

    @Test
    fun errorFailsImmediatelyAndCannotReachCommit() = runBlocking {
        val text = StringBuilder()
        val reported = mutableListOf<String>()
        var emittedAfterError = false
        var committed = false

        val failure = runCatching {
            flow {
                emit(AiService.StreamEvent.Delta("半章"))
                emit(AiService.StreamEvent.Error("供应商失败"))
                emittedAfterError = true
                emit(AiService.StreamEvent.Done)
            }.collectCompleted(
                onDelta = { text.append(it) },
                onFailure = { reported += it },
            )
            committed = true
        }.exceptionOrNull()

        assertTrue(failure is AiStreamCompletionException)
        assertEquals("供应商失败", failure?.message)
        assertEquals("半章", text.toString())
        assertEquals(listOf("供应商失败"), reported)
        assertFalse(emittedAfterError)
        assertFalse(committed)
    }

    @Test
    fun outputLimitFailureKeepsMachineReadableKind() = runBlocking {
        val failure = runCatching {
            flowOf(
                AiService.StreamEvent.Delta("partial"),
                AiService.StreamEvent.Error(
                    "达到输出上限",
                    AiService.StreamFailureKind.OUTPUT_LIMIT,
                ),
            ).collectCompleted(onDelta = {})
        }.exceptionOrNull()

        assertTrue(failure is AiStreamCompletionException)
        assertEquals(
            AiService.StreamFailureKind.OUTPUT_LIMIT,
            (failure as AiStreamCompletionException).kind,
        )
    }

    @Test
    fun normalEofWithoutDoneRejectsPartialText() = runBlocking {
        val text = StringBuilder()
        val reported = mutableListOf<String>()
        var committed = false

        val failure = runCatching {
            flowOf(AiService.StreamEvent.Delta("未完成正文")).collectCompleted(
                onDelta = { text.append(it) },
                onFailure = { reported += it },
            )
            committed = true
        }.exceptionOrNull()

        assertTrue(failure is AiStreamCompletionException)
        assertTrue(failure?.message.orEmpty().contains("未收到 Done"))
        assertEquals("未完成正文", text.toString())
        assertEquals(listOf("流式响应结束但未收到 Done"), reported)
        assertFalse(committed)
    }

    @Test
    fun cancellationPropagatesWithoutBeingConvertedOrCommitted() = runBlocking {
        val cancellation = CancellationException("用户停止")
        val text = StringBuilder()
        var failureReported = false
        var committed = false

        val failure = runCatching {
            flow {
                emit(AiService.StreamEvent.Delta("取消前片段"))
                throw cancellation
            }.collectCompleted(
                onDelta = { text.append(it) },
                onFailure = { failureReported = true },
            )
            committed = true
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals("取消前片段", text.toString())
        assertFalse(failureReported)
        assertFalse(committed)
    }
}
