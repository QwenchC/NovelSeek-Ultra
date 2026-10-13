package com.example.novelseek_ultra.data.writing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterMaintenanceBarrierTest {
    @Test
    fun noEnabledMaintenanceCompletesWithoutExtraWork() = runBlocking {
        var factoriesStarted = 0

        val completed = ChapterMaintenanceBarrier.complete(1_000) {
            factoriesStarted++
            emptyList()
        }

        assertTrue(completed)
        assertEquals(1, factoriesStarted)
    }

    @Test
    fun nextStepWaitsForEveryRegisteredSourceChangingJob() = runBlocking {
        withTimeout(2_000) {
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val allowFirstWrite = CompletableDeferred<Unit>()
            val allowSecondWrite = CompletableDeferred<Unit>()
            val firstWritten = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            var nextSteps = 0
            val barrier = async {
                val completed = ChapterMaintenanceBarrier.complete(1_500) { scope ->
                    listOf(
                        scope.launch {
                            firstStarted.complete(Unit)
                            allowFirstWrite.await()
                            writes += "summary"
                            firstWritten.complete(Unit)
                        },
                        scope.launch {
                            secondStarted.complete(Unit)
                            allowSecondWrite.await()
                            writes += "entities"
                        },
                    )
                }
                nextSteps++
                completed
            }

            firstStarted.await()
            secondStarted.await()
            assertEquals(0, nextSteps)
            allowFirstWrite.complete(Unit)
            firstWritten.await()
            assertFalse(barrier.isCompleted)
            assertEquals(0, nextSteps)
            allowSecondWrite.complete(Unit)

            assertTrue(barrier.await())
            assertEquals(listOf("summary", "entities"), writes)
            assertEquals(1, nextSteps)
        }
    }

    @Test
    fun unlistedChildrenInMaintenanceScopeAreAlsoJoined() = runBlocking {
        withTimeout(2_000) {
            val started = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            var written = false
            val barrier = async {
                ChapterMaintenanceBarrier.complete(1_500) { scope ->
                    scope.launch {
                        started.complete(Unit)
                        allowWrite.await()
                        written = true
                    }
                    emptyList()
                }
            }

            started.await()
            assertFalse(barrier.isCompleted)
            allowWrite.complete(Unit)

            assertTrue(barrier.await())
            assertTrue(written)
        }
    }

    @Test
    fun timeoutCancelsAndJoinsCleanupBeforeReturningWithoutLateWrite() = runBlocking {
        withTimeout(2_000) {
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanupToFinish = CompletableDeferred<Unit>()
            val allowProviderToFinish = CompletableDeferred<Unit>()
            var cleanupFinished = false
            var writes = 0
            lateinit var child: Job
            val barrier = async {
                ChapterMaintenanceBarrier.complete(50) { scope ->
                    child = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            allowProviderToFinish.await()
                            writes++
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                allowCleanupToFinish.await()
                                cleanupFinished = true
                            }
                        }
                    }
                    listOf(child)
                }
            }

            cleanupStarted.await()
            try {
                assertFalse(barrier.isCompleted)
                assertTrue(child.isCancelled)
            } finally {
                allowCleanupToFinish.complete(Unit)
            }

            assertFalse(barrier.await())
            allowProviderToFinish.complete(Unit)
            assertTrue(child.isCompleted)
            assertTrue(cleanupFinished)
            assertEquals(0, writes)
        }
    }

    @Test
    fun parentCancellationCancelsNestedChildrenAndJoinsBeforeEnding() = runBlocking {
        withTimeout(2_000) {
            val nestedStarted = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanupToFinish = CompletableDeferred<Unit>()
            val allowProviderToFinish = CompletableDeferred<Unit>()
            var cleanupFinished = false
            var writes = 0
            lateinit var parent: Job
            lateinit var nested: Job
            val barrier = async {
                ChapterMaintenanceBarrier.complete(10_000) { scope ->
                    parent = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        nested = launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                nestedStarted.complete(Unit)
                                allowProviderToFinish.await()
                                writes++
                            } finally {
                                withContext(NonCancellable) {
                                    cleanupStarted.complete(Unit)
                                    allowCleanupToFinish.await()
                                    cleanupFinished = true
                                }
                            }
                        }
                    }
                    listOf(parent)
                }
            }

            nestedStarted.await()
            barrier.cancel()
            cleanupStarted.await()
            try {
                assertFalse(barrier.isCompleted)
                assertTrue(parent.isCancelled)
                assertTrue(nested.isCancelled)
            } finally {
                allowCleanupToFinish.complete(Unit)
            }
            barrier.join()
            allowProviderToFinish.complete(Unit)

            assertTrue(barrier.isCancelled)
            assertTrue(parent.isCompleted)
            assertTrue(nested.isCompleted)
            assertTrue(cleanupFinished)
            assertEquals(0, writes)
        }
    }

    @Test
    fun factoryFailureCancelsAlreadyStartedJobsAndPropagatesOriginalError() = runBlocking {
        val failure = IllegalStateException("maintenance setup failed")
        var cancelledChild = false
        var observed: Throwable? = null

        try {
            ChapterMaintenanceBarrier.complete(1_000) { scope ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledChild = true
                    }
                }
                throw failure
            }
        } catch (error: IllegalStateException) {
            observed = error
        }

        // Coroutine stack-trace recovery may copy the throwable while retaining the original
        // as its cause; validate the failure contract without requiring the recovered wrapper.
        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        assertTrue(generateSequence(observed) { it.cause }.any { it === failure })
        assertTrue(cancelledChild)
    }
}
