package com.example.novelseek_ultra.data.writing

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePlanProtocolTest {
    @Test
    fun parsesOnlyCompleteSceneWrapperAndPreservesConstraints() {
        val scene = SceneSpec("s1", "药铺", pov = "林澈", goal = "寻找线索", entryState = "不知道凶手",
            requiredEvents = listOf("发现药方"), forbiddenEvents = listOf("说出凶手身份"))
        val plan = parse(wrapper(listOf(scene)))
        assertEquals(scene, plan.scenes.single())
        assertEquals("source", plan.sourceFingerprint)
    }

    @Test
    fun rejectsProseFencesPartialRootArrayAndUnknownFields() {
        val valid = wrapper(listOf(scene()))
        listOf("说明：$valid", "```json\n$valid\n```", valid.dropLast(1), "[]",
            valid.replace("\"id\":", "\"extra\":1,\"id\":"),
            valid.replace("\"targetWords\":1500", "\"targetWords\":\"1500\""))
            .forEach { assertTrue(it, runCatching { parse(it) }.isFailure) }
    }

    @Test
    fun rejectsEmptyDuplicateOverlargeAndContradictoryPlans() {
        val invalid = listOf(emptyList(), listOf(scene(), scene()),
            (1..13).map { scene().copy(id = "s$it") },
            listOf(scene().copy(targetWords = 99)),
            listOf(scene().copy(requiredEvents = listOf("揭秘"), forbiddenEvents = listOf("揭秘"))),
            (1..4).map { scene().copy(id = "s$it", targetWords = 8_000) })
        invalid.forEach { assertTrue(runCatching { parse(wrapper(it)) }.isFailure) }
        assertTrue(runCatching { ScenePlanProtocol.parse(wrapper(listOf(scene())), "p", "c", "s", 1, 4) }.isFailure)
    }

    @Test
    fun reviewRequiresExactLocatedEvidenceAndCannotBlockForLiteraryPreference() {
        val plan = parse(wrapper(listOf(scene().copy(forbiddenEvents = listOf("知道凶手身份")))))
        val body = "他知道凶手身份。"
        val completed = listOf(CompletedScene("s1", body, "", 1))
        val finding = ReviewFinding("s1", ReviewCategory.KNOWLEDGE_LEAK, ReviewSeverity.BLOCKING,
            "知道凶手身份", 1, 7, "知道凶手身份", "角色提前获知", "保留疑问")
        val raw = "{\"findings\":${ScenePlanProtocol.json.encodeToString(ListSerializer(ReviewFinding.serializer()), listOf(finding))}}"
        assertEquals(finding, ReviewProtocol.parse(raw, plan, completed).single())
        listOf(finding.copy(quote = "不在原文"), finding.copy(startOffset = 0),
            finding.copy(constraint = "模型随意发明约束"),
            finding.copy(category = ReviewCategory.STYLE), finding.copy(category = ReviewCategory.PACING))
            .forEach { assertTrue(runCatching { ReviewProtocol.validate(listOf(it), plan, completed) }.isFailure) }
        ReviewProtocol.validate(listOf(finding.copy(category = ReviewCategory.STYLE,
            severity = ReviewSeverity.SUGGESTION, constraint = "")), plan, completed)
    }

    @Test
    fun reviewRejectsUnknownScenesAndSurrogateSplits() {
        val plan = parse(wrapper(listOf(scene())))
        val completed = listOf(CompletedScene("s1", "😀他", "", 1))
        val finding = ReviewFinding("s1", ReviewCategory.STYLE, ReviewSeverity.SUGGESTION,
            "😀", 0, 2, explanation = "语气", suggestion = "调整")
        ReviewProtocol.validate(listOf(finding), plan, completed)
        assertTrue(runCatching { ReviewProtocol.validate(listOf(finding.copy(sceneId = "missing")), plan, completed) }.isFailure)
        assertTrue(runCatching { ReviewProtocol.validate(listOf(finding.copy(quote = "\uD83D", endOffset = 1)), plan, completed) }.isFailure)
    }

    private fun scene() = SceneSpec("s1", "场景", goal = "取得线索")
    private fun wrapper(scenes: List<SceneSpec>) = "{\"scenes\":${ScenePlanProtocol.json.encodeToString(ListSerializer(SceneSpec.serializer()), scenes)}}"
    private fun parse(raw: String) = ScenePlanProtocol.parse(raw, "p", "c", "source", 1)
}
