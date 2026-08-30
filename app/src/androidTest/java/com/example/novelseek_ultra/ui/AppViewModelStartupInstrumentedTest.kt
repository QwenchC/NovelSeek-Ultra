package com.example.novelseek_ultra.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.model.AgentIndex
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentSessionMeta
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for restoring a persisted agent session during AppViewModel construction. */
@RunWith(AndroidJUnit4::class)
class AppViewModelStartupInstrumentedTest {

    @Test
    fun persistedAgentSessionRestoresBeforeFirstFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val repo = AppRepository.get(application)
        val previousIndex = repo.loadAgentIndex()
        val sessionId = "instrumented-startup-${System.nanoTime()}"
        val createdAt = "2026-08-30T22:36:41+08:00"
        val meta = AgentSessionMeta(sessionId, "startup regression", createdAt)
        val store = ViewModelStore()
        var viewModel: AppViewModel? = null

        try {
            repo.saveAgentSessionById(
                AgentSession(
                    id = sessionId,
                    title = meta.title,
                    createdAt = createdAt,
                ),
            )
            repo.saveAgentIndex(
                AgentIndex(
                    currentId = sessionId,
                    items = listOf(meta) + previousIndex.items.filterNot { it.id == sessionId },
                ),
            )

            instrumentation.runOnMainSync {
                viewModel = ViewModelProvider(
                    store,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(application),
                )[AppViewModel::class.java]
            }

            assertEquals(sessionId, viewModel?.agent?.currentSessionId?.value)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            repo.deleteAgentSessionById(sessionId)
            repo.saveAgentIndex(previousIndex)
        }
    }
}
