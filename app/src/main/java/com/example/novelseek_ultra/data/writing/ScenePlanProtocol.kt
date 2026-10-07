package com.example.novelseek_ultra.data.writing

import com.example.novelseek_ultra.data.ai.PromptRequestBudgeter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Strict whole-document protocol. Prose, fences, unknown fields and partial JSON are rejected. */
object ScenePlanProtocol {
    const val MAX_SCENES = 8
    const val MAX_SCENE_WORDS = 8_000
    const val MAX_CHAPTER_WORDS = 30_000
    const val MAX_PLAN_TOKENS = 20_000
    private const val MAX_JSON_CHARS = 128_000
    internal val json = Json { ignoreUnknownKeys = false; isLenient = false; encodeDefaults = true }
    private val sceneKeys = setOf(
        "id", "title", "pov", "time", "location", "goal", "conflict", "turn", "entryState",
        "exitState", "requiredEvents", "forbiddenEvents", "targetWords",
    )

    fun parse(
        raw: String,
        projectId: String,
        chapterId: String,
        sourceFingerprint: String,
        updatedAt: Long,
        maxJsonTokens: Int = MAX_PLAN_TOKENS,
    ): ChapterScenePlan {
        require(raw.length <= MAX_JSON_CHARS) { "场景计划 JSON 过长" }
        require(maxJsonTokens > 0 && PromptRequestBudgeter.estimateText(raw) <= maxJsonTokens) {
            "场景计划超过输出预算"
        }
        val root = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("场景计划必须是 JSON 对象")
        require(root.keys == setOf("scenes")) { "场景计划只能包含 scenes 字段" }
        val array = root["scenes"] as? JsonArray
            ?: throw IllegalArgumentException("scenes 必须是数组")
        require(array.size in 1..MAX_SCENES) { "场景数量必须在 1 到 $MAX_SCENES 之间" }
        val scenes = array.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("每个场景必须是对象")
            require(obj.keys == sceneKeys) { "场景字段缺失或包含未声明字段" }
            fun text(name: String): String {
                val value = obj[name] as? JsonPrimitive
                require(value?.isString == true) { "$name 必须是字符串" }
                return value.content
            }
            fun strings(name: String): List<String> {
                val value = obj[name] as? JsonArray ?: throw IllegalArgumentException("$name 必须是数组")
                return value.map {
                    val item = it as? JsonPrimitive
                    require(item?.isString == true) { "$name 中的每项必须是字符串" }
                    item.content
                }
            }
            val words = obj["targetWords"] as? JsonPrimitive
            require(words != null && !words.isString && words.intOrNull != null) {
                "targetWords 必须是整数"
            }
            SceneSpec(
                id = text("id"), title = text("title"), pov = text("pov"), time = text("time"),
                location = text("location"), goal = text("goal"), conflict = text("conflict"),
                turn = text("turn"), entryState = text("entryState"), exitState = text("exitState"),
                requiredEvents = strings("requiredEvents"), forbiddenEvents = strings("forbiddenEvents"),
                targetWords = checkNotNull(words.intOrNull),
            )
        }
        return ChapterScenePlan(projectId, chapterId, sourceFingerprint, scenes, updatedAt).also(::validate)
    }

    fun validate(plan: ChapterScenePlan) {
        require(plan.projectId.isNotBlank() && plan.chapterId.isNotBlank()) { "项目和章节标识不能为空" }
        require(plan.sourceFingerprint.isNotBlank()) { "场景计划来源不能为空" }
        require(plan.scenes.size in 1..MAX_SCENES) { "场景数量超出限制" }
        require(plan.scenes.map { it.id }.distinct().size == plan.scenes.size) { "场景 id 重复" }
        plan.scenes.forEach { scene ->
            require(scene.id.isNotBlank() && scene.id == scene.id.trim() && scene.id.length <= 128) {
                "场景 id 为空或无效"
            }
            require(scene.title.isNotBlank() && scene.goal.isNotBlank()) { "场景标题和目标不能为空" }
            val fields = listOf(scene.title, scene.pov, scene.time, scene.location, scene.goal,
                scene.conflict, scene.turn, scene.entryState, scene.exitState)
            require(fields.all { it.length <= 4_000 }) { "场景约束字段过长" }
            require(scene.targetWords in 100..MAX_SCENE_WORDS) { "场景目标字数超出限制" }
            listOf(scene.requiredEvents, scene.forbiddenEvents).forEach { events ->
                require(events.size <= 16 && events.all { it.isNotBlank() && it.length <= 1_000 }) {
                    "场景事件约束为空或过长"
                }
                require(events.distinct().size == events.size) { "场景事件约束重复" }
            }
            require(scene.requiredEvents.intersect(scene.forbiddenEvents.toSet()).isEmpty()) {
                "场景存在互相矛盾的必需与禁止事件"
            }
        }
        require(plan.scenes.sumOf { it.targetWords } <= MAX_CHAPTER_WORDS) { "章节目标字数超出限制" }
        require(PromptRequestBudgeter.estimateText(json.encodeToString(ChapterScenePlan.serializer(), plan)) <= MAX_PLAN_TOKENS) {
            "场景计划超过上下文预算上限"
        }
    }
}
