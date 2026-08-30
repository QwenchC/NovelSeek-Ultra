package com.example.novelseek_ultra.agent

import android.os.SystemClock
import android.util.Base64
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.ai.ChatMessage
import com.example.novelseek_ultra.data.ai.StreamUsage
import com.example.novelseek_ultra.data.ai.WebSearchService
import com.example.novelseek_ultra.data.ai.isUsableApiConfig
import com.example.novelseek_ultra.data.ai.launchWithFailureBoundary
import com.example.novelseek_ultra.data.model.AgentIndex
import com.example.novelseek_ultra.data.model.AgentContextUsage
import com.example.novelseek_ultra.data.model.AgentPendingReview
import com.example.novelseek_ultra.data.model.AgentReasoningLevels
import com.example.novelseek_ultra.data.model.AgentSession
import com.example.novelseek_ultra.data.model.AgentSessionCacheMetrics
import com.example.novelseek_ultra.data.model.AgentSessionMemory
import com.example.novelseek_ultra.data.model.AgentSessionMeta
import com.example.novelseek_ultra.data.model.AgentStep
import com.example.novelseek_ultra.data.model.ChapterPromo
import com.example.novelseek_ultra.data.model.CandidateChapter
import com.example.novelseek_ultra.data.model.Container
import com.example.novelseek_ultra.data.model.ContainerEntry
import com.example.novelseek_ultra.data.model.CoverImageItem
import com.example.novelseek_ultra.data.model.CultivationRealm
import com.example.novelseek_ultra.data.model.Illustration
import com.example.novelseek_ultra.data.model.GenerationRun
import com.example.novelseek_ultra.data.model.Project
import com.example.novelseek_ultra.data.model.Volume
import com.example.novelseek_ultra.data.nowIso
import com.example.novelseek_ultra.ui.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resume

internal data class AgentContextUsageRefreshIdentity(
    val sessionId: String,
    val steps: List<AgentStep>,
    val memory: AgentSessionMemory,
    val activePlan: AgentPlan?,
    val reasoningLevel: String,
    val engineMode: String,
)

internal object AgentContextUsageRefreshPolicy {
    fun isCurrent(
        expectedRevision: Long,
        currentRevision: Long,
        transitionPending: Boolean,
        expected: AgentContextUsageRefreshIdentity,
        current: AgentContextUsageRefreshIdentity,
    ): Boolean =
        expectedRevision == currentRevision &&
            !transitionPending &&
            expected.sessionId == current.sessionId &&
            expected.steps === current.steps &&
            expected.memory == current.memory &&
            expected.activePlan == current.activePlan &&
            expected.reasoningLevel == current.reasoningLevel &&
            expected.engineMode == current.engineMode
}

/**
 * The autonomous writing agent. It drives the app's operations by repeatedly asking the active text
 * model for the next action (a strict JSON object naming one [AgentTool]), executing it, and
 * feeding the result back — a ReAct-style loop that works on any OpenAI-compatible chat model
 * (no provider function-calling required).
 *
 * Semi-autonomous: irreversible / expensive tools ([AgentTool.sensitive]) pause for user confirm.
 * The user can stop, continue, answer questions, and inject new instructions at any time. A
 * foreground-service heartbeat and durable checkpoint diagnose stalled/process-recreated runs;
 * uncertain tool side effects remain interrupted until they are explicitly reconciled.
 */
