package com.example.novelseek_ultra.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.CoverImageConfig
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.ui.components.MarkdownText
import com.example.novelseek_ultra.util.tx

/**
 * Dialogs shared between [ProjectScreen] (short novels) and [LongNovelScreen] (long novels).
 * Both project types support project covers, batch chapter-promo image generation, and a
 * markdown outline preview, so these live in one place rather than being duplicated per screen.
 *
 * They are `internal` (module-visible) so both screen files in this package can call them, and
 * they rely on `base64ToBitmap` (also `internal`, declared in CharactersScreen.kt).
 */

@Composable
internal fun OutlinePreviewDialog(
    lang: String,
    outline: String,
    onOpenOutlinePage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "大纲预览", "Outline Preview")) },
        text = {
            if (outline.isBlank()) {
                Text(
                    tx(lang,
                        "尚未生成大纲。点击下方「去大纲页」生成。",
                        "No outline yet. Tap 'Open Outline' below to generate one."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(text = outline, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenOutlinePage) {
                Text(tx(lang, "去大纲页", "Open Outline"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "关闭", "Close")) }
        },
    )
}

@Composable
internal fun CoverDialog(
    lang: String,
    projectId: String,
    vm: AppViewModel,
    images: List<CoverImageItem>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    generating: Boolean,
    error: String?,
    config: CoverImageConfig,
    onConfigChange: (CoverImageConfig) -> Unit,
    onGenerate: (charactersInfo: String?) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val project by vm.projects.collectAsState()
    val defaultCoverId = project.find { it.id == projectId }?.default_cover_id
    val characters = remember(projectId) { vm.characters(projectId) }
    var selectedCharIds by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "项目封面", "Project Cover")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ── Gallery ────────────────────────────────────────────────
                if (images.isNotEmpty()) {
                    val item = images.getOrNull(index)
                    item?.let { cover ->
                        val bmp = remember(cover.imageBase64) { cover.imageBase64?.let { base64ToBitmap(it) } }
                        bmp?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            IconButton(
                                onClick = { onIndexChange((index - 1).coerceAtLeast(0)) },
                                enabled = index > 0, modifier = Modifier.size(32.dp),
                            ) { Icon(Icons.Outlined.ChevronLeft, null, modifier = Modifier.size(20.dp)) }
                            Text(
                                "${index + 1} / ${images.size}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                            )
                            IconButton(
                                onClick = { onIndexChange((index + 1).coerceAtMost(images.lastIndex)) },
                                enabled = index < images.lastIndex, modifier = Modifier.size(32.dp),
                            ) { Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(20.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            val isDefault = cover.id == defaultCoverId
                            TextButton(
                                onClick = { if (!isDefault) onSetDefault(cover.id) },
                                enabled = !isDefault,
                            ) {
                                Text(
                                    if (isDefault) tx(lang, "已设为默认", "Default") else tx(lang, "设为默认", "Set Default"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onDelete(cover.id) }) {
                                Text(tx(lang, "删除", "Delete"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    HorizontalDivider()
                }

                // ── Generation config ──────────────────────────────────────
                Text(tx(lang, "生成新封面", "Generate New Cover"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = config.model, onValueChange = { onConfigChange(config.copy(model = it)) },
                    label = { Text(tx(lang, "图像模型", "Image Model")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = config.style, onValueChange = { onConfigChange(config.copy(style = it)) },
                    label = { Text(tx(lang, "图片风格（可选）", "Style hint (optional)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                // Character consistency: cover figures match the picked characters' looks.
                CharacterConsistencyPicker(lang, characters, selectedCharIds) { id ->
                    selectedCharIds = if (id in selectedCharIds) selectedCharIds - id else selectedCharIds + id
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = config.width.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { onConfigChange(config.copy(width = it)) } },
                        label = { Text(tx(lang, "宽度", "Width")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = config.height.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { onConfigChange(config.copy(height = it)) } },
                        label = { Text(tx(lang, "高度", "Height")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGenerate(buildCharactersInfo(characters, selectedCharIds)) },
                enabled = !generating,
            ) {
                if (generating) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(tx(lang, "生成", "Generate"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "关闭", "Close")) }
        },
    )
}

/**
 * Optional character selector for image generation (illustrations / covers). Picking a character
 * threads their appearance into the AI image-prompt so the depicted figure stays visually
 * consistent. "不选" (none) = no character constraint.
 */
@Composable
internal fun CharacterConsistencyPicker(
    lang: String,
    characters: List<Character>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tx(lang, "人物一致性（可选，可多选；建议只选一个）",
                "Character consistency (optional, multi-select; one is recommended)"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (characters.isEmpty()) {
            Text(
                tx(lang, "本项目暂无角色，可在「角色」页添加。", "No characters yet — add some on the Characters page."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                characters.forEach { ch ->
                    FilterChip(
                        selected = ch.id in selectedIds,
                        onClick = { onToggle(ch.id) },
                        label = { Text(ch.name, maxLines = 1) },
                    )
                }
            }
            val missing = characters.filter { it.id in selectedIds && it.appearance.isBlank() }
            if (missing.isNotEmpty()) {
                Text(
                    tx(lang, "⚠ ${missing.joinToString("、") { it.name }} 尚无「外貌」描述，一致性效果有限。",
                        "⚠ ${missing.joinToString(", ") { it.name }} lack an appearance description — consistency will be limited."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Format selected characters into the "- name：appearance" block the prompt builders expect. */
internal fun buildCharactersInfo(characters: List<Character>, selectedIds: Set<String>): String? {
    val picked = characters.filter { it.id in selectedIds }
    if (picked.isEmpty()) return null
    return picked.joinToString("\n") { c ->
        if (c.appearance.isBlank()) "- ${c.name}" else "- ${c.name}：${c.appearance}"
    }
}

@Composable
internal fun BatchPromoConfigDialog(
    lang: String,
    onDismiss: () -> Unit,
    onConfirm: (style: String, model: String, width: Int, height: Int) -> Unit,
) {
    var model by remember { mutableStateOf("zimage") }
    var style by remember { mutableStateOf("cinematic") }
    var widthStr by remember { mutableStateOf("1200") }
    var heightStr by remember { mutableStateOf("400") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "批量推文生成", "Batch Promo Generation")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tx(lang,
                        "将为所有尚未生成推文图片的章节批量生成封面头图（已有推文或内容过少的章节将跳过）。",
                        "Generates promo header images for all chapters that don't have one yet. Chapters with an existing promo or too little content are skipped."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text(tx(lang, "图像模型", "Image Model")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = style, onValueChange = { style = it },
                    label = { Text(tx(lang, "图片风格（可选）", "Style hint (optional)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthStr, onValueChange = { widthStr = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "宽度", "Width")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = heightStr, onValueChange = { heightStr = it.filter { c -> c.isDigit() } },
                        label = { Text(tx(lang, "高度", "Height")) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    tx(lang,
                        "推文图片为本章顶部横幅（3:1 比例），建议宽为高的3倍，如 1200×400。",
                        "Promo image is a 3:1 chapter header banner. Recommended: width = 3× height (e.g. 1200×400)."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    style.trim(),
                    model.trim().ifBlank { "zimage" },
                    widthStr.toIntOrNull()?.coerceAtLeast(64) ?: 1200,
                    heightStr.toIntOrNull()?.coerceAtLeast(64) ?: 400,
                )
            }) { Text(tx(lang, "开始生成", "Start")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) }
        },
    )
}
