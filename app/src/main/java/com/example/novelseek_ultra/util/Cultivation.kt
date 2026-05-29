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
