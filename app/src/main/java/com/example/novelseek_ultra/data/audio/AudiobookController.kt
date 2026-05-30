package com.example.novelseek_ultra.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.novelseek_ultra.data.AppRepository
import com.example.novelseek_ultra.data.ai.EdgeTtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Drives audiobook playback for one project at a time. A chapter's text is split into short
 * segments; each segment is synthesized to MP3 via [EdgeTtsService] and played in order. Because
 * the first (small) segment synthesizes fast and the rest follow back-to-back, playback feels
 * streamed. When a chapter ends, the next chapter (by order_index) auto-plays. Foreground/in-app
 * only — leaving the listen screen pauses and saves progress.
 */
class AudiobookController(
    private val context: Context,
    private val repo: AppRepository,
    private val scope: CoroutineScope,
) {
    private val tts = EdgeTtsService()

    private val _projectId = MutableStateFlow<String?>(null)
    val projectId: StateFlow<String?> = _projectId.asStateFlow()
    private val _chapterId = MutableStateFlow<String?>(null)
    val chapterId: StateFlow<String?> = _chapterId.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()
    private val _segmentIndex = MutableStateFlow(0)
    val segmentIndex: StateFlow<Int> = _segmentIndex.asStateFlow()
    private val _segmentCount = MutableStateFlow(0)
    val segmentCount: StateFlow<Int> = _segmentCount.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _voice = MutableStateFlow(repo.listenVoice())
    val voice: StateFlow<String> = _voice.asStateFlow()
    private val _rate = MutableStateFlow(repo.listenRate())
    val rate: StateFlow<Int> = _rate.asStateFlow()

    private var engineJob: Job? = null
    private val mpLock = Any()
    private var mp: MediaPlayer? = null

    // One-segment-ahead prefetch: while segment i plays, segment i+1 is synthesized in the
    // background. The voice/rate it was made with are recorded so a mid-playback voice/speed change
    // can invalidate it and re-prepare with the new settings.
    private val preLock = Any()
    private var preChapter: String? = null
    private var preIndex: Int = -1
    private var preVoice: String = ""
    private var preRate: Int = 0
    private var preJob: Deferred<File?>? = null

    // ── public controls ──────────────────────────────────────────────────────

    /** Select a project, restoring its saved chapter + segment (or the first chapter). Stops audio. */
    fun selectProject(projectId: String) {
        if (_projectId.value == projectId) return
        stopInternal(save = true)
        _projectId.value = projectId
        repo.setLastListenProjectId(projectId)
        val saved = repo.listenProgress(projectId)
        val chapters = orderedChapters(projectId)
        val chId = saved?.first?.takeIf { id -> chapters.any { it.id == id } }
            ?: chapters.firstOrNull()?.id
        _chapterId.value = chId
        _segmentIndex.value = saved?.second ?: 0
        _segmentCount.value = chId?.let { segmentsOf(it).size } ?: 0
        _status.value = ""
    }

    fun toggle() {
        if (_isPlaying.value) pause() else play()
    }

    fun play() {
        val pid = _projectId.value ?: return
        val chId = _chapterId.value ?: orderedChapters(pid).firstOrNull()?.id ?: return
        _chapterId.value = chId
        if (engineJob?.isActive == true) {
            _isPlaying.value = true
            synchronized(mpLock) { mp?.let { if (!it.isPlaying) runCatching { it.start() } } }
            return
        }
        _isPlaying.value = true
        startEngine(pid, chId, _segmentIndex.value)
    }

    fun pause() {
        _isPlaying.value = false
        synchronized(mpLock) { mp?.let { if (it.isPlaying) runCatching { it.pause() } } }
        saveProgress()
    }

    /** Jump to a specific chapter and start playing it from the beginning. */
    fun playChapter(chapterId: String) {
        val pid = _projectId.value ?: return
        stopInternal(save = true)
        _chapterId.value = chapterId
        _segmentIndex.value = 0
        _segmentCount.value = segmentsOf(chapterId).size
        _isPlaying.value = true
        startEngine(pid, chapterId, 0)
    }

    fun nextChapter() {
        val pid = _projectId.value ?: return
        val cur = _chapterId.value ?: return
        nextChapterAfter(pid, cur)?.let { playChapter(it.id) }
    }

    fun prevChapter() {
        val pid = _projectId.value ?: return
        val cur = _chapterId.value ?: return
        val chapters = orderedChapters(pid)
        val idx = chapters.indexOfFirst { it.id == cur }
        if (idx > 0) playChapter(chapters[idx - 1].id)
    }

    fun setVoice(voice: String) { _voice.value = voice; repo.setListenVoice(voice); repreparePrefetch() }
    fun setRate(rate: Int) { _rate.value = rate; repo.setListenRate(rate); repreparePrefetch() }

    /** Jump to a segment within the current chapter (drag the progress slider). */
    fun seekSegment(index: Int) {
        val pid = _projectId.value ?: return
        val chId = _chapterId.value ?: return
        val count = segmentsOf(chId).size
        if (count == 0) return
        val idx = index.coerceIn(0, count - 1)
        val wasPlaying = _isPlaying.value
        stopInternal(save = false)
        _segmentIndex.value = idx
        _segmentCount.value = count
        saveProgress()
        if (wasPlaying) { _isPlaying.value = true; startEngine(pid, chId, idx) }
    }

    /** Pause + persist progress; called when the listen screen leaves composition. */
    fun pauseAndSave() {
        if (_isPlaying.value) pause() else saveProgress()
    }

    fun release() = stopInternal(save = true)

    // ── engine ────────────────────────────────────────────────────────────────

    private fun startEngine(projectId: String, startChapterId: String, startSegment: Int) {
        engineJob?.cancel()
        cancelPrefetch()
        engineJob = scope.launch(Dispatchers.IO) {
            var chId = startChapterId
            var seg = startSegment
            while (coroutineContext.isActive) {
                val segments = segmentsOf(chId)
                _chapterId.value = chId
                _segmentCount.value = segments.size

                if (segments.isEmpty() || seg >= segments.size) {
                    val next = nextChapterAfter(projectId, chId)
                    if (next == null) { _isPlaying.value = false; break }
                    chId = next.id; seg = 0
                    continue
                }

                _segmentIndex.value = seg
                saveProgress()

                _preparing.value = true
                val file = obtainSegment(chId, seg, segments[seg])
                if (file == null) { _preparing.value = false; _isPlaying.value = false; break }
                if (!coroutineContext.isActive) { runCatching { file.delete() }; break }

                // Prepare the NEXT segment while this one plays, so playback is gapless.
                if (seg + 1 < segments.size) launchPrefetch(chId, seg + 1)

                playSegment(file)
                seg++
            }
        }
    }

    /** Current segment audio: use the prefetched file if it matches current params, else synth now. */
    private suspend fun obtainSegment(chapterId: String, seg: Int, text: String): File? {
        takePrefetch(chapterId, seg)?.let { job ->
            runCatching { job.await() }.getOrNull()?.let { return it }
        }
        return try {
            writeTemp(tts.synthesize(text, _voice.value, _rate.value))
        } catch (e: Exception) {
            _status.value = "朗读失败：${e.message ?: "网络错误"}"
            null
        }
    }

    private fun launchPrefetch(chapterId: String, index: Int) {
        synchronized(preLock) {
            cancelPrefetchLocked()
            val text = segmentsOf(chapterId).getOrNull(index) ?: return
            val v = _voice.value
            val r = _rate.value
            preChapter = chapterId; preIndex = index; preVoice = v; preRate = r
            preJob = scope.async(Dispatchers.IO) {
                runCatching { writeTemp(tts.synthesize(text, v, r)) }.getOrNull()
            }
        }
    }

    /** Hand the prefetched job to the engine if it matches (chapter, index, voice, rate); else drop it. */
    private fun takePrefetch(chapterId: String, index: Int): Deferred<File?>? = synchronized(preLock) {
        if (preChapter == chapterId && preIndex == index && preVoice == _voice.value && preRate == _rate.value) {
            val job = preJob
            preJob = null; preChapter = null; preIndex = -1
            job
        } else {
            cancelPrefetchLocked()
            null
        }
    }

    /** Re-synthesize the in-flight prefetch with the current voice/rate (after a settings change). */
    private fun repreparePrefetch() {
        synchronized(preLock) {
            val chId = preChapter ?: return
            val idx = preIndex
            if (idx >= 0) launchPrefetch(chId, idx)   // cancels the stale job and relaunches
        }
    }

    private fun cancelPrefetch() = synchronized(preLock) { cancelPrefetchLocked() }

    private fun cancelPrefetchLocked() {
        preJob?.let { job ->
            job.cancel()
            // If it already finished, clean up its orphaned temp file off-thread.
            scope.launch(Dispatchers.IO) { runCatching { job.await()?.delete() } }
        }
        preJob = null; preChapter = null; preIndex = -1
    }

    private suspend fun playSegment(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val player = MediaPlayer()
        synchronized(mpLock) { releaseMpLocked(); mp = player }
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                synchronized(mpLock) { if (mp === player) mp = null }
                runCatching { player.release() }
                runCatching { file.delete() }
                if (cont.isActive) cont.resume(Unit)
            }
            player.setOnErrorListener { _, _, _ ->
                synchronized(mpLock) { if (mp === player) mp = null }
                runCatching { player.release() }
                runCatching { file.delete() }
                if (cont.isActive) cont.resume(Unit)   // skip a bad segment rather than stalling
                true
            }
            player.prepare()                 // local file → fast/synchronous
            _preparing.value = false
            if (_isPlaying.value) player.start()   // if paused, resume() will start it later
        } catch (e: Exception) {
            _preparing.value = false
            runCatching { file.delete() }
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation {
            synchronized(mpLock) { if (mp === player) mp = null }
            runCatching { player.stop() }
            runCatching { player.release() }
            runCatching { file.delete() }
        }
    }

    private fun stopInternal(save: Boolean) {
        if (save) saveProgress()
        engineJob?.cancel()
        engineJob = null
        cancelPrefetch()
        _isPlaying.value = false
        _preparing.value = false
        synchronized(mpLock) { releaseMpLocked() }
    }

    private fun releaseMpLocked() {
        mp?.let { runCatching { if (it.isPlaying) it.stop() }; runCatching { it.release() } }
        mp = null
    }

    private fun saveProgress() {
        val pid = _projectId.value ?: return
        val chId = _chapterId.value ?: return
        repo.setListenProgress(pid, chId, _segmentIndex.value)
    }

    private fun writeTemp(bytes: ByteArray): File {
        val dir = File(context.cacheDir, "tts").apply { if (!exists()) mkdirs() }
        return File(dir, "seg-${System.nanoTime()}.mp3").apply { writeBytes(bytes) }
    }

    // ── data helpers ───────────────────────────────────────────────────────────

    private fun orderedChapters(projectId: String) =
        repo.chapters(projectId).sortedBy { it.order_index }

    private fun nextChapterAfter(projectId: String, chapterId: String) =
        orderedChapters(projectId).let { list ->
            val idx = list.indexOfFirst { it.id == chapterId }
            if (idx >= 0 && idx < list.size - 1) list[idx + 1] else null
        }

    private fun segmentsOf(chapterId: String): List<String> {
        val body = repo.chapterBody(chapterId)
        return splitSegments(body.final.ifBlank { body.draft })
    }

    companion object {
        private const val MAX_SEG_CHARS = 220

        /** Split text into sentence-grouped segments of up to [MAX_SEG_CHARS] chars. */
        fun splitSegments(text: String): List<String> {
            val clean = text.trim()
            if (clean.isEmpty()) return emptyList()
            // Break after sentence terminators and newlines, keeping the terminator with its sentence.
            val sentences = mutableListOf<String>()
            val sb = StringBuilder()
            for (ch in clean) {
                sb.append(ch)
                if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '\n' ||
                    ch == '；' || ch == ';'
                ) {
                    val s = sb.toString().trim()
                    if (s.isNotEmpty()) sentences.add(s)
                    sb.setLength(0)
                }
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }?.let { sentences.add(it) }

            val segments = mutableListOf<String>()
            val cur = StringBuilder()
            for (s in sentences) {
                if (cur.isNotEmpty() && cur.length + s.length > MAX_SEG_CHARS) {
                    segments.add(cur.toString()); cur.setLength(0)
                }
                if (s.length > MAX_SEG_CHARS) {
                    // A single very long sentence: hard-split into chunks.
                    if (cur.isNotEmpty()) { segments.add(cur.toString()); cur.setLength(0) }
                    var i = 0
                    while (i < s.length) {
                        val end = (i + MAX_SEG_CHARS).coerceAtMost(s.length)
                        segments.add(s.substring(i, end)); i = end
                    }
                } else {
                    cur.append(s)
                }
            }
            if (cur.isNotEmpty()) segments.add(cur.toString())
            return segments.filter { it.isNotBlank() }
        }
    }
}
