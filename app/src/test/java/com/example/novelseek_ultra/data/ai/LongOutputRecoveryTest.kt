package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongOutputRecoveryTest {
    private val config = TextModelConfig(
        provider = "custom",
        contextWindowTokens = 4_096,
        maxOutputTokens = 1_024,
    )
    private val initial = listOf(
        ChatMessage("system", "Write a document."),
        ChatMessage("user", "Start now."),
    )

    @Test
    fun outputLimitContinuesAndRemovesExactRepeatedTail() = runBlocking {
        val requests = mutableListOf<List<ChatMessage>>()
        val previews = mutableListOf<String>()

        val result = LongOutputRecovery.collect(
            config = config,
            initialMessages = initial,
            format = LongOutputFormat.MARKDOWN,
            taskLabel = "test document",
            language = "en",
            request = { messages ->
                requests += messages
                if (requests.size == 1) {
                    flowOf(
                        AiService.StreamEvent.Delta("abcdefghijklmnop"),
                        AiService.StreamEvent.Error(
                            "limit",
                            AiService.StreamFailureKind.OUTPUT_LIMIT,
                        ),
                    )
                } else {
                    flowOf(
                        AiService.StreamEvent.Delta("efghijklmnopqrstuvwxyz"),
                        AiService.StreamEvent.Done,
                    )
                }
            },
            onCumulative = { previews += it },
        )

        assertEquals("abcdefghijklmnopqrstuvwxyz", result)
        assertEquals(2, requests.size)
        assertEquals(initial, requests[1].take(initial.size))
        assertEquals("abcdefghijklmnopqrstuvwxyz", previews.last())
    }

    @Test
    fun nonLimitFailureIsNotRetried() = runBlocking {
        var requests = 0
        val failure = runCatching {
            LongOutputRecovery.collect(
                config = config,
                initialMessages = initial,
                format = LongOutputFormat.MARKDOWN,
                taskLabel = "test document",
                language = "en",
                request = {
                    requests++
                    flowOf(AiService.StreamEvent.Error("network"))
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is AiStreamCompletionException)
        assertEquals(1, requests)
    }

    @Test
    fun repeatedOutputLimitsStopAtBoundWithActionableFailure() = runBlocking {
        var requests = 0
        val failure = runCatching {
            LongOutputRecovery.collect(
                config = config,
                initialMessages = initial,
                format = LongOutputFormat.JSON_OBJECT,
                taskLabel = "角色导入第 1/2 批",
                language = "zh",
                maxRequests = 2,
                request = {
                    requests++
                    flowOf(
                        AiService.StreamEvent.Delta(if (requests == 1) "{\"characters\":[" else "{"),
                        AiService.StreamEvent.Error(
                            "limit",
                            AiService.StreamFailureKind.OUTPUT_LIMIT,
                        ),
                    )
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is LongOutputRecovery.ExhaustedException)
        assertTrue(failure?.message.orEmpty().contains("已取消保存"))
        assertTrue(failure?.message.orEmpty().contains("最大输出 Tokens"))
        assertEquals(2, requests)
    }

    @Test
    fun tinyDeltasUseBoundedPreviewUpdatesButStillPublishExactFinalText() = runBlocking {
        val previews = mutableListOf<String>()
        val expected = "字".repeat(1_000)

        val result = LongOutputRecovery.collect(
            config = config,
            initialMessages = initial,
            format = LongOutputFormat.MARKDOWN,
            taskLabel = "test document",
            language = "en",
            request = {
                flow {
                    expected.forEach { emit(AiService.StreamEvent.Delta(it.toString())) }
                    emit(AiService.StreamEvent.Done)
                }
            },
            onCumulative = { previews += it },
        )

        assertEquals(expected, result)
        assertEquals(expected, previews.last())
        assertTrue(previews.size < 20)
    }

    @Test
    fun continuationFailsWhenBudgetCannotRetainEnoughCutoffContext() {
        val constrained = TextModelConfig(
            provider = "custom",
            contextWindowTokens = 4_096,
            maxOutputTokens = 2_048,
        )
        val base = listOf(ChatMessage("system", "x".repeat(5_500)))
        PromptRequestBudgeter.validate(constrained, base)

        val failure = runCatching {
            LongOutputRecovery.continuationMessages(
                config = constrained,
                initialMessages = base,
                accumulated = "续".repeat(1_000),
                format = LongOutputFormat.MARKDOWN,
                language = "zh",
            )
        }.exceptionOrNull()

        assertTrue(failure is PromptRequestBudgeter.BudgetExceededException)
        assertTrue(failure?.message.orEmpty().contains("截断点上下文"))
        assertTrue(failure?.message.orEmpty().contains("已取消保存"))
    }

    @Test
    fun continuationNeverDropsOriginalUserTaskToMakeRoom() {
        val constrained = TextModelConfig(
            provider = "custom",
            contextWindowTokens = 4_096,
            maxOutputTokens = 2_048,
        )
        val base = listOf(
            ChatMessage("system", "Return the requested document."),
            ChatMessage("user", "u".repeat(5_500)),
        )
        PromptRequestBudgeter.validate(constrained, base)

        val failure = runCatching {
            LongOutputRecovery.continuationMessages(
                config = constrained,
                initialMessages = base,
                accumulated = "续".repeat(1_000),
                format = LongOutputFormat.JSON_OBJECT,
                language = "zh",
            )
        }.exceptionOrNull()

        assertTrue(failure is PromptRequestBudgeter.BudgetExceededException)
        assertTrue(failure?.message.orEmpty().contains("已取消保存"))
    }
}
