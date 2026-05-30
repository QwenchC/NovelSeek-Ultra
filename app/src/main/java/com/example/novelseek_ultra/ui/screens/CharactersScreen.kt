package com.example.novelseek_ultra.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.novelseek_ultra.ui.components.AppTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val streamingText by vm.streamingText.collectAsState()
    var characters by remember(state, projectId) { mutableStateOf(vm.characters(projectId)) }
    var editing by remember { mutableStateOf<Character?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    // Import-from-outline dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var importedChars by remember { mutableStateOf<List<Character>>(emptyList()) }
    var importError by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                },
                title = { Text(tx(lang, "角色", "Characters"), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(
                        onClick = {
                            val outline = vm.outlineText(projectId)
                            if (outline.isBlank()) {
                                importError = tx(lang, "请先生成或填写大纲", "Generate or write an outline first")
                                showImportDialog = true
                                return@IconButton
                            }
                            importError = ""
                            importedChars = emptyList()
                            showImportDialog = true
                            vm.generateCharactersFromOutline(projectId) { parsed ->
                                importedChars = parsed
                            }
                        },
                        enabled = !isGenerating,
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = tx(lang, "从大纲导入角色", "Import from outline"))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(tx(lang, "新建角色", "New Character")) },
            )
        },
    ) { padding ->
        if (characters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(tx(lang, "还没有角色", "No characters yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(characters, key = { it.id }) { c ->
                    CharacterCard(
                        c, lang,
                        onEdit = { editing = c },
                        onDelete = {
                            characters = characters.filterNot { it.id == c.id }
                            vm.setCharacters(projectId, characters)
                        },
                    )
                }
            }
        }
    }

    if (showCreate) {
        EditCharacterDialog(
            vm = vm, lang = lang, projectId = projectId,
            initial = Character(id = "char-${System.currentTimeMillis()}", name = ""),
            onDismiss = { showCreate = false },
            onSave = { saved ->
                characters = characters + saved
                vm.setCharacters(projectId, characters)
                showCreate = false
            },
        )
    }
    editing?.let { c ->
        EditCharacterDialog(
            vm = vm, lang = lang, projectId = projectId, initial = c,
            onDismiss = { editing = null },
            onSave = { saved ->
                characters = characters.map { if (it.id == saved.id) saved else it }
                vm.setCharacters(projectId, characters)
                editing = null
            },
        )
    }

    if (showImportDialog) {
        ImportFromOutlineDialog(
            lang = lang,
            isGenerating = isGenerating,
            streamingText = streamingText,
            importedChars = importedChars,
            importError = importError,
            existingNames = characters.map { it.name }.toSet(),
            onConfirm = { toAdd ->
                val merged = characters.toMutableList()
                toAdd.forEach { new ->
                    val existingIdx = merged.indexOfFirst { it.name == new.name }
                    if (existingIdx >= 0) merged[existingIdx] = new.copy(id = merged[existingIdx].id)
                    else merged.add(new)
                }
                characters = merged
                vm.setCharacters(projectId, characters)
                showImportDialog = false
                importedChars = emptyList()
            },
            onDismiss = {
                vm.stopGenerating()
                showImportDialog = false
                importedChars = emptyList()
            },
        )
    }
}

