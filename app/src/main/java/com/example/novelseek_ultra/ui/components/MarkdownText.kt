package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Tiny inline Markdown renderer for outline / summary preview. Handles the small subset that
 * shows up in AI-generated novel outlines:
 *   - `# / ## / ###` headings (mapped onto Material typography)
 *   - `- item` and `* item` bullet lists
 *   - `1. item` numbered lists (kept as-is, prefix is preserved)
 *   - `**bold**` and `*italic*` inline emphasis
 *   - blank lines as paragraph separators
 *
 * Everything else falls through as plain body text. This intentionally has no dependency on a
 * full Markdown parser — outline-quality rendering only.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val lines = text.split("\n")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var prevBlank = true
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                if (!prevBlank) Spacer(Modifier.height(6.dp))
                prevBlank = true
                continue
            }
            prevBlank = false
            renderLine(line)
        }
    }
}

@Composable
private fun renderLine(line: String) {
    // Headings
    val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(line)
    if (headingMatch != null) {
        val level = headingMatch.groupValues[1].length
        val content = headingMatch.groupValues[2]
        val style: TextStyle = when (level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
        Text(
            text = renderInline(content),
            style = style.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (level <= 2) 6.dp else 2.dp),
        )
        return
    }

    // Bullet list
    val bulletMatch = Regex("^([-*])\\s+(.*)$").find(line)
    if (bulletMatch != null) {
        Row(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(16.dp),
            )
            Text(
                text = renderInline(bulletMatch.groupValues[2]),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        return
    }

    // Numbered list
    val numberedMatch = Regex("^(\\d+[.、])\\s+(.*)$").find(line)
    if (numberedMatch != null) {
        Row(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                numberedMatch.groupValues[1],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = renderInline(numberedMatch.groupValues[2]),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        return
    }

    // Plain paragraph
    Text(
        text = renderInline(line),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Parse `**bold**` and `*italic*` into an AnnotatedString with the right SpanStyle. */
private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '*') {
            // Greedy: try ** (bold) first, then * (italic)
            if (i + 1 < text.length && text[i + 1] == '*') {
                val close = text.indexOf("**", startIndex = i + 2)
                if (close > i + 1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, close))
                    pop()
                    i = close + 2
                    continue
                }
            } else {
                val close = text.indexOf('*', startIndex = i + 1)
                if (close > i) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, close))
                    pop()
                    i = close + 1
                    continue
                }
            }
        }
        append(ch)
        i += 1
    }
}
