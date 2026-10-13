package com.example.novelseek_ultra.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDraftPolicyTest {
    @Test
    fun firstBackgroundLoadInitializesAnEditor() {
        assertEquals("stored", reloadCleanSettingsDraft("", null, "stored"))
    }

    @Test
    fun cleanEditorFollowsAnImportedOrExternallyChangedValue() {
        assertEquals("imported", reloadCleanSettingsDraft("stored", "stored", "imported"))
    }

    @Test
    fun unrelatedReloadDoesNotEraseUnsavedTyping() {
        assertEquals("draft", reloadCleanSettingsDraft("draft", "stored", "stored"))
    }

    @Test
    fun externalReplacementAlsoPreservesAnUnsavedDraft() {
        assertEquals("draft", reloadCleanSettingsDraft("draft", "stored", "imported"))
    }
}
