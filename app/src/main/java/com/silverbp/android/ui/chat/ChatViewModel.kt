package com.silverbp.android.ui.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.chat.ChatRepository
import com.silverbp.android.chat.ChatSessionSummary
import com.silverbp.android.chat.ChatTitleGenerator
import com.silverbp.android.chat.ChatTranscriptBuilder
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.ModelBootstrap
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.ModelLoadStatus
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.chat.ChatRecognizer
import com.silverbp.android.recognition.chat.ChatRecognizerFactory
import com.silverbp.android.settings.UserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ChatUiState(
    val sessionId: String? = null,
    val sessions: List<ChatSessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingAssistantId: String? = null,
    val streamingText: String = "",
    val isGenerating: Boolean = false,
    val modelPhase: ModelLoadPhase = ModelLoadPhase.Idle,
    val backend: RecognitionBackend = RecognitionBackend.Local,
    val stagedImagePath: String? = null,
    val errorMessage: String? = null,
) {
    /** Send is allowed iff a non-empty text exists, no generation is in flight, and the backend is ready. */
    fun canSend(input: String): Boolean {
        if (isGenerating) return false
        if (input.isBlank() && stagedImagePath == null) return false
        return when (backend) {
            RecognitionBackend.Cloud -> true
            else -> modelPhase == ModelLoadPhase.Ready
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repo: ChatRepository = ServiceLocator.chatRepository,
    private val modelStatus: ModelLoadStatus = ServiceLocator.modelLoadStatus,
    private val settings: UserSettingsRepository = ServiceLocator.userSettings,
    private val recordsContextBuilder: RecordsContextBuilder = RecordsContextBuilder(),
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    /**
     * Source of truth for which session is shown. flatMapLatest on this flow
     * auto-unsubscribes the prior session's observeMessages collector — that's
     * how we avoid leaking subscriptions across switches.
     */
    private val currentSessionId = MutableStateFlow<String?>(null)

    private var currentJob: Job? = null

    /**
     * Reserved for the model's output. Total budget = maxNumTokens − this.
     * Not user-visible — same value across all backends. AICore caps output at
     * 256 anyway, but we keep a generous reserve for Local/Cloud.
     */
    private val reservedOutputTokens: Int = 384

    init {
        // Resolve the active session lazily and feed currentSessionId.
        viewModelScope.launch {
            val id = repo.ensureActiveSession()
            currentSessionId.value = id
        }

        // Wire reactive streams. Messages depend on currentSessionId via
        // flatMapLatest — switching sessions cancels the prior collector.
        val messagesFlow = currentSessionId
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else repo.observeMessages(id)
            }
        val sessionsFlow = repo.observeSessions().onStart { emit(emptyList()) }

        viewModelScope.launch {
            combine(
                currentSessionId,
                messagesFlow,
                sessionsFlow,
                modelStatus.phase,
                settings.flow.map { it.recognitionBackend }.distinctUntilChanged(),
            ) { sid, msgs, sessions, phase, backend ->
                Quint(sid, msgs, sessions, phase, backend)
            }.collect { (sid, msgs, sessions, phase, backend) ->
                _ui.update {
                    it.copy(
                        sessionId = sid,
                        messages = msgs,
                        sessions = sessions,
                        modelPhase = phase,
                        backend = backend,
                    )
                }
            }
        }
    }

    fun stageImage(path: String) {
        _ui.update { it.copy(stagedImagePath = path, errorMessage = null) }
    }

    fun clearStagedImage() {
        _ui.update { it.copy(stagedImagePath = null) }
    }

    fun dismissError() {
        _ui.update { it.copy(errorMessage = null) }
    }

    /** Switch to an existing session. No-op if same id. Cancels any in-flight generation. */
    fun switchTo(id: String) {
        if (id == currentSessionId.value) return
        currentJob?.cancel()
        clearStreamingState()
        currentSessionId.value = id
    }

    /** Always create a new (empty) session and switch to it. */
    fun newSession() {
        currentJob?.cancel()
        clearStreamingState()
        viewModelScope.launch {
            val id = repo.newSession()
            currentSessionId.value = id
        }
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            repo.renameSession(id, trimmed.take(40))
        }
    }

    /**
     * Delete a session. If it's the active one, switch to the next-most-recent
     * remaining session, or create a fresh one if none remain.
     */
    fun deleteSession(id: String) {
        viewModelScope.launch {
            val wasActive = id == currentSessionId.value
            if (wasActive) {
                currentJob?.cancel()
                clearStreamingState()
            }
            repo.deleteSession(id)
            if (wasActive) {
                val remaining = repo.observeSessions().first().firstOrNull()?.id
                currentSessionId.value = remaining ?: repo.newSession()
            }
        }
    }

    fun send(text: String) {
        val sessionId = currentSessionId.value ?: return
        val image = _ui.value.stagedImagePath
        val trimmed = text.trim()
        if (trimmed.isBlank() && image == null) return
        if (_ui.value.isGenerating) return

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    isGenerating = true,
                    stagedImagePath = null,
                    errorMessage = null,
                )
            }

            // 1. Persist user turn.
            repo.appendUser(sessionId, trimmed, image)

            // 2. Build system block from settings (persona override + records toggle).
            val settingsSnap = settings.flow.first()
            val persona = settingsSnap.chatPersona.ifBlank { CHAT_SYSTEM_PERSONA }
            val recordsContext = if (settingsSnap.chatIncludeRecordsContext) {
                runCatching { recordsContextBuilder.build() }.getOrElse { "" }
            } else {
                ""
            }
            val systemBlock = buildString {
                appendLine(persona)
                if (recordsContext.isNotBlank()) {
                    appendLine()
                    appendLine(recordsContext)
                }
            }.trim()

            val historyDb = repo.messagesFor(sessionId)
            val budget = (settingsSnap.maxNumTokens - reservedOutputTokens).coerceAtLeast(256)
            val transcript = ChatTranscriptBuilder.build(systemBlock, historyDb, budget)

            // 3. Insert assistant placeholder we'll stream into.
            val assistantId = repo.appendAssistantPlaceholder(sessionId)
            _ui.update { it.copy(streamingAssistantId = assistantId, streamingText = "") }

            // 4. Stream from the active backend.
            val recognizer: ChatRecognizer = ChatRecognizerFactory.current()
            val acc = StringBuilder()
            try {
                if (!recognizer.isReady()) {
                    // The Local engine can be evicted out from under us while the
                    // camera / photo-picker is foregrounded (the multi-GB model
                    // makes us the prime low-memory-kill target). Rather than drop
                    // the turn, re-warm and wait for the model so the staged photo
                    // + question survive. ensureWarm() / the wait no-op for
                    // non-Local backends and when no variant is downloaded.
                    val recovered = if (_ui.value.backend == RecognitionBackend.Local) {
                        ModelBootstrap.ensureWarm(ServiceLocator.context)
                        val loadingNote = "模型載入中,稍候自動回覆…"
                        _ui.update { it.copy(streamingText = loadingNote) }
                        repo.updateAssistantText(assistantId, loadingNote)
                        awaitLocalEngineReady(recognizer)
                    } else {
                        false
                    }
                    if (!recovered) {
                        val msg = when (_ui.value.backend) {
                            RecognitionBackend.Cloud -> "請先在「設定」輸入 Gemini API key"
                            else -> "模型尚未就緒,請稍候再送出"
                        }
                        repo.updateAssistantText(assistantId, msg)
                        // Keep the attachment staged so the user doesn't lose the
                        // photo they were asking about and can just re-send.
                        if (image != null) stageImage(image)
                        _ui.update {
                            it.copy(
                                streamingText = msg,
                                streamingAssistantId = null,
                                isGenerating = false,
                                errorMessage = msg,
                            )
                        }
                        return@launch
                    }
                }

                if (image != null && !recognizer.supportsImages()) {
                    _ui.update { it.copy(errorMessage = "目前後端不支援圖片,已改用文字。") }
                }

                recognizer.chat(transcript).collect { delta ->
                    if (delta.isEmpty()) return@collect
                    acc.append(delta)
                    val snapshot = acc.toString()
                    _ui.update { it.copy(streamingText = snapshot) }
                    repo.updateAssistantText(assistantId, snapshot)
                }
                if (acc.isEmpty()) {
                    val fallback = "(沒有產生內容)"
                    repo.updateAssistantText(assistantId, fallback)
                }
            } catch (t: Throwable) {
                // Network failures (no connectivity, DNS, timeout) reach here for
                // the Cloud backend — show a plain, actionable message rather than
                // a raw Java exception string for elderly users.
                val msg = if (t is java.io.IOException) {
                    "網路連線失敗,請確認連線後再試"
                } else {
                    "回應失敗: ${t.message ?: t.javaClass.simpleName}"
                }
                repo.updateAssistantText(assistantId, msg)
                _ui.update { it.copy(errorMessage = msg) }
            } finally {
                _ui.update {
                    it.copy(
                        streamingAssistantId = null,
                        streamingText = "",
                        isGenerating = false,
                    )
                }

                // 5. Auto-title: only after a successful first turn pair on a default-titled session.
                if (acc.isNotEmpty()) {
                    maybeAutoTitle(
                        sessionId = sessionId,
                        firstUserMsg = trimmed,
                        firstAssistantMsg = acc.toString(),
                        recognizer = recognizer,
                    )
                }
            }
        }
    }

    /**
     * Fire title generation as a detached child of viewModelScope (which uses a
     * SupervisorJob, so a failure here can't cascade into the main chat
     * stream). Re-checks session title + message count immediately before
     * applying so a manual rename or follow-up turn racing against generation
     * cancels the auto-title silently.
     */
    private fun maybeAutoTitle(
        sessionId: String,
        firstUserMsg: String,
        firstAssistantMsg: String,
        recognizer: ChatRecognizer,
    ) {
        viewModelScope.launch {
            val before = repo.getSession(sessionId) ?: return@launch
            if (before.title !in ChatRepository.DEFAULT_TITLES) return@launch
            if (repo.messagesFor(sessionId).size != 2) return@launch

            val title = ChatTitleGenerator.generate(
                userMsg = firstUserMsg,
                assistantMsg = firstAssistantMsg,
                recognizer = recognizer,
            ) ?: return@launch

            val after = repo.getSession(sessionId) ?: return@launch
            if (after.title !in ChatRepository.DEFAULT_TITLES) return@launch
            if (repo.messagesFor(sessionId).size != 2) return@launch

            repo.renameSession(sessionId, title)
        }
    }

    /**
     * Suspend until the (re)loading Local engine reports ready, bounded by
     * [LOCAL_WARM_TIMEOUT_MS]. [ModelLoadStatus.phase] flips Loading→Ready when
     * preload completes; we re-check the recognizer's real engine state on each
     * emission (the phase flag alone can lag the actual engine). Cancellable via
     * the parent send job. Returns the final readiness.
     */
    private suspend fun awaitLocalEngineReady(recognizer: ChatRecognizer): Boolean {
        if (recognizer.isReady()) return true
        withTimeoutOrNull(LOCAL_WARM_TIMEOUT_MS) {
            modelStatus.phase.first { recognizer.isReady() }
        }
        return recognizer.isReady()
    }

    private fun clearStreamingState() {
        _ui.update {
            it.copy(
                isGenerating = false,
                streamingAssistantId = null,
                streamingText = "",
            )
        }
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        clearStreamingState()
    }

    /**
     * Persist a freshly-attached Bitmap to `cacheDir/chat-images/<uuid>.jpg`
     * and stage its path. Runs off the main thread.
     */
    suspend fun persistAndStageBitmap(context: Context, bitmap: Bitmap) {
        val path = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "chat-images").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            file.absolutePath
        }
        stageImage(path)
    }
}

/**
 * Upper bound on how long a chat send will wait for the Local engine to finish
 * (re)loading before giving up and asking the user to re-send. Sized with margin
 * over a measured cold load of the largest variant (E4B, ~3.6 GB) on a vivo
 * V2562: ~84 s including a vision-cache rebuild, so a worst case can exceed 90 s.
 */
private const val LOCAL_WARM_TIMEOUT_MS = 120_000L

/**
 * Tiny tuple used inside the combine call. Stdlib `Pair`/`Triple` only go up
 * to 3 elements; rolling our own keeps the combine readable without pulling in
 * Arrow.
 */
private data class Quint<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