class AgentController(
    private val vm: AppViewModel,
    private val repo: AppRepository,
    private val scope: CoroutineScope,
    private val appContext: android.content.Context,
) {
    enum class Status {
        IDLE,
        RUNNING,
        AWAITING_USER,
        AWAITING_CONFIRM,
        AWAITING_REVIEW,
        DONE,
        ERROR,
        STOPPED,
    }

    private val web = WebSearchService()
    private val _steps = MutableStateFlow<List<AgentStep>>(emptyList())
    val steps: StateFlow<List<AgentStep>> = _steps.asStateFlow()
    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()
    /** Text shown to the user while the run is paused for an answer/confirmation. */
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()
    private val _pendingReview = MutableStateFlow<AgentPendingReview?>(null)
    val pendingReview: StateFlow<AgentPendingReview?> = _pendingReview.asStateFlow()
    /** Live partial text while a long generation tool (outline/chapter/revise) is streaming. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()
    /** When true (and a project is locked), sensitive steps run without per-step confirmation. */
    private val _autoApprove = MutableStateFlow(false)
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()
    /** All sessions (newest-first) + which one is current. */
    private val _sessions = MutableStateFlow<List<AgentSessionMeta>>(emptyList())
    val sessions: StateFlow<List<AgentSessionMeta>> = _sessions.asStateFlow()
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    private val _engineMode = MutableStateFlow(ENGINE_CLASSIC)
    val engineMode: StateFlow<String> = _engineMode.asStateFlow()
    private val _reasoningLevel = MutableStateFlow(AgentReasoningLevels.MEDIUM)
    val reasoningLevel: StateFlow<String> = _reasoningLevel.asStateFlow()
    private val _sessionCacheMetrics = MutableStateFlow(AgentSessionCacheMetrics())
    val sessionCacheMetrics: StateFlow<AgentSessionCacheMetrics> = _sessionCacheMetrics.asStateFlow()
    private val _contextMemory = MutableStateFlow(AgentSessionMemory())
    private val _contextUsage = MutableStateFlow(AgentContextUsage())
    val contextUsage: StateFlow<AgentContextUsage> = _contextUsage.asStateFlow()
    private val _activePlan = MutableStateFlow<AgentPlan?>(null)
    val activePlan: StateFlow<AgentPlan?> = _activePlan.asStateFlow()

    private data class ContextCompactionSnapshot(
        val sessionId: String,
        val steps: List<AgentStep>,
        val memory: AgentSessionMemory,
        val activePlan: AgentPlan?,
        val pendingReview: AgentPendingReview?,
        val pendingPrompt: String?,
    )

    private data class ContextUsageRefreshSnapshot(
        val identity: AgentContextUsageRefreshIdentity,
        val activeProjectId: String?,
        val pendingReview: AgentPendingReview?,
        val config: com.example.novelseek_ultra.data.model.TextModelConfig,
    )

    private data class RequiredExecutorContext(
        val text: String,
        val stepIds: Set<String>,
    )

    private data class AutomaticRecoveryRequest(
        val sessionId: String,
        val revision: Long,
        val durableRunToken: String,
        val runInstanceToken: Long,
        val requiredStatus: Status,
        val observedJob: Job?,
        val jobToJoin: Job?,
        val attempt: Int,
        val reason: String,
    )

    /** Identifies one installed execution. Stop/recovery/new launches invalidate it atomically. */
    private data class RunClaim(
        val controlRevision: Long,
        val instanceToken: Long,
        val sessionId: String?,
    )

    @Volatile
    private var currentTitle: String = ""
    @Volatile
    private var currentCreatedAt: String = ""

    private val stateMutationLock = Any()
    // Shared across controller instances so an Activity/ViewModel handover cannot invert two
    // independent session writers even before the old instance observes its closed fence.
    private val persistenceLock = GLOBAL_AGENT_PERSISTENCE_LOCK
    private var job: Job? = null
    @Volatile
    private var gate: CompletableDeferred<String?>? = null
    private var sessionTransitionJob: Job? = null
    private var interjectionRestartJob: Job? = null
    private var contextUsageRefreshJob: Job? = null
    private var contextUsageRefreshRevision: Long = 0L
    private var livenessRecoveryJob: Job? = null
    private var livenessRecoveryInProgress: Boolean = false
    @Volatile
    private var runCheckpoint: AgentRunCheckpoint = AgentRunCheckpoint()
    @Volatile
    private var lastProgressElapsedRealtime: Long = 0L
    @Volatile
    private var runInstanceToken: Long = 0L
    @Volatile
    private var streamingRunClaim: RunClaim? = null
    @Volatile
    private var closedOrObsolete: Boolean = false
    @Volatile
    private var sessionTransitionRevision: Long = 0
    @Volatile
    private var sessionTransitionPending: Boolean = false
    @Volatile
    private var runControlRevision: Long = 0
    @Volatile
    private var instructionRevision: Long = 0

    // Kotlin runs property initializers and init blocks in source order. Session restoration calls
    // sensitiveToolNames(), so these lazy delegates must exist before the init block can load a
    // persisted session. Keeping the expensive tool construction lazy still avoids building the
    // table for a brand-new session until it is actually needed.
    private val TOOLS: List<AgentTool> by lazy { buildTools() }
    private val mutatingToolNames: Set<String> by lazy {
        TOOLS.asSequence()
            .filter { it.effect == AgentToolEffect.MUTATING }
            .map { it.name }
            .toSet()
    }

    init {
        synchronized(ACTIVE_CONTROLLER_LOCK) {
            val previous = active?.takeUnless { it === this }
            // Fence the previous writer, then drain the process-wide writer before loading state.
            // This guarantees initialization observes the final durable snapshot from the old owner
            // and no already-started old write can land after the new owner is published.
            previous?.closedOrObsolete = true
            synchronized(GLOBAL_AGENT_PERSISTENCE_LOCK) {
                // Intentionally empty: acquiring the monitor drains an in-flight old writer.
            }
            previous?.cancelObsoleteLocalWork()

            val idx = repo.loadAgentIndex()
            _sessions.value = idx.items.sortedByDescending { it.createdAt }
            val curId = idx.currentId ?: idx.items.firstOrNull()?.id
            if (curId != null && repo.loadAgentSessionById(curId) != null) loadSession(curId)
            else newSession()
            active = this
        }
        // Only active network/tool work consumes the dataSync FGS allowance. Paused gates keep a
        // normal status notification and are fully restorable from the durable session.
        scope.launch {
            _status.collect { st ->
                if (active !== this@AgentController) return@collect
                when (st) {
                    Status.RUNNING ->
                        AgentForegroundService.start(appContext, notifText(st), holdLocks = true)
                    Status.AWAITING_USER,
                    Status.AWAITING_CONFIRM,
                    Status.AWAITING_REVIEW,
                    -> AgentForegroundService.showWaiting(appContext, notifText(st))
                    else -> AgentForegroundService.stop(appContext)
                }
            }
        }
        scope.launch {
            repo.generationRunRevision.collect {
                reconcilePendingReview()
            }
        }
        scope.launch {
            val transcriptInputs = combine(
                _steps,
                _activePlan,
                _reasoningLevel,
                _engineMode,
                _contextMemory,
            ) { _, _, _, _, _ -> Unit }
            combine(
                transcriptInputs,
                _currentSessionId,
                _activeProjectId,
                _pendingReview,
            ) { _, _, _, _ -> Unit }.collect {
                refreshContextUsage()
            }
        }
    }

    private fun notifText(st: Status): String = when (st) {
        Status.AWAITING_USER -> "等待你的回复…"
        Status.AWAITING_CONFIRM -> "等待确认操作…"
        Status.AWAITING_REVIEW -> "章节候选稿等待审核…"
        else -> _steps.value.lastOrNull()?.text?.take(50) ?: "执行中"
    }

    // ── control surface ──────────────────────────────────────────────────────

    fun start(command: String) = sendInput(command)

    fun continueRun() {
        val reviewBlocked = synchronized(stateMutationLock) {
            if (_pendingReview.value == null) {
                false
            } else {
                _status.value = Status.AWAITING_REVIEW
                _pendingPrompt.value = "请先审核章节候选稿；采用或拒绝后才能继续执行。"
                true
            }
        }
        if (reviewBlocked) {
            persist()
            return
        }
        val expectedRevision = synchronized(stateMutationLock) {
            if (_status.value == Status.RUNNING) return
            if (_steps.value.isEmpty()) return
            if (sessionTransitionPending) return
            if (livenessRecoveryInProgress) return
            runControlRevision
        }
        launchLoop(expectedRevision)
    }

    /** Send an instruction. During a question/confirm it answers; while running it's injected as a
     *  new instruction; while paused it's added and the run resumes. */
    fun sendInput(text: String) {
        val t = text.trim()
        if (t.isEmpty() || sessionTransitionPending) return
        var gateCompletion: Pair<CompletableDeferred<String?>, String?>? = null
        var restartRequest: Pair<Job, Long>? = null
        var launchRevision: Long? = null
        synchronized(stateMutationLock) {
            if (sessionTransitionPending) return
            when (_status.value) {
                Status.AWAITING_USER -> {
                    val askActionId = _steps.value.lastOrNull {
                        it.type == AgentStep.ACTION &&
                            it.tool == ASK_USER_ACTION &&
                            it.actionStatus == AgentStep.ACTION_SUCCEEDED
                    }?.id.orEmpty()
                    addStep(AgentStep.ANSWER, t, resultForActionId = askActionId)
                    _pendingPrompt.value = null
                    val currentGate = gate
                    if (currentGate != null) {
                        gateCompletion = currentGate to t
                    } else {
                        // A question restored after process death has no suspended coroutine.
                        _status.value = Status.STOPPED
                        launchRevision = runControlRevision
                    }
                }
                Status.AWAITING_CONFIRM -> {
                    // Free-form guidance declines the exact pending action and forces replanning.
                    addStep(AgentStep.USER, t)
                    _pendingPrompt.value = null
                    gate?.let { gateCompletion = it to "no:$t" }
                }
                Status.AWAITING_REVIEW -> {
                    addStep(AgentStep.USER, t)
                    addStep(
                        AgentStep.MESSAGE,
                        "当前章节候选稿仍待审核。为避免把未采用内容当作正式正文，智能体不会继续执行；请先打开候选稿审核。",
                    )
                    _pendingPrompt.value = "请先审核章节候选稿；采用或拒绝后才能继续执行。"
                }
                Status.RUNNING -> {
                    if (livenessRecoveryInProgress) {
                        livenessRecoveryJob?.cancel()
                        livenessRecoveryJob = null
                        livenessRecoveryInProgress = false
                    }
                    addStep(AgentStep.USER, t)
                    val runningJob = job?.takeUnless { it.isCompleted }
                    if (runningJob != null) {
                        runControlRevision += 1
                        val restartRevision = runControlRevision
                        runningJob.cancel()
                        val recovery = AgentRunRecovery.recover(_steps.value)
                        _steps.value = recovery.steps
                        blockActivePlanForInterruptedActions(recovery.interruptedActionIds)
                        _streamingText.value = ""
                        streamingRunClaim = null
                        _status.value = Status.STOPPED
                        addStep(
                            AgentStep.OBSERVATION,
                            "用户插入了新指令；旧执行已取消。越过执行边界的动作可能部分完成，" +
                                "系统不会静默重试，将按最新指令重新规划。",
                        )
                        restartRequest = runningJob to restartRevision
                    } else {
                        _status.value = Status.STOPPED
                        launchRevision = runControlRevision
                    }
                }
                else -> {
                    addStep(AgentStep.USER, t)
                    _status.value = Status.STOPPED
                    launchRevision = runControlRevision
                }
            }
            // Publish the revision after the matching transcript entry is visible.
            instructionRevision++
        }
        gateCompletion?.let { (deferred, value) -> deferred.complete(value) }
        persist()
        restartRequest?.let { (runningJob, restartRevision) ->
            scheduleRestartAfterInterjection(runningJob, restartRevision)
        }
        launchRevision?.let { launchLoop(it) }
    }

    fun confirm(approve: Boolean) {
        val deferred = synchronized(stateMutationLock) {
            if (_status.value != Status.AWAITING_CONFIRM) return
            _pendingPrompt.value = null
            gate
        }
        deferred?.complete(if (approve) "yes" else "no")
    }

    fun stop() {
        val activeJob = synchronized(stateMutationLock) {
            runControlRevision += 1
            interjectionRestartJob?.cancel()
            interjectionRestartJob = null
            livenessRecoveryJob?.cancel()
            livenessRecoveryJob = null
            livenessRecoveryInProgress = false
            runCheckpoint = AgentRunCheckpoint()
            lastProgressElapsedRealtime = 0L
            job
        }
        activeJob?.cancel()
        gate?.complete(null)
        synchronized(stateMutationLock) {
            gate = null
            _pendingPrompt.value = null
            _streamingText.value = ""
            streamingRunClaim = null
            val recovery = AgentRunRecovery.recover(_steps.value)
            _steps.value = recovery.steps
            blockActivePlanForInterruptedActions(recovery.interruptedActionIds)
            if (
                _status.value == Status.RUNNING ||
                _status.value == Status.AWAITING_USER ||
                _status.value == Status.AWAITING_CONFIRM ||
                _status.value == Status.AWAITING_REVIEW
            ) {
                _status.value = Status.STOPPED
            }
        }
        persist()
    }

    /** Release this controller without allowing an obsolete ViewModel to clear a newer instance. */
    fun close() {
        val wasActive = synchronized(ACTIVE_CONTROLLER_LOCK) {
            if (active !== this) {
                closedOrObsolete = true
                false
            } else {
                // Keep ownership stable until the final checkpoint is written. A newer controller
                // cannot take over and then be overwritten by this instance's older snapshot.
                stop()
                closedOrObsolete = true
                active = null
                true
            }
        }
        if (wasActive) {
            AgentForegroundService.stop(appContext)
        } else {
            // The ViewModel is obsolete. Cancel only its local work; never persist or touch the
            // process-wide service owned by the newer active controller.
            cancelObsoleteLocalWork()
        }
    }

    private fun cancelObsoleteLocalWork() {
        closedOrObsolete = true
        val obsoleteJob = synchronized(stateMutationLock) {
            runControlRevision += 1
            interjectionRestartJob?.cancel()
            interjectionRestartJob = null
            livenessRecoveryJob?.cancel()
            livenessRecoveryJob = null
            sessionTransitionJob?.cancel()
            sessionTransitionJob = null
            contextUsageRefreshJob?.cancel()
            contextUsageRefreshJob = null
            job
        }
        obsoleteJob?.cancel()
        gate?.complete(null)
    }

    /** Clear the CURRENT session's chain (keeps the session). */
    fun clearSession() = mutateSessionAfterRunStops {
        _steps.value = emptyList()
        _engineMode.value = sanitizeEngineMode(vm.agentEngine())
        _reasoningLevel.value = AgentReasoningLevels.normalize(vm.dualAgentReasoningLevel())
        _sessionCacheMetrics.value = AgentSessionCacheMetrics()
        _contextMemory.value = AgentSessionMemory()
        _contextUsage.value = AgentContextUsage()
        _activePlan.value = null
        runCheckpoint = AgentRunCheckpoint()
        lastProgressElapsedRealtime = 0L
        runInstanceToken = 0L
        gate = null
        _pendingPrompt.value = null
        _pendingReview.value = null
        instructionRevision = 0
        _status.value = Status.IDLE
        persist()
    }

    /** Lock the session to a project the agent operates on. Changing scope revokes the old grant. */
    fun lockProject(projectId: String?) {
        if (job?.isCompleted == false || sessionTransitionPending) return
        val previousId = _activeProjectId.value
        updateLockedProject(projectId)
        if (previousId != _activeProjectId.value) instructionRevision++
        persist()
    }

    /** Pre-authorize auto-continue only when it can be bound to an existing locked project. */
    fun setAutoApprove(enabled: Boolean) {
        _autoApprove.value = enabled && validLockedProjectId() != null
        persist()
    }

    /** Apply a changed global preference immediately only when this conversation is still blank. */
    fun refreshDefaultsForBlankSession() {
        val changed = synchronized(stateMutationLock) {
            if (_steps.value.isNotEmpty() || job?.isCompleted == false || sessionTransitionPending) {
                false
            } else {
                val selectedEngine = sanitizeEngineMode(vm.agentEngine())
                val selectedReasoning = AgentReasoningLevels.normalize(
                    vm.dualAgentReasoningLevel(),
                )
                if (
                    _engineMode.value == selectedEngine &&
                    _reasoningLevel.value == selectedReasoning
                ) {
                    false
                } else {
                    _engineMode.value = selectedEngine
                    _reasoningLevel.value = selectedReasoning
                    _activePlan.value = null
                    true
                }
            }
        }
        if (changed) persist()
    }

    /**
     * Change only this conversation's dual-agent depth; the Settings default is left untouched.
     * An unfinished plan is discarded so the next model request replans with one consistent depth.
     */
    fun setSessionReasoningLevel(level: String): Boolean =
        setSessionReasoningLevel(_currentSessionId.value, level)

    fun setSessionReasoningLevel(expectedSessionId: String?, level: String): Boolean {
        var changed = false
        val accepted = synchronized(stateMutationLock) {
            val runIsActive = _status.value == Status.RUNNING ||
                _status.value == Status.AWAITING_USER ||
                _status.value == Status.AWAITING_CONFIRM ||
                _status.value == Status.AWAITING_REVIEW
            if (
                expectedSessionId == null ||
                _currentSessionId.value != expectedSessionId ||
                _engineMode.value != ENGINE_DUAL ||
                runIsActive ||
                job?.isCompleted == false ||
                sessionTransitionPending
            ) {
                false
            } else {
                val normalized = AgentReasoningLevels.normalize(level)
                if (_reasoningLevel.value != normalized) {
                    _reasoningLevel.value = normalized
                    if (_activePlan.value?.isComplete == false) {
                        _activePlan.value = null
                    }
                    changed = true
                }
                true
            }
        }
        if (changed) persist()
        return accepted
    }

    /** Manually compact the eligible terminal prefix of the selected idle conversation. */
    fun compactSessionContext(expectedSessionId: String): Boolean =
        compactSessionContextInternal(
            expectedSessionId = expectedSessionId,
            allowActiveRequestBoundary = false,
            refreshAfter = true,
        )

    /** Re-estimate the next executor request without mutating or compacting the conversation. */
    fun refreshContextUsage() {
        val refreshJob = synchronized(stateMutationLock) {
            contextUsageRefreshRevision += 1
            val revision = contextUsageRefreshRevision
            contextUsageRefreshJob?.cancel()
            val sessionId = _currentSessionId.value?.takeUnless { sessionTransitionPending }
            if (sessionId == null) {
                contextUsageRefreshJob = null
                _contextUsage.value = AgentContextUsage()
                null
            } else {
                val snapshot = ContextUsageRefreshSnapshot(
                    identity = AgentContextUsageRefreshIdentity(
                        sessionId = sessionId,
                        steps = _steps.value,
                        memory = _contextMemory.value,
                        activePlan = _activePlan.value,
                        reasoningLevel = _reasoningLevel.value,
                        engineMode = _engineMode.value,
                    ),
                    activeProjectId = _activeProjectId.value,
                    pendingReview = _pendingReview.value,
                    config = vm.activeTextModelConfig(),
                )
                scope.launch(
                    context = Dispatchers.Default,
                    start = CoroutineStart.LAZY,
                ) {
                    var normalizedMemory = AgentSessionMemory()
                    val usage = try {
                        normalizedMemory = AgentContextCompressor.normalizeMemory(
                            snapshot.identity.steps,
                            snapshot.identity.memory,
                            sensitiveToolNames(),
                        )
                        currentCoroutineContext().ensureActive()
                        val prepared = prepareExecutorRequest(
                            allowAutoCompression = false,
                            refreshSnapshot = snapshot.copy(
                                identity = snapshot.identity.copy(memory = normalizedMemory),
                            ),
                            publishUsage = false,
                        )
                        measuredContextUsage(prepared.measurement, normalizedMemory)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        AgentContextUsage(
                            compressionCount = normalizedMemory.compressionCount,
                            lastCompressedAt = normalizedMemory.lastCompressedAt,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    publishContextUsageRefresh(revision, snapshot, usage)
                }.also { contextUsageRefreshJob = it }
            }
        }
        refreshJob?.start()
    }

    private fun publishContextUsageRefresh(
        revision: Long,
        snapshot: ContextUsageRefreshSnapshot,
        usage: AgentContextUsage,
    ) {
        synchronized(stateMutationLock) {
            val currentSessionId = _currentSessionId.value ?: return
            val currentIdentity = AgentContextUsageRefreshIdentity(
                sessionId = currentSessionId,
                steps = _steps.value,
                memory = _contextMemory.value,
                activePlan = _activePlan.value,
                reasoningLevel = _reasoningLevel.value,
                engineMode = _engineMode.value,
            )
            if (
                !AgentContextUsageRefreshPolicy.isCurrent(
                    expectedRevision = revision,
                    currentRevision = contextUsageRefreshRevision,
                    transitionPending = sessionTransitionPending,
                    expected = snapshot.identity,
                    current = currentIdentity,
                ) ||
                snapshot.activeProjectId != _activeProjectId.value ||
                snapshot.pendingReview != _pendingReview.value
            ) {
                return
            }
            _contextUsage.value = usage
        }
    }

    private fun compactSessionContextInternal(
        expectedSessionId: String,
        allowActiveRequestBoundary: Boolean,
        refreshAfter: Boolean,
    ): Boolean {
        val sensitiveTools = sensitiveToolNames()
        val snapshot = synchronized(stateMutationLock) {
            val runIsActive = _status.value == Status.RUNNING ||
                _status.value == Status.AWAITING_USER ||
                _status.value == Status.AWAITING_CONFIRM ||
                _status.value == Status.AWAITING_REVIEW
            if (
                closedOrObsolete ||
                _currentSessionId.value != expectedSessionId ||
                sessionTransitionPending ||
                (!allowActiveRequestBoundary && (runIsActive || job?.isCompleted == false))
            ) {
                null
            } else {
                ContextCompactionSnapshot(
                    sessionId = expectedSessionId,
                    steps = _steps.value,
                    memory = _contextMemory.value,
                    activePlan = _activePlan.value,
                    pendingReview = _pendingReview.value,
                    pendingPrompt = _pendingPrompt.value,
                )
            }
        } ?: return false

        // Digesting and summarizing a long transcript can be expensive. Do it outside the state
        // lock, then commit only if every input that controls the safe boundary is unchanged.
        val next = AgentContextCompressor.compact(
            steps = snapshot.steps,
            memory = snapshot.memory,
            activePlan = snapshot.activePlan,
            pendingReview = snapshot.pendingReview,
            hasPendingPrompt = snapshot.pendingPrompt != null,
            sensitiveTools = sensitiveTools,
            compressedAt = nowIso(),
        ) ?: return false
        val committed = synchronized(stateMutationLock) {
            if (
                closedOrObsolete ||
                _currentSessionId.value != snapshot.sessionId ||
                sessionTransitionPending ||
                _steps.value !== snapshot.steps ||
                _contextMemory.value != snapshot.memory ||
                _activePlan.value != snapshot.activePlan ||
                _pendingReview.value != snapshot.pendingReview ||
                _pendingPrompt.value != snapshot.pendingPrompt
            ) {
                false
            } else {
                _contextMemory.value = next
                true
            }
        }
        if (!committed) return false
        // Compression is disposable derived state. Only persist it while the same memory CAS is
        // still current; otherwise a later clear/delete/switch wins and must never be overwritten
        // or resurrected by this older background compaction.
        val saved = synchronized(stateMutationLock) {
            if (
                closedOrObsolete ||
                _currentSessionId.value != snapshot.sessionId ||
                sessionTransitionPending ||
                _contextMemory.value != next
            ) {
                false
            } else {
                // The established lock order is state -> persistence. Holding the transition lock
                // across this one atomic save prevents delete/switch from starting between the CAS
                // check and the write, which could otherwise recreate a just-deleted session.
                synchronized(persistenceLock) {
                    if (closedOrObsolete) {
                        false
                    } else {
                        repo.saveAgentSessionById(buildCurrentSessionSnapshot(snapshot.sessionId))
                        true
                    }
                }
            }
        }
        if (!saved) return false
        if (refreshAfter) refreshContextUsage()
        return true
    }

    // ── session management ──────────────────────────────────────────────────────

    private fun loadSession(id: String) {
        val persistedSession = repo.loadAgentSessionById(id) ?: return
        val sessionRuns = repo.projects.value.flatMap { project ->
            repo.listGenerationRuns(project.id).filter {
                it.initiator == GenerationRun.INITIATOR_AGENT &&
                    it.agentSessionId == persistedSession.id
            }
        }
        val s = AgentGenerationReviewRecovery.recover(persistedSession, sessionRuns)
        val generationReviewRecovered = s != persistedSession
        _currentSessionId.value = s.id
        currentTitle = s.title
        currentCreatedAt = s.createdAt.ifBlank { nowIso() }
        _engineMode.value = sanitizeEngineMode(s.engineMode)
        _reasoningLevel.value = AgentReasoningLevels.normalize(s.reasoningLevel)
        _sessionCacheMetrics.value = s.cacheMetrics.sanitized()
        val recovery = AgentRunRecovery.recover(s.steps)
        _steps.value = recovery.steps
        _contextMemory.value = AgentContextCompressor.normalizeMemory(
            recovery.steps,
            s.memory,
            sensitiveToolNames(),
        )
        _contextUsage.value = AgentContextUsage(
            compressionCount = _contextMemory.value.compressionCount,
            lastCompressedAt = _contextMemory.value.lastCompressedAt,
        )
        val validLockedProjectId = s.lockedProjectId?.takeIf { repo.project(it) != null }
        _activeProjectId.value = validLockedProjectId
        _activePlan.value = if (
            _engineMode.value == ENGINE_DUAL &&
            (s.lockedProjectId == null || validLockedProjectId != null)
        ) {
            s.activePlan?.let { ensurePlanIdentity(it, s.id) }
        } else {
            null
        }
        blockActivePlanForInterruptedActions(recovery.interruptedActionIds)
        _autoApprove.value = s.autoApprove && _activeProjectId.value != null
        val tailQuestion = recovery.steps.lastOrNull()?.takeIf { it.type == AgentStep.QUESTION }
        val restoreQuestion = !recovery.hasInterruptedActions && tailQuestion != null &&
            (s.runStatus == "awaiting_user" || s.runStatus == "idle")
        _pendingReview.value = s.pendingReview
        val savedCheckpoint = s.runCheckpoint.sanitized()
        val shouldAutoRecover = recovery.steps.isNotEmpty() &&
            s.pendingReview == null &&
            !restoreQuestion &&
            (s.runStatus.equals("running", ignoreCase = true) || savedCheckpoint.recoveryPending)
        runCheckpoint = if (shouldAutoRecover) {
            savedCheckpoint.copy(
                phase = AgentRunPhase.RECOVERING,
                recoveryPending = true,
                lastRecoveryReason = savedCheckpoint.lastRecoveryReason.ifBlank {
                    "应用进程重建后，上次运行没有留下终态"
                },
            )
        } else {
            savedCheckpoint.copy(phase = AgentRunPhase.IDLE, recoveryPending = false)
        }
        lastProgressElapsedRealtime = 0L
        runInstanceToken = 0L
        _status.value = when {
            recovery.hasInterruptedActions -> Status.STOPPED
            s.pendingReview != null && s.runStatus == "stopped" -> Status.STOPPED
            s.pendingReview != null -> Status.AWAITING_REVIEW
            restoreQuestion -> Status.AWAITING_USER
            else -> restoredStatus(s.runStatus)
        }
        _streamingText.value = ""
        streamingRunClaim = null
        _pendingPrompt.value = when {
            s.pendingReview != null ->
                s.pendingPrompt ?: "请先审核章节候选稿；采用或拒绝后才能继续执行。"
            restoreQuestion -> s.pendingPrompt ?: tailQuestion?.text
            else -> null
        }
        instructionRevision = _steps.value.count {
            it.type == AgentStep.USER || it.type == AgentStep.ANSWER
        }.toLong()
        if (recovery.hasInterruptedActions) {
            addStep(
                AgentStep.OBSERVATION,
                "检测到上次中断的 ${recovery.interruptedActionIds.size} 个动作，已标记为“中断待核对”。" +
                    "恢复会先重新规划；在核对完成前，任何后续写操作都会先要求确认。",
            )
        }
        if (generationReviewRecovered || recovery.hasInterruptedActions || shouldAutoRecover) {
            persist()
        }
        // A candidate may have been reviewed while this session was not selected. Reconcile on
        // load as well as on GenerationRun revisions, but do not unexpectedly auto-resume merely
        // because the user opened an old conversation.
        reconcilePendingReview(resumeAccepted = false)
        if (shouldAutoRecover) scheduleRestoredRunDiagnosis(s.id)
    }

    fun newSession() = mutateSessionAfterRunStops {
        newSessionNow()
    }

    private fun newSessionNow() {
        val id = "sess-${System.currentTimeMillis()}"
        currentTitle = "会话 ${_sessions.value.size + 1}"
        currentCreatedAt = nowIso()
        _currentSessionId.value = id
        _steps.value = emptyList()
        _activeProjectId.value = null
        _autoApprove.value = false
        _engineMode.value = sanitizeEngineMode(vm.agentEngine())
        _reasoningLevel.value = AgentReasoningLevels.normalize(vm.dualAgentReasoningLevel())
        _sessionCacheMetrics.value = AgentSessionCacheMetrics()
        _contextMemory.value = AgentSessionMemory()
        _contextUsage.value = AgentContextUsage()
        _activePlan.value = null
        runCheckpoint = AgentRunCheckpoint()
        lastProgressElapsedRealtime = 0L
        runInstanceToken = 0L
        gate = null
        _pendingPrompt.value = null
        _pendingReview.value = null
        instructionRevision = 0
        _status.value = Status.IDLE
        _streamingText.value = ""
        streamingRunClaim = null
        _sessions.update { listOf(AgentSessionMeta(id, currentTitle, currentCreatedAt)) + it }
        persist()
    }

    fun switchSession(id: String) {
        if (id == _currentSessionId.value) return
        mutateSessionAfterRunStops {
            synchronized(persistenceLock) {
                if (!closedOrObsolete) {
                    loadSession(id)
                    repo.saveAgentIndex(AgentIndex(id, _sessions.value))
                }
            }
        }
    }

    fun deleteSession(id: String) = mutateSessionAfterRunStops {
        synchronized(persistenceLock) {
            if (!closedOrObsolete) {
                // Delete, replacement selection and index update are one durable ownership unit.
                // A takeover either waits for all of it or fences it before it begins.
                repo.deleteAgentSessionById(id)
                _sessions.update { sessions -> sessions.filterNot { it.id == id } }
                if (_currentSessionId.value == id) {
                    val next = _sessions.value.firstOrNull()?.id
                    if (next != null) loadSession(next) else newSessionNow()
                }
                repo.saveAgentIndex(AgentIndex(_currentSessionId.value, _sessions.value))
            }
        }
    }

    fun renameSession(id: String, title: String) {
        synchronized(stateMutationLock) {
            if (id == _currentSessionId.value) currentTitle = title
            _sessions.update { sessions ->
                sessions.map { if (it.id == id) it.copy(title = title) else it }
            }
        }
        persist()
    }

    // ── the loop ───────────────────────────────────────────────────────────────

    /**
     * Atomically claims the single run slot. [expectedRevision] makes Stop/session transitions win
     * even when they happen after a caller decides to launch but before the coroutine is created.
     */
    private fun launchLoop(
        expectedRevision: Long,
        requiredStatus: Status? = null,
        automaticRecovery: Boolean = false,
    ): Boolean {
        val accepted = synchronized(stateMutationLock) {
            if (expectedRevision != runControlRevision) return@synchronized false
            if (sessionTransitionPending) return@synchronized false
            if (requiredStatus != null && _status.value != requiredStatus) return@synchronized false
            if (job?.isCompleted == false) return@synchronized false
            if (livenessRecoveryInProgress && !automaticRecovery) return@synchronized false

            if (!automaticRecovery) {
                runCheckpoint = runCheckpoint.copy(
                    runToken = java.util.UUID.randomUUID().toString(),
                    automaticRecoveryAttempts = 0,
                    recoveryPending = false,
                    lastRecoveryReason = "",
                )
            } else if (runCheckpoint.runToken.isBlank()) {
                runCheckpoint = runCheckpoint.copy(runToken = java.util.UUID.randomUUID().toString())
            }
            _status.value = Status.RUNNING
            runInstanceToken = if (runInstanceToken == Long.MAX_VALUE) 1L else runInstanceToken + 1L
            val runClaim = RunClaim(
                controlRevision = expectedRevision,
                instanceToken = runInstanceToken,
                sessionId = _currentSessionId.value,
            )
            touchRunProgressLocked(AgentRunPhase.STARTING)
            val launched = scope.launchWithFailureBoundary(
                context = Dispatchers.IO,
                onFailure = { error ->
                    synchronized(stateMutationLock) {
                        if (runClaimMatchesLocked(runClaim)) {
                            addStep(
                                AgentStep.ERROR,
                                "智能体后台任务异常：${error.message ?: error::class.simpleName ?: "未知错误"}",
                            )
                            _status.value = Status.ERROR
                        }
                    }
                    // A persistence failure must not become a second uncaught exception.
                    runCatching { persist() }
                },
            ) {
                try {
                    runLoop(runClaim)
                } catch (_: CancellationException) {
                    // User stop, session transition, interjection, or diagnosed stale work.
                } catch (e: Exception) {
                    synchronized(stateMutationLock) {
                        if (runClaimMatchesLocked(runClaim)) {
                            addStep(AgentStep.ERROR, e.message ?: e::class.simpleName ?: "未知错误")
                            _status.value = Status.ERROR
                        }
                    }
                }
                persist()
            }
            job = launched
            true
        }
        if (accepted) persist() // Persist RUNNING before the first network/tool boundary can vanish.
        return accepted
    }

    /**
     * Session state is shared by the loop, so switching/clearing must wait until the old coroutine
     * has completely stopped. A revision makes rapid repeated UI choices "latest wins".
     */
    private fun mutateSessionAfterRunStops(mutation: () -> Unit) {
        val transition = synchronized(stateMutationLock) {
            if (closedOrObsolete) return
            runControlRevision += 1
            interjectionRestartJob?.cancel()
            interjectionRestartJob = null
            livenessRecoveryJob?.cancel()
            livenessRecoveryJob = null
            livenessRecoveryInProgress = false
            sessionTransitionRevision += 1
            sessionTransitionJob?.cancel()
            sessionTransitionPending = true
            sessionTransitionRevision
        }
        val runningJob = job?.takeUnless { it.isCompleted }
        if (runningJob == null) {
            synchronized(stateMutationLock) {
                try {
                    if (!closedOrObsolete && transition == sessionTransitionRevision) mutation()
                } finally {
                    if (transition == sessionTransitionRevision) sessionTransitionPending = false
                }
            }
            return
        }

        stop()
        synchronized(stateMutationLock) {
            if (transition != sessionTransitionRevision) return
            sessionTransitionJob = scope.launch {
                runningJob.join()
                synchronized(stateMutationLock) {
                    if (!closedOrObsolete && transition == sessionTransitionRevision) {
                        try {
                            mutation()
                        } finally {
                            if (transition == sessionTransitionRevision) sessionTransitionPending = false
                        }
                    }
                }
            }
        }
    }

    private fun scheduleRestartAfterInterjection(runningJob: Job, expectedRevision: Long) {
        synchronized(stateMutationLock) {
            if (expectedRevision != runControlRevision || sessionTransitionPending) return
            interjectionRestartJob?.cancel()
            interjectionRestartJob = scope.launch {
                runningJob.join()
                launchLoop(expectedRevision, requiredStatus = Status.STOPPED)
            }
        }
    }

    private fun scheduleRestoredRunDiagnosis(expectedSessionId: String) {
        scope.launch {
            // Yield past controller construction/session-transition cleanup. The session/token CAS
            // inside diagnosis still decides whether the restored work is current.
            delay(1)
            val stillCurrent = synchronized(stateMutationLock) {
                _currentSessionId.value == expectedSessionId &&
                    runCheckpoint.recoveryPending &&
                    !sessionTransitionPending
            }
            if (stillCurrent) {
                diagnoseAndRecoverIfStalled(AgentRunDiagnosisTrigger.PROCESS_RESTORE)
            }
        }
    }

    /**
     * Diagnose the live coroutine rather than trusting the persisted/UI RUNNING label. A recovery
     * is claimed under [stateMutationLock], checkpointed, then waits off-main for the old job to
     * finish before it can launch. Stop, an interjection, or a session transition changes the CAS
     * identity and therefore always wins.
     */
    fun diagnoseAndRecoverIfStalled(
        trigger: AgentRunDiagnosisTrigger,
    ): AgentRunLivenessDecision {
        var request: AutomaticRecoveryRequest? = null
        var persistTerminalDecision = false
        val decision = synchronized(stateMutationLock) {
            if (closedOrObsolete) return@synchronized AgentRunLivenessDecision.INACTIVE
            if (sessionTransitionPending) return@synchronized AgentRunLivenessDecision.INACTIVE
            if (livenessRecoveryInProgress) return@synchronized AgentRunLivenessDecision.HEALTHY

            val checkpoint = runCheckpoint.sanitized()
            val observedJob = job
            val activeJob = observedJob?.takeUnless { it.isCompleted }
            val progressAge = lastProgressElapsedRealtime.takeIf { it > 0L }?.let {
                (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
            }
            val runExpected = _status.value == Status.RUNNING || checkpoint.recoveryPending
            val evaluated = AgentRunLivenessPolicy.evaluate(
                AgentRunLivenessSnapshot(
                    runExpected = runExpected,
                    // RECOVERING without an installed coordinator is a durable orphan marker
                    // (process restore / FGS timeout), even if its cancelled job is still unwinding.
                    hasActiveJob = activeJob != null && checkpoint.phase != AgentRunPhase.RECOVERING,
                    phase = checkpoint.phase,
                    progressAgeMs = progressAge,
                    automaticRecoveryAttempts = checkpoint.automaticRecoveryAttempts,
                ),
            )

            when (evaluated) {
                AgentRunLivenessDecision.RECOVER_ORPHANED,
                AgentRunLivenessDecision.RECOVER_STALE,
                -> {
                    val sessionId = _currentSessionId.value
                        ?: return@synchronized AgentRunLivenessDecision.INACTIVE
                    runControlRevision += 1
                    val revision = runControlRevision
                    val durableToken = checkpoint.runToken.ifBlank {
                        java.util.UUID.randomUUID().toString()
                    }
                    val attempt = (checkpoint.automaticRecoveryAttempts + 1)
                        .coerceAtMost(AgentRunLivenessPolicy.MAX_AUTOMATIC_RECOVERIES)
                    val reason = when (evaluated) {
                        AgentRunLivenessDecision.RECOVER_ORPHANED ->
                            if (trigger == AgentRunDiagnosisTrigger.PROCESS_RESTORE) {
                                "应用恢复后未找到上次运行的执行任务"
                            } else {
                                "界面状态仍在运行，但执行任务已经不存在"
                            }
                        else -> {
                            val seconds = AgentRunLivenessPolicy.timeoutFor(checkpoint.phase) / 1_000L
                            "${AgentRunPhase.normalize(checkpoint.phase)} 阶段超过 ${seconds} 秒没有真实进度"
                        }
                    }
                    activeJob?.cancel()
                    val recovered = AgentRunRecovery.recover(_steps.value)
                    _steps.value = recovered.steps
                    blockActivePlanForInterruptedActions(recovered.interruptedActionIds)
                    _streamingText.value = ""
                    streamingRunClaim = null
                    if (_status.value != Status.RUNNING) _status.value = Status.STOPPED
                    runCheckpoint = checkpoint.copy(
                        runToken = durableToken,
                        phase = AgentRunPhase.RECOVERING,
                        lastProgressAtEpochMs = System.currentTimeMillis(),
                        automaticRecoveryAttempts = attempt,
                        recoveryPending = true,
                        lastRecoveryReason = reason,
                    )
                    lastProgressElapsedRealtime = SystemClock.elapsedRealtime()
                    livenessRecoveryInProgress = true
                    addStep(
                        AgentStep.OBSERVATION,
                        "诊断到智能体执行已失活：$reason。正在自动恢复 $attempt/" +
                            "${AgentRunLivenessPolicy.MAX_AUTOMATIC_RECOVERIES}。" +
                            if (recovered.hasInterruptedActions) {
                                " 越过执行边界的动作已标记为中断待核对，不会静默重复执行。"
                            } else {
                                ""
                            },
                    )
                    request = AutomaticRecoveryRequest(
                        sessionId = sessionId,
                        revision = revision,
                        durableRunToken = durableToken,
                        runInstanceToken = runInstanceToken,
                        requiredStatus = _status.value,
                        observedJob = observedJob,
                        jobToJoin = activeJob,
                        attempt = attempt,
                        reason = reason,
                    )
                }

                AgentRunLivenessDecision.STOP_RETRY_LIMIT -> {
                    runControlRevision += 1
                    activeJob?.cancel()
                    val recovered = AgentRunRecovery.recover(_steps.value)
                    _steps.value = recovered.steps
                    blockActivePlanForInterruptedActions(recovered.interruptedActionIds)
                    _streamingText.value = ""
                    streamingRunClaim = null
                    _status.value = Status.STOPPED
                    runCheckpoint = checkpoint.copy(
                        phase = AgentRunPhase.IDLE,
                        recoveryPending = false,
                        lastRecoveryReason = "自动恢复已达到上限",
                    )
                    lastProgressElapsedRealtime = 0L
                    addStep(
                        AgentStep.ERROR,
                        "智能体已连续自动恢复 ${AgentRunLivenessPolicy.MAX_AUTOMATIC_RECOVERIES} 次，" +
                            "为避免无限重试已暂停。请检查网络、模型配置和项目当前状态后再手动继续。",
                    )
                    persistTerminalDecision = true
                }

                else -> Unit
            }
            evaluated
        }

        if (persistTerminalDecision) persist()
        request?.let { claimed ->
            // attempts/reason and interrupted-action quarantine must reach disk before relaunch.
            persist()
            startAutomaticRecovery(claimed)
        }
        return decision
    }

    private fun startAutomaticRecovery(request: AutomaticRecoveryRequest) {
        val coordinator = scope.launch(
            context = Dispatchers.IO,
            start = CoroutineStart.LAZY,
        ) {
            try {
                val joined = request.jobToJoin?.let { oldJob ->
                    withTimeoutOrNull(RECOVERY_JOIN_TIMEOUT_MS) {
                        oldJob.join()
                        true
                    } ?: false
                } ?: true
                if (!joined) {
                    failAutomaticRecovery(
                        request,
                        "旧执行在取消后仍未结束，无法确认是否仍在写入。系统不会并发重试；" +
                            "请检查项目状态，待旧任务结束后再手动继续。",
                    )
                    return@launch
                }

                val stillClaimed = synchronized(stateMutationLock) {
                    recoveryRequestMatchesLocked(request) &&
                        (request.observedJob == null || request.observedJob.isCompleted)
                }
                if (!stillClaimed) return@launch

                val launched = launchLoop(
                    expectedRevision = request.revision,
                    requiredStatus = request.requiredStatus,
                    automaticRecovery = true,
                )
                if (!launched) {
                    failAutomaticRecovery(request, "恢复条件在重新启动前发生变化，已安全暂停。")
                }
            } catch (_: CancellationException) {
                // Explicit Stop/interjection/session transition owns the newer revision.
            } catch (error: Throwable) {
                failAutomaticRecovery(
                    request,
                    "自动恢复协调失败：${error.message ?: error::class.simpleName ?: "未知错误"}",
                )
            } finally {
                val self = currentCoroutineContext()[Job]
                synchronized(stateMutationLock) {
                    if (livenessRecoveryJob === self) {
                        livenessRecoveryJob = null
                        livenessRecoveryInProgress = false
                    }
                }
            }
        }
        val installed = synchronized(stateMutationLock) {
            if (
                livenessRecoveryInProgress &&
                livenessRecoveryJob == null &&
                recoveryRequestMatchesLocked(request)
            ) {
                livenessRecoveryJob = coordinator
                true
            } else {
                false
            }
        }
        if (installed) coordinator.start() else coordinator.cancel()
    }

    private fun recoveryRequestMatchesLocked(request: AutomaticRecoveryRequest): Boolean =
        _currentSessionId.value == request.sessionId &&
            runControlRevision == request.revision &&
            runInstanceToken == request.runInstanceToken &&
            runCheckpoint.runToken == request.durableRunToken &&
            !sessionTransitionPending &&
            job === request.observedJob

    private fun failAutomaticRecovery(request: AutomaticRecoveryRequest, message: String) {
        val changed = synchronized(stateMutationLock) {
            if (!recoveryRequestMatchesLocked(request)) {
                false
            } else {
                _status.value = Status.STOPPED
                runCheckpoint = runCheckpoint.copy(
                    phase = AgentRunPhase.IDLE,
                    recoveryPending = false,
                    lastRecoveryReason = message,
                )
                lastProgressElapsedRealtime = 0L
                addStep(AgentStep.ERROR, message)
                true
            }
        }
        if (changed) persist()
    }

    /** Called from Android's short FGS timeout callback; never blocks waiting for I/O or a job. */
    fun handleForegroundServiceTimeout() {
        val changed = synchronized(stateMutationLock) {
            if (_status.value != Status.RUNNING) {
                false
            } else {
                runControlRevision += 1
                livenessRecoveryJob?.cancel()
                livenessRecoveryJob = null
                livenessRecoveryInProgress = false
                job?.cancel()
                val recovered = AgentRunRecovery.recover(_steps.value)
                _steps.value = recovered.steps
                blockActivePlanForInterruptedActions(recovered.interruptedActionIds)
                _streamingText.value = ""
                streamingRunClaim = null
                _status.value = Status.STOPPED
                runCheckpoint = runCheckpoint.sanitized().copy(
                    runToken = runCheckpoint.runToken.ifBlank {
                        java.util.UUID.randomUUID().toString()
                    },
                    phase = AgentRunPhase.RECOVERING,
                    lastProgressAtEpochMs = System.currentTimeMillis(),
                    recoveryPending = true,
                    lastRecoveryReason = "Android 已结束本轮 dataSync 前台服务时限",
                )
                lastProgressElapsedRealtime = SystemClock.elapsedRealtime()
                addStep(
                    AgentStep.OBSERVATION,
                    "系统后台运行时限已到，任务已安全中断并保存恢复标记；" +
                        "回到应用后会先诊断，再决定是否自动恢复。",
                )
                true
            }
        }
        if (changed) scope.launch(Dispatchers.IO) { runCatching { persist() } }
    }

    private suspend fun touchRunProgress(runClaim: RunClaim, phase: String) {
        currentCoroutineContext().ensureActive()
        val changed = synchronized(stateMutationLock) {
            if (!runClaimMatchesLocked(runClaim)) {
                false
            } else {
                touchRunProgressLocked(phase)
                true
            }
        }
        if (!changed) throw CancellationException("Agent run was superseded")
    }

    private fun touchRunProgressLocked(phase: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        lastProgressElapsedRealtime = nowElapsed
        runCheckpoint = runCheckpoint.sanitized().copy(
            phase = AgentRunPhase.normalize(phase),
            lastProgressAtEpochMs = System.currentTimeMillis(),
            recoveryPending = true,
        )
    }

    private fun streamingProgressCallback(): (String) -> Unit {
        val expectedRun = streamingRunClaim
        return { text -> updateStreamingProgress(expectedRun, text) }
    }

    private fun updateStreamingProgress(expectedRun: RunClaim?, text: String) {
        synchronized(stateMutationLock) {
            // A cancelled child callback must not make a stopped session look alive again.
            if (
                expectedRun == null ||
                !runClaimMatchesLocked(expectedRun) ||
                _status.value != Status.RUNNING
            ) {
                return
            }
            val previous = _streamingText.value
            _streamingText.value = text
            if (text != previous) touchRunProgressLocked(AgentRunPhase.STREAMING)
        }
    }

    private suspend fun runLoop(runClaim: RunClaim) {
        ensureRunClaim(runClaim)
        if (!vm.agentTextModelReady()) {
            synchronized(stateMutationLock) {
                if (!runClaimMatchesLocked(runClaim)) return
                addStep(AgentStep.ERROR, "未配置可用的文本模型，请先到「设置」配置。")
                _status.value = Status.ERROR
            }
            return
        }
        var iterations = 0
        while (currentCoroutineContext().isActive && iterations < MAX_STEPS) {
            ensureRunClaim(runClaim)
            iterations++
            revokeInvalidProjectScope()
            val planningRevision = instructionRevision
            val planningSessionId = _currentSessionId.value
            val planningProjectId = _activeProjectId.value
            if (
                _engineMode.value == ENGINE_DUAL &&
                !ensureDualPlan(planningRevision, planningSessionId, planningProjectId, runClaim)
            ) {
                return
            }
            if (!runContextMatches(planningRevision, planningSessionId, planningProjectId, runClaim)) {
                recordStaleWork("用户在规划期间改变了指令或项目范围，旧规划已丢弃。", runClaim)
                continue
            }

            val modelRevision = instructionRevision
            val modelSessionId = _currentSessionId.value
            val modelProjectId = _activeProjectId.value
            val reply = callModel(runClaim) ?: return
            if (!runContextMatches(modelRevision, modelSessionId, modelProjectId, runClaim)) {
                recordStaleWork("用户已补充指令或改变项目范围，本次模型动作不会执行。", runClaim)
                continue
            }
            val action = when (val parsed = AgentActionParser.parse(reply)) {
                is AgentActionParseResult.Success -> parsed.value
                is AgentActionParseResult.Failure -> {
                    synchronized(stateMutationLock) {
                        if (!runClaimMatchesLocked(runClaim)) return
                        addStep(AgentStep.OBSERVATION, parsed.error.asModelObservation())
                    }
                    continue
                }
            }
            if (!runContextMatches(modelRevision, modelSessionId, modelProjectId, runClaim)) {
                recordStaleWork("模型回复解析期间上下文已改变，本次动作不会执行。", runClaim)
                continue
            }
            if (action.thought.isNotBlank()) {
                synchronized(stateMutationLock) {
                    if (!runClaimMatchesLocked(runClaim)) return
                    addStep(AgentStep.THOUGHT, action.thought)
                }
            }

            if (_engineMode.value == ENGINE_DUAL) {
                if (action.action == COMPLETE_PLAN_STEP_ACTION) {
                    val accepted = synchronized(stateMutationLock) {
                        if (!runContextMatches(modelRevision, modelSessionId, modelProjectId, runClaim)) {
                            false
                        } else {
                            completePlanStep(action)
                            true
                        }
                    }
                    if (!accepted) recordStaleWork("计划步骤完成请求已过期，不会提交。", runClaim)
                    continue
                }
                val plan = _activePlan.value
                if (action.action == "final") {
                    if (plan?.isComplete != true) {
                        val committed = synchronized(stateMutationLock) {
                            if (!runClaimMatchesLocked(runClaim)) {
                                false
                            } else {
                                addStep(
                                    AgentStep.OBSERVATION,
                                    "双智能体计划仍有未完成步骤；请先验证当前步骤并调用 $COMPLETE_PLAN_STEP_ACTION。",
                                )
                                true
                            }
                        }
                        if (!committed) return
                        persist()
                        continue
                    }
                } else {
                    val currentStep = plan?.currentStep
                    if (currentStep == null || action.planStepId != currentStep.id) {
                        val committed = synchronized(stateMutationLock) {
                            if (!runClaimMatchesLocked(runClaim)) {
                                false
                            } else {
                                addStep(
                                    AgentStep.OBSERVATION,
                                    "双智能体动作必须关联当前计划步骤 " +
                                        "${currentStep?.id ?: "（无）"}；本次动作未执行。",
                                )
                                true
                            }
                        }
                        if (!committed) return
                        persist()
                        continue
                    }
                }
            }

            when (action.action) {
                "final" -> {
                    val finalized = synchronized(stateMutationLock) {
                        if (!runContextMatches(modelRevision, modelSessionId, modelProjectId, runClaim)) {
                            false
                        } else {
                            addStep(AgentStep.MESSAGE, action.args.str("message") ?: "任务完成。")
                            _status.value = Status.DONE
                            true
                        }
                    }
                    if (!finalized) {
                        recordStaleWork("最终回复已过期，不会结束当前新任务。", runClaim)
                        continue
                    }
                    return
                }

                "ask_user" -> {
                    val question = action.args.str("question") ?: "需要你的补充信息。"
                    val questionGate = CompletableDeferred<String?>()
                        val prepared = synchronized(stateMutationLock) {
                        if (!runContextMatches(modelRevision, modelSessionId, modelProjectId, runClaim)) {
                            false
                        } else {
                            if (_engineMode.value == ENGINE_DUAL) {
                                val askActionId = addActionStep(
                                    tool = "ask_user",
                                    text = argsPreview(action.args),
                                    args = action.args,
                                    status = AgentStep.ACTION_SUCCEEDED,
                                    planStepId = action.planStepId.orEmpty(),
                                    resolvedProjectId = modelProjectId.orEmpty(),
                                )
                                addStep(AgentStep.QUESTION, question, resultForActionId = askActionId)
                            } else {
                                addStep(AgentStep.QUESTION, question)
                            }
                            gate = questionGate
                            _pendingPrompt.value = question
                            _status.value = Status.AWAITING_USER
                            true
                        }
                    }
                    if (!prepared) {
                        recordStaleWork("提问动作已过期，不会覆盖用户的新指令。", runClaim)
                        continue
                    }
                    persist()
                    awaitGate(questionGate) ?: return
                    if (!resumeAfterGate(Status.AWAITING_USER, runClaim)) return
                    continue
                }

                else -> {
                    val tool = TOOLS.firstOrNull { it.name == action.action }
                    if (tool == null) {
                        synchronized(stateMutationLock) {
                            if (!runClaimMatchesLocked(runClaim)) return
                            addStep(AgentStep.OBSERVATION, "未知工具：${action.action}")
                        }
                        continue
                    }

                    val scopeAtProposal = modelProjectId
                    val resolvedProjectId = resolveActionProjectId(action.args, scopeAtProposal)
                    val preview = argsPreview(action.args)
                    val argumentError = AgentToolArgumentPolicy.validationError(tool.name, action.args)
                    if (argumentError != null) {
                        val rejected = synchronized(stateMutationLock) {
                            if (!runContextMatches(modelRevision, modelSessionId, scopeAtProposal, runClaim)) {
                                false
                            } else {
                                addActionStep(
                                    tool = tool.name,
                                    text = preview,
                                    args = action.args,
                                    status = AgentStep.ACTION_FAILED,
                                    planStepId = action.planStepId.orEmpty(),
                                    resolvedProjectId = resolvedProjectId,
                                )
                                addStep(AgentStep.OBSERVATION, argumentError)
                                true
                            }
                        }
                        if (!rejected) {
                            recordStaleWork("工具参数校验期间上下文已改变，本次动作不会执行。", runClaim)
                        } else {
                            persist()
                        }
                        continue
                    }
                    val uncertainReplay = isInterruptedReplay(tool.name, resolvedProjectId)
                    val interruptedMutationQuarantine =
                        AgentInterruptedActionQuarantine.requiresConfirmation(
                            steps = _steps.value,
                            candidateTool = tool.name,
                            mutatingTools = mutatingToolNames,
                        )
                    val scopeEscape = escapesAutoApprovedProject(tool, action.args)
                    val alwaysConfirm =
                        AgentToolArgumentPolicy.requiresAlwaysConfirmation(tool.name, action.args)
                    val needsConfirmation = uncertainReplay || interruptedMutationQuarantine ||
                        (tool.sensitive &&
                            (!isAutoApprovalEffective() || alwaysConfirm)) ||
                        scopeEscape
                    val stepId: String

                    if (needsConfirmation) {
                        val confirmationGate = CompletableDeferred<String?>()
                        var proposedStepId: String? = null
                        val proposed = synchronized(stateMutationLock) {
                            if (!runContextMatches(modelRevision, modelSessionId, scopeAtProposal, runClaim)) {
                                false
                            } else {
                                proposedStepId = addActionStep(
                                    tool = tool.name,
                                    text = "（待确认）$preview",
                                    args = action.args,
                                    status = AgentStep.ACTION_PROPOSED,
                                    planStepId = action.planStepId.orEmpty(),
                                    resolvedProjectId = resolvedProjectId,
                                )
                                gate = confirmationGate
                                _status.value = Status.AWAITING_CONFIRM
                                _pendingPrompt.value = when {
                                    interruptedMutationQuarantine ->
                                        "检测到尚未核对的中断写操作，可能已经部分完成。请先检查受影响项目的当前状态；" +
                                            "确认后会把这些记录标为已核对，并执行：${tool.name}。"
                                    uncertainReplay ->
                                        "检测到同一工具存在尚未核对的中断动作，可能已经部分完成。确认执行：${tool.name}？"
                                    alwaysConfirm ->
                                        "高风险操作始终需要确认：${tool.name}。是否执行？"
                                    scopeEscape ->
                                        "该操作将离开自动继续所锁定的项目。确认执行：${tool.name}？"
                                    else -> "确认执行：${tool.name}？"
                                }
                                true
                            }
                        }
                        if (!proposed) {
                            recordStaleWork("工具提议时上下文已改变，本次动作不会执行。", runClaim)
                            continue
                        }
                        stepId = checkNotNull(proposedStepId)
                        persist()
                        val decision = awaitGate(confirmationGate) ?: return
                        if (!resumeAfterGate(Status.AWAITING_CONFIRM, runClaim)) return
                        if (decision != "yes") {
                            val note = if (decision.startsWith("no:")) {
                                "用户拒绝并补充：${decision.removePrefix("no:")}"
                            } else {
                                "用户拒绝了该操作"
                            }
                            val committed = synchronized(stateMutationLock) {
                                if (!runClaimMatchesLocked(runClaim)) {
                                    false
                                } else {
                                    replaceStepText(stepId, "（已拒绝）$preview")
                                    replaceActionStatus(stepId, AgentStep.ACTION_DENIED)
                                    addStep(AgentStep.OBSERVATION, note)
                                    true
                                }
                            }
                            if (!committed) return
                            persist()
                            continue
                        }

                        val approved = synchronized(stateMutationLock) {
                            if (!runContextMatches(modelRevision, modelSessionId, scopeAtProposal, runClaim)) {
                                false
                            } else {
                                if (interruptedMutationQuarantine) {
                                    _steps.value = AgentInterruptedActionQuarantine.reconcileMutations(
                                        steps = _steps.value,
                                        mutatingTools = mutatingToolNames,
                                    )
                                } else if (uncertainReplay) {
                                    markInterruptedReplayReviewed(
                                        tool.name,
                                        resolvedProjectId,
                                    )
                                }
                                replaceStepText(stepId, preview)
                                replaceActionStatus(stepId, AgentStep.ACTION_RUNNING)
                                true
                            }
                        }
                        if (!approved) {
                            val committed = synchronized(stateMutationLock) {
                                if (!runClaimMatchesLocked(runClaim)) {
                                    false
                                } else {
                                    replaceStepText(stepId, "（上下文变化，未执行）$preview")
                                    replaceActionStatus(stepId, AgentStep.ACTION_DENIED)
                                    addStep(
                                        AgentStep.OBSERVATION,
                                        "确认后项目范围或用户指令发生变化，本次动作未执行。",
                                    )
                                    true
                                }
                            }
                            if (!committed) return
                            persist()
                            continue
                        }
                    } else {
                        var runningStepId: String? = null
                        val committed = synchronized(stateMutationLock) {
                            if (!runContextMatches(modelRevision, modelSessionId, scopeAtProposal, runClaim)) {
                                false
                            } else {
                                runningStepId = addActionStep(
                                    tool = tool.name,
                                    text = preview,
                                    args = action.args,
                                    status = AgentStep.ACTION_RUNNING,
                                    planStepId = action.planStepId.orEmpty(),
                                    resolvedProjectId = resolvedProjectId,
                                )
                                true
                            }
                        }
                        if (!committed) {
                            recordStaleWork("工具动作提交前上下文已改变，本次动作不会执行。", runClaim)
                            continue
                        }
                        stepId = checkNotNull(runningStepId)
                    }

                    touchRunProgress(runClaim, AgentRunPhase.TOOL)
                    persist()
                    ensureRunClaim(runClaim)
                    if (!runContextMatches(modelRevision, modelSessionId, scopeAtProposal, runClaim)) {
                        return
                    }
                    if (_activeProjectId.value != scopeAtProposal) {
                        return
                    }
                    if (
                        tool.name != "create_project" &&
                        resolvedProjectId.isNotBlank() &&
                        repo.project(resolvedProjectId) == null
                    ) {
                        val denied = synchronized(stateMutationLock) {
                            if (!runClaimMatchesLocked(runClaim)) {
                                false
                            } else {
                                replaceActionStatus(stepId, AgentStep.ACTION_DENIED)
                                addStep(AgentStep.OBSERVATION, "目标项目已不存在，本次动作未执行。")
                                true
                            }
                        }
                        if (denied) persist()
                        return
                    }
                    val actionContext = AgentActionContext(
                        actionId = stepId,
                        sessionId = _currentSessionId.value.orEmpty(),
                        engineMode = _engineMode.value,
                    )
                    synchronized(stateMutationLock) {
                        if (!runClaimMatchesLocked(runClaim)) return
                        streamingRunClaim = runClaim
                    }
                    val result = runCatchingNonCancellation {
                        tool.run(action.args, actionContext)
                    }
                    currentCoroutineContext().ensureActive()
                    val execution = result.getOrElse {
                        AgentToolExecution.Completed("执行出错：" + it.message)
                    }
                    if (execution is AgentToolExecution.PendingReview) {
                        val committed = synchronized(stateMutationLock) {
                            if (!runClaimMatchesLocked(runClaim)) {
                                false
                            } else {
                                replaceActionStatus(stepId, AgentStep.ACTION_AWAITING_REVIEW)
                                _streamingText.value = ""
                                if (streamingRunClaim == runClaim) streamingRunClaim = null
                                _pendingReview.value = execution.review
                                _pendingPrompt.value =
                                    "候选稿已生成；请预览并采用或拒绝后，智能体才会继续。"
                                _status.value = Status.AWAITING_REVIEW
                                addStep(
                                    AgentStep.OBSERVATION,
                                    execution.observation,
                                    resultForActionId = stepId,
                                    resultKind = AgentStep.RESULT_PENDING_REVIEW,
                                )
                                touchRunProgressLocked(AgentRunPhase.TOOL)
                                true
                            }
                        }
                        if (!committed) return
                        persist()
                        return
                    }
                    val observation = (execution as AgentToolExecution.Completed).observation
                    val actionSucceeded =
                        result.isSuccess && !AgentToolOutcome.isSemanticFailure(observation)
                    val committed = synchronized(stateMutationLock) {
                        if (!runClaimMatchesLocked(runClaim)) {
                            false
                        } else {
                            replaceActionStatus(
                                stepId,
                                if (actionSucceeded) AgentStep.ACTION_SUCCEEDED else AgentStep.ACTION_FAILED,
                            )
                            _streamingText.value = ""
                            if (streamingRunClaim == runClaim) streamingRunClaim = null
                            addStep(
                                AgentStep.OBSERVATION,
                                observation,
                                resultForActionId = stepId,
                                resultKind = AgentStep.RESULT_COMMITTED,
                            )
                            touchRunProgressLocked(AgentRunPhase.TOOL)
                            true
                        }
                    }
                    if (!committed) return
                    // Action status and its observation are committed together. If the process
                    // dies earlier, recovery sees RUNNING and will never replay it automatically.
                    persist()
                }
            }
        }
        if (iterations >= MAX_STEPS) {
            synchronized(stateMutationLock) {
                if (!runClaimMatchesLocked(runClaim)) return
                addStep(AgentStep.MESSAGE, "已达到本轮最大步数并暂停，可点击「继续」。")
                _status.value = Status.STOPPED
            }
        }
    }

    /**
     * Ensure a dual-engine session has a validated plan for the latest user instruction.
     * Existing plans are reused only while their source instruction and project scope remain valid.
     */
    private suspend fun ensureDualPlan(
        expectedRevision: Long,
        expectedSessionId: String?,
        expectedProjectId: String?,
        runClaim: RunClaim,
    ): Boolean {
        if (!runContextMatches(expectedRevision, expectedSessionId, expectedProjectId, runClaim)) return true
        val command = _steps.value.lastOrNull { it.type == AgentStep.USER }
        if (command == null) {
            synchronized(stateMutationLock) {
                if (!runClaimMatchesLocked(runClaim)) return true
                addStep(AgentStep.ERROR, "双智能体缺少可规划的用户指令。")
                _status.value = Status.STOPPED
            }
            persist()
            return false
        }

        val previousPlan = _activePlan.value
        val reusable = previousPlan != null &&
            previousPlan.sourceCommandId == command.id &&
            previousPlan.steps.none { it.status == AgentPlanStepStatus.BLOCKED }
        if (reusable) {
            val started = checkNotNull(previousPlan).beginNextPendingStep()
            val committed = synchronized(stateMutationLock) {
                if (!runContextMatches(expectedRevision, expectedSessionId, expectedProjectId, runClaim)) {
                    false
                } else {
                    if (started != previousPlan) _activePlan.value = started
                    true
                }
            }
            if (!committed) return true
            if (started != previousPlan) persist()
            return true
        }

        var correction = ""
        var lastFailure = "规划器没有返回有效计划"
        repeat(PLANNER_RETRIES) { attempt ->
            touchRunProgress(runClaim, AgentRunPhase.PLANNING)
            persist()
            val response = runCatchingNonCancellation {
                sessionAgentChat(buildPlannerMessages(command, previousPlan, correction))
            }
            val latestCommandId = _steps.value.lastOrNull { it.type == AgentStep.USER }?.id
            if (
                latestCommandId != command.id ||
                !runContextMatches(expectedRevision, expectedSessionId, expectedProjectId, runClaim)
            ) {
                return true
            }

            val raw = response.getOrNull()
            if (raw.isNullOrBlank()) {
                lastFailure = response.exceptionOrNull()?.let {
                    it.message ?: it::class.simpleName
                } ?: "规划器返回空响应"
                correction = "上次规划失败：${lastFailure.take(1_000)}。请严格按协议重新输出。"
            } else {
                touchRunProgress(runClaim, AgentRunPhase.PLANNING)
                when (
                    val parsed = AgentPlanParser.parse(
                        raw = raw,
                        createdAt = System.currentTimeMillis(),
                        sourceCommandId = command.id,
                    )
                ) {
                    is AgentPlanParseResult.Failure -> {
                        lastFailure = parsed.error.asModelObservation()
                        correction = lastFailure
                    }

                    is AgentPlanParseResult.Success -> {
                        val maxSteps = AgentReasoningPolicy.forLevel(
                            _reasoningLevel.value,
                        ).plannerMaxSteps
                        if (parsed.value.steps.size > maxSteps) {
                            lastFailure =
                                "当前推理级别最多允许 $maxSteps 个步骤，实际返回 ${parsed.value.steps.size} 个"
                            correction = "$lastFailure。请合并重复或非必要步骤后重新输出。"
                        } else {
                            val knownTools =
                                TOOLS.asSequence().map { it.name }.toSet() + ASK_USER_ACTION
                            val unknownTools = parsed.value.steps
                                .flatMap { it.suggestedTools }
                                .filterNot { it in knownTools }
                                .distinct()
                            if (unknownTools.isNotEmpty()) {
                                lastFailure =
                                    "计划引用了未知工具：${unknownTools.joinToString(", ")}"
                                correction =
                                    "$lastFailure。suggestedTools 只能使用系统列出的真实工具名。"
                            } else {
                                val started = parsed.value
                                    .copy(planId = newPlanIdentity())
                                    .beginNextPendingStep()
                                val committed = synchronized(stateMutationLock) {
                                    if (
                                        !runContextMatches(
                                            expectedRevision,
                                            expectedSessionId,
                                            expectedProjectId,
                                            runClaim,
                                        )
                                    ) {
                                        false
                                    } else {
                                        _activePlan.value = started
                                        addStep(
                                            AgentStep.PLAN,
                                            "规划器已生成执行计划：\n${started.asPromptText()}",
                                        )
                                        true
                                    }
                                }
                                if (!committed) return true
                                persist()
                                return true
                            }
                        }
                    }
                }
            }
            if (attempt < PLANNER_RETRIES - 1) delay(500)
        }

        synchronized(stateMutationLock) {
            if (!runContextMatches(expectedRevision, expectedSessionId, expectedProjectId, runClaim)) {
                return true
            }
            addStep(
                AgentStep.ERROR,
                "规划器连续 ${PLANNER_RETRIES} 次未能生成合法计划：${lastFailure.take(1_500)}。已暂停，未回退到经典引擎。",
            )
            _status.value = Status.STOPPED
        }
        persist()
        return false
    }

    /**
     * A plan step is completed only in a later model turn, after a related successful action and
     * its observation are present in the durable transcript.
     */
    private fun completePlanStep(action: AgentAction) {
        val plan = _activePlan.value
        val current = plan?.currentStep
        val requestedId = action.planStepId ?: action.args.str("planStepId")
        if (plan == null || current == null || requestedId != current.id) {
            addStep(
                AgentStep.OBSERVATION,
                "无法完成计划步骤：planStepId 必须等于当前步骤 ${current?.id ?: "（无）"}。",
            )
            persist()
            return
        }

        val summary = action.args.str("summary")
        if (summary == null || summary.length > 1_500) {
            addStep(
                AgentStep.OBSERVATION,
                "无法完成计划步骤 $requestedId：args.summary 必须是 1–1500 字的完成证据摘要。",
            )
            persist()
            return
        }

        val relatedActionIndex = AgentPlanEvidence.latestActionIndexForPlanStep(
            steps = _steps.value,
            planId = plan.planId,
            planStepId = requestedId,
        )
        val relatedAction = _steps.value.getOrNull(relatedActionIndex)
        val hasDurableResult = AgentPlanEvidence.hasDurableResult(_steps.value, relatedActionIndex)
        if (relatedAction?.actionStatus != AgentStep.ACTION_SUCCEEDED || !hasDurableResult) {
            addStep(
                AgentStep.OBSERVATION,
                "步骤 $requestedId 尚无“已成功动作 + 后续结果”的持久化证据，不能标记完成。",
            )
            persist()
            return
        }

        addActionStep(
            tool = COMPLETE_PLAN_STEP_ACTION,
            text = summary,
            args = action.args,
            status = AgentStep.ACTION_SUCCEEDED,
            planStepId = requestedId,
            resolvedProjectId = _activeProjectId.value.orEmpty(),
        )
        val advanced = plan.completeCurrentStepAndAdvance(requestedId)
        _activePlan.value = advanced
        addStep(
            AgentStep.PLAN,
            "执行器已验证步骤 $requestedId：$summary\n${advanced.asPromptText()}",
        )
        persist()
    }

    /** Call the model for the next action, retrying transient failures. Returns null (and pauses
     *  the run with an explanatory step) if it keeps failing — so the user can hit 继续 to retry. */
    private suspend fun callModel(runClaim: RunClaim): String? {
        var lastErr: String? = null
        repeat(MODEL_RETRIES) { attempt ->
            ensureRunClaim(runClaim)
            touchRunProgress(runClaim, AgentRunPhase.MODEL)
            persist()
            val r = runCatchingNonCancellation { sessionAgentChat(buildMessages()) }
            ensureRunClaim(runClaim)
            val out = r.getOrNull()
            if (!out.isNullOrBlank()) {
                touchRunProgress(runClaim, AgentRunPhase.MODEL)
                return out
            }
            lastErr = r.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: "空响应"
            if (attempt < MODEL_RETRIES - 1) delay(2000)
        }
        synchronized(stateMutationLock) {
            if (!runClaimMatchesLocked(runClaim)) return null
            addStep(AgentStep.ERROR, "模型多次无响应（${lastErr}）。已暂停，可点「继续」重试或补充指令。")
            _status.value = Status.STOPPED
        }
        return null
    }

    private suspend fun awaitGate(expectedGate: CompletableDeferred<String?>): String? {
        return try {
            expectedGate.await()
        } finally {
            synchronized(stateMutationLock) {
                if (gate === expectedGate) gate = null
            }
        }
    }

    /** Stop/session-transition wins even if a gate completed a moment before cancellation. */
    private suspend fun resumeAfterGate(expectedStatus: Status, runClaim: RunClaim): Boolean {
        ensureRunClaim(runClaim)
        return synchronized(stateMutationLock) {
            if (
                _status.value != expectedStatus ||
                !runClaimMatchesLocked(runClaim)
            ) {
                false
            } else {
                _status.value = Status.RUNNING
                touchRunProgressLocked(AgentRunPhase.STARTING)
                true
            }
        }
    }

    /**
     * Bridges ViewModel callback jobs without leaving the agent suspended when a job cannot start
     * or terminates before invoking its callback.
     */
    private fun bindChildJob(
        continuation: CancellableContinuation<String>,
        child: Job?,
        startFailure: String,
    ) {
        if (child == null) {
            if (continuation.isActive) continuation.resume("执行出错：$startFailure")
            return
        }
        continuation.invokeOnCancellation { child.cancel() }
        child.invokeOnCompletion { cause ->
            if (continuation.isActive) {
                val detail = cause?.let { it.message ?: it::class.simpleName }
                    ?: "任务结束但未返回结果"
                continuation.resume("执行出错：$startFailure；$detail")
            }
        }
    }

    private suspend fun <T> runCatchingNonCancellation(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private fun runContextMatches(
        expectedRevision: Long,
        expectedSessionId: String?,
        expectedProjectId: String?,
        runClaim: RunClaim,
    ): Boolean =
        runClaimMatchesLocked(runClaim) &&
            instructionRevision == expectedRevision &&
            _currentSessionId.value == expectedSessionId &&
            _activeProjectId.value == expectedProjectId &&
            (expectedProjectId == null || repo.project(expectedProjectId) != null)

    private fun runClaimMatchesLocked(runClaim: RunClaim): Boolean =
        !closedOrObsolete && AgentRunGenerationFence.matches(
            expectedControlRevision = runClaim.controlRevision,
            expectedInstanceToken = runClaim.instanceToken,
            expectedSessionId = runClaim.sessionId,
            currentControlRevision = runControlRevision,
            currentInstanceToken = runInstanceToken,
            currentSessionId = _currentSessionId.value,
            sessionTransitionPending = sessionTransitionPending,
        )

    private suspend fun ensureRunClaim(runClaim: RunClaim) {
        currentCoroutineContext().ensureActive()
        if (!runClaimMatchesLocked(runClaim)) {
            throw CancellationException("Agent run was superseded")
        }
    }

    private fun recordStaleWork(message: String, runClaim: RunClaim) {
        val changed = synchronized(stateMutationLock) {
            if (!runClaimMatchesLocked(runClaim)) {
                false
            } else {
                addStep(AgentStep.OBSERVATION, message)
                true
            }
        }
        if (changed) persist()
    }

    private fun revokeInvalidProjectScope() {
        val projectId = _activeProjectId.value ?: return
        if (repo.project(projectId) != null) return
        synchronized(stateMutationLock) {
            if (_activeProjectId.value != projectId || repo.project(projectId) != null) return
            _activeProjectId.value = null
            _autoApprove.value = false
            _activePlan.value = null
            instructionRevision++
            addStep(
                AgentStep.OBSERVATION,
                "锁定项目已被删除或不可用，已撤销项目授权并丢弃旧计划。",
            )
        }
        persist()
    }

    // ── model conversation ──────────────────────────────────────────────────────

    /**
     * Bind provider usage to the conversation that issued this request. A cancelled request may
     * deliver its final OkHttp callback while a session transition is starting, so late usage is
     * rejected under [stateMutationLock] before it can contaminate another session.
     */
    private suspend fun sessionAgentChat(messages: List<ChatMessage>): String? {
        val expectedSessionId = synchronized(stateMutationLock) {
            _currentSessionId.value?.takeUnless { sessionTransitionPending }
        } ?: return null
        return vm.agentChat(messages) { usage ->
            recordSessionCacheUsage(expectedSessionId, usage)
        }
    }

    private fun recordSessionCacheUsage(expectedSessionId: String, usage: StreamUsage) {
        val changed = synchronized(stateMutationLock) {
            if (
                sessionTransitionPending ||
                _currentSessionId.value != expectedSessionId ||
                usage.cacheHitTokens == null ||
                usage.cacheMissTokens == null
            ) {
                false
            } else {
                val before = _sessionCacheMetrics.value
                val after = before.record(usage.cacheHitTokens, usage.cacheMissTokens)
                if (after == before) {
                    false
                } else {
                    _sessionCacheMetrics.value = after
                    true
                }
            }
        }
        // Never acquire the persistence lock while stateMutationLock is held.
        if (changed) persist()
    }

    private fun buildMessages(): List<ChatMessage> =
        prepareExecutorRequest(allowAutoCompression = true).messages

    private fun prepareExecutorRequest(
        allowAutoCompression: Boolean,
        refreshSnapshot: ContextUsageRefreshSnapshot? = null,
        publishUsage: Boolean = true,
    ): AgentTranscriptRequestBudgeter.PreparedRequest {
        val config = refreshSnapshot?.config ?: vm.activeTextModelConfig()
        val activeProjectId = if (refreshSnapshot != null) {
            refreshSnapshot.activeProjectId
        } else {
            _activeProjectId.value
        }
        val activePlan = if (refreshSnapshot != null) {
            refreshSnapshot.identity.activePlan
        } else {
            _activePlan.value
        }
        val reasoningLevel = refreshSnapshot?.identity?.reasoningLevel ?: _reasoningLevel.value
        val engineMode = refreshSnapshot?.identity?.engineMode ?: _engineMode.value
        val pendingReview = if (refreshSnapshot != null) {
            refreshSnapshot.pendingReview
        } else {
            _pendingReview.value
        }
        val state = buildCurrentState(activeProjectId)
        val sourceSteps = refreshSnapshot?.identity?.steps ?: _steps.value
        val latestGoalStep = sourceSteps.lastOrNull { it.type == AgentStep.USER }
        val latestGoal = latestGoalStep?.text
            ?: "（尚无用户指令）"
        val requiredContext = requiredExecutorContext(
            sourceSteps = sourceSteps,
            activePlan = activePlan,
            pendingReview = pendingReview,
        )
        val requiredEvidence = requiredContext.text.ifBlank { "（无）" }
        val excludedReplayStepIds = requiredContext.stepIds + listOfNotNull(latestGoalStep?.id)
        val emptyTranscript = "（空，等待第一条指令）"
        if (engineMode != ENGINE_DUAL) {
            val system = AgentPrompts.system(toolDocs())
            val fixedMessages = listOf(
                ChatMessage("system", system),
                ChatMessage(
                    "user",
                    buildExecutorUserPrompt(state, latestGoal, requiredEvidence, emptyTranscript),
                ),
            )
            return prepareTranscriptRequest(
                config = config,
                preferredBudget = AgentTranscriptBudget(),
                transcriptSharePercent = 100,
                fixedMessages = fixedMessages,
                allowAutoCompression = allowAutoCompression,
                excludedReplayStepIds = excludedReplayStepIds,
                refreshSnapshot = refreshSnapshot,
                publishUsage = publishUsage,
            ) { transcript ->
                listOf(
                    ChatMessage("system", system),
                    ChatMessage(
                        "user",
                        buildExecutorUserPrompt(
                            state,
                            latestGoal,
                            requiredEvidence,
                            transcript.ifBlank { emptyTranscript },
                        ),
                    ),
                )
            }
        }

        val reasoning = AgentReasoningPolicy.forLevel(reasoningLevel)
        val system = AgentPrompts.dualExecutorSystem(
            toolDocs = toolDocs(),
            plan = activePlan?.asPromptText() ?: "（计划尚未生成）",
            reasoningLevel = reasoning.level,
        )
        val fixedMessages = listOf(
            ChatMessage("system", system),
            ChatMessage(
                "user",
                buildExecutorUserPrompt(state, latestGoal, requiredEvidence, emptyTranscript),
            ),
        )
        return prepareTranscriptRequest(
            config = config,
            preferredBudget = reasoning.executorTranscriptBudget,
            transcriptSharePercent = reasoning.transcriptSharePercent,
            fixedMessages = fixedMessages,
            allowAutoCompression = allowAutoCompression,
            excludedReplayStepIds = excludedReplayStepIds,
            refreshSnapshot = refreshSnapshot,
            publishUsage = publishUsage,
        ) { transcript ->
            listOf(
                ChatMessage("system", system),
                ChatMessage(
                    "user",
                    buildExecutorUserPrompt(
                        state,
                        latestGoal,
                        requiredEvidence,
                        transcript.ifBlank { emptyTranscript },
                    ),
                ),
            )
        }
    }

    private fun buildPlannerMessages(
        command: AgentStep,
        previousPlan: AgentPlan?,
        correction: String,
    ): List<ChatMessage> {
        val reasoning = AgentReasoningPolicy.forLevel(_reasoningLevel.value)
        val system = AgentPrompts.plannerSystem(toolDocs(), reasoning.level)
        val state = buildCurrentState()
        val emptyTranscript = "（空）"
        val fixedMessages = listOf(
            ChatMessage("system", system),
            ChatMessage(
                "user",
                buildPlannerUserPrompt(command, state, previousPlan, correction, emptyTranscript),
            ),
        )
        return prepareTranscriptRequest(
            config = vm.activeTextModelConfig(),
            preferredBudget = reasoning.plannerTranscriptBudget,
            transcriptSharePercent = reasoning.transcriptSharePercent,
            fixedMessages = fixedMessages,
            allowAutoCompression = true,
            excludedReplayStepIds = setOf(command.id),
        ) { transcript ->
            listOf(
                ChatMessage("system", system),
                ChatMessage(
                    "user",
                    buildPlannerUserPrompt(
                        command,
                        state,
                        previousPlan,
                        correction,
                        transcript.ifBlank { emptyTranscript },
                    ),
                ),
            )
        }.messages
    }

    private fun prepareTranscriptRequest(
        config: com.example.novelseek_ultra.data.model.TextModelConfig,
        preferredBudget: AgentTranscriptBudget,
        transcriptSharePercent: Int,
        fixedMessages: List<ChatMessage>,
        allowAutoCompression: Boolean,
        excludedReplayStepIds: Set<String> = emptySet(),
        refreshSnapshot: ContextUsageRefreshSnapshot? = null,
        publishUsage: Boolean = true,
        renderMessages: (String) -> List<ChatMessage>,
    ): AgentTranscriptRequestBudgeter.PreparedRequest {
        val expectedSessionId = refreshSnapshot?.identity?.sessionId
            ?: synchronized(stateMutationLock) { _currentSessionId.value }
        var compressionAttempts = 0
        while (true) {
            val fullSteps = refreshSnapshot?.identity?.steps ?: _steps.value
            val sensitiveTools = sensitiveToolNames()
            val memory = AgentContextCompressor.normalizeMemory(
                fullSteps,
                refreshSnapshot?.identity?.memory ?: _contextMemory.value,
                sensitiveTools,
            )
            // Validate the durable prefix against the complete audit chain first. Filtering fixed
            // request-critical steps before validation would change the prefix digest and discard
            // an otherwise valid memory overlay.
            val replaySteps = AgentContextCompressor.replaySteps(
                fullSteps,
                memory,
                sensitiveTools,
            ).filterNot { it.id in excludedReplayStepIds }
            val rawFallbackSteps = fullSteps.filterNot { it.id in excludedReplayStepIds }
            val prepared = try {
                AgentTranscriptRequestBudgeter.prepare(
                    config = config,
                    preferredBudget = preferredBudget,
                    transcriptSharePercent = transcriptSharePercent,
                    fixedMessages = fixedMessages,
                    replaySteps = replaySteps,
                    historyPrefix = memory.summary.takeIf { it.isNotBlank() },
                    renderMessages = renderMessages,
                )
            } catch (memoryError: IllegalArgumentException) {
                if (memory.summary.isBlank()) throw memoryError
                // A smaller model may not fit a summary created under a larger profile. Preserve
                // the durable memory but safely fall back to the existing raw-tail budget logic.
                AgentTranscriptRequestBudgeter.prepare(
                    config = config,
                    preferredBudget = preferredBudget,
                    transcriptSharePercent = transcriptSharePercent,
                    fixedMessages = fixedMessages,
                    replaySteps = rawFallbackSteps,
                    historyPrefix = null,
                    renderMessages = renderMessages,
                )
            }
            val shouldCompact = AgentContextCompressionPolicy.shouldAttempt(
                prepared = prepared,
                attempts = compressionAttempts,
            )
            if (
                allowAutoCompression &&
                expectedSessionId != null &&
                shouldCompact
            ) {
                compressionAttempts++
                if (
                    compactSessionContextInternal(
                        expectedSessionId = expectedSessionId,
                        allowActiveRequestBoundary = true,
                        refreshAfter = false,
                    )
                ) {
                    continue
                }
            }
            AgentTranscriptRequestBudgeter.validate(config, prepared.messages)
            if (publishUsage) {
                publishContextUsage(expectedSessionId, prepared.measurement, memory)
            }
            return prepared
        }
    }

    private fun publishContextUsage(
        expectedSessionId: String?,
        measurement: AgentTranscriptRequestBudgeter.Measurement,
        memory: AgentSessionMemory,
    ) {
        synchronized(stateMutationLock) {
            if (
                expectedSessionId == null ||
                _currentSessionId.value != expectedSessionId ||
                sessionTransitionPending
            ) {
                return
            }
            _contextUsage.value = measuredContextUsage(measurement, memory)
        }
    }

    private fun measuredContextUsage(
        measurement: AgentTranscriptRequestBudgeter.Measurement,
        memory: AgentSessionMemory,
    ): AgentContextUsage = AgentContextUsage(
        observable = true,
        capacityTokens = measurement.capacityTokens.toLong(),
        inputBudgetTokens = measurement.inputBudgetTokens.toLong(),
        fixedTokens = measurement.fixedTokens.toLong(),
        historyTokens = measurement.historyTokens.toLong(),
        outputReserveTokens = measurement.outputReserveTokens.toLong(),
        remainingTokens = measurement.remainingTokens.toLong(),
        usageRatio = measurement.usageRatio.coerceIn(0.0, 1.0),
        compressionCount = memory.compressionCount,
        lastCompressedAt = memory.lastCompressedAt,
    )

    private fun buildExecutorUserPrompt(
        state: String,
        latestGoal: String,
        requiredEvidence: String,
        transcript: String,
    ): String =
        "## 最新用户目标（完整保留）\n$latestGoal\n\n## 当前状态\n$state" +
            "\n\n## 必须保留的当前问答与执行证据\n$requiredEvidence" +
            "\n\n## 执行链（节选）\n$transcript" +
            "\n\n请决定下一步，只输出一个 JSON 动作。"

    /** Select only live gate/current-plan evidence; durable older audit evidence stays in memory. */
    private fun requiredExecutorContext(
        sourceSteps: List<AgentStep>,
        activePlan: AgentPlan? = _activePlan.value,
        pendingReview: AgentPendingReview? = _pendingReview.value,
    ): RequiredExecutorContext {
        if (sourceSteps.isEmpty()) return RequiredExecutorContext("", emptySet())
        val requiredIds = linkedSetOf<String>()
        fun include(step: AgentStep?) {
            step?.id?.takeIf { it.isNotBlank() }?.let(requiredIds::add)
        }
        fun includeActionAndResults(actionId: String) {
            if (actionId.isBlank()) return
            include(sourceSteps.firstOrNull { it.id == actionId && it.type == AgentStep.ACTION })
            sourceSteps.filter { it.resultForActionId == actionId }.forEach(::include)
        }

        val latestAnswerIndex = sourceSteps.indexOfLast { it.type == AgentStep.ANSWER }
        if (latestAnswerIndex >= 0) {
            val answer = sourceSteps[latestAnswerIndex]
            include(answer)
            val question = sourceSteps.subList(0, latestAnswerIndex)
                .lastOrNull { it.type == AgentStep.QUESTION }
            include(question)
            includeActionAndResults(
                answer.resultForActionId.ifBlank { question?.resultForActionId.orEmpty() },
            )
        } else {
            include(sourceSteps.lastOrNull { it.type == AgentStep.QUESTION })
        }

        activePlan?.takeUnless { it.isComplete }?.planId
            ?.takeIf { it.isNotBlank() }
            ?.let { planId ->
                val actionIds = sourceSteps.asSequence()
                    .filter { it.type == AgentStep.ACTION && it.planId == planId }
                    .map { it.id }
                    .toSet()
                sourceSteps.filter { step ->
                    step.planId == planId || step.resultForActionId in actionIds
                }.forEach(::include)
            }
        pendingReview?.actionId?.let(::includeActionAndResults)
        sourceSteps.filter {
            it.type == AgentStep.ACTION && it.actionStatus in REQUIRED_ACTION_STATUSES
        }.forEach { action -> includeActionAndResults(action.id) }

        val selected = sourceSteps.filter { it.id in requiredIds }
        return RequiredExecutorContext(
            text = AgentTranscriptBudgeter.formatRequired(selected),
            stepIds = requiredIds,
        )
    }

    private fun buildPlannerUserPrompt(
        command: AgentStep,
        state: String,
        previousPlan: AgentPlan?,
        correction: String,
        transcript: String,
    ): String = buildString {
        appendLine("## 最新用户目标")
        appendLine(command.text)
        appendLine()
        appendLine("## 当前状态")
        appendLine(state)
        if (previousPlan != null) {
            appendLine()
            appendLine("## 旧计划（若目标或范围变化，只保留仍然有效的已完成成果，不要重复执行）")
            appendLine(previousPlan.asPromptText())
        }
        appendLine()
        appendLine("## 执行记录（节选）")
        appendLine(transcript)
        if (correction.isNotBlank()) {
            appendLine()
            appendLine("## 上次输出校正")
            appendLine(correction.take(2_000))
        }
        appendLine()
        append("请只输出规划 JSON。")
    }

    private fun buildCurrentState(
        activeProjectId: String? = _activeProjectId.value,
    ): String = buildString {
        val pid = activeProjectId
        appendLine("当前聚焦项目：${pid ?: "（无，可用 create_project 新建或 list_projects 查看）"}")
        if (pid != null) repo.project(pid)?.let { p ->
            appendLine(
                "项目《${p.title}》类型=${repo.novelType(pid)} " +
                    "章节=${repo.chapters(pid).size} 副本=${repo.volumes(pid).size} " +
                    "弧线=${repo.plotArcs(pid).size} 角色=${repo.characters(pid).size}",
            )
        }
    }

    private fun argsPreview(args: JsonObject): String =
        args.entries.joinToString(", ") { (k, v) ->
            val s = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k=${s.take(60)}"
        }

    // ── persistence ───────────────────────────────────────────────────────────

    private fun addStep(
        type: String,
        text: String,
        tool: String = "",
        image: String = "",
        resultForActionId: String = "",
        resultKind: String = "",
    ): String {
        val step = AgentStep(
            id = newAgentStepId(),
            type = type,
            text = text,
            tool = tool,
            createdAt = nowIso(),
            image = image,
            resultForActionId = resultForActionId,
            resultKind = resultKind,
        )
        _steps.update { it + step }
        // Keep the foreground notification's text current while running.
        if (_status.value == Status.RUNNING && active === this) {
            AgentForegroundService.update(appContext, text.take(50))
        }
        return step.id
    }

    private fun addActionStep(
        tool: String,
        text: String,
        args: JsonObject,
        status: String,
        planStepId: String = "",
        resolvedProjectId: String = "",
    ): String {
        val step = AgentStep(
            id = newAgentStepId(),
            type = AgentStep.ACTION,
            text = text,
            tool = tool,
            createdAt = nowIso(),
            argsJson = args.toString(),
            actionStatus = status,
            planStepId = planStepId,
            planId = if (planStepId.isNotBlank()) _activePlan.value?.planId.orEmpty() else "",
            resolvedProjectId = resolvedProjectId,
        )
        _steps.update { it + step }
        if (_status.value == Status.RUNNING && active === this) {
            AgentForegroundService.update(appContext, text.take(50))
        }
        return step.id
    }

    /** Persist generated image bytes to a preview file and add an image bubble to the chain. */
    private fun addImageStep(label: String, bytes: ByteArray) {
        val dir = java.io.File(appContext.filesDir, "agent_images").apply { if (!exists()) mkdirs() }
        val file = java.io.File(dir, "img-${System.currentTimeMillis()}.png")
        runCatching { file.writeBytes(bytes) }
        addStep(AgentStep.IMAGE, label, image = file.absolutePath)
    }

    private fun addImageStepFromBase64(label: String, b64: String) {
        runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.let { addImageStep(label, it) }
    }

    private fun replaceStepText(id: String, newText: String) {
        _steps.update { steps -> steps.map { if (it.id == id) it.copy(text = newText) else it } }
    }

    private fun replaceActionStatus(id: String, status: String) {
        _steps.update { steps ->
            steps.map {
                if (it.id == id && it.type == AgentStep.ACTION) it.copy(actionStatus = status) else it
            }
        }
    }

    private fun normalizedCheckpointForStatus(status: Status): AgentRunCheckpoint {
        val checkpoint = runCheckpoint.sanitized()
        val keepRecoveryPending = status == Status.RUNNING ||
            (status == Status.STOPPED &&
                checkpoint.phase == AgentRunPhase.RECOVERING &&
                checkpoint.recoveryPending)
        val normalized = if (keepRecoveryPending) {
            checkpoint.copy(recoveryPending = true)
        } else {
            checkpoint.copy(phase = AgentRunPhase.IDLE, recoveryPending = false)
        }
        runCheckpoint = normalized
        return normalized
    }

    private fun buildCurrentSessionSnapshot(id: String): AgentSession {
        val statusSnapshot = _status.value
        return AgentSession(
            id = id,
            title = currentTitle,
            createdAt = currentCreatedAt,
            steps = _steps.value,
            lockedProjectId = _activeProjectId.value,
            autoApprove = _autoApprove.value,
            engineMode = _engineMode.value,
            reasoningLevel = _reasoningLevel.value,
            cacheMetrics = _sessionCacheMetrics.value,
            memory = _contextMemory.value,
            activePlan = _activePlan.value,
            runStatus = statusSnapshot.name.lowercase(),
            runCheckpoint = normalizedCheckpointForStatus(statusSnapshot),
            pendingPrompt = _pendingPrompt.value,
            pendingReview = _pendingReview.value,
        )
    }

    private fun persist() {
        if (closedOrObsolete) return
        synchronized(persistenceLock) {
            if (closedOrObsolete) return
            val id = _currentSessionId.value ?: return
            repo.saveAgentSessionById(buildCurrentSessionSnapshot(id))
            // Snapshot construction and both files share one writer, preventing an older snapshot
            // from overwriting a newer user input/title after a thread scheduling inversion.
            val items = _sessions.value.map {
                if (it.id == id) it.copy(title = currentTitle) else it
            }
            _sessions.value = items
            repo.saveAgentIndex(AgentIndex(id, items))
        }
    }

    /**
     * GenerationRun is the source of truth for a paused agent action. Acceptance turns the pending
     * action into committed evidence and resumes the loop; rejection/failure clears the gate but
     * never fabricates a successful plan result.
     */
    private fun reconcilePendingReview(resumeAccepted: Boolean = true) {
        val expected = _pendingReview.value ?: return
        val run = repo.getGenerationRun(expected.projectId, expected.runId)
        var changed = false
        var resumeRevision: Long? = null
        synchronized(stateMutationLock) {
            if (_pendingReview.value != expected) return@synchronized
            val wasAwaitingReview = _status.value == Status.AWAITING_REVIEW
            when (run?.status) {
                GenerationRun.STATUS_COMPLETED, GenerationRun.STATUS_RUNNING -> return@synchronized
                GenerationRun.STATUS_ACCEPTED -> {
                    replaceActionStatus(expected.actionId, AgentStep.ACTION_SUCCEEDED)
                    addStep(
                        AgentStep.OBSERVATION,
                        "用户已采用章节候选稿；正式正文现已更新，可以基于新正文继续。",
                        resultForActionId = expected.actionId,
                        resultKind = AgentStep.RESULT_COMMITTED,
                    )
                    _pendingReview.value = null
                    _pendingPrompt.value = null
                    _status.value = Status.STOPPED
                    if (resumeAccepted && wasAwaitingReview && !sessionTransitionPending) {
                        resumeRevision = runControlRevision
                    }
                    changed = true
                }
                GenerationRun.STATUS_REJECTED -> {
                    replaceActionStatus(expected.actionId, AgentStep.ACTION_DENIED)
                    addStep(
                        AgentStep.OBSERVATION,
                        "用户拒绝了章节候选稿；正式正文保持不变，智能体已暂停。",
                        resultForActionId = expected.actionId,
                        resultKind = AgentStep.RESULT_COMMITTED,
                    )
                    _pendingReview.value = null
                    _pendingPrompt.value = null
                    _status.value = Status.STOPPED
                    changed = true
                }
                GenerationRun.STATUS_FAILED,
                GenerationRun.STATUS_CANCELLED,
                null,
                -> {
                    replaceActionStatus(expected.actionId, AgentStep.ACTION_FAILED)
                    addStep(
                        AgentStep.OBSERVATION,
                        "章节候选稿已失效或不存在；正式正文未改变，智能体已暂停。",
                        resultForActionId = expected.actionId,
                        resultKind = AgentStep.RESULT_COMMITTED,
                    )
                    _pendingReview.value = null
                    _pendingPrompt.value = null
                    _status.value = Status.STOPPED
                    changed = true
                }
            }
        }
        if (!changed) return
        persist()
        resumeRevision?.let { revision ->
            launchLoop(revision, requiredStatus = Status.STOPPED)
        }
    }

    // ── tool helpers ────────────────────────────────────────────────────────────

    private fun JsonObject.str(k: String): String? = AgentToolArgumentPolicy.nonBlankString(this, k)
    private fun JsonObject.intOr(k: String, def: Int): Int = AgentToolArgumentPolicy.integer(this, k) ?: def
    private fun JsonObject.intOrNull(k: String): Int? = AgentToolArgumentPolicy.integer(this, k)
    private fun JsonObject.boolOr(k: String, def: Boolean): Boolean =
        AgentToolArgumentPolicy.boolean(this, k) ?: def
    private fun JsonObject.boolOrNull(k: String): Boolean? =
        AgentToolArgumentPolicy.boolean(this, k)
    private fun pid(args: JsonObject): String? = args.str("projectId") ?: _activeProjectId.value

    private fun validLockedProjectId(): String? =
        _activeProjectId.value?.takeIf { repo.project(it) != null }

    private fun sanitizeEngineMode(mode: String): String =
        if (mode == ENGINE_DUAL) ENGINE_DUAL else ENGINE_CLASSIC

    private fun newPlanIdentity(): String = "plan-${java.util.UUID.randomUUID()}"

    /** UUID avoids step-ID reuse after process/device restart; IDs anchor evidence and compaction. */
    private fun newAgentStepId(): String = "a-${java.util.UUID.randomUUID()}"

    /**
     * Plans persisted before plan identities existed receive a deterministic identity. Their old
     * unscoped actions intentionally keep an empty planId, so uncertain legacy evidence must be
     * re-established instead of being borrowed by a restored or later plan.
     */
    private fun ensurePlanIdentity(plan: AgentPlan, sessionId: String): AgentPlan {
        if (plan.planId.isNotBlank()) return plan
        val seed = "$sessionId|${plan.createdAt}|${plan.sourceCommandId}"
        val migratedId = java.util.UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))
        return plan.copy(planId = "legacy-$migratedId")
    }

    private fun restoredStatus(saved: String): Status = when (saved.lowercase()) {
        "done" -> Status.DONE
        "error" -> Status.ERROR
        "stopped", "running", "awaiting_user", "awaiting_confirm", "awaiting_review" -> Status.STOPPED
        else -> Status.IDLE
    }

    private fun resolveActionProjectId(args: JsonObject, scopeProjectId: String?): String =
        args.str("projectId") ?: scopeProjectId.orEmpty()

    private fun isInterruptedReplay(
        tool: String,
        resolvedProjectId: String,
    ): Boolean = _steps.value.any { step ->
        AgentInterruptedReplay.matches(
            step = step,
            tool = tool,
            resolvedProjectId = resolvedProjectId,
            fallbackProjectId = _activeProjectId.value.orEmpty(),
        )
    }

    private fun markInterruptedReplayReviewed(
        tool: String,
        resolvedProjectId: String,
    ) {
        val fallback = _activeProjectId.value.orEmpty()
        _steps.update { steps ->
            val reviewedIndex = steps.indexOfLast { step ->
                AgentInterruptedReplay.matches(
                    step,
                    tool,
                    resolvedProjectId,
                    fallback,
                )
            }
            if (reviewedIndex < 0) steps else steps.mapIndexed { index, step ->
                if (index == reviewedIndex) step.copy(actionStatus = AgentStep.ACTION_RECONCILED)
                else step
            }
        }
    }

    private fun blockActivePlanForInterruptedActions(interruptedActionIds: List<String>) {
        if (interruptedActionIds.isEmpty()) return
        val plan = _activePlan.value ?: return
        val current = plan.currentStep ?: return
        val currentWasInterrupted = _steps.value.any {
            it.id in interruptedActionIds && it.planStepId == current.id
        }
        if (currentWasInterrupted) {
            _activePlan.value = plan.blockCurrentStep(current.id)
        }
    }

    private fun isAutoApprovalEffective(): Boolean =
        _autoApprove.value && validLockedProjectId() != null

    /**
     * Auto-approval is scoped to one project. Explicitly targeting another project, or creating a
     * new project (which changes the scope without a projectId argument), must go back to the user.
     */
    private fun escapesAutoApprovedProject(tool: AgentTool, args: JsonObject): Boolean {
        if (!isAutoApprovalEffective()) return false
        val lockedId = validLockedProjectId() ?: return false
        val requestedId = args.str("projectId")
        return (requestedId != null && requestedId != lockedId) || tool.name == "create_project"
    }

    private fun updateLockedProject(projectId: String?) {
        val validId = projectId?.takeIf { repo.project(it) != null }
        if (_activeProjectId.value != validId) {
            _autoApprove.value = false
            if (_engineMode.value == ENGINE_DUAL) _activePlan.value = null
        }
        _activeProjectId.value = validId
    }

    private data class AgentActionContext(
        val actionId: String,
        val sessionId: String,
        val engineMode: String,
    )

    private sealed interface AgentToolExecution {
        data class Completed(val observation: String) : AgentToolExecution
        data class PendingReview(
            val observation: String,
            val review: AgentPendingReview,
        ) : AgentToolExecution
    }

    private fun pendingReviewFor(
        projectId: String,
        chapterId: String,
        context: AgentActionContext,
        observation: String,
    ): AgentToolExecution {
        val run = repo.latestGenerationRun(projectId, chapterId)
            ?.takeIf {
                it.status == GenerationRun.STATUS_COMPLETED &&
                    it.initiator == GenerationRun.INITIATOR_AGENT &&
                    it.agentSessionId == context.sessionId &&
                    it.agentActionId == context.actionId
            }
            ?: return AgentToolExecution.Completed(observation)
        val candidate = run.candidates.firstOrNull {
            it.status == CandidateChapter.STATUS_COMPLETED
        } ?: return AgentToolExecution.Completed(observation)
        return AgentToolExecution.PendingReview(
            observation = observation,
            review = AgentPendingReview(
                projectId = projectId,
                chapterId = chapterId,
                runId = run.id,
                candidateId = candidate.id,
                actionId = context.actionId,
            ),
        )
    }

    private enum class AgentToolEffect { READ_ONLY, MUTATING }

    private inner class AgentTool(
        val name: String,
        val desc: String,
        val sensitive: Boolean = false,
        val run: suspend (JsonObject, AgentActionContext) -> AgentToolExecution,
    ) {
        val effect: AgentToolEffect = if (name in READ_ONLY_TOOL_NAMES) {
            AgentToolEffect.READ_ONLY
        } else {
            // A newly added tool is never allowed to bypass interrupted-write quarantine merely
            // because its author forgot to classify it.
            AgentToolEffect.MUTATING
        }

        constructor(
            name: String,
            desc: String,
            sensitive: Boolean = false,
            run: suspend (JsonObject) -> String,
        ) : this(
            name,
            desc,
            sensitive,
            { args, _ -> AgentToolExecution.Completed(run(args)) },
        )
    }

    private fun sensitiveToolNames(): Set<String> = TOOLS.asSequence()
        .filter {
            it.sensitive || AgentToolArgumentPolicy.alwaysRequiresConfirmationByName(it.name)
        }
        .map { it.name }
        .toSet()

    /** Exposed for the system prompt. */
    fun toolDocs(): String = TOOLS.joinToString("\n") {
        val policy = when {
            AgentToolArgumentPolicy.alwaysRequiresConfirmationByName(it.name) -> " (始终需确认)"
            it.sensitive -> " (需确认)"
            else -> ""
        }
        "- ${it.name}$policy: ${it.desc}"
    }

    private fun buildTools(): List<AgentTool> = listOf(
        AgentTool("list_projects", "列出所有小说项目（id/标题/类型）") { _ ->
            val ps = repo.projects.value
            if (ps.isEmpty()) "（暂无项目）" else ps.joinToString("\n") { "- ${it.id} | ${it.title} | ${repo.novelType(it.id)}" }
        },
        AgentTool("create_project", "新建项目。args: title, genre, description, novelType(long|short)。会自动聚焦到新项目") { a ->
            val title = a.str("title") ?: return@AgentTool "缺少 title"
            val now = nowIso()
            val p = Project(id = "proj-${System.currentTimeMillis()}", title = title, genre = a.str("genre"),
                description = a.str("description"), created_at = now, updated_at = now)
            repo.createProject(p)
            repo.setNovelType(p.id, if (a.str("novelType") == "short") "short" else "long")
            updateLockedProject(p.id)
            "已创建项目《$title》(id=${p.id}) 并聚焦"
        },
        AgentTool("focus_project", "聚焦到某个已存在项目。args: projectId") { a ->
            val id = a.str("projectId") ?: return@AgentTool "缺少 projectId"
            if (repo.project(id) == null) return@AgentTool "项目不存在"
            updateLockedProject(id); "已聚焦项目 $id"
        },
        AgentTool("get_overview", "查看当前/指定项目概览（大纲节选、副本/弧线/角色/章节）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val p = repo.project(id) ?: return@AgentTool "项目不存在"
            buildString {
                appendLine("《${p.title}》题材=${p.genre.orEmpty()} 简介=${p.description.orEmpty().take(120)}")
                appendLine("世界观：${repo.worldSetting(id).take(120).ifBlank { "(空)" }}")
                appendLine("大纲节选：${repo.outline(id).take(200).ifBlank { "(空)" }}")
                appendLine("副本：" + (repo.volumes(id).joinToString("；") { it.name }.ifBlank { "(无)" }))
                appendLine("角色：" + (repo.characters(id).joinToString("、") { it.name }.ifBlank { "(无)" }))
                appendLine("章节数：${repo.chapters(id).size}")
            }
        },
        AgentTool("set_world_setting", "覆盖世界观。args: projectId?, text(非空)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val text = AgentToolArgumentPolicy.nonBlankString(a, "text")
                ?: return@AgentTool "缺少 text（必须显式提供非空字符串；已拒绝覆盖）"
            repo.setWorldSetting(id, text); "已更新世界观"
        },
        AgentTool("set_timeline", "覆盖时间线。args: projectId?, text(非空)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val text = AgentToolArgumentPolicy.nonBlankString(a, "text")
                ?: return@AgentTool "缺少 text（必须显式提供非空字符串；已拒绝覆盖）"
            repo.setTimeline(id, text); "已更新时间线"
        },
        AgentTool("generate_outline", "用 AI 生成长篇大纲并保存（依题材/简介/境界）。args: projectId?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val out = vm.agentGenerateOutline(id, streamingProgressCallback())
                ?: return@AgentTool "大纲生成失败"
            "已生成大纲（${out.length} 字符）。节选：${out.take(150)}…"
        },
        AgentTool("extract_characters_from_chapter", "从某章正文识别并把新出场的角色同步到角色管理（剧情推进新增角色时用）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            val added = vm.agentExtractCharactersFromChapter(id, cid)
            if (added.isEmpty()) "本章未发现需要新增的角色" else "已新增 ${added.size} 个角色：${added.joinToString("、")}"
        },
        AgentTool("import_characters_from_outline", "从大纲识别并导入角色。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (!vm.agentTextModelReady()) return@AgentTool "未配置文本模型"
            if (repo.outline(id).isBlank()) return@AgentTool "缺少可导入的非空大纲"
            suspendCancellableCoroutine { cont ->
                val child = vm.generateCharactersFromOutlineWithSource(id) { parsed, sourceOutline ->
                    if (!cont.isActive) return@generateCharactersFromOutlineWithSource
                    val add = vm.mergeGeneratedCharacters(id, parsed, expectedOutline = sourceOutline)
                    val result = when {
                        add.isNotEmpty() -> "已导入 ${add.size} 个角色：${add.joinToString("、") { it.name }}"
                        parsed.isEmpty() -> "角色导入失败：大纲中未提取到可导入角色"
                        repo.project(id) == null || repo.outline(id) != sourceOutline ->
                            "内容冲突：项目已删除或大纲已在导入期间修改"
                        else -> "角色导入失败：提取到的角色均已存在"
                    }
                    cont.resume(result)
                }
                bindChildJob(cont, child, "角色导入任务未启动")
            }
        },
        AgentTool("generate_character", "用一句话描述生成一个契合设定的角色。args: projectId?, brief") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val brief = a.str("brief") ?: return@AgentTool "缺少 brief"
            val c = vm.generateCharacterFromBrief(id, brief) ?: return@AgentTool "生成失败"
            val withId = c.copy(id = "char-${System.currentTimeMillis()}")
            val added = vm.mergeGeneratedCharacters(id, listOf(withId))
            if (added.isEmpty()) {
                "角色创建失败：项目已删除或同名角色已存在"
            } else {
                "已创建角色：${c.name}"
            }
        },
        AgentTool("generate_portrait", "为角色生成立绘（可传 prompt 自定义画面，否则用角色形象）。args: projectId?, character(姓名或id), prompt?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val ch = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val prompt = a.str("prompt")
                ?: ch.portraitPrompt?.takeIf { it.isNotBlank() } ?: ch.appearance.takeIf { it.isNotBlank() }
                ?: return@AgentTool "该角色暂无外貌描述，请提供 prompt 或先补充形象"
            val bytes = vm.generatePortraitImage(prompt) ?: return@AgentTool "立绘生成失败"
            currentCoroutineContext().ensureActive()
            val committed = repo.updateCharacterIfUnchanged(id, ch) {
                it.copy(portraitBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            if (!committed) return@AgentTool "未找到角色或角色已被修改，立绘未保存"
            addImageStep("${ch.name} 立绘", bytes)
            "已为 ${ch.name} 生成立绘"
        },
        AgentTool("generate_cover", "为项目生成封面（可传 prompt 自定义画面，否则按项目信息默认生成）。args: projectId?, prompt?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val p = repo.project(id) ?: return@AgentTool "项目不存在"
            val prompt = a.str("prompt") ?: "${p.genre.orEmpty()} ${p.title} cover art, ${p.description.orEmpty()}"
            val bytes = vm.generatePortraitImage(prompt, 1080, 1920) ?: return@AgentTool "封面生成失败"
            currentCoroutineContext().ensureActive()
            val item = CoverImageItem(id = "cover-${System.currentTimeMillis()}",
                name = "AI 封面", imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), prompt = prompt, createdAt = nowIso())
            val committed = repo.upsertCoverImage(id, item, makeDefault = true)
            if (!committed) return@AgentTool "项目不存在"
            addImageStep("项目封面", bytes)
            "已生成封面并设为默认"
        },
        AgentTool("create_volume", "新建副本。args: projectId?, name, description?, realmPlan?(本副本修为/境界上限的硬约束，如\"主角只突破到第一大境界巅峰，逐层稳步突破\")") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val realmPlan = a.str("realmPlan").orEmpty()
            val candidate = Volume(
                id = "vol-${System.currentTimeMillis()}",
                name = a.str("name") ?: "新副本",
                description = a.str("description").orEmpty(),
                createdAt = nowIso(),
                realmPlan = realmPlan,
            )
            val v = repo.appendVolumesIfProjectExists(id, listOf(candidate)).singleOrNull()
                ?: return@AgentTool "副本创建失败：项目已删除"
            "已创建副本《${v.name}》(id=${v.id})" + (if (realmPlan.isNotBlank()) "（已设修为上限）" else "")
        },
        AgentTool("generate_volumes", "AI 生成若干副本（不生成弧线）。args: projectId?, count, requirements?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            suspendCancellableCoroutine { cont ->
                val child = vm.generateVolumes(id, a.intOr("count", 3), a.str("requirements")) { n ->
                    if (cont.isActive) cont.resume("已生成 $n 个副本")
                }
                bindChildJob(cont, child, "副本生成任务未启动")
            }
        },
        AgentTool("list_volumes", "列出副本及其弧线。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vols = repo.volumes(id)
            if (vols.isEmpty()) "（无副本）" else vols.joinToString("\n") { v ->
                val arcs = repo.plotArcs(id).filter { it.volumeId == v.id }
                "副本 ${v.id} 《${v.name}》: " + (arcs.joinToString("；") { "${it.id}:${it.title}" }.ifBlank { "(无弧线)" })
            }
        },
        AgentTool("generate_arcs_for_volume", "在某副本内 AI 生成若干弧线。args: projectId?, volumeId, count, requirements?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            suspendCancellableCoroutine { cont ->
                val child = vm.generateArcsForVolume(id, vid, a.intOr("count", 3), a.str("requirements")) { n ->
                    if (cont.isActive) cont.resume("已在副本生成 $n 条弧线")
                }
                bindChildJob(cont, child, "弧线生成任务未启动")
            }
        },
        AgentTool("plan_arc_chapters", "为某弧线规划并创建若干章节。args: projectId?, arcId, count", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val n = vm.agentPlanArcChapters(id, arcId, a.intOr("count", 5))
            "已为弧线规划并创建 $n 个章节"
        },
        AgentTool("list_chapters", "列出章节（序号/id/标题/字数/所属副本+弧线）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cs = repo.chapters(id).sortedBy { it.order_index }
            if (cs.isEmpty()) return@AgentTool "（无章节）"
            val arcs = repo.plotArcs(id); val vols = repo.volumes(id)
            cs.joinToString("\n") { ch ->
                val arc = vm.chapterArcId(id, ch)?.let { aid -> arcs.firstOrNull { it.id == aid } }
                val vol = arc?.volumeId?.let { vid -> vols.firstOrNull { it.id == vid } }
                val loc = if (arc != null) " [副本:${vol?.name ?: "?"} / 弧线:${arc.title}]" else " [未归属弧线]"
                "第${ch.order_index}章 ${ch.id} 《${ch.title}》 ${ch.word_count}字$loc"
            }
        },
        AgentTool("get_chapter", "查看某章正文节选。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val b = repo.chapterBody(cid); val t = b.final.ifBlank { b.draft }
            if (t.isBlank()) "（该章暂无正文）" else "长度 ${t.length}；节选：${t.take(300)}…"
        },
        AgentTool("refine_chapter_plan", "生成正文前先细化本章规划（目标/核心冲突）——批量建的空白章规划很粗糙，写前先勘误细化。args: projectId?, chapterId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            vm.agentRefineChapterPlan(id, cid)?.let { "已细化章节规划——\n$it" } ?: "细化失败"
        },
        AgentTool("generate_chapter", "为某章生成候选正文，完成后等待用户审核（建议先 refine_chapter_plan）。args: projectId?, chapterId", sensitive = true) { a, context ->
            val id = pid(a)
                ?: return@AgentTool AgentToolExecution.Completed("无聚焦项目")
            val cid = a.str("chapterId")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 chapterId")
            val text = vm.agentGenerateChapterText(
                id,
                cid,
                context.engineMode,
                context.sessionId,
                context.actionId,
                streamingProgressCallback(),
            )
                ?: return@AgentTool AgentToolExecution.Completed("章节候选生成失败")
            pendingReviewFor(
                id,
                cid,
                context,
                "已生成章节候选稿（" + text.length + " 字符），等待用户审核；正式正文未改变。",
            )
        },
        AgentTool("revise_chapter", "按要求生成整章修订候选，完成后等待用户审核。小问题优先局部修改。args: projectId?, chapterId, instruction", sensitive = true) { a, context ->
            val id = pid(a)
                ?: return@AgentTool AgentToolExecution.Completed("无聚焦项目")
            val cid = a.str("chapterId")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 chapterId")
            val instruction = a.str("instruction")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 instruction")
            val text = vm.agentReviseChapter(
                id,
                cid,
                instruction,
                context.engineMode,
                context.sessionId,
                context.actionId,
                streamingProgressCallback(),
            )
                ?: return@AgentTool AgentToolExecution.Completed("整章修订候选生成失败")
            pendingReviewFor(
                id,
                cid,
                context,
                "已生成整章修订候选（" + text.length + " 字符），等待用户审核；正式正文未改变。",
            )
        },
        AgentTool("read_chapter", "读取某章完整正文（用于定位要局部修改的原文片段）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            val b = repo.chapterBody(cid); val t = b.final.ifBlank { b.draft }
            if (t.isBlank()) "（该章暂无正文）"
            else if (t.length > 8000) t.take(8000) + "\n…（已截断，共 ${t.length} 字符；如需后半段请告知）" else t
        },
        AgentTool("replace_in_chapter", "局部修改候选：精确替换正文片段，完成后等待用户审核。args: projectId?, chapterId, find, replace(空白=删除且始终需确认)", sensitive = true) { a, context ->
            val id = pid(a)
                ?: return@AgentTool AgentToolExecution.Completed("无聚焦项目")
            val cid = a.str("chapterId")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 chapterId")
            val find = a.str("find")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 find")
            val replace = AgentToolArgumentPolicy.explicitString(a, "replace")
                ?: return@AgentTool AgentToolExecution.Completed(
                    "缺少 replace（必须显式提供字符串；空字符串表示删除）",
                )
            val observation = vm.agentReplaceInChapter(
                id,
                cid,
                find,
                replace,
                context.engineMode,
                context.sessionId,
                context.actionId,
            )
            pendingReviewFor(id, cid, context, observation)
        },
        AgentTool("edit_paragraph", "局部修改候选：替换某章第 N 个非空段落，完成后等待用户审核。args: projectId?, chapterId, paragraphIndex(从1开始), newText") { a, context ->
            val id = pid(a)
                ?: return@AgentTool AgentToolExecution.Completed("无聚焦项目")
            val cid = a.str("chapterId")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 chapterId")
            val index = a.intOr("paragraphIndex", 0)
            if (index < 1) return@AgentTool AgentToolExecution.Completed("paragraphIndex 需≥1")
            val newText = a.str("newText")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 newText")
            val observation = vm.agentEditChapterParagraph(
                id,
                cid,
                index,
                newText,
                context.engineMode,
                context.sessionId,
                context.actionId,
            )
            pendingReviewFor(id, cid, context, observation)
        },
        AgentTool("list_paragraphs", "列出某章各段落（带序号，便于定位局部修改）。args: projectId?, chapterId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val b = repo.chapterBody(cid); val text = b.final.ifBlank { b.draft }
            if (text.isBlank()) return@AgentTool "（该章暂无正文）"
            text.split("\n").filter { it.isNotBlank() }.mapIndexed { i, p ->
                "段${i + 1}：${if (p.length > 120) p.take(120) + "…" else p}"
            }.joinToString("\n")
        },
        AgentTool("create_container", "新建资料容器。args: projectId?, name, type(by_character|by_chapter|single), autoUpdate?, affectsGeneration?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val type = a.str("type") ?: Container.SINGLE
            val c = Container(id = "ctn-${System.currentTimeMillis()}", name = a.str("name") ?: "新容器",
                type = type, autoUpdatePerChapter = a.boolOr("autoUpdate", false),
                affectsGeneration = a.boolOr("affectsGeneration", false), createdAt = nowIso())
            repo.createContainer(id, c)
            "已创建容器《${c.name}》(${type})"
        },
        AgentTool("retrieve", "就当前项目内容提问/检索（角色/境界/关系/事件/正文片段）。args: projectId?, question") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val q = a.str("question") ?: return@AgentTool "缺少 question"
            vm.agentAnswerQuestion(id, q)
        },
        AgentTool("web_search", "用 DuckDuckGo 联网搜索。args: query") { a ->
            val q = a.str("query") ?: return@AgentTool "缺少 query"
            val res = web.search(q, 5)
            if (res.isEmpty()) "（未找到结果或搜索不可用）"
            else res.joinToString("\n") { "- ${it.title}: ${it.snippet.take(120)} (${it.url})" }
        },
        AgentTool("insert_chapter", "在某章前/后插入新空白章。args: projectId?, referenceChapterId, before(true=前/false=后)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val ref = a.str("referenceChapterId") ?: return@AgentTool "缺少 referenceChapterId"
            val c = vm.insertChapter(id, ref, a.boolOr("before", false), "新章节") ?: return@AgentTool "插入失败（参考章节不存在）"
            "已插入新章节 ${c.id}（第${c.order_index}章），可用 generate_chapter 生成正文"
        },
        AgentTool("update_arc", "修改弧线信息。args: projectId?, arcId, title?, summary?, chapterCount?, status?(upcoming|active|completed)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val arc = repo.plotArcs(id).firstOrNull { it.id == arcId } ?: return@AgentTool "未找到弧线"
            val updated = arc.copy(
                title = a.str("title") ?: arc.title,
                summary = a.str("summary") ?: arc.summary,
                chapterCount = a.intOrNull("chapterCount") ?: arc.chapterCount,
                status = a.str("status") ?: arc.status,
            )
            if (updated == arc) return@AgentTool "未产生任何变化：弧线字段与当前值相同"
            if (!repo.updatePlotArcIfUnchanged(id, arc) { updated }) {
                return@AgentTool "内容冲突：弧线已被修改、删除或项目已删除"
            }
            "已更新弧线 $arcId"
        },
        AgentTool("update_volume", "修改副本信息。args: projectId?, volumeId, name?, description?, realmPlan?(本副本修为/境界上限的硬约束；设定后规划与生成都会严格遵守)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            val volume = repo.volumes(id).firstOrNull { it.id == vid } ?: return@AgentTool "未找到副本"
            val updated = volume.copy(
                name = a.str("name") ?: volume.name,
                description = a.str("description") ?: volume.description,
                realmPlan = a.str("realmPlan") ?: volume.realmPlan,
            )
            if (updated == volume) return@AgentTool "未产生任何变化：副本字段与当前值相同"
            if (!repo.updateVolumeIfUnchanged(id, volume) { updated }) {
                return@AgentTool "内容冲突：副本已被修改、删除或项目已删除"
            }
            "已更新副本 $vid" + (if (a.str("realmPlan") != null) "（已更新修为上限）" else "")
        },
        AgentTool("update_chapter", "修改章节标题/目标/冲突（真正改章节名，勿把标题写进正文）。args: projectId?, chapterId, title?, goal?, conflict?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            val updated = ch.copy(
                title = a.str("title") ?: ch.title,
                outline_goal = a.str("goal") ?: ch.outline_goal,
                conflict = a.str("conflict") ?: ch.conflict,
            )
            if (updated == ch) return@AgentTool "未产生任何变化：章节字段与当前值相同"
            if (!repo.updateChapterIfUnchanged(id, ch) { updated.copy(updated_at = nowIso()) }) {
                return@AgentTool "内容冲突：章节已被修改、删除或项目已删除"
            }
            "已更新章节 $cid（标题：${a.str("title") ?: ch.title}）"
        },
        AgentTool("reorder_volume", "调整副本顺序，移动到第几位。args: projectId?, volumeId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            val volumes = repo.volumes(id).sortedBy { it.order }
            val volume = volumes.firstOrNull { it.id == vid } ?: return@AgentTool "未找到副本"
            if (!repo.reorderVolumeIfUnchanged(id, volume, volumes, pos)) {
                return@AgentTool "内容冲突：副本列表已被修改或项目已删除"
            }
            "已把副本移到第 $pos 位"
        },
        AgentTool("reorder_arc", "调整某弧线在其所属副本内的顺序，移动到第几位。args: projectId?, arcId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            val arcs = repo.plotArcs(id)
            val arc = arcs.firstOrNull { it.id == arcId } ?: return@AgentTool "未找到弧线"
            val siblings = arcs.filter { it.volumeId == arc.volumeId }.sortedBy { it.order }
            if (!repo.reorderPlotArcIfUnchanged(id, arc, siblings, pos)) {
                return@AgentTool "内容冲突：弧线或其所属副本内的顺序已被修改，或项目已删除"
            }
            "已把弧线移到（所属副本内）第 $pos 位"
        },
        AgentTool("delete_chapter", "删除某章节（正文/草稿一并删除，并清理弧线与知识库引用）。args: projectId?, chapterId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            if (!repo.deleteChapterFullyIfUnchanged(id, ch)) {
                return@AgentTool "内容冲突：章节已被修改、删除或项目已删除"
            }
            "已删除第${ch.order_index}章《${ch.title}》"
        },
        AgentTool("assign_chapter_to_arc", "把章节归属到某弧线（建立 章节↔弧线↔副本 索引）。args: projectId?, chapterId, arcId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val ch = repo.chapters(id).firstOrNull { it.id == cid } ?: return@AgentTool "未找到章节"
            val arc = repo.plotArcs(id).firstOrNull { it.id == arcId } ?: return@AgentTool "未找到弧线"
            if (!repo.assignChapterToArcIfUnchanged(id, ch, arc)) {
                return@AgentTool "内容冲突：章节或弧线已被修改、删除，或项目已删除"
            }
            "已将章节 $cid 归属到弧线 $arcId"
        },
        AgentTool("get_structure", "查看项目结构树：副本 → 弧线 → 章节（用于发现归属/顺序问题）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vols = repo.volumes(id).sortedBy { it.order }
            val arcs = repo.plotArcs(id).sortedBy { it.order }
            val chapters = repo.chapters(id).sortedBy { it.order_index }
            fun chaptersOfArc(arcId: String) = chapters.filter { vm.chapterArcId(id, it) == arcId }
            buildString {
                vols.forEach { v ->
                    appendLine("副本 ${v.id}《${v.name}》")
                    arcs.filter { it.volumeId == v.id }.forEach { arc ->
                        appendLine("  弧线 ${arc.id}《${arc.title}》(${arc.status})")
                        chaptersOfArc(arc.id).forEach { ch -> appendLine("    第${ch.order_index}章 ${ch.id}《${ch.title}》") }
                    }
                }
                val orphanArcs = arcs.filter { it.volumeId == null || vols.none { v -> v.id == it.volumeId } }
                if (orphanArcs.isNotEmpty()) { appendLine("未归属副本的弧线："); orphanArcs.forEach { appendLine("  ${it.id}《${it.title}》") } }
                val orphanCh = chapters.filter { vm.chapterArcId(id, it) == null }
                if (orphanCh.isNotEmpty()) { appendLine("未归属弧线的章节："); orphanCh.forEach { appendLine("  第${it.order_index}章 ${it.id}《${it.title}》") } }
            }.trim().ifBlank { "（项目结构为空）" }
        },
        AgentTool("review_consistency", "审阅章节之间的前后矛盾 / 逻辑谬误 / 设定不一致。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            vm.agentReviewConsistency(id, streamingProgressCallback()) ?: "审阅失败"
        },
        AgentTool("generate_promo", "为某章生成【章节推文】＝整章头图(每章最多一张，概括全章；不同于段落插图)。仅对【已有正文】的章节生成，【已有推文的章节自动跳过】(force=true 才强制重做)。给 prompt 用自定义画面，不给则 AI 依正文出图。批量生成头图时直接对每个章节调用即可，会自动跳过空章/已有头图。args: projectId?, chapterId, prompt?, force?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val mediaSource = repo.captureChapterMediaSource(id, cid) ?: return@AgentTool "未找到章节"
            val source = mediaSource.chapter
            // 章节推文只针对【已有正文】的章节。
            val content = source.effectiveText
            if (content.length < 100) return@AgentTool "第${source.source.orderIndex}章《${source.source.title}》正文为空/过少，章节推文仅针对已有正文的章节，已跳过"
            // 【已有推文则跳过】，除非显式 force=true 强制重做。
            val existing = mediaSource.promo
            if (existing?.imageBase64 != null && !a.boolOr("force", false))
                return@AgentTool "第${source.source.orderIndex}章《${source.source.title}》已有推文，已跳过（如需重做请加 force=true）"
            val custom = a.str("prompt")
            if (custom != null) {
                val bytes = vm.generatePortraitImage(custom, 1024, 1024) ?: return@AgentTool "推文配图生成失败"
                currentCoroutineContext().ensureActive()
                val promo = ChapterPromo(
                    imagePrompt = custom,
                    summary = existing?.summary.orEmpty(),
                    imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
                val committed = repo.setChapterPromoIfSourceCurrent(
                    id,
                    source.source,
                    existing,
                    promo,
                )
                if (!committed) return@AgentTool "内容冲突：章节或推文在生成期间已修改/删除，过期配图未保存"
                addImageStep("推文配图", bytes)
                return@AgentTool "已用自定义提示词生成并应用推文配图"
            }
            val callbackSessionId = _currentSessionId.value
            val callbackRunRevision = runControlRevision
            suspendCancellableCoroutine { cont ->
                val callback: (ChapterPromo?, String?) -> Unit = callback@{ promo, err ->
                    val accepted = synchronized(stateMutationLock) {
                        if (
                            !cont.isActive ||
                            sessionTransitionPending ||
                            _currentSessionId.value != callbackSessionId ||
                            runControlRevision != callbackRunRevision
                        ) {
                            false
                        } else {
                            promo?.imageBase64?.let { addImageStepFromBase64("推文配图", it) }
                            true
                        }
                    }
                    if (!accepted || !cont.isActive) return@callback
                    cont.resume(
                        if (promo != null) "已生成推文：${promo.summary.take(60)}"
                        else "推文生成失败：${err.orEmpty()}",
                    )
                }
                val child = vm.generateChapterPromo(
                    id, cid, source.source.title, content, null, "zimage", 1024, 1024, callback,
                )
                bindChildJob(cont, child, "推文生成任务未启动")
            }
        },
        AgentTool("generate_illustration", "为某章生成【段落插图】＝锚定到正文【某一段】的插画(一章可多张，对应具体场景；不同于章节推文头图)。给 prompt 用自定义画面，不给则按该段正文节选自动出图。args: projectId?, chapterId, prompt?, paragraphIndex?(锚定第几段,默认1)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val source = repo.captureChapterSource(id, cid) ?: return@AgentTool "未找到章节"
            val content = source.effectiveText
            val prompt = a.str("prompt") ?: content.take(220).ifBlank { return@AgentTool "无 prompt 且本章无正文，无法生成插图" }
            val bytes = vm.generatePortraitImage(prompt, 768, 1024) ?: return@AgentTool "插图生成失败"
            currentCoroutineContext().ensureActive()
            val anchor = a.intOr("paragraphIndex", 1).coerceAtLeast(1)
            val illustration = Illustration(
                id = "ill-${System.currentTimeMillis()}", anchorIndex = anchor, paragraphIndices = listOf(anchor),
                prompt = prompt, imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP), createdAt = nowIso())
            val committed = repo.upsertIllustrationIfChapterSourceCurrent(id, source.source, illustration)
            if (!committed) return@AgentTool "内容冲突：章节在插图生成期间已修改/删除，过期插图未保存"
            addImageStep("第${anchor}段插图", bytes)
            "已生成并插入插图（锚定第 $anchor 段）"
        },
        AgentTool("create_snapshot", "保存当前项目为版本快照。args: projectId?, label?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            suspendCancellableCoroutine { cont ->
                val child = vm.saveSnapshot(id, a.str("label") ?: "智能体存档") { ok ->
                    if (cont.isActive) cont.resume(if (ok) "已保存版本快照" else "保存失败")
                }
                bindChildJob(cont, child, "快照保存任务未启动")
            }
        },
        AgentTool("list_snapshots", "列出项目的版本快照。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val list = vm.listSnapshots(id)
            if (list.isEmpty()) "（无快照）" else list.joinToString("\n") { "- ${it.id} | ${it.label.ifBlank { "(无备注)" }} | ${it.chapterCount}章 | ${it.createdAt}" }
        },
        AgentTool("restore_snapshot", "回退到某版本快照（覆盖当前内容，回退前会自动备份）。args: projectId?, snapshotId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            if (vm.listSnapshots(id).none { it.id == sid }) return@AgentTool "快照回退失败：未找到快照"
            suspendCancellableCoroutine { cont ->
                val child = vm.restoreSnapshot(id, sid) { res ->
                    if (cont.isActive) {
                        cont.resume(
                            if (res.success) {
                                "已回退；知识库待重建章节数 ${res.staleChapterIds.size}"
                            } else {
                                "快照回退失败：${res.errorMessage.orEmpty()}"
                            },
                        )
                    }
                }
                bindChildJob(cont, child, "快照回退任务未启动")
            }
        },
        AgentTool("rename_snapshot", "重命名版本快照。args: projectId?, snapshotId, label") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            vm.renameSnapshot(id, sid, a.str("label").orEmpty()); "已重命名快照"
        },
        AgentTool("delete_snapshot", "删除版本快照。args: projectId?, snapshotId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val sid = a.str("snapshotId") ?: return@AgentTool "缺少 snapshotId"
            vm.deleteSnapshot(id, sid); "已删除快照"
        },

        // ── 境界体系 (cultivation realms) ──
        AgentTool("list_realms", "查看修炼境界体系。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val realms = repo.cultivationRealms(id).sortedBy { it.order }
            if (realms.isEmpty()) "（暂无境界体系）"
            else realms.joinToString("\n") { r ->
                val subs = r.subRealms?.joinToString("、") { it.name }.orEmpty()
                "${r.order + 1}. ${r.id} 《${r.name}》${r.description?.let { "：$it" }.orEmpty()}" + if (subs.isNotBlank()) "（子境界：$subs）" else ""
            }
        },
        AgentTool("set_realms", "设置/重建整套境界体系（覆盖）。args: projectId?, realms=JSON数组 [{\"name\":..,\"description\":..,\"subRealms\":[{\"name\":..,\"description\":..}]}]", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arr = a["realms"]?.toString() ?: return@AgentTool "缺少 realms（JSON 数组）"
            val n = vm.agentSetRealmsFromJson(id, arr)
            if (n > 0) "已设置 $n 个境界" else "境界解析失败（需为 JSON 数组）"
        },
        AgentTool("add_realm", "追加一个境界到体系末尾。args: projectId?, name, description?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val name = a.str("name") ?: return@AgentTool "缺少 name"
            val existing = repo.cultivationRealms(id)
            val order = (existing.maxOfOrNull { it.order } ?: -1) + 1
            repo.setCultivationRealms(id, existing + CultivationRealm(
                id = "realm-${System.currentTimeMillis()}", order = order, name = name, description = a.str("description")))
            "已追加境界《$name》"
        },
        AgentTool("delete_realm", "删除某境界。args: projectId?, realmId 或 name", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("realmId") ?: a.str("name") ?: return@AgentTool "缺少 realmId/name"
            val before = repo.cultivationRealms(id)
            val after = before.filterNot { it.id == key || it.name == key }
            if (after.size == before.size) return@AgentTool "未找到境界"
            repo.setCultivationRealms(id, after.mapIndexed { i, r -> r.copy(order = i) }); "已删除境界"
        },

        // ── 角色读取 / 编辑 / 删除 ──
        AgentTool("list_characters", "列出角色（id/姓名/身份/性别/当前境界/主角）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val chars = repo.characters(id); if (chars.isEmpty()) return@AgentTool "（暂无角色）"
            val realms = repo.cultivationRealms(id)
            chars.joinToString("\n") { c ->
                val realm = c.currentRealmId?.let { rid -> realms.firstOrNull { it.id == rid }?.name }
                "- ${c.id} | ${c.name}${if (c.isProtagonist) "(主角)" else ""} | ${c.role.ifBlank { "?" }}${realm?.let { " | 境界:$it" }.orEmpty()}"
            }
        },
        AgentTool("get_character", "查看角色详情。args: projectId?, character(姓名或id)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            buildString {
                appendLine("${c.name}${if (c.isProtagonist) "（主角）" else ""} id=${c.id}")
                if (c.role.isNotBlank()) appendLine("身份：${c.role}")
                if (c.gender.isNotBlank()) appendLine("性别：${c.gender}")
                if (c.personality.isNotBlank()) appendLine("性格：${c.personality}")
                if (c.background.isNotBlank()) appendLine("背景：${c.background}")
                if (c.motivation.isNotBlank()) appendLine("动机：${c.motivation}")
                if (c.appearance.isNotBlank()) appendLine("形象：${c.appearance}")
                c.currentRealmId?.let { rid -> repo.cultivationRealms(id).firstOrNull { it.id == rid }?.let { appendLine("当前境界：${it.name}") } }
            }.trim()
        },
        AgentTool("update_character", "修改角色信息（含指定境界）。args: projectId?, characterId, name?, gender?, role?, personality?, background?, motivation?, appearance?, isProtagonist?, realm?(境界名), subRealm?(子境界名)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("characterId") ?: a.str("character") ?: return@AgentTool "缺少 characterId"
            val c = repo.characters(id).firstOrNull { it.id == cid || it.name == cid } ?: return@AgentTool "未找到角色"
            val realms = repo.cultivationRealms(id)
            val requestedRealmName = a.str("realm")
            val requestedSubRealmName = a.str("subRealm")
            val requestedRealm = requestedRealmName?.let { name ->
                realms.firstOrNull { it.name == name }
                    ?: return@AgentTool "未找到境界：$name"
            }
            var realmId = requestedRealm?.id ?: c.currentRealmId
            var subId = if (requestedRealm != null) null else c.currentSubRealmId

            if (requestedSubRealmName != null) {
                val matches = realms.flatMap { realm ->
                    realm.subRealms.orEmpty()
                        .filter { it.name == requestedSubRealmName }
                        .map { realm to it }
                }.filter { (realm, _) -> requestedRealm == null || realm.id == requestedRealm.id }
                if (matches.isEmpty()) {
                    return@AgentTool "未找到子境界：$requestedSubRealmName"
                }
                if (matches.size > 1) {
                    return@AgentTool "子境界名称不唯一，请同时指定唯一的 realm：$requestedSubRealmName"
                }
                val (ownerRealm, subRealm) = matches.single()
                realmId = ownerRealm.id
                subId = subRealm.id
            }

            val updated = c.copy(
                name = a.str("name") ?: c.name,
                gender = a.str("gender") ?: c.gender,
                role = a.str("role") ?: c.role,
                personality = a.str("personality") ?: c.personality,
                background = a.str("background") ?: c.background,
                motivation = a.str("motivation") ?: c.motivation,
                appearance = a.str("appearance") ?: c.appearance,
                isProtagonist = a.boolOrNull("isProtagonist") ?: c.isProtagonist,
                currentRealmId = realmId,
                currentSubRealmId = subId,
            )
            if (updated == c) return@AgentTool "未产生任何变化：角色字段与当前值相同"
            if (!repo.updateCharacterIfUnchanged(id, c) { updated }) {
                return@AgentTool "内容冲突：角色已被修改、删除或项目已删除"
            }
            "已更新角色 ${c.name}"
        },
        AgentTool("get_character_growth", "查看某角色的成长路线（按章演进的发展记录）。args: projectId?, character(姓名或id)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val chain = repo.characterGrowth(id, c.id)
            if (chain.isEmpty()) "（${c.name} 暂无成长记录）"
            else chain.joinToString("\n") { e ->
                val src = e.chapterOrder?.let { "第${it}章" } ?: if (e.manual) "手动" else ""
                "- $src：${e.value.take(200)}"
            }
        },
        AgentTool("add_character_growth", "为某角色追加一条成长记录（绑定章节，会软引导后续生成）。args: projectId?, character(姓名或id), value, chapterId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val value = a.str("value") ?: return@AgentTool "缺少 value"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            val ch = a.str("chapterId")?.let { cid -> repo.chapters(id).firstOrNull { it.id == cid } }
            vm.addCharacterGrowth(id, c.id, value, ch, manual = ch == null)
            "已记录 ${c.name} 的成长" + (ch?.let { "（第${it.order_index}章）" } ?: "")
        },
        AgentTool("delete_character", "删除角色。args: projectId?, character(姓名或id)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val key = a.str("character") ?: return@AgentTool "缺少 character"
            val c = repo.characters(id).firstOrNull { it.id == key || it.name == key } ?: return@AgentTool "未找到角色"
            if (!repo.deleteCharacterIfUnchanged(id, c)) {
                return@AgentTool "内容冲突：角色已被修改、删除或项目已删除"
            }
            "已删除角色 ${c.name}"
        },

        // ── 结构删除 / 章节移动 ──
        AgentTool("delete_arc", "删除某剧情弧线（不删章节）。args: projectId?, arcId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val arcId = a.str("arcId") ?: return@AgentTool "缺少 arcId"
            val arc = repo.plotArcs(id).firstOrNull { it.id == arcId } ?: return@AgentTool "未找到弧线"
            if (!repo.deletePlotArcIfUnchanged(id, arc)) {
                return@AgentTool "内容冲突：弧线已被修改、删除或项目已删除"
            }
            "已删除弧线"
        },
        AgentTool("delete_volume", "删除某副本及其下所有弧线（不删章节）。args: projectId?, volumeId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val vid = a.str("volumeId") ?: return@AgentTool "缺少 volumeId"
            val volume = repo.volumes(id).firstOrNull { it.id == vid } ?: return@AgentTool "未找到副本"
            val ownedArcs = repo.plotArcs(id).filter { it.volumeId == vid }.sortedBy { it.order }
            if (!repo.deleteVolumeIfUnchanged(id, volume, ownedArcs)) {
                return@AgentTool "内容冲突：副本或其所属弧线已被修改、删除，或项目已删除"
            }
            "已删除副本及其弧线"
        },
        AgentTool("move_chapter", "调整章节顺序，移动到第几位。args: projectId?, chapterId, position(从1开始)") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("chapterId") ?: return@AgentTool "缺少 chapterId"
            val pos = a.intOr("position", 0); if (pos < 1) return@AgentTool "position 需≥1"
            if (repo.chapters(id).none { it.id == cid }) return@AgentTool "未找到章节"
            vm.moveChapterToPosition(id, cid, pos); "已把章节移到第 $pos 位"
        },
        AgentTool("renumber_chapters", "把章节序号重排为连续的 1..N（修复删除后留下的跳号；不影响章节与弧线的归属索引）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val changed = vm.renumberChapters(id)
            if (changed) "已将章节序号重排为连续编号" else "章节序号本已连续，无需重排"
        },

        // ── 项目编辑 / 删除 ──
        AgentTool("update_project", "修改项目信息。args: projectId?, title?, genre?, description?, status?, targetWordCount?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val project = repo.project(id) ?: return@AgentTool "项目不存在"
            val updated = project.copy(
                title = a.str("title") ?: project.title,
                genre = a.str("genre") ?: project.genre,
                description = a.str("description") ?: project.description,
                status = a.str("status") ?: project.status,
                target_word_count = a.intOrNull("targetWordCount") ?: project.target_word_count,
            )
            if (updated == project) return@AgentTool "未产生任何变化：项目字段与当前值相同"
            vm.updateProject(id) { updated }
            "已更新项目信息"
        },
        AgentTool("delete_project", "删除整个项目（不可恢复）。args: projectId", sensitive = true) { a ->
            val id = a.str("projectId") ?: _activeProjectId.value ?: return@AgentTool "缺少 projectId"
            val title = repo.project(id)?.title ?: return@AgentTool "项目不存在"
            vm.deleteProject(id)
            if (_activeProjectId.value == id) updateLockedProject(null)
            "已删除项目《$title》"
        },

        // ── 大纲 / 正文 直接读写 ──
        AgentTool("get_outline", "查看完整大纲文本。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val o = repo.outline(id)
            if (o.isBlank()) "（暂无大纲）" else if (o.length > 2500) o.take(2500) + "\n…（已截断，共 ${o.length} 字符）" else o
        },
        AgentTool("set_outline", "直接写入/覆盖大纲文本。args: projectId?, text(非空)", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val text = AgentToolArgumentPolicy.nonBlankString(a, "text")
                ?: return@AgentTool "缺少 text（必须显式提供非空字符串；已拒绝覆盖）"
            repo.setOutline(id, text); "已更新大纲"
        },
        AgentTool("set_chapter_body", "创建整章正文替换候选（用于精确设定或清理脏数据，不调用 AI），等待用户审核后才生效。args: projectId?, chapterId, text(非空)", sensitive = true) { a, context ->
            val id = pid(a)
                ?: return@AgentTool AgentToolExecution.Completed("无聚焦项目")
            val cid = a.str("chapterId")
                ?: return@AgentTool AgentToolExecution.Completed("缺少 chapterId")
            val text = AgentToolArgumentPolicy.nonBlankString(a, "text")
                ?: return@AgentTool AgentToolExecution.Completed(
                    "缺少 text（必须显式提供非空字符串；已拒绝覆盖）",
                )
            val observation = vm.agentSetChapterText(
                id,
                cid,
                text,
                context.engineMode,
                context.sessionId,
                context.actionId,
            )
            pendingReviewFor(id, cid, context, observation)
        },

        // ── 摘要 / 知识库 ──
        AgentTool("generate_book_summary", "生成全书梗概（汇总章节/弧线摘要）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (!vm.agentTextModelReady()) return@AgentTool "未配置文本模型"
            suspendCancellableCoroutine { cont ->
                val child = vm.generateBookSummary(id) { sp ->
                    if (cont.isActive) cont.resume(if (sp != null) "已生成全书梗概" else "生成失败（可能需先生成章节摘要）")
                }
                bindChildJob(cont, child, "全书梗概任务未启动")
            }
        },
        AgentTool("generate_chapter_summaries", "为所有章节生成摘要（供长程记忆/审阅）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            if (!vm.agentTextModelReady()) return@AgentTool "未配置文本模型"
            suspendCancellableCoroutine { cont ->
                val child = vm.generateChapterSummariesForAll(
                    id,
                    onDone = { ok, err -> if (cont.isActive) cont.resume("章节摘要：成功 $ok，失败 $err") },
                )
                bindChildJob(cont, child, "章节摘要任务未启动")
            }
        },
        AgentTool("rebuild_kb", "重建本地知识库向量索引（需已配置 Embedding）。args: projectId?", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cfg = vm.embeddingConfig()
            if (!cfg.isUsableApiConfig()) return@AgentTool "未配置安全可用的 Embedding，无法重建"
            suspendCancellableCoroutine { cont ->
                val child = vm.rebuildKnowledgeBase(id, onDone = { ch, chunks, errs, firstErr ->
                    if (cont.isActive) cont.resume("知识库重建：$ch 章 / $chunks 块 / 失败 $errs${firstErr?.let { "（$it）" }.orEmpty()}")
                })
                bindChildJob(cont, child, "知识库重建任务未启动")
            }
        },
        AgentTool("set_kb_features", "开关本地知识库 / 摘要 / 实体功能。args: knowledgeBase?, summaries?, entities?") { a ->
            val oldKnowledgeBase = vm.knowledgeBaseEnabled()
            val oldSummaries = vm.summariesEnabled()
            val oldEntities = vm.entitiesEnabled()
            val knowledgeBase = a.boolOrNull("knowledgeBase") ?: oldKnowledgeBase
            val summaries = a.boolOrNull("summaries") ?: oldSummaries
            val entities = a.boolOrNull("entities") ?: oldEntities
            if (knowledgeBase == oldKnowledgeBase && summaries == oldSummaries && entities == oldEntities) {
                return@AgentTool "未产生任何变化：知识库功能开关与当前值相同"
            }
            if (knowledgeBase != oldKnowledgeBase) vm.setKnowledgeBaseEnabled(knowledgeBase)
            if (summaries != oldSummaries) vm.setSummariesEnabled(summaries)
            if (entities != oldEntities) vm.setEntitiesEnabled(entities)
            "已更新知识库功能开关（KB=${vm.knowledgeBaseEnabled()} 摘要=${vm.summariesEnabled()} 实体=${vm.entitiesEnabled()}）"
        },

        // ── 容器管理 ──
        AgentTool("list_containers", "列出资料容器（id/名称/类型/开关）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cs = repo.containers(id); if (cs.isEmpty()) return@AgentTool "（暂无容器）"
            cs.joinToString("\n") { c ->
                "- ${c.id} | ${c.name} | ${c.type}" +
                    (if (c.autoUpdatePerChapter) " 按章更新" else "") + (if (c.affectsGeneration) " 影响章节" else "") +
                    (if (c.affectsVolumeGeneration) " 影响副本" else "") + (if (c.affectsArcGeneration) " 影响弧线" else "")
            }
        },
        AgentTool("update_container", "修改容器名称/开关（类型不可改）。args: projectId?, containerId, name?, autoUpdate?, affectsGeneration?, affectsVolumeGeneration?, affectsArcGeneration?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            val c = repo.container(id, cid) ?: return@AgentTool "未找到容器"
            val name = a.str("name") ?: c.name
            val autoUpdate = a.boolOrNull("autoUpdate") ?: c.autoUpdatePerChapter
            val affectsGeneration = a.boolOrNull("affectsGeneration") ?: c.affectsGeneration
            val affectsVolumeGeneration = a.boolOrNull("affectsVolumeGeneration") ?: c.affectsVolumeGeneration
            val affectsArcGeneration = a.boolOrNull("affectsArcGeneration") ?: c.affectsArcGeneration
            if (
                name == c.name &&
                autoUpdate == c.autoUpdatePerChapter &&
                affectsGeneration == c.affectsGeneration &&
                affectsVolumeGeneration == c.affectsVolumeGeneration &&
                affectsArcGeneration == c.affectsArcGeneration
            ) {
                return@AgentTool "未产生任何变化：容器字段与当前值相同"
            }
            repo.updateContainerMeta(
                id,
                cid,
                name = name,
                autoUpdatePerChapter = autoUpdate,
                affectsGeneration = affectsGeneration,
                affectsVolumeGeneration = affectsVolumeGeneration,
                affectsArcGeneration = affectsArcGeneration,
            )
            "已更新容器 $name"
        },
        AgentTool("append_container_entry", "向容器某分块追加一个值。args: projectId?, containerId, blockKey(角色id/章节id/“main”), value") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            val block = a.str("blockKey") ?: Container.SINGLE_BLOCK_KEY
            val value = a.str("value") ?: return@AgentTool "缺少 value"
            if (repo.container(id, cid) == null) return@AgentTool "未找到容器"
            repo.appendContainerEntry(id, cid, block, ContainerEntry(id = "e-${System.currentTimeMillis()}", value = value, createdAt = nowIso(), manual = true))
            "已写入容器值"
        },
        AgentTool("delete_container", "删除容器及其全部值。args: projectId?, containerId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cid = a.str("containerId") ?: return@AgentTool "缺少 containerId"
            if (repo.container(id, cid) == null) return@AgentTool "未找到容器"
            repo.deleteContainer(id, cid); "已删除容器"
        },

        // ── 封面管理 ──
        AgentTool("list_covers", "列出项目封面（id/名称/是否默认）。args: projectId?") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val covers = vm.getCoverImages(id); if (covers.isEmpty()) return@AgentTool "（暂无封面）"
            val def = repo.project(id)?.default_cover_id
            covers.joinToString("\n") { "- ${it.id} | ${it.name}${if (it.id == def) " (默认)" else ""}" }
        },
        AgentTool("set_default_cover", "设为默认封面。args: projectId?, coverId") { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cov = a.str("coverId") ?: return@AgentTool "缺少 coverId"
            if (vm.getCoverImages(id).none { it.id == cov }) return@AgentTool "未找到封面"
            vm.setDefaultCover(id, cov); "已设为默认封面"
        },
        AgentTool("delete_cover", "删除某封面。args: projectId?, coverId", sensitive = true) { a ->
            val id = pid(a) ?: return@AgentTool "无聚焦项目"
            val cov = a.str("coverId") ?: return@AgentTool "缺少 coverId"
            if (vm.getCoverImages(id).none { it.id == cov }) return@AgentTool "未找到封面"
            vm.deleteProjectCover(id, cov); "已删除封面"
        },

        // ── 设置：文本模型 / 图片引擎（绝不输出任何密钥） ──
        AgentTool("get_settings", "查看当前生成设置（文本模型/图片引擎/知识库开关，不含任何密钥）。args: 无") { _ ->
            val cfg = vm.activeTextModelConfig()
            "文本模型：${cfg.provider} / ${cfg.model}；图片引擎：${vm.imageEngine()}；" +
                "知识库=${vm.knowledgeBaseEnabled()} 摘要=${vm.summariesEnabled()} 实体=${vm.entitiesEnabled()}"
        },
        AgentTool("list_text_models", "列出可选的文本模型 profile（id/名称/provider/模型，不含密钥）。args: 无") { _ ->
            val active = vm.activeTextModelProfileId()
            val profiles = vm.textModelProfiles()
            if (profiles.isEmpty()) "（无 profile）"
            else profiles.joinToString("\n") { "- ${it.id} | ${it.name} | ${it.provider}/${it.model}${if (it.id == active) " (当前)" else ""}" }
        },
        AgentTool("set_text_model", "切换当前文本模型 profile。args: profileId") { a ->
            val pidv = a.str("profileId") ?: return@AgentTool "缺少 profileId"
            if (vm.textModelProfiles().none { it.id == pidv }) return@AgentTool "未找到该 profile"
            vm.setActiveProfile(pidv); "已切换文本模型 profile"
        },
        AgentTool("set_image_engine", "切换图片生成引擎。args: engine(pollinations|comfyui)") { a ->
            val e = a.str("engine") ?: return@AgentTool "缺少 engine"
            if (e != "pollinations" && e != "comfyui") return@AgentTool "engine 仅支持 pollinations / comfyui"
            vm.setImageEngine(e); "已切换图片引擎为 $e"
        },
    )

    companion object {
        const val ENGINE_CLASSIC = "classic"
        const val ENGINE_DUAL = "dual"
        private const val ASK_USER_ACTION = "ask_user"
        private const val COMPLETE_PLAN_STEP_ACTION = "complete_plan_step"
        private const val PLANNER_RETRIES = 2
        private const val MAX_STEPS = 40
        private const val MODEL_RETRIES = 3
        private const val RECOVERY_JOIN_TIMEOUT_MS = 20_000L
        private val READ_ONLY_TOOL_NAMES = setOf(
            "focus_project",
            "get_character",
            "get_character_growth",
            "get_chapter",
            "get_outline",
            "get_overview",
            "get_settings",
            "get_structure",
            "list_characters",
            "list_chapters",
            "list_containers",
            "list_covers",
            "list_paragraphs",
            "list_projects",
            "list_realms",
            "list_snapshots",
            "list_text_models",
            "list_volumes",
            "read_chapter",
            "retrieve",
            "review_consistency",
            "web_search",
        )
        private val ACTIVE_CONTROLLER_LOCK = Any()
        private val GLOBAL_AGENT_PERSISTENCE_LOCK = Any()
        private val REQUIRED_ACTION_STATUSES = setOf(
            AgentStep.ACTION_PROPOSED,
            AgentStep.ACTION_RUNNING,
            AgentStep.ACTION_AWAITING_REVIEW,
            AgentStep.ACTION_INTERRUPTED,
        )

        /** The active controller, so [AgentForegroundService]'s Stop action can reach it. */
        @Volatile
        var active: AgentController? = null
    }
}
