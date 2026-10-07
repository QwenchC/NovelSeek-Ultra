package com.example.novelseek_ultra.agent

/** Pure admission/ownership checks for the controller's short-lived backup-import fence. */
object AgentBackupImportLeasePolicy {
    data class Snapshot(
        val runIsActive: Boolean = false,
        val hasIncompleteJob: Boolean = false,
        val hasGate: Boolean = false,
        val hasPendingReview: Boolean = false,
        val recoveryPending: Boolean = false,
        val transitionPending: Boolean = false,
        val importPending: Boolean = false,
        val controllerClosed: Boolean = false,
    )

    fun canAcquire(snapshot: Snapshot): Boolean = !snapshot.runIsActive &&
        !snapshot.hasIncompleteJob && !snapshot.hasGate && !snapshot.hasPendingReview &&
        !snapshot.recoveryPending && !snapshot.transitionPending && !snapshot.importPending &&
        !snapshot.controllerClosed

    fun ownsLease(expectedToken: Long, currentToken: Long?, importPending: Boolean): Boolean =
        importPending && expectedToken > 0L && expectedToken == currentToken

    fun canPersistSnapshot(
        expectedSnapshotRevision: Long,
        currentSnapshotRevision: Long,
        expectedTransitionRevision: Long,
        currentTransitionRevision: Long,
        importPending: Boolean,
        controllerClosed: Boolean,
    ): Boolean = !importPending && !controllerClosed &&
        expectedSnapshotRevision == currentSnapshotRevision &&
        expectedTransitionRevision == currentTransitionRevision
}
