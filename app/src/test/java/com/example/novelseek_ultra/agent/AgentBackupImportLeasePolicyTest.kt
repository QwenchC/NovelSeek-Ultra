package com.example.novelseek_ultra.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackupImportLeasePolicyTest {
    @Test
    fun onlyAFullyIdleControllerCanAcquireTheImportLease() {
        val idle = AgentBackupImportLeasePolicy.Snapshot()
        assertTrue(AgentBackupImportLeasePolicy.canAcquire(idle))
        listOf(
            idle.copy(runIsActive = true),
            idle.copy(hasIncompleteJob = true),
            idle.copy(hasGate = true),
            idle.copy(hasPendingReview = true),
            idle.copy(recoveryPending = true),
            idle.copy(transitionPending = true),
            idle.copy(importPending = true),
            idle.copy(controllerClosed = true),
        ).forEach { assertFalse(AgentBackupImportLeasePolicy.canAcquire(it)) }
    }

    @Test
    fun stoppedUiDoesNotHideAnUnfinishedJobOrAnUnresolvedGate() {
        assertFalse(AgentBackupImportLeasePolicy.canAcquire(
            AgentBackupImportLeasePolicy.Snapshot(runIsActive = false, hasIncompleteJob = true),
        ))
        assertFalse(AgentBackupImportLeasePolicy.canAcquire(
            AgentBackupImportLeasePolicy.Snapshot(runIsActive = false, hasGate = true),
        ))
        assertFalse(AgentBackupImportLeasePolicy.canAcquire(
            AgentBackupImportLeasePolicy.Snapshot(runIsActive = false, hasPendingReview = true),
        ))
        assertFalse(AgentBackupImportLeasePolicy.canAcquire(
            AgentBackupImportLeasePolicy.Snapshot(runIsActive = false, recoveryPending = true),
        ))
    }

    @Test
    fun onlyTheCurrentLeaseMayReleaseTheFence() {
        assertTrue(AgentBackupImportLeasePolicy.ownsLease(12L, 12L, importPending = true))
        assertFalse(AgentBackupImportLeasePolicy.ownsLease(11L, 12L, importPending = true))
        assertFalse(AgentBackupImportLeasePolicy.ownsLease(12L, 12L, importPending = false))
        assertFalse(AgentBackupImportLeasePolicy.ownsLease(12L, null, importPending = true))
        assertFalse(AgentBackupImportLeasePolicy.ownsLease(0L, 0L, importPending = true))
    }

    @Test
    fun queuedOldSnapshotsCannotCrossNewWritesSessionSwitchesOrImports() {
        fun allowed(snapshot: Long = 2L, current: Long = 2L, transition: Long = 8L, currentTransition: Long = 8L,
                    importing: Boolean = false, closed: Boolean = false) =
            AgentBackupImportLeasePolicy.canPersistSnapshot(snapshot, current, transition, currentTransition, importing, closed)

        assertTrue(allowed())
        assertFalse(allowed(snapshot = 1L)) // A newer snapshot has already been captured.
        assertFalse(allowed(currentTransition = 9L)) // Switch/delete/import invalidated this identity.
        assertFalse(allowed(importing = true))
        assertFalse(allowed(closed = true)) // An obsolete ViewModel must not write another owner's state.
    }
}
