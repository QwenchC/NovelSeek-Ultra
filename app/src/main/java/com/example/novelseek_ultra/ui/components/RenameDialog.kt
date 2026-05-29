package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Reusable single-field rename dialog. Confirm fires [onConfirm] with the trimmed new name only
 * when it is non-blank AND different from the initial value, so an unchanged confirm is a no-op
 * (avoids needless state writes / autosaves).
 */
@Composable
fun RenameDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.trim().isNotEmpty() && text.trim() != initialValue.trim(),
                onClick = {
                    onConfirm(text.trim())
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
