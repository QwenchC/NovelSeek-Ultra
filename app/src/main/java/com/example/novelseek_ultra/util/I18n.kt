package com.example.novelseek_ultra.util

/** Mirrors PC `src/utils/i18n.ts`: pick one of two strings based on the current UI language. */
fun tx(lang: String, zh: String, en: String): String = if (lang == "en") en else zh

fun formatWordCount(n: Int): String = when {
    n >= 10_000 -> String.format("%.1fw", n / 10_000.0)
    n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
    else -> n.toString()
}