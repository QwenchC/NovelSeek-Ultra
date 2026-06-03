package com.example.novelseek_ultra.util

import com.example.novelseek_ultra.data.model.CultivationRealm

/** Mirrors PC `src/utils/cultivation.ts buildRealmSystemContext`. */
fun buildRealmSystemContext(realms: List<CultivationRealm>, language: String): String {
    if (realms.isEmpty()) return ""
    val sorted = realms.sortedBy { it.order }
    val header = if (language == "en") "[Cultivation Realm System]" else "【修炼境界体系】"
    return buildString {
        appendLine(header)
        sorted.forEach { realm ->
            val mainLine = if (language == "en")
                "${realm.order + 1}. ${realm.name}${if (!realm.description.isNullOrBlank()) " — ${realm.description}" else ""}"
            else
                "${realm.order + 1}、${realm.name}${if (!realm.description.isNullOrBlank()) " —— ${realm.description}" else ""}"
            appendLine(mainLine)
            realm.subRealms?.sortedBy { it.order }?.forEach { sub ->
                val subLine = if (language == "en")
                    "   • ${sub.name}${if (!sub.description.isNullOrBlank()) " — ${sub.description}" else ""}"
                else
                    "   • ${sub.name}${if (!sub.description.isNullOrBlank()) " —— ${sub.description}" else ""}"
                appendLine(subLine)
            }
        }
    }.trim()
}

/**
 * Mirrors PC `src/utils/cultivation.ts buildVolumeRealmConstraint`. Builds a TOP-PRIORITY hard
 * constraint block from a volume's user-written 修为/境界规划 ([Volume.realmPlan]) — the fix for
 * cultivation-realm drift in long xuanhuan novels: the user states a per-volume ceiling (e.g. "only
 * up to the peak of the first major realm") and this block forbids the model from over-leveling,
 * skipping, dropping, repeating, or jumping erratically. Injected (at high priority) into both
 * planning (arc / chapter outlines) and chapter-body generation.
 *
 * Returns "" when the volume has no realmPlan, so callers can append unconditionally.
 *
 * @param phase "generate" = writing chapter bodies; "plan" = planning arc/chapter outlines.
 */
fun buildVolumeRealmConstraint(
    realmPlan: String?,
    volumeName: String,
    language: String,
    phase: String = "generate",
): String {
    val plan = realmPlan?.trim().orEmpty()
    if (plan.isEmpty()) return ""

    return if (language == "en") {
        buildString {
            appendLine("[HARD CULTIVATION LIMIT for volume \"$volumeName\" — TOP PRIORITY, overrides everything else]")
            appendLine(plan)
            appendLine("Iron rules (must obey):")
            appendLine("- The protagonist's cultivation may ONLY rise within the range set above; never exceed this volume's ceiling.")
            appendLine("- Advance monotonically and gradually — climb sub-realms one step at a time. No level-skipping.")
            appendLine("- Never repeat a breakthrough into a realm already reached; never drop a realm without an explicit plot cause; no erratic up-and-down.")
            appendLine("- Most chapters keep cultivation UNCHANGED; only break through at a few key plot beats, by a small step.")
            append(
                if (phase == "plan")
                    "- When planning chapters/arcs, do NOT schedule breakthroughs that would exceed the ceiling by the end of this volume."
                else
                    "- Take the protagonist's current realm as the floor — do not contradict or reset it."
            )
        }
    } else {
        buildString {
            appendLine("【本副本「$volumeName」修为/境界硬约束 —— 最高优先级，高于其它一切设定】")
            appendLine(plan)
            appendLine("铁律（必须遵守）：")
            appendLine("- 主角修为只能在上述范围内提升，绝不允许超过本副本设定的上限。")
            appendLine("- 修为只能单调、循序渐进地提升，小境界逐层突破，严禁跳级。")
            appendLine("- 严禁重复突破已经达到过的境界；无明确剧情理由不得跌落；不得忽高忽低。")
            appendLine("- 绝大多数章节修为保持不变，仅在少数关键剧情节点小幅突破。")
            append(
                if (phase == "plan")
                    "- 规划弧线/章节时，不得安排会令本副本结束时超过上限的突破节奏。"
                else
                    "- 以上文角色当前境界为基准下限，不得与之矛盾或将其重置。"
            )
        }
    }
}