@Composable
private fun ImportFromOutlineDialog(
    lang: String,
    isGenerating: Boolean,
    streamingText: String,
    importedChars: List<Character>,
    importError: String,
    existingNames: Set<String>,
    onConfirm: (List<Character>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Track which parsed chars the user wants to import (exclude already existing by default)
    val selected = remember(importedChars) {
        mutableStateOf(importedChars.map { it.name !in existingNames })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "从大纲导入角色", "Import Characters from Outline")) },
        text = {
            LazyColumn(
                modifier = Modifier.height(400.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    importError.isNotBlank() -> item {
                        Text(importError, color = MaterialTheme.colorScheme.error)
                    }
                    isGenerating && importedChars.isEmpty() -> {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                                Text(tx(lang, "AI 正在识别角色……", "AI is identifying characters…"))
                            }
                        }
                        if (streamingText.isNotBlank()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(4.dp))
                                // Show last 800 chars so user sees the live tail; prefix with char count
                                val preview = if (streamingText.length > 800)
                                    "…(${streamingText.length} 字符)\n" + streamingText.takeLast(800)
                                else streamingText
                                Text(preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    importedChars.isEmpty() -> item {
                        Text(tx(lang, "未能从大纲中识别到角色", "No characters found in outline"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        item {
                            Text(
                                tx(lang,
                                    "识别到 ${importedChars.size} 个角色，请选择要导入的：",
                                    "Found ${importedChars.size} characters. Select which to import:"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        itemsIndexed(importedChars) { idx, c ->
                            val isSelected = selected.value.getOrElse(idx) { false }
                            val isDuplicate = c.name in existingNames
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { chk ->
                                        selected.value = selected.value.toMutableList().also { it[idx] = chk }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                        if (isDuplicate) Text(
                                            tx(lang, "（已存在）", "(exists)"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    if (c.role.isNotBlank()) Text(
                                        "${c.gender.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()}${c.role}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isGenerating && importedChars.isNotEmpty() && selected.value.any { it },
                onClick = {
                    val toAdd = importedChars.filterIndexed { idx, _ -> selected.value.getOrElse(idx) { false } }
                    onConfirm(toAdd)
                },
            ) { Text(tx(lang, "导入选中", "Import Selected")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tx(lang, if (isGenerating) "取消" else "关闭", if (isGenerating) "Cancel" else "Close"))
            }
        },
    )
}

@Composable
private fun CharacterCard(c: Character, lang: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    // Mirrors HomeScreen's ProjectCard: the portrait fills the card's left edge, clipped to the
    // card's rounded corners (no image border), cover-cropped. Card height is driven by the text
    // column; a minHeight keeps short-info cards tall enough to show the portrait nicely.
    val portrait = remember(c.portraitBase64) { c.portraitBase64?.let { base64ToBitmap(it) } }
    val portraitW = 84.dp
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Content row drives the Box (and card) height.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (portrait != null) 116.dp else 0.dp)
                    .padding(start = if (portrait != null) portraitW + 12.dp else 0.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.isProtagonist) {
                            Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                    }
                    if (c.role.isNotBlank()) Text(c.role, style = MaterialTheme.typography.labelMedium)
                    if (c.personality.isNotBlank()) Text(
                        "${tx(lang, "性格", "Personality")}: ${c.personality}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    )
                    if (c.appearance.isNotBlank()) Text(
                        "${tx(lang, "形象", "Appearance")}: ${c.appearance}",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    )
                }
                Column {
                    IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = null) }
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = null) }
                }
            }
            // Portrait fills the card height (set by the Row above), clipped to the left corners.
            portrait?.let { bmp ->
                Box(modifier = Modifier.matchParentSize()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(portraitW)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditCharacterDialog(
    vm: AppViewModel,
    lang: String,
    projectId: String,
    initial: Character,
    onDismiss: () -> Unit,
    onSave: (Character) -> Unit,
) {
    var c by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    var isGeneratingAppearance by remember { mutableStateOf(false) }
    var isGeneratingPortrait by remember { mutableStateOf(false) }
    var portraitStyle by remember { mutableStateOf("") }   // 画风 input for portrait generation

    // AI quick-generate: user types a brief, AI fills every field grounded in the novel's
    // outline + realm system. Only offered when creating a new character.
    val isCreating = initial.name.isBlank()
    var brief by remember { mutableStateOf("") }
    var isGeneratingChar by remember { mutableStateOf(false) }
    var briefError by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreating) tx(lang, "新建角色", "New Character") else tx(lang, "编辑角色", "Edit Character")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(440.dp)) {
                if (isCreating) {
                    item {
                        Column {
                            OutlinedTextField(
                                value = brief,
                                onValueChange = { brief = it; briefError = "" },
                                label = { Text(tx(lang, "描述你想要的角色，AI 自动填写", "Describe the character; AI auto-fills")) },
                                placeholder = { Text(tx(lang, "如：一个隐居山林、亦正亦邪的炼丹宗师", "e.g. a reclusive, morally grey alchemy master")) },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (briefError.isNotBlank()) {
                                Text(briefError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(6.dp))
                            FilledTonalButton(
                                enabled = !isGeneratingChar && brief.isNotBlank(),
                                onClick = {
                                    isGeneratingChar = true
                                    briefError = ""
                                    scope.launch {
                                        val res = vm.generateCharacterFromBrief(projectId, brief)
                                        if (res != null) {
                                            // Merge AI fields onto the in-progress character, preserving id + portrait.
                                            c = c.copy(
                                                name = res.name,
                                                gender = res.gender,
                                                role = res.role,
                                                personality = res.personality,
                                                background = res.background,
                                                motivation = res.motivation,
                                                appearance = res.appearance,
                                                isProtagonist = res.isProtagonist,
                                                currentRealmId = res.currentRealmId ?: c.currentRealmId,
                                                currentSubRealmId = res.currentSubRealmId ?: c.currentSubRealmId,
                                            )
                                        } else {
                                            briefError = tx(lang, "生成失败，请检查模型配置或重试", "Generation failed — check model config or retry")
                                        }
                                        isGeneratingChar = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (isGeneratingChar) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                                else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(tx(lang, "AI 生成角色", "AI Generate Character"))
                            }
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider()
                        }
                    }
                }
                item { OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text(tx(lang, "姓名", "Name")) }, singleLine = true) }
                item { OutlinedTextField(c.gender, { c = c.copy(gender = it) }, label = { Text(tx(lang, "性别", "Gender")) }, singleLine = true) }
                item { OutlinedTextField(c.role, { c = c.copy(role = it) }, label = { Text(tx(lang, "身份", "Role")) }, singleLine = true) }
                item { OutlinedTextField(c.personality, { c = c.copy(personality = it) }, label = { Text(tx(lang, "性格", "Personality")) }, minLines = 2) }
                item { OutlinedTextField(c.background, { c = c.copy(background = it) }, label = { Text(tx(lang, "背景", "Background")) }, minLines = 2) }
                item { OutlinedTextField(c.motivation, { c = c.copy(motivation = it) }, label = { Text(tx(lang, "动机", "Motivation")) }, minLines = 2) }
                item { OutlinedTextField(c.appearance, { c = c.copy(appearance = it) }, label = { Text(tx(lang, "形象", "Appearance")) }, minLines = 2) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.isProtagonist, onCheckedChange = { c = c.copy(isProtagonist = it) })
                        Text(tx(lang, "主角", "Protagonist"))
                    }
                }
                // 画风 input — appended to the portrait prompt at generation time.
                item {
                    OutlinedTextField(
                        portraitStyle, { portraitStyle = it },
                        label = { Text(tx(lang, "立绘画风（可选，如：水墨/赛博朋克/动漫）",
                            "Portrait style (optional, e.g. ink/cyberpunk/anime)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            enabled = !isGeneratingAppearance,
                            onClick = {
                                isGeneratingAppearance = true
                                scope.launch {
                                    val res = vm.generateCharacterAppearance(
                                        c.name, c.role, c.personality, c.background, c.motivation, null,
                                    )
                                    if (res != null) {
                                        c = c.copy(appearance = res.first, portraitPrompt = res.second)
                                    }
                                    isGeneratingAppearance = false
                                }
                            },
                        ) {
                            if (isGeneratingAppearance) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                            else Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tx(lang, "AI 形象", "AI Appearance"))
                        }
                        val hasPortrait = c.portraitBase64 != null
                        FilledTonalButton(
                            enabled = !isGeneratingPortrait && (c.portraitPrompt?.isNotBlank() == true || c.appearance.isNotBlank()),
                            onClick = {
                                isGeneratingPortrait = true
                                scope.launch {
                                    // Base prompt = AI image_prompt (if any) else the appearance text;
                                    // the 画风 input is appended so it survives the platform's enhance.
                                    val base = (c.portraitPrompt?.takeIf { it.isNotBlank() } ?: c.appearance)
                                    val prompt = if (portraitStyle.isBlank()) base
                                                 else "$base，画风：${portraitStyle.trim()}"
                                    val bytes = vm.generatePortraitImage(prompt)
                                    if (bytes != null) {
                                        // Overwrites any existing portrait.
                                        c = c.copy(portraitBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
                                    }
                                    isGeneratingPortrait = false
                                }
                            },
                        ) {
                            if (isGeneratingPortrait) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                            else Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (hasPortrait) tx(lang, "重新生成立绘", "Regenerate Portrait")
                                else tx(lang, "AI 立绘", "AI Portrait"),
                            )
                        }
                    }
                }
                item {
                    c.portraitBase64?.let { b64 ->
                        base64ToBitmap(b64)?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(c) }, enabled = c.name.isNotBlank()) {
                Text(tx(lang, "保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

internal fun base64ToBitmap(b64: String) = runCatching {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
