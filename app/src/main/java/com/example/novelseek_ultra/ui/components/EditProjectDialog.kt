package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.util.tx

/**
 * Edit a project's title AND description. Replaces the title-only rename dialog so the description
 * entered at project creation becomes editable — and, because it now lives in an editable text
 * field plus a one-tap "copy" button, copyable.
 */
@Composable
fun EditProjectDialog(
    lang: String,
    initialTitle: String,
    initialDescription: String,
    onConfirm: (title: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx(lang, "编辑项目", "Edit Project")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tx(lang, "项目名称", "Project name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(tx(lang, "项目简介", "Description")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = description.isNotBlank(),
                        onClick = { clipboard.setText(AnnotatedString(description)) },
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                        Text(" " + tx(lang, "复制简介", "Copy"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.trim().isNotEmpty(),
                onClick = {
                    onConfirm(title.trim(), description.trim())
                    onDismiss()
                },
            ) { Text(tx(lang, "保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx(lang, "取消", "Cancel")) } },
    )
}
