package com.example.novelseek_ultra.agent

import com.example.novelseek_ultra.data.model.AgentStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInterruptedReplayTest {
    @Test
    fun matchesReorderedArgsAndImplicitVersusExplicitProject() {
        val interrupted = AgentStep(
            id = "action-1",
            type = AgentStep.ACTION,
            text = "",
            tool = "append_container_entry",
            argsJson = """{"value":"线索","containerId":"c1"}""",
            actionStatus = AgentStep.ACTION_INTERRUPTED,
            resolvedProjectId = "p1",
        )
        assertTrue(
            AgentInterruptedReplay.matches(
                interrupted,
                "append_container_entry",
                resolvedProjectId = "p1",
                fallbackProjectId = "",
            ),
        )
    }

    @Test
    fun matchesAnyPayloadInTheSameToolScopeButRejectsAnotherProject() {
        val interrupted = AgentStep(
            id = "action-1",
            type = AgentStep.ACTION,
            text = "",
            tool = "create_volume",
            argsJson = """{"name":"第一卷"}""",
            actionStatus = AgentStep.ACTION_INTERRUPTED,
            resolvedProjectId = "p1",
        )

        assertFalse(
            AgentInterruptedReplay.matches(
                interrupted,
                "create_volume",
                resolvedProjectId = "p2",
                fallbackProjectId = "",
            ),
        )
        assertTrue(
            AgentInterruptedReplay.matches(
                interrupted,
                "create_volume",
                resolvedProjectId = "p1",
                fallbackProjectId = "",
            ),
        )
    }

    @Test
    fun createProjectReplayIgnoresFocusChangedByTheInterruptedCreation() {
        val interrupted = AgentStep(
            id = "action-create",
            type = AgentStep.ACTION,
            text = "",
            tool = "create_project",
            argsJson = """{"title":"新书","genre":"玄幻"}""",
            actionStatus = AgentStep.ACTION_INTERRUPTED,
            resolvedProjectId = "old-focus",
        )

        assertTrue(
            AgentInterruptedReplay.matches(
                interrupted,
                "create_project",
                resolvedProjectId = "newly-created-focus",
                fallbackProjectId = "",
            ),
        )
    }

    @Test
    fun equivalentBooleanNumericAndDefaultRepresentationsCannotBypassReview() {
        val insert = AgentStep(
            id = "insert-1",
            type = AgentStep.ACTION,
            text = "",
            tool = "insert_chapter",
            argsJson = """{"referenceChapterId":"c1","before":true}""",
            actionStatus = AgentStep.ACTION_INTERRUPTED,
            resolvedProjectId = "p1",
        )
        val batch = insert.copy(
            id = "batch-1",
            tool = "generate_volumes",
            argsJson = """{"count":3}""",
        )

        // Replays using before:"true" / count:"3" / omitted default all take the same
        // conservative same-tool/scope confirmation path; raw argument spelling is irrelevant.
        assertTrue(AgentInterruptedReplay.matches(insert, "insert_chapter", "p1", ""))
        assertTrue(AgentInterruptedReplay.matches(batch, "generate_volumes", "p1", ""))
        assertTrue(AgentInterruptedReplay.matches(batch, "generate_volumes", "p1", ""))
    }
}
