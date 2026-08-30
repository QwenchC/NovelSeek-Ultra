package com.example.novelseek_ultra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSessionCacheMetricsTest {
    @Test
    fun `missing or incomplete provider cache usage remains unknown`() {
        val empty = AgentSessionCacheMetrics()

        assertEquals(empty, empty.record(cacheHitTokens = 100, cacheMissTokens = null))
        assertEquals(empty, empty.record(cacheHitTokens = null, cacheMissTokens = 100))
        assertEquals(empty, empty.record(cacheHitTokens = -1, cacheMissTokens = 100))
        assertNull(empty.hitRate())
    }

    @Test
    fun `complete observations accumulate into a weighted zero-to-one hit rate`() {
        val metrics = AgentSessionCacheMetrics()
            .record(cacheHitTokens = 90, cacheMissTokens = 10)
            .record(cacheHitTokens = 30, cacheMissTokens = 70)

        assertEquals(2L, metrics.observedRequests)
        assertEquals(120L, metrics.hitTokens)
        assertEquals(80L, metrics.missTokens)
        assertEquals(0.6, metrics.hitRate()!!, 0.0001)
    }

    @Test
    fun `zero-token observation has no synthetic percentage`() {
        val metrics = AgentSessionCacheMetrics().record(0, 0)

        assertEquals(1L, metrics.observedRequests)
        assertNull(metrics.hitRate())
    }

    @Test
    fun `durable counters saturate and imported negative values sanitize`() {
        val saturated = AgentSessionCacheMetrics(
            observedRequests = Long.MAX_VALUE,
            hitTokens = Long.MAX_VALUE - 5,
            missTokens = Long.MAX_VALUE - 5,
        ).record(10, 10)

        assertEquals(Long.MAX_VALUE, saturated.observedRequests)
        assertEquals(Long.MAX_VALUE, saturated.hitTokens)
        assertEquals(Long.MAX_VALUE, saturated.missTokens)
        assertEquals(0.5, saturated.hitRate()!!, 0.0001)
        assertEquals(
            AgentSessionCacheMetrics(),
            AgentSessionCacheMetrics(-1, -2, -3).sanitized(),
        )
    }
}
