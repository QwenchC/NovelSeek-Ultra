package com.example.novelseek_ultra.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.novelseek_ultra.ui.components.AppTopBar
import com.example.novelseek_ultra.data.model.TextModelProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val lang by vm.uiLanguage.collectAsState()
    val theme by vm.theme.collectAsState()
    val importPreview by vm.importPreview.collectAsState()
    val status by vm.statusMessage.collectAsState()
    val scope = rememberCoroutineScope()
    var includeAppSettings by remember { mutableStateOf(false) }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            if (text != null) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "backup.json"
                vm.stageImport(name, text)
            } else {
                // surface a friendly message; reuse statusMessage by triggering a failed parse
                vm.stageImport("backup.json", "{}")
            }
        }
    }

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(vm.buildBackupJson().toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AppTopBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tx(lang, "设置", "Settings"), style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    // Light/dark toggle — icon only, no label. Filled bulb = light theme (lamp
                    // on); outlined bulb = dark theme (lamp off). Tap to flip.
                    val isDark = theme == "dark"
                    IconButton(onClick = { vm.setTheme(if (isDark) "light" else "dark") }) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.Lightbulb
                                          else Icons.Filled.Lightbulb,
                            contentDescription = tx(
                                lang,
                                if (isDark) "切换到亮色主题（开灯）" else "切换到暗色主题（关灯）",
                                if (isDark) "Switch to light theme" else "Switch to dark theme",
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Language ──────────────────────────────────────────────
            SectionCard {
                Text(
                    tx(lang, "界面语言", "Interface Language"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = lang == "zh",
                        onClick = { vm.setUiLanguage("zh") },
                        label = { Text("中文") },
                    )
                    FilterChip(
                        selected = lang == "en",
                        onClick = { vm.setUiLanguage("en") },
                        label = { Text("English") },
                    )
                }
            }

            // ── Text model profiles ───────────────────────────────────
            TextModelProfilesSection(vm, lang)

            // ── Image generation engine (Pollinations / ComfyUI) ──────
            ImageEngineSection(vm, lang)

            // ── Pollinations image key ────────────────────────────────
            PollinationsKeySection(vm, lang)

            // ── Local Knowledge Base (RAG) ────────────────────────────
            KnowledgeBaseSection(vm, lang)

            // ── Project KB management ─────────────────────────────────
            ProjectKbManagementSection(vm, lang)

            // ── Augmentation layers (summaries + entities) ────────────
            AugmentationLayersSection(vm, lang)

            // ── Backup / Restore ──────────────────────────────────────
            SectionCard {
                Text(
                    tx(lang, "数据备份 / 恢复", "Data Backup / Restore"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tx(
                        lang,
                        "导出文件与 PC 版完全兼容。⚠ 章节正文不在备份里（按 PC 版约定，正文存在 SQLite 中由应用管理）。" +
                                "导入时 API Key 会安全地写入 Android Keystore，而不是明文 SharedPreferences。",
                        "Exports are 100% compatible with the PC build. ⚠ Chapter bodies are not in the backup " +
                                "(per PC convention, they live in SQLite). On import, API keys are routed into the " +
                                "Android Keystore instead of plain SharedPreferences."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val stamp = SimpleDateFormat(
                                "yyyy-MM-dd-HH-mm-ss", Locale.US
                            ).format(Date())
                            createFileLauncher.launch("novelseek-backup-$stamp.json")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tx(lang, "导出全部数据", "Export Backup"))
                    }
                    OutlinedButton(
                        onClick = {
                            vm.clearStatus()
                            pickFileLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Upload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tx(lang, "从备份导入", "Import Backup"))
                    }
                }

                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tx(
                        lang,
                        "⚠ 安全提示：导出文件包含你的 API Key 和所有项目内容。不要分享给不信任的人。",
                        "⚠ Security: the export contains your API keys and all project content. Don't share it with anyone you don't trust."
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.cancelImport() },
            title = { Text(tx(lang, "确认导入备份", "Confirm Import")) },
            text = {
                Column {
                    Text(preview.fileName, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tx(
                            lang,
                            "备份内项目数：${preview.summary.projectIdsInBackup}（与本机重叠 ${preview.summary.projectIdsOverlap}）",
                            "Projects in backup: ${preview.summary.projectIdsInBackup} (overlap with this device: ${preview.summary.projectIdsOverlap})",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        tx(
                            lang,
                            "章节配图/摘要：${preview.summary.chapterPromosInBackup} 个",
                            "Chapter promos: ${preview.summary.chapterPromosInBackup}",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (preview.summary.hasAppSettings) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeAppSettings,
                                onCheckedChange = { includeAppSettings = it },
                            )
                            Text(
                                tx(
                                    lang,
                                    "同时导入应用设置（含 API Key、模型 profile 等）",
                                    "Also import app settings (API keys, model profiles, …)",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.confirmImport(includeAppSettings) }) {
                    Text(tx(lang, "导入", "Import"))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelImport() }) {
                    Text(tx(lang, "取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/**
 * Single-line text field for API keys / secrets. Masks the value by default (dots) and offers an
 * eye toggle to reveal it temporarily. Used for every secret on the Settings page so keys are
 * never shown in plain text by default (shoulder-surfing / screenshot protection).
 */
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                )
            }
        },
    )
}

/**
 * Collapsible project picker. Collapsed by default — shows just the selected project name + a
 * chevron. Tapping the header expands the full chip list. Keeps the Settings page compact when
 * there are many projects (the previous always-expanded list got unwieldy).
 */
@Composable
private fun CollapsibleProjectSelector(
    lang: String,
    projects: List<com.example.novelseek_ultra.data.model.Project>,
    selectedId: String,
    onSelect: (String) -> Unit,
    labelText: String = tx(lang, "选择项目", "Select Project"),
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTitle = projects.firstOrNull { it.id == selectedId }?.title
        ?: tx(lang, "（未选择）", "(none)")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$labelText：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                selectedTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                projects.forEach { p ->
                    AssistChip(
                        onClick = { onSelect(p.id); expanded = false },
                        label = { Text(p.title, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedId == p.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextModelProfilesSection(vm: AppViewModel, lang: String) {
    val state by vm.state.collectAsState()
    val profiles = remember(state) { vm.textModelProfiles() }
    val activeId = state["activeTextModelProfileId"]?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
    var editing by remember { mutableStateOf<TextModelProfile?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tx(lang, "AI 文本模型", "AI Text Model"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "Android 端使用 OpenAI 兼容协议直接调用，请选择活跃配置。",
                "Android calls models via the OpenAI-compatible protocol. Pick the active profile."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (profiles.isEmpty()) {
            Text(
                tx(lang, "尚未配置任何模型。点击 + 新建。", "No profiles yet. Tap + to add one."),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            profiles.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    AssistChip(
                        onClick = { vm.setActiveProfile(p.id) },
                        label = { Text(p.name) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (activeId == p.id) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        p.model.ifBlank { p.provider },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editing = p }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                    if (!p.builtIn) {
                        IconButton(onClick = { vm.deleteTextModelProfile(p.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val ok = vm.testTextConnection()
                    testResult = ok to if (ok)
                        tx(lang, "✓ 连接成功", "✓ Connection OK")
                    else
                        tx(lang, "✗ 连接失败，请检查 API Key / URL / 模型名", "✗ Connection failed — check API key / URL / model")
                    testing = false
                }
            }, enabled = !testing) {
                if (testing) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                else Text(tx(lang, "测试当前模型", "Test Active Model"))
            }
        }
        testResult?.let { (ok, msg) ->
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showCreate) {
        ProfileDialog(lang = lang, initial = null,
            onDismiss = { showCreate = false },
            onSave = { saved ->
                vm.saveTextModelProfile(saved.copy(id = "custom-${System.currentTimeMillis()}"))
                showCreate = false
            })
    }
    editing?.let { p ->
        ProfileDialog(lang = lang, initial = p,
            onDismiss = { editing = null },
            onSave = { saved ->
                vm.saveTextModelProfile(saved)
                editing = null
            })
    }
}

@Composable
private fun ProfileDialog(
    lang: String,
    initial: TextModelProfile?,
    onDismiss: () -> Unit,
    onSave: (TextModelProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var provider by remember { mutableStateOf(initial?.provider ?: "custom") }
    var apiUrl by remember { mutableStateOf(initial?.apiUrl.orEmpty()) }
    var model by remember { mutableStateOf(initial?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var temp by remember { mutableStateOf((initial?.temperature ?: 0.7).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tx(lang, "新建模型 Profile", "New Profile") else tx(lang, "编辑模型 Profile", "Edit Profile")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tx(lang, "名称", "Name")) }, singleLine = true)
                OutlinedTextField(provider, { provider = it }, label = { Text(tx(lang, "Provider", "Provider")) }, singleLine = true)
                OutlinedTextField(apiUrl, { apiUrl = it }, label = { Text("API URL") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text(tx(lang, "模型名", "Model")) }, singleLine = true)
                ApiKeyField(value = apiKey, onValueChange = { apiKey = it }, label = "API Key")
                initial?.keyUrl?.takeIf { it.isNotBlank() }?.let { ApiKeyLink(it, lang) }
                OutlinedTextField(temp, { temp = it }, label = { Text("Temperature") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && apiUrl.isNotBlank() && model.isNotBlank(), onClick = {
                onSave(
                    TextModelProfile(
                        id = initial?.id ?: "",
                        name = name,
                        provider = provider,
                        apiUrl = apiUrl,
                        model = model,
                        apiKey = apiKey,
                        temperature = temp.toDoubleOrNull() ?: 0.7,
                        builtIn = initial?.builtIn ?: false,
                        keyUrl = initial?.keyUrl,
                    )
                )
            }) { Text(tx(lang, "保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

@Composable
private fun ImageEngineSection(vm: AppViewModel, lang: String) {
    val state by vm.state.collectAsState()
    var engine by remember(state) { mutableStateOf(vm.imageEngine()) }
    var comfyUrl by remember(state) { mutableStateOf(vm.comfyUIUrl()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard {
        Text(tx(lang, "图像生成引擎", "Image Generation Engine"),
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "选择图像生成后端，作用于角色立绘、封面图、章节插图与章节推文。\n" +
                    "Pollinations 走云端，无需本地环境；ComfyUI 连接你本地/局域网运行的 ComfyUI，使用固定的 z-image-turbo 工作流（不带 LoRA）。",
                "Pick the backend for character portraits, covers, chapter illustrations and promos.\n" +
                    "Pollinations is cloud-based (no local setup); ComfyUI connects to your local/LAN ComfyUI instance using a fixed z-image-turbo workflow (no LoRA)."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = engine == "pollinations",
                onClick = { engine = "pollinations"; vm.setImageEngine("pollinations") },
                label = { Text("Pollinations") },
            )
            FilterChip(
                selected = engine == "comfyui",
                onClick = { engine = "comfyui"; vm.setImageEngine("comfyui") },
                label = { Text("ComfyUI") },
            )
        }

        if (engine == "comfyui") {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = comfyUrl,
                onValueChange = { comfyUrl = it; vm.setComfyUIUrl(it); testResult = null },
                label = { Text(tx(lang, "ComfyUI 地址", "ComfyUI URL")) },
                placeholder = { Text("http://192.168.1.100:8188") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tx(lang,
                    "手机需与运行 ComfyUI 的电脑在同一局域网。请填电脑的局域网 IP（例如 http://192.168.1.100:8188），不要用 localhost——那指向手机自己。",
                    "Your phone must be on the same LAN as the ComfyUI machine. Use that PC's LAN IP (e.g. http://192.168.1.100:8188), not localhost — localhost points at the phone itself."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !testing && comfyUrl.isNotBlank(),
                    onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            testResult = vm.testComfyUIConnection()
                            testing = false
                        }
                    },
                ) {
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(tx(lang, "测试 ComfyUI 连接", "Test ComfyUI"))
                }
                when (testResult) {
                    true -> Text(tx(lang, "✓ 连接成功", "✓ Connected"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    false -> Text(tx(lang, "✕ 连接失败", "✕ Failed"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                    null -> {}
                }
            }
        }
    }
}

/** A tappable "get API key" hyperlink that opens [url] in the browser. */
@Composable
private fun ApiKeyLink(url: String, lang: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = tx(lang, "→ 获取 API Key：$url", "→ Get an API key: $url"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { runCatching { uriHandler.openUri(url) } },
    )
}

@Composable
private fun PollinationsKeySection(vm: AppViewModel, lang: String) {
    var key by remember { mutableStateOf(vm.pollinationsKey()) }
    SectionCard {
        Text(tx(lang, "Pollinations 图像 Key", "Pollinations Image Key"),
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "用于角色立绘、封面图、章节插图等所有图像生成。\n" +
                    "注册后可拿到 sk_（服务端）/ pk_（客户端）token，填一个即可。\n" +
                    "不填也能跑——会走匿名通道，但 gen.pollinations.ai 对匿名请求限流非常严格（重新生成大概率失败），且画质会用降级模型。强烈建议填一个。",
                "Used for character portraits, cover art, and chapter illustrations.\n" +
                    "Register to get an sk_ (server-side) or pk_ (client-side) token — either works.\n" +
                    "Anonymous calls still work in theory, but gen.pollinations.ai aggressively throttles them and serves a lower-quality fallback model. Strongly recommended to fill one in."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ApiKeyLink("https://enter.pollinations.ai/", lang)
        Spacer(Modifier.height(8.dp))
        ApiKeyField(
            value = key,
            onValueChange = { key = it; vm.setPollinationsKey(it) },
            label = "Pollinations Key (sk_… or pk_…)",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── 1) Local Knowledge Base — embedding config + enable toggle ────────────────

@Composable
private fun KnowledgeBaseSection(vm: AppViewModel, lang: String) {
    val state by vm.state.collectAsState()
    var enabled by remember(state) { mutableStateOf(vm.knowledgeBaseEnabled()) }
    var cfg by remember(state) { mutableStateOf(vm.embeddingConfig()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    SectionCard {
        Text(
            tx(lang, "本地知识库（实验性）", "Local Knowledge Base (Experimental)"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "开启后，保存章节时正文会被切片并通过 Embedding 模型转为向量本地存储；生成新章节时自动检索历史中最相关的片段，拼到提示词末尾作为「长程相关记忆」。原有的近 3 章上下文逻辑不变，KB 检索仅作增强。",
                "When enabled, saving a chapter chunks its text and embeds it locally; new chapter generation retrieves the most relevant past snippets and appends them as long-range memory. The legacy last-3-chapters context is unchanged — KB only augments it."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = enabled,
                onCheckedChange = { enabled = it; vm.setKnowledgeBaseEnabled(it) },
            )
            Text(tx(lang, "启用本地知识库", "Enable local knowledge base"))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            tx(lang, "Embedding 服务（OpenAI 兼容）", "Embedding Provider (OpenAI Compatible)"),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "默认指向阿里云百炼（DashScope）。也支持任意 OpenAI 兼容的 Embedding 端点。",
                "Defaults point to Alibaba Cloud Bailian (DashScope). Any OpenAI-compatible embedding endpoint also works."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ApiKeyLink("https://bailian.console.aliyun.com/?tab=model#/api-key", lang)

        Spacer(Modifier.height(8.dp))
        ApiKeyField(
            value = cfg.apiKey,
            onValueChange = { cfg = cfg.copy(apiKey = it); vm.saveEmbeddingConfig(cfg) },
            label = "API Key",
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = cfg.apiUrl, onValueChange = { cfg = cfg.copy(apiUrl = it); vm.saveEmbeddingConfig(cfg) },
            enabled = enabled,
            label = { Text("API URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cfg.model, onValueChange = { cfg = cfg.copy(model = it); vm.saveEmbeddingConfig(cfg) },
                enabled = enabled,
                label = { Text(tx(lang, "模型", "Model")) }, singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = cfg.dimensions?.toString().orEmpty(),
                onValueChange = {
                    val n = it.filter { c -> c.isDigit() }.toIntOrNull()
                    cfg = cfg.copy(dimensions = if (n != null && n > 0) n else null)
                    vm.saveEmbeddingConfig(cfg)
                },
                enabled = enabled,
                label = { Text(tx(lang, "维度", "Dim")) }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = enabled && !testing && cfg.apiKey.isNotBlank()
                          && cfg.apiUrl.isNotBlank() && cfg.model.isNotBlank(),
                onClick = {
                    testing = true; testResult = null
                    scope.launch {
                        val ok = vm.testEmbeddingConnection()
                        testResult = ok
                        testing = false
                    }
                },
            ) {
                if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(tx(lang, "测试 Embedding 连接", "Test Embedding"))
            }
            when (testResult) {
                true -> Text(tx(lang, "✓ 连接成功", "✓ Connected"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                false -> Text(tx(lang, "✕ 连接失败", "✕ Failed"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
                null -> {}
            }
        }
    }
}

// ── 2) Project KB management — pick a project, see stats, rebuild ─────────────

@Composable
private fun ProjectKbManagementSection(vm: AppViewModel, lang: String) {
    val state by vm.state.collectAsState()
    val kbRev by vm.kbRevision.collectAsState()
    val enabled = remember(state) { vm.knowledgeBaseEnabled() }
    val projects by vm.projects.collectAsState()
    var selectedProjectId by remember(projects) { mutableStateOf(projects.firstOrNull()?.id.orEmpty()) }
    var rebuilding by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Recompute on kbRev too: auto-indexing (after chapter save/generation) writes chunks to a
    // separate file that doesn't touch `state`, so without kbRev the stats would look frozen.
    val stats = remember(state, kbRev, selectedProjectId, rebuilding) {
        if (selectedProjectId.isNotBlank()) vm.kbStats(selectedProjectId) else null
    }
    val summaryCount = remember(state, selectedProjectId) {
        if (selectedProjectId.isNotBlank()) vm.summariesOf(selectedProjectId).count { it.scopeType == "chapter" } else 0
    }
    val entityCount = remember(state, selectedProjectId) {
        if (selectedProjectId.isNotBlank()) vm.entitiesOf(selectedProjectId).size else 0
    }

    SectionCard {
        Text(
            tx(lang, "项目知识库管理", "Project Knowledge Base"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        if (projects.isEmpty()) {
            Text(
                tx(lang, "（暂无项目）", "(No projects)"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CollapsibleProjectSelector(
                lang = lang,
                projects = projects,
                selectedId = selectedProjectId,
                onSelect = { selectedProjectId = it },
            )
        }

        Spacer(Modifier.height(10.dp))

        if (stats != null) {
            Text(
                tx(lang,
                    "已索引片段：${stats.totalChunks} · 已索引来源：${stats.totalSources}",
                    "Chunks indexed: ${stats.totalChunks} · Sources: ${stats.totalSources}"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.embeddingModels.isNotEmpty()) {
                Text(
                    tx(lang, "使用模型：${stats.embeddingModels.joinToString(", ")}",
                        "Models: ${stats.embeddingModels.joinToString(", ")}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Summary / entity counts give the user a direct way to confirm the augmentation
            // layers are actually firing on chapter save / generation.
            Text(
                tx(lang,
                    "已生成章节摘要：$summaryCount 篇 · 已抽取实体：$entityCount 个",
                    "Chapter summaries: $summaryCount · Entities extracted: $entityCount"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = enabled && selectedProjectId.isNotBlank() && !rebuilding,
            onClick = {
                rebuilding = true
                progressText = tx(lang, "准备中…", "Preparing…")
                scope.launch {
                    vm.rebuildKnowledgeBase(
                        projectId = selectedProjectId,
                        onProgress = { i, total, title ->
                            progressText = tx(lang,
                                "索引中 $i / $total：$title",
                                "Indexing $i / $total: $title")
                        },
                        onDone = { chapters, chunks, errors, firstError ->
                            val base = tx(lang,
                                "完成。共索引 $chapters 章，$chunks 个片段，失败 $errors。",
                                "Done. $chapters chapters → $chunks chunks ($errors errors).")
                            // When everything failed, the user has zero diagnostics unless we
                            // bubble the first provider error message up. Truncate so it doesn't
                            // overflow the screen.
                            val withDetail = if (errors > 0 && firstError != null) {
                                base + "\n" + tx(lang, "首条错误：", "First error: ") +
                                    firstError.take(300)
                            } else base
                            progressText = withDetail
                            rebuilding = false
                        },
                    )
                }
            },
        ) {
            if (rebuilding) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(tx(lang, "重建知识库", "Rebuild"))
        }
        if (progressText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(progressText, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "日常保存章节时会自动入库，只有第一次接入 / 切换 embedding 模型时才需要「重建」。",
                "Regular chapter saves auto-index. Use 'Rebuild' only on first setup or after switching models."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 3) Augmentation layers — summaries + entities toggles + bulk rebuilders ──

@Composable
private fun AugmentationLayersSection(vm: AppViewModel, lang: String) {
    val state by vm.state.collectAsState()
    var summariesOn by remember(state) { mutableStateOf(vm.summariesEnabled()) }
    var entitiesOn by remember(state) { mutableStateOf(vm.entitiesEnabled()) }
    val projects by vm.projects.collectAsState()
    var selectedProjectId by remember(projects) { mutableStateOf(projects.firstOrNull()?.id.orEmpty()) }
    var buildingSummaries by remember { mutableStateOf(false) }
    var buildingBook by remember { mutableStateOf(false) }
    var summariesProgress by remember { mutableStateOf("") }
    var bookProgress by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    SectionCard {
        Text(
            tx(lang, "增强层（分层摘要 + 实体抽取）", "Augmentation Layers (Summaries + Entities)"),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            tx(lang,
                "这两层增强会在保存章节时额外调用一次文本模型（按文本模型平台计费），并在生成新章节时把「全书梗概 / 当前弧线进度 / 未回收伏笔」塞进提示词。建议长篇（30 章以上）开启。",
                "Each layer triggers one extra text-model call per chapter save (billed via your text platform). When generating new chapters, the prompt gains book/arc summaries and an open-foreshadowing list. Recommended for long novels (30+ chapters)."),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            // NOT gated on kbEnabled: summaries/entities only need the TEXT model, not the
            // embedding/vector store. Gating them on the local KB toggle (as the PC build did)
            // silently swallowed the user's clicks when KB was off, so the feature never turned on.
            Checkbox(
                checked = summariesOn,
                onCheckedChange = { summariesOn = it; vm.setSummariesEnabled(it) },
            )
            Column {
                Text(tx(lang, "启用分层摘要（章节 / 弧线 / 全书）",
                    "Enable hierarchical summaries (chapter / arc / book)"))
                Text(
                    tx(lang,
                        "保存或生成章节时自动生成 ~150 字章节摘要（用文本模型，不需要 embedding），提示词里追加「全书梗概」+「当前弧线进度」段。",
                        "Auto-generates ~150-char chapter summaries on save/generation (uses the text model, no embedding needed). Prompts gain 'book synopsis' and 'arc progress' sections."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = entitiesOn,
                onCheckedChange = { entitiesOn = it; vm.setEntitiesEnabled(it) },
            )
            Column {
                Text(tx(lang, "启用实体抽取（人物登场 / 伏笔 / 地点 / 事件 / 物品）",
                    "Enable entity extraction (characters / foreshadowing / locations / events / items)"))
                Text(
                    tx(lang,
                        "保存章节时自动结构化抽取，并在提示词里追加「未回收伏笔」清单，避免长篇写飞。",
                        "Structured extraction on save; the 'open foreshadowing' list is added to the prompt to keep long arcs coherent."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (projects.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CollapsibleProjectSelector(
                lang = lang,
                projects = projects,
                selectedId = selectedProjectId,
                onSelect = { selectedProjectId = it },
                labelText = tx(lang, "目标项目", "Target project"),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(tx(lang, "步骤 1：重建所有章节摘要",
            "Step 1: Rebuild all chapter summaries"),
            style = MaterialTheme.typography.titleSmall)
        Text(tx(lang,
            "一次性为已有章节生成摘要（首次启用必跑）。开启摘要 toggle 后，日常保存章节会自动维护。",
            "One-shot pass over existing chapters (required on first enable). After the toggle is on, regular chapter saves keep summaries up-to-date."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            enabled = selectedProjectId.isNotBlank() && !buildingSummaries,
            onClick = {
                buildingSummaries = true
                summariesProgress = tx(lang, "加载章节中…", "Loading chapters…")
                scope.launch {
                    vm.generateChapterSummariesForAll(
                        projectId = selectedProjectId,
                        onProgress = { i, total, title ->
                            summariesProgress = tx(lang,
                                "生成摘要 $i / $total：$title",
                                "Summarizing $i / $total: $title")
                        },
                        onDone = { ok, errors ->
                            summariesProgress = tx(lang,
                                "完成：$ok 章已生成摘要，$errors 章失败。",
                                "Done: $ok chapters summarized, $errors failed.")
                            buildingSummaries = false
                        },
                    )
                }
            },
        ) {
            if (buildingSummaries) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(tx(lang, "重建章节摘要", "Rebuild Chapter Summaries"))
        }
        if (summariesProgress.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(summariesProgress, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        Text(tx(lang, "步骤 2：生成「全书梗概」",
            "Step 2: Build book summary"),
            style = MaterialTheme.typography.titleSmall)
        Text(tx(lang,
            "基于已有章节/弧线摘要汇总。建议每 5-10 章手动刷新一次。",
            "Rolls up existing chapter / arc summaries. Refresh every 5-10 chapters."),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            enabled = selectedProjectId.isNotBlank() && !buildingBook,
            onClick = {
                buildingBook = true
                bookProgress = tx(lang, "生成中…", "Generating…")
                vm.generateBookSummary(selectedProjectId) { result ->
                    bookProgress = if (result != null)
                        tx(lang, "完成。全书梗概 ${result.wordCount} 字。",
                            "Done. Book summary: ${result.wordCount} chars.")
                    else tx(lang, "失败或无可用摘要。", "Failed or no summaries available.")
                    buildingBook = false
                }
            },
        ) {
            if (buildingBook) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(tx(lang, "生成全书梗概", "Build Book Summary"))
        }
        if (bookProgress.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(bookProgress, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}