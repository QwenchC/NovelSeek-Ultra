package com.example.novelseek_ultra.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.data.model.CultivationSubRealm
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── JSON bundle for import / export ──────────────────────────────

@Serializable
private data class RealmsBundle(
    val version: Int = 2,
    val kind: String = "cultivation-realms",
    val realms: List<CultivationRealm>,
)

private val bundleJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

private fun encodeBundle(realms: List<CultivationRealm>): String =
    bundleJson.encodeToString(RealmsBundle(realms = realms.sortedBy { it.order }))

private fun decodeBundle(text: String): List<CultivationRealm>? = runCatching {
    bundleJson.decodeFromString<RealmsBundle>(text).realms
}.getOrNull()

// ── Main screen ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CultivationScreen(vm: AppViewModel, projectId: String, onBack: () -> Unit) {
    val lang by vm.uiLanguage.collectAsState()
    val state by vm.state.collectAsState()
    var realms by remember(state) { mutableStateOf(vm.cultivationRealms(projectId)) }
    var editing by remember { mutableStateOf<CultivationRealm?>(null) }
    var showImportConfirm by remember { mutableStateOf<List<CultivationRealm>?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Add-realm inline form state
    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }

    val saveBack: (List<CultivationRealm>) -> Unit = { next ->
        realms = next.sortedBy { it.order }
        vm.setCultivationRealms(projectId, realms)
    }

    // ── Export launcher ──
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(encodeBundle(realms).toByteArray(Charsets.UTF_8))
                }
            }
            val subCount = realms.sumOf { it.subRealms?.size ?: 0 }
            statusMsg = tx(lang,
                "已导出 ${realms.size} 个大境界（含 $subCount 个小境界）",
                "Exported ${realms.size} major realms ($subCount sub-realms)")
        }
    }

    // ── Import launcher ──
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val imported = text?.let { decodeBundle(it) }
            if (imported == null || imported.isEmpty()) {
                statusMsg = tx(lang, "导入失败：无法解析文件", "Import failed: cannot parse file")
            } else {
                showImportConfirm = imported
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null) }
                },
                title = { Text(tx(lang, "境界体系", "Cultivation Realms"), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    ) { Icon(Icons.Outlined.Upload, contentDescription = tx(lang, "导入", "Import")) }
                    IconButton(
                        onClick = {
                            if (realms.isEmpty()) {
                                statusMsg = tx(lang, "没有境界可以导出", "No realms to export")
                            } else {
                                val stamp = System.currentTimeMillis()
                                exportLauncher.launch("cultivation-realms-$stamp.json")
                            }
                        }
                    ) { Icon(Icons.Outlined.Download, contentDescription = tx(lang, "导出", "Export")) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Status message
            if (statusMsg.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(statusMsg, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { statusMsg = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = null)
                            }
                        }
                    }
                }
            }

            // Inline add-realm form
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx(lang, "新建大境界", "Add Major Realm"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = draftName, onValueChange = { draftName = it },
                            label = { Text(tx(lang, "境界名称（必填）", "Name (required)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draftDesc, onValueChange = { draftDesc = it },
                            label = { Text(tx(lang, "描述（可选）", "Description (optional)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val name = draftName.trim()
                                if (name.isBlank()) return@Button
                                val id = "realm-${System.currentTimeMillis()}"
                                saveBack(realms + CultivationRealm(
                                    id = id, order = realms.size,
                                    name = name,
                                    description = draftDesc.ifBlank { null },
                                    subRealms = emptyList(),
                                ))
                                draftName = ""; draftDesc = ""
                            },
                            enabled = draftName.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(tx(lang, "添加", "Add"))
                        }
                    }
                }
            }

            if (realms.isEmpty()) {
                item {
                    Text(
                        tx(lang, "境界体系用于玄幻 / 修真题材，给 AI 提供层级化的力量阶梯参考。",
                            "Cultivation realms give the AI a tiered power-ladder reference for xianxia/xuanhuan stories."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(realms, key = { it.id }) { realm ->
                RealmCard(
                    realm = realm, lang = lang,
                    onEdit = { editing = realm },
                    onDelete = { saveBack(realms.filterNot { it.id == realm.id }) },
                    onMoveUp = {
                        val idx = realms.indexOfFirst { it.id == realm.id }
                        if (idx > 0) {
                            val list = realms.toMutableList()
                            val swap = list[idx]; list[idx] = list[idx - 1]; list[idx - 1] = swap
                            saveBack(list.mapIndexed { i, r -> r.copy(order = i) })
                        }
                    },
                    onMoveDown = {
                        val idx = realms.indexOfFirst { it.id == realm.id }
                        if (idx in 0 until realms.size - 1) {
                            val list = realms.toMutableList()
                            val swap = list[idx]; list[idx] = list[idx + 1]; list[idx + 1] = swap
                            saveBack(list.mapIndexed { i, r -> r.copy(order = i) })
                        }
                    },
                )
            }
        }
    }

    // Edit dialog
    editing?.let { realm ->
        RealmEditDialog(
            lang = lang, realm = realm,
            onDismiss = { editing = null },
            onSave = { updated ->
                saveBack(realms.map { if (it.id == realm.id) updated.copy(id = realm.id, order = realm.order) else it })
                editing = null
            },
        )
    }

    // Import confirm dialog
    showImportConfirm?.let { incoming ->
        ImportConfirmDialog(
            lang = lang,
            incoming = incoming,
            current = realms,
            onDismiss = { showImportConfirm = null },
            onReplace = {
                val next = incoming.sortedBy { it.order }.mapIndexed { i, r -> r.copy(order = i) }
                saveBack(next)
                showImportConfirm = null
                val sub = next.sumOf { it.subRealms?.size ?: 0 }
                statusMsg = tx(lang,
                    "已替换为 ${next.size} 个大境界（含 $sub 个小境界）",
                    "Replaced with ${next.size} major realms ($sub sub-realms)")
            },
            onMerge = {
                val byId = realms.associateBy { it.id }.toMutableMap()
                for (r in incoming) {
                    val existing = byId[r.id]
                    if (existing == null) {
                        byId[r.id] = r
                    } else {
                        val subMap = (existing.subRealms ?: emptyList()).associateBy { it.id }.toMutableMap()
                        for (s in r.subRealms ?: emptyList()) subMap[s.id] = s
                        byId[r.id] = r.copy(subRealms = subMap.values.sortedBy { it.order })
                    }
                }
                val next = byId.values.sortedBy { it.order }.mapIndexed { i, r -> r.copy(order = i) }
                saveBack(next)
                showImportConfirm = null
                val sub = next.sumOf { it.subRealms?.size ?: 0 }
                statusMsg = tx(lang,
                    "合并完成：${next.size} 个大境界（含 $sub 个小境界）",
                    "Merged: ${next.size} major realms ($sub sub-realms)")
            },
        )
    }
}

// ── Realm card ───────────────────────────────────────────────────

@Composable
private fun RealmCard(
    realm: CultivationRealm, lang: String,
    onEdit: () -> Unit, onDelete: () -> Unit,
    onMoveUp: () -> Unit, onMoveDown: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${realm.order + 1}. ${realm.name}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null) }
                IconButton(onClick = onMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = null) }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = null) }
            }
            if (!realm.description.isNullOrBlank()) {
                Text(realm.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            realm.subRealms?.takeIf { it.isNotEmpty() }?.let { subs ->
                Spacer(Modifier.height(6.dp))
                subs.sortedBy { it.order }.forEach { s ->
                    Text(
                        "  • ${s.name}${if (!s.description.isNullOrBlank()) " — ${s.description}" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Edit dialog with per-sub-realm fields ─────────────────────────

private data class SubDraft(val name: String = "", val description: String = "")

@Composable
private fun RealmEditDialog(
    lang: String,
    realm: CultivationRealm,
    onDismiss: () -> Unit,
    onSave: (CultivationRealm) -> Unit,
) {
    var name by remember { mutableStateOf(realm.name) }
    var description by remember { mutableStateOf(realm.description.orEmpty()) }
    val subs: SnapshotStateList<SubDraft> = remember {
        mutableStateListOf(*realm.subRealms.orEmpty().sortedBy { it.order }
            .map { SubDraft(it.name, it.description.orEmpty()) }.toTypedArray())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "编辑境界", "Edit Realm")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Major realm fields
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(tx(lang, "大境界名称", "Major realm name")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text(tx(lang, "描述（可选）", "Description (optional)")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                Text(tx(lang, "小境界", "Sub-realms"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)

                // Per-sub-realm rows
                subs.forEachIndexed { i, sub ->
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.", style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(20.dp))
                                OutlinedTextField(
                                    value = sub.name,
                                    onValueChange = { subs[i] = subs[i].copy(name = it) },
                                    label = { Text(tx(lang, "名称", "Name")) },
                                    singleLine = true, modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { subs.removeAt(i) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = null)
                                }
                            }
                            OutlinedTextField(
                                value = sub.description,
                                onValueChange = { subs[i] = subs[i].copy(description = it) },
                                label = { Text(tx(lang, "描述（可选）", "Desc")) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // Add sub-realm button
                TextButton(
                    onClick = { subs.add(SubDraft()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(tx(lang, "添加小境界", "Add sub-realm"))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val builtSubs = subs.filter { it.name.isNotBlank() }
                        .mapIndexed { i, s ->
                            CultivationSubRealm(
                                id = realm.subRealms?.getOrNull(i)?.id ?: "sub-${System.currentTimeMillis()}-$i",
                                order = i, name = s.name,
                                description = s.description.ifBlank { null },
                            )
                        }
                    onSave(realm.copy(
                        name = name,
                        description = description.ifBlank { null },
                        subRealms = builtSubs.ifEmpty { null },
                    ))
                }
            ) { Text(tx(lang, "保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}

// ── Import confirm dialog ─────────────────────────────────────────

@Composable
private fun ImportConfirmDialog(
    lang: String,
    incoming: List<CultivationRealm>,
    current: List<CultivationRealm>,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onMerge: () -> Unit,
) {
    val incomingSub = incoming.sumOf { it.subRealms?.size ?: 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "导入境界", "Import Realms")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tx(lang,
                    "备份文件包含 ${incoming.size} 个大境界（含 $incomingSub 个小境界）。",
                    "Backup contains ${incoming.size} major realms ($incomingSub sub-realms)."))
                Text(tx(lang,
                    "当前共 ${current.size} 个大境界。",
                    "You currently have ${current.size} major realms."))
                Spacer(Modifier.height(4.dp))
                Text(tx(lang,
                    "• 智能合并：相同 ID 的境界以导入为准，其余保留。\n• 替换全部：清空当前全部境界，换为导入内容。",
                    "• Smart Merge: imported realms overwrite same-ID entries; rest kept.\n• Replace All: wipe current realms and use imported ones."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onMerge) { Text(tx(lang, "智能合并", "Smart Merge")) }
                TextButton(onClick = onReplace) { Text(tx(lang, "替换全部", "Replace All")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}
