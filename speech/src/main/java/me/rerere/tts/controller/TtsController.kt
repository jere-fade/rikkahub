package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TtsHttpException
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.util.UUID

private const val TAG = "TtsController"

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)
    private val diskCache = TtsDiskCache(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null
    private var isPaused = false

    // 重放缓存：上一次朗读的会话元数据
    private var currentSessionId: UUID? = null
    private var lastSessionManifest: TtsSessionManifest? = null
    private var lastSpokenText: String? = null

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()
    private var lastPrefetchedIndex: Int = -1

    // 行为参数
    private val chunkDelayMs = 120L
    private val prefetchCount = 4

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastHttpError = MutableStateFlow<TtsHttpException?>(null)
    val lastHttpError: StateFlow<TtsHttpException?> = _lastHttpError.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _currentChunkIndex = MutableStateFlow(0)
    val currentChunkIndex: StateFlow<Int> = _currentChunkIndex.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        status = if (!_isAvailable.value) PlaybackStatus.Idle else audioState.status
                    )
                }
            }
        }
        // 冷启动时尝试从磁盘恢复上一次会话，使进程被杀后 replayLast 仍可用
        scope.launch {
            val manifest = diskCache.latestSession() ?: return@launch
            // 此时还没有 provider；先保留，恢复后再由 setProvider 校验指纹
            lastSessionManifest = manifest
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider != null) {
            // 若磁盘上保留的会话与新 provider 不一致，丢弃旧缓存以免重放错音频
            val cached = lastSessionManifest
            if (cached != null && cached.providerFingerprint != diskCache.fingerprint(provider)) {
                scope.launch { diskCache.deleteSession(UUID.fromString(cached.sessionId)) }
                lastSessionManifest = null
                currentSessionId = null
            }
        }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        lastSpokenText = text
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            // 优先复用磁盘上同一文本的已有会话，避免在冷启动后用户重新朗读同一消息时
            // 旋转 sessionId 导致原缓存失效、replay 时全量重新合成。
            val fingerprint = diskCache.fingerprint(provider)
            val adopted = diskCache.findSessionByTextBlocking(text, fingerprint)
            val newSessionId = if (adopted != null) {
                UUID.fromString(adopted.sessionId)
            } else {
                UUID.randomUUID()
            }
            currentSessionId = newSessionId
            if (adopted != null) {
                // 复用已有 manifest；不要覆盖写，否则丢失原始 createdAt
                lastSessionManifest = adopted
            } else {
                scope.launch {
                    diskCache.evictOlderThan(MAX_TTS_SESSIONS)
                    val manifest = TtsSessionManifest(
                        sessionId = newSessionId.toString(),
                        createdAt = System.currentTimeMillis(),
                        providerFingerprint = fingerprint,
                        originalText = text,
                        chunkCount = newChunks.size,
                        chunkTexts = newChunks.map { it.text },
                    )
                    diskCache.writeManifest(manifest)
                }
                lastSessionManifest = TtsSessionManifest(
                    sessionId = newSessionId.toString(),
                    createdAt = System.currentTimeMillis(),
                    providerFingerprint = fingerprint,
                    originalText = text,
                    chunkCount = newChunks.size,
                    chunkTexts = newChunks.map { it.text },
                )
            }
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
        prefetchFrom((_currentChunk.value).coerceAtLeast(0))
    }

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _currentChunkIndex.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
            _totalChunks.update { queue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        lastPrefetchedIndex = -1
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _currentChunkIndex.update { 0 }
        _totalChunks.update { 0 }
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    /**
     * 重放最近一次朗读。空操作情形：
     * - 未配置 provider
     * - 没有可用的上一次会话（首次启动 / 缓存被清空 / provider 切换后旧缓存已失效）
     *
     * 必须复用 manifest 的 sessionId，否则所有磁盘上的 chunk 都将失效、播放时全量重新合成。
     */
    fun replayLast() {
        val manifest = lastSessionManifest ?: return
        val provider = currentProvider ?: run {
            _error.update { "No TTS provider selected" }
            return
        }
        if (manifest.providerFingerprint != diskCache.fingerprint(provider)) {
            // 缓存已失效，丢弃并要求用户重新朗读
            scope.launch { diskCache.deleteSession(UUID.fromString(manifest.sessionId)) }
            lastSessionManifest = null
            return
        }

        // 复位运行时状态，但保留磁盘上的会话目录与原始 sessionId
        internalReset()
        val replaySessionId = UUID.fromString(manifest.sessionId)
        currentSessionId = replaySessionId

        val replayChunks = chunker.split(manifest.originalText)
        if (replayChunks.isEmpty()) return
        // 复用 manifest 里的 index，让磁盘上的 chunk 索引对得上
        val indexed = replayChunks.mapIndexed { i, c -> c.copy(index = i) }
        allChunks.addAll(indexed)
        queue.addAll(indexed)
        lastSessionManifest = manifest.copy(
            createdAt = System.currentTimeMillis(),
        )
        _currentChunk.update { 0 }
        _totalChunks.update { queue.size }
        _error.update { null }
        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                status = PlaybackStatus.Buffering,
            )
        }

        if (workerJob?.isActive != true) startWorker()
        // 从 0 开始预取，让预取也走磁盘优先的路径
        prefetchFrom(0)
    }

    fun regenerateChunk(targetChunkIndex: Int) {
        val text = lastSpokenText ?: return
        val sessionId = currentSessionId ?: return

        // Delete the chunk from disk so it gets re-synthesized
        scope.launch {
            diskCache.deleteChunk(sessionId, targetChunkIndex)
        }

        // Stop current playback and restart — other chunks load from disk,
        // only the deleted chunk triggers re-synthesis.
        stop()
        speak(text, flush = true)
    }

    fun clearLastHttpError() {
        _lastHttpError.update { null }
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    // 更新状态（1-based）
                    _currentChunk.update { processedCount + 1 }
                    _currentChunkIndex.update { processedCount }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value
                        )
                    }

                    // 预取下一窗口
                    prefetchFrom(chunk.index + 1)

                    val response = try {
                        awaitOrCreate(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Synthesis error", e)
                        _error.update { e.message ?: "TTS synthesis error" }
                        if (e is TtsHttpException) {
                            _lastHttpError.update { e }
                        }
                        processedCount++
                        continue
                    }

                    // 播放
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }

                    if (queue.isNotEmpty()) delay(chunkDelayMs)

                    processedCount++
                }
            } finally {
                _isSpeaking.update { false }
                if (queue.isEmpty()) {
                    _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                }
            }
        }
    }

    private fun prefetchFrom(startIndex: Int) {
        val provider = currentProvider ?: return
        val sessionId = currentSessionId ?: return
        val begin = startIndex.coerceAtLeast(lastPrefetchedIndex + 1)
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            cache.computeIfAbsent(chunk.id) {
                scope.async(Dispatchers.IO) { loadOrSynthesize(provider, chunk, sessionId) }
            }
        }
        lastPrefetchedIndex = endExclusive - 1
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val sessionId = currentSessionId
        val deferred = cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) { loadOrSynthesize(provider, chunk, sessionId) }
        }
        return try {
            deferred.await()
        } finally {
            // 缓存项在 reset/stop 时统一清空
        }
    }

    /**
     * 磁盘优先读取；命中失败时走网络合成并落盘，供 prefetch 与播放两个路径共用，
     * 确保重放时所有 chunk（无论是否被预取）都走相同的"先看磁盘"逻辑。
     */
    private suspend fun loadOrSynthesize(
        provider: TTSProviderSetting,
        chunk: TtsChunk,
        sessionId: UUID?,
    ): TTSResponse {
        if (sessionId != null) {
            diskCache.readChunk(sessionId, chunk.index)?.let { bytes ->
                return TTSResponse(
                    audioData = bytes,
                    format = AudioFormat.MP3,
                    metadata = mapOf("source" to "disk"),
                )
            }
        }
        val response = synthesizer.synthesize(provider, chunk)
        if (sessionId != null) {
            diskCache.writeChunk(sessionId, chunk.index, response.audioData)
        }
        return response
    }
    // endregion
}
