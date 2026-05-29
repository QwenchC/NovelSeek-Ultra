package com.example.novelseek_ultra.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.model.Character
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.util.tx

@Composable
fun RealmInfoDialog(
    lang: String,
    realms: List<CultivationRealm>,
    characters: List<Character>,
    onUpdateCharacter: (Character) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "境界信息", "Realm Info")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp),
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(tx(lang, "境界体系", "Realms"), style = MaterialTheme.typography.labelMedium) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(tx(lang, "角色境界", "Char Realms"), style = MaterialTheme.typography.labelMedium) },
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> RealmSystemTab(realms, lang)
                        else -> CharacterRealmTab(characters, realms, lang, onUpdateCharacter)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "关闭", "Close")) } },
    )
}

@Composable
private fun RealmSystemTab(realms: List<CultivationRealm>, lang: String) {
    val sorted = realms.sortedBy { it.order }
    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                tx(lang, "暂无境界数据", "No realm data"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    var expandedIds by remember { mutableStateOf(setOf(sorted.firstOrNull()?.id ?: "")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        sorted.forEach { realm ->
            val hasSubs = !realm.subRealms.isNullOrEmpty()
            val isExpanded = realm.id in expandedIds
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (hasSubs) {
                    IconButton(
                        onClick = {
                            expandedIds = if (isExpanded) expandedIds - realm.id else expandedIds + realm.id
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null, modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(realm.name, style = MaterialTheme.typography.bodyMedium)
                    if (!realm.description.isNullOrBlank())
                        Text(
                            realm.description, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                        )
                }
            }
            if (hasSubs && isExpanded) {
                realm.subRealms!!.sortedBy { it.order }.forEach { sub ->
                    // Width-constrained Row + a weight(1f) Column so the sub-realm name and
                    // (multi-line) description wrap instead of overflowing off-screen.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("· ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(sub.name, style = MaterialTheme.typography.bodySmall)
                            if (!sub.description.isNullOrBlank())
                                Text(
                                    sub.description, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), thickness = 0.5.dp)
        }
    }
}

@Composable
private fun CharacterRealmTab(
    characters: List<Character>,
    realms: List<CultivationRealm>,
    lang: String,
    onUpdateCharacter: (Character) -> Unit,
) {
    var expandedCharId by remember { mutableStateOf<String?>(null) }
    val sortedRealms = realms.sortedBy { it.order }
    if (characters.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                tx(lang, "暂无角色", "No characters"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        characters.forEach { char ->
            val realmDisplay = buildRealmDisplay(char, realms, lang)
            val isOpen = expandedCharId == char.id
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(char.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        realmDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (char.currentRealmId != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { expandedCharId = if (isOpen) null else char.id },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                    )
                }
            }
            AnimatedVisibility(visible = isOpen) {
                Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                    TextButton(
                        onClick = {
                            onUpdateCharacter(char.copy(currentRealmId = null, currentSubRealmId = null))
                            expandedCharId = null
                        },
                    ) {
                        Text(tx(lang, "× 清除境界", "× Clear realm"), style = MaterialTheme.typography.labelSmall)
                    }
                    sortedRealms.forEach { realm ->
                        TextButton(
                            onClick = {
                                onUpdateCharacter(char.copy(currentRealmId = realm.id, currentSubRealmId = null))
                                expandedCharId = null
                            },
                        ) {
                            Text("【${realm.name}】", style = MaterialTheme.typography.bodySmall)
                        }
                        realm.subRealms?.sortedBy { it.order }?.forEach { sub ->
                            TextButton(
                                onClick = {
                                    onUpdateCharacter(char.copy(currentRealmId = realm.id, currentSubRealmId = sub.id))
                                    expandedCharId = null
                                },
                                modifier = Modifier.padding(start = 16.dp),
                            ) {
                                Text("↳ ${sub.name}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

internal fun buildRealmDisplay(char: Character, realms: List<CultivationRealm>, lang: String): String {
    val realmId = char.currentRealmId ?: return tx(lang, "未设置境界", "Realm not set")
    val realm = realms.find { it.id == realmId } ?: return tx(lang, "未知境界", "Unknown")
    val sub = char.currentSubRealmId?.let { sid -> realm.subRealms?.find { it.id == sid } }
    return if (sub != null) "${realm.name} · ${sub.name}" else realm.name
}
