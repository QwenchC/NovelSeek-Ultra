package com.example.novelseek_ultra.agent

/** Pure request-boundary policy so automatic compaction remains bounded and directly testable. */
internal object AgentContextCompressionPolicy {
    const val SAFE_USAGE_RATIO = 0.82
    const val MAX_AUTOMATIC_ATTEMPTS = 1

    fun shouldAttempt(
        prepared: AgentTranscriptRequestBudgeter.PreparedRequest,
        attempts: Int,
    ): Boolean = attempts < MAX_AUTOMATIC_ATTEMPTS && (
        prepared.transcript.historyTruncated ||
            prepared.transcript.contentTruncated ||
            prepared.measurement.exceedsInputBudget ||
            prepared.measurement.usageRatio >= SAFE_USAGE_RATIO
        )
}
