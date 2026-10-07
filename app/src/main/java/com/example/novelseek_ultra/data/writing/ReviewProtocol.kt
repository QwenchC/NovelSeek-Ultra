package com.example.novelseek_ultra.data.writing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Review is advisory; a blocking finding must quote prose and name an explicit scene constraint. */
object ReviewProtocol {
    private const val MAX_FINDINGS = 48
    private const val MAX_RAW_CHARS = 96_000
    private val hardCategories = setOf(ReviewCategory.FACT_CONFLICT, ReviewCategory.KNOWLEDGE_LEAK,
        ReviewCategory.MISSING_EVENT)

    fun parse(raw: String, plan: ChapterScenePlan, completed: List<CompletedScene>): List<ReviewFinding> {
        require(raw.length <= MAX_RAW_CHARS) { "审稿结果过长" }
        val root = ScenePlanProtocol.json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("审稿结果必须是 JSON 对象")
        require(root.keys == setOf("findings")) { "审稿结果只能包含 findings 字段" }
        val findings = root["findings"] as? JsonArray
            ?: throw IllegalArgumentException("findings 必须是数组")
        require(findings.size <= MAX_FINDINGS) { "审稿意见数量超出限制" }
        return findings.map { element ->
            val item = element as? JsonObject ?: throw IllegalArgumentException("每项审稿意见必须是对象")
            require(item.keys == setOf("sceneId", "category", "severity", "quote", "startOffset",
                "endOffset", "constraint", "explanation", "suggestion")) { "审稿字段缺失或包含额外字段" }
            listOf("sceneId", "category", "severity", "quote", "constraint", "explanation", "suggestion")
                .forEach { key -> require((item[key] as? JsonPrimitive)?.isString == true) { "$key 必须是字符串" } }
            listOf("startOffset", "endOffset").forEach { key ->
                val primitive = item[key] as? JsonPrimitive
                require(primitive != null && !primitive.isString && primitive.intOrNull != null) { "$key 必须是整数" }
            }
            ScenePlanProtocol.json.decodeFromJsonElement(ReviewFinding.serializer(), item)
        }
            .also { validate(it, plan, completed) }
    }

    fun validate(findings: List<ReviewFinding>, plan: ChapterScenePlan, completed: List<CompletedScene>) {
        require(findings.size <= MAX_FINDINGS) { "审稿意见数量超出限制" }
        val specs = plan.scenes.associateBy { it.id }
        val bodies = completed.associateBy { it.sceneId }
        findings.forEach { finding ->
            val spec = specs[finding.sceneId] ?: throw IllegalArgumentException("审稿引用未知场景")
            val body = bodies[finding.sceneId]?.body ?: throw IllegalArgumentException("审稿引用未完成场景")
            require(finding.quote.isNotBlank() && finding.quote.length <= 2_000) { "审稿证据不能为空或过长" }
            require(finding.startOffset >= 0 && finding.endOffset > finding.startOffset && finding.endOffset <= body.length) {
                "审稿证据位置无效"
            }
            require(isBoundary(body, finding.startOffset) && isBoundary(body, finding.endOffset)) { "审稿位置切断 Unicode 字符" }
            require(body.substring(finding.startOffset, finding.endOffset) == finding.quote) { "审稿证据与正文不匹配" }
            require(finding.explanation.isNotBlank() && finding.suggestion.isNotBlank() &&
                finding.explanation.length <= 4_000 && finding.suggestion.length <= 4_000) {
                "审稿说明与建议为空或过长"
            }
            if (finding.category !in hardCategories) {
                require(finding.severity == ReviewSeverity.SUGGESTION) { "文学偏好只能作为建议，不能阻断采用" }
            }
            if (finding.severity == ReviewSeverity.BLOCKING) {
                val constraints = spec.requiredEvents + spec.forbiddenEvents + listOf(spec.entryState, spec.exitState)
                require(finding.constraint.isNotBlank() && finding.constraint in constraints) {
                    "阻断意见必须引用该场景明确的事实约束"
                }
            }
        }
    }

    private fun isBoundary(text: String, offset: Int): Boolean = offset !in 1 until text.length ||
        !Character.isHighSurrogate(text[offset - 1]) || !Character.isLowSurrogate(text[offset])
}
