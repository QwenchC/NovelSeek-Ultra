package com.example.novelseek_ultra.data.model

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSettingsBackupTest {
    @Test
    fun dualReasoningDefaultIsIncludedWithApplicationSettings() {
        assertTrue("dualAgentReasoningLevel" in APP_SETTINGS_FIELDS)
    }
}
