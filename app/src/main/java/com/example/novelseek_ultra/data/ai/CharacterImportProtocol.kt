package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.TextModelConfig
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Strict, all-or-nothing wire protocol for extracting character profiles from long outlines. */
internal object CharacterImportProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    class FormatException(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    /**
     * Derives an outline slice budget from both sides of the request: remaining input capacity and
     * output capacity. Detailed character profiles routinely expand far beyond their outline
     * mentions, so one batch receives at most one fifth of the configured output-token budget.
     */
    fun outlineBatchTokenBudget(
        config: TextModelConfig,
        fixedMessages: List<ChatMessage>,
    ): Int {
        val requestConfig = TextModelRequestPolicy.normalizeForRequest(config)
        val maxOutput = PromptRequestBudgeter.maxOutputTokens(requestConfig)
        if (maxOutput < MIN_CHARACTER_OUTPUT_TOKENS) {
            throw PromptRequestBudgeter.BudgetExceededException(
                "从大纲导入详细角色档案至少需要 $MIN_CHARACTER_OUTPUT_TOKENS 个最大输出 Tokens；" +
                    "当前为 $maxOutput，请在设置中提高后重试。",
            )
        }
        val availableInput = PromptRequestBudgeter.inputBudgetTokens(requestConfig) -
            PromptRequestBudgeter.estimateMessages(fixedMessages) - BATCH_INPUT_SAFETY_TOKENS
        val outputDriven = (maxOutput / EXPECTED_PROFILE_EXPANSION).coerceAtLeast(MIN_BATCH_TOKENS)
        val budget = minOf(availableInput, outputDriven)
        if (budget < MIN_BATCH_TOKENS) {
            throw PromptRequestBudgeter.BudgetExceededException(
                "角色导入提示词只剩约 ${availableInput.coerceAtLeast(0)} 个输入 Tokens，无法安全分批；" +
                    "请提高模型上下文上限或精简境界设定。",
            )
        }
        return budget
    }

    /** Split without dropping text, preferring line boundaries and hard-splitting oversized lines. */
    fun splitOutline(outline: String, tokenBudget: Int): List<String> {
        require(tokenBudget > 0) { "tokenBudget must be positive" }
        val normalized = outline.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()
        val overlapBudget = (tokenBudget / OVERLAP_DIVISOR)
            .coerceIn(MIN_OVERLAP_TOKENS, MAX_OVERLAP_TOKENS)
            .coerceAtMost((tokenBudget / 3).coerceAtLeast(1))
        val separator = "\n"
        val coreBudget = (tokenBudget - overlapBudget -
            PromptRequestBudgeter.estimateText(separator)).coerceAtLeast(1)
        val cores = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            current.toString().trim().takeIf { it.isNotEmpty() }?.let(cores::add)
            current.clear()
        }

        val lines = normalized.split('\n')
        lines.forEachIndexed { index, line ->
            var remaining = if (index == lines.lastIndex) line else "$line\n"
            while (remaining.isNotEmpty()) {
                val combined = current.toString() + remaining
                if (PromptRequestBudgeter.estimateText(combined) <= coreBudget) {
                    current.append(remaining)
                    remaining = ""
                } else {
                    if (current.isNotEmpty()) flush()
                    if (PromptRequestBudgeter.estimateText(remaining) <= coreBudget) {
                        current.append(remaining)
                        remaining = ""
                    } else {
                        val prefixEnd = largestPrefixEnd(remaining, coreBudget)
                        check(prefixEnd > 0) { "tokenBudget cannot fit one Unicode code point" }
                        remaining.substring(0, prefixEnd).trim().takeIf { it.isNotEmpty() }
                            ?.let(cores::add)
                        remaining = remaining.substring(prefixEnd)
                    }
                }
            }
        }
        flush()
        return cores.mapIndexed { index, core ->
            if (index == 0) {
                core
            } else {
                val overlap = largestFittingSuffix(
                    value = cores[index - 1],
                    tokenBudget = overlapBudget,
                ).trim()
                val batch = if (overlap.isEmpty()) core else overlap + separator + core
                check(PromptRequestBudgeter.estimateText(batch) <= tokenBudget) {
                    "character outline batch exceeded its token budget"
                }
                batch
            }
        }
    }

    /**
     * Accept exactly one root object: `{ "characters": [...] }`. JSON fences, leading/trailing
     * prose, loose arrays, missing fields, and legacy pipe-delimited text are deliberately rejected.
     */
    fun parseCompleteJson(text: String): List<Character> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw FormatException("模型返回了空的角色 JSON")
        return try {
            val root = json.parseToJsonElement(trimmed) as? JsonObject
                ?: throw FormatException("角色结果必须是 JSON 对象，而不是数组或文本")
            val array = root["characters"] as? JsonArray
                ?: throw FormatException("角色 JSON 缺少 characters 数组")
            array.mapIndexed { index, element ->
                val obj = element as? JsonObject
                    ?: throw FormatException("characters[$index] 必须是 JSON 对象")
                Character(
                    id = "",
                    name = requiredString(obj, "name", index).trim().ifBlank {
                        throw FormatException("characters[$index].name 不能为空")
                    },
                    gender = requiredString(obj, "gender", index).trim(),
                    role = requiredString(obj, "role", index).trim(),
                    personality = requiredString(obj, "personality", index).trim(),
                    motivation = requiredString(obj, "motivation", index).trim(),
                    background = requiredString(obj, "background", index).trim(),
                    appearance = requiredString(obj, "appearance", index).trim(),
                    isProtagonist = requiredBoolean(obj, "isProtagonist", index),
                )
            }
        } catch (failure: FormatException) {
            throw failure
        } catch (failure: Throwable) {
            throw FormatException("角色结果不是完整、可解析的 JSON 对象", failure)
        }
    }

    /** Stable first-seen order; duplicate names are merged case-insensitively with richer fields. */
    fun mergeByName(
        batches: List<List<Character>>,
        idPrefix: String,
    ): List<Character> {
        val merged = linkedMapOf<String, Character>()
        batches.flatten().forEach { candidate ->
            val name = candidate.name.trim()
            if (name.isEmpty()) return@forEach
            val key = name.lowercase(Locale.ROOT).replace(WHITESPACE, " ")
            val previous = merged[key]
            merged[key] = if (previous == null) {
                candidate.copy(name = name)
            } else {
                previous.copy(
                    gender = richer(previous.gender, candidate.gender),
                    role = richer(previous.role, candidate.role),
                    personality = richer(previous.personality, candidate.personality),
                    motivation = richer(previous.motivation, candidate.motivation),
                    background = richer(previous.background, candidate.background),
                    appearance = richer(previous.appearance, candidate.appearance),
                    isProtagonist = previous.isProtagonist || candidate.isProtagonist,
                )
            }
        }
        return merged.values.mapIndexed { index, character ->
            character.copy(id = "$idPrefix-$index")
        }
    }

    private fun requiredString(obj: JsonObject, key: String, index: Int): String {
        val primitive = obj[key] as? JsonPrimitive
            ?: throw FormatException("characters[$index].$key 必须是字符串")
        if (!primitive.isString) throw FormatException("characters[$index].$key 必须是字符串")
        return primitive.contentOrNull.orEmpty()
    }

    private fun requiredBoolean(obj: JsonObject, key: String, index: Int): Boolean {
        val primitive = obj[key] as? JsonPrimitive
            ?: throw FormatException("characters[$index].$key 必须是布尔值")
        return primitive.booleanOrNull
            ?: throw FormatException("characters[$index].$key 必须是布尔值")
    }

    private fun richer(first: String, second: String): String {
        val left = first.trim()
        val right = second.trim()
        return if (right.length > left.length) right else left
    }

    private fun largestPrefixEnd(value: String, tokenBudget: Int): Int {
        var low = 0
        var high = value.length
        while (low < high) {
            val candidate = (low + high + 1) ushr 1
            val safe = safePrefixBoundary(value, candidate)
            if (PromptRequestBudgeter.estimateText(value.substring(0, safe)) <= tokenBudget) {
                low = candidate
            } else {
                high = candidate - 1
            }
        }
        return safePrefixBoundary(value, low)
    }

    private fun largestFittingSuffix(value: String, tokenBudget: Int): String {
        var low = 0
        var high = value.length
        while (low < high) {
            val length = (low + high + 1) ushr 1
            val start = safeSuffixBoundary(value, value.length - length)
            if (PromptRequestBudgeter.estimateText(value.substring(start)) <= tokenBudget) {
                low = length
            } else {
                high = length - 1
            }
        }
        return value.substring(safeSuffixBoundary(value, value.length - low))
    }

    private fun safePrefixBoundary(value: String, index: Int): Int = when {
        index in 1 until value.length && java.lang.Character.isHighSurrogate(value[index - 1]) &&
            java.lang.Character.isLowSurrogate(value[index]) -> index - 1
        else -> index.coerceIn(0, value.length)
    }

    private fun safeSuffixBoundary(value: String, index: Int): Int = when {
        index in 1 until value.length && java.lang.Character.isLowSurrogate(value[index]) &&
            java.lang.Character.isHighSurrogate(value[index - 1]) -> index + 1
        else -> index.coerceIn(0, value.length)
    }

    private const val MIN_CHARACTER_OUTPUT_TOKENS = 1_024
    private const val EXPECTED_PROFILE_EXPANSION = 5
    private const val MIN_BATCH_TOKENS = 128
    private const val BATCH_INPUT_SAFETY_TOKENS = 128
    private const val OVERLAP_DIVISOR = 10
    private const val MIN_OVERLAP_TOKENS = 16
    private const val MAX_OVERLAP_TOKENS = 128
    private val WHITESPACE = Regex("\\s+")
}
