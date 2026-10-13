package com.example.novelseek_ultra.ui.screens

/** Reload applied values after an external/import update without discarding an unsaved editor draft. */
internal fun <T> reloadCleanSettingsDraft(draft: T, previousApplied: T?, incoming: T): T =
    if (previousApplied == null || draft == previousApplied) incoming else draft
