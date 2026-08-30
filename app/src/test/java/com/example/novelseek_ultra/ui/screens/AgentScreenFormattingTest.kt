package com.example.novelseek_ultra.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScreenFormattingTest {
    @Test
    fun missingProviderUsageIsNotPresentedAsZeroPercent() {
        val text = sessionCacheStatusText(
            lang = "zh",
            observedRequests = 0,
            hitTokens = 0,
            missTokens = 0,
            hitRate = null,
        )

        assertEquals("会话推理缓存：暂无供应商数据", text)
        assertFalse(text.contains("0%"))
    }

    @Test
    fun observedEmptyUsageHasNoInventedRate() {
        val text = sessionCacheStatusText(
            lang = "en",
            observedRequests = 1,
            hitTokens = 0,
            missTokens = 0,
            hitRate = null,
        )

        assertEquals("Session reasoning cache: no measurable tokens", text)
    }

    @Test
    fun observedUsageShowsWeightedRateAndTokenCounts() {
        val text = sessionCacheStatusText(
            lang = "zh",
            observedRequests = 2,
            hitTokens = 25,
            missTokens = 75,
            hitRate = 0.25,
        )

        assertTrue(text.contains("25.0%"))
        assertTrue(text.contains("25 / 100 tokens"))
    }

    @Test
    fun contextRingSeparatesOutputReserveFromInputOccupancy() {
        val model = contextRingUiModel(
            observable = true,
            capacityTokens = 9_500,
            inputBudgetTokens = 8_000,
            fixedTokens = 2_000,
            historyTokens = 2_000,
            outputReserveTokens = 1_500,
            remainingTokens = 4_000,
            usageRatio = 0.5,
        )

        assertEquals(50, model.usagePercent)
        assertEquals(2_000f / 9_500f, model.fixedFraction, 0.0001f)
        assertEquals(2_000f / 9_500f, model.historyFraction, 0.0001f)
        assertEquals(1_500f / 9_500f, model.outputFraction, 0.0001f)
        assertEquals(
            1f,
            model.fixedFraction + model.historyFraction +
                model.outputFraction + model.remainingFraction,
            0.0001f,
        )
    }

    @Test
    fun outputReserveDoesNotIncreaseOccupiedPercent() {
        val smallReserve = contextRingUiModel(
            observable = true,
            capacityTokens = 8_500,
            inputBudgetTokens = 8_000,
            fixedTokens = 1_000,
            historyTokens = 1_000,
            outputReserveTokens = 500,
            remainingTokens = 6_000,
            usageRatio = 0.25,
        )
        val largeReserve = contextRingUiModel(
            observable = true,
            capacityTokens = 12_000,
            inputBudgetTokens = 8_000,
            fixedTokens = 1_000,
            historyTokens = 1_000,
            outputReserveTokens = 4_000,
            remainingTokens = 6_000,
            usageRatio = 0.25,
        )

        assertEquals(25, smallReserve.usagePercent)
        assertEquals(25, largeReserve.usagePercent)
    }

    @Test
    fun unobservableContextHasNoInventedPercentageOrSegments() {
        val model = contextRingUiModel(
            observable = false,
            capacityTokens = 64_000,
            inputBudgetTokens = 55_000,
            fixedTokens = 0,
            historyTokens = 0,
            outputReserveTokens = 8_000,
            remainingTokens = 55_000,
            usageRatio = null,
        )

        assertNull(model.usagePercent)
        assertEquals(1f, model.remainingFraction)
        assertTrue(contextUsageDescription("zh", model).contains("暂无可用预算数据"))
        assertFalse(contextUsageDescription("zh", model).contains("0%"))
    }
}
