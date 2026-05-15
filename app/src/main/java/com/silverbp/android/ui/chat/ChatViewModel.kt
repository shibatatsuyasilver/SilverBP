package com.silverbp.android.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.chat.ChatRepository
import com.silverbp.android.chat.ChatSession
import com.silverbp.android.core.BpRepository
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.chat.ChatRecognizer
import com.silverbp.android.recognition.chat.ChatRecognizerFactory
import com.silverbp.android.recognition.chat.GemmaChatService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID


private const val TAG = "ChatViewModel"

data class ChatUiState(
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val pendingAssistantText: String = "",
    val isThinking: Boolean = false,
    val error: String? = null,
    val recognizerSupportsImages: Boolean = true,
)

class ChatViewModel(
    private val chatRepo: ChatRepository = ServiceLocator.chatRepository,
    private val bpRepo: BpRepository = ServiceLocator.bpRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var recognizer: ChatRecognizer? = null
    private var sendJob: Job? = null

    init {
        startNewSession(context = null)
    }

    fun startNewSession(context: Context?) {
        sendJob?.cancel()
        viewModelScope.launch {
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val session = ChatSession(
                id = sessionId,
                title = "對話 ${LocalDate.now()}",
                createdAt = now,
                updatedAt = now,
            )
            chatRepo.upsertSession(session)

            val ctx = context ?: ServiceLocator.context
            val rec = ChatRecognizerFactory.current(ctx)
            recognizer?.close()
            recognizer = rec

            val readings = bpRepo.observeAll().first()
            val recordsContext = RecordsContextBuilder.build(readings)
            val systemPrompt = buildSystemPrompt(recordsContext)
            rec.startSession(systemPrompt)

            _state.value = ChatUiState(
                sessionId = sessionId,
                recognizerSupportsImages = rec.supportsImages(),
            )
            Log.d(TAG, "new session id=$sessionId backend=${rec.javaClass.simpleName}")
        }
    }

    fun sendMessage(context: Context, userText: String, imageBitmap: Bitmap? = null) {
        val rec = recognizer ?: return
        val sessionId = _state.value.sessionId
        if (sessionId.isEmpty() || _state.value.isThinking) return

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            val userMsg = ChatMessage(
                sessionId = sessionId,
                role = ChatMessage.Role.User,
                text = userText,
            )
            chatRepo.insertMessage(userMsg)
            _state.value = _state.value.copy(
                messages = _state.value.messages + userMsg,
                isThinking = true,
                error = null,
                pendingAssistantText = "",
            )

            try {
                val imageToSend = if (rec.supportsImages()) imageBitmap else null
                var accumulated = ""
                val fullReply = rec.chat(
                    userText = userText,
                    imageBitmap = imageToSend,
                    onToken = { delta ->
                        accumulated += delta
                        _state.value = _state.value.copy(pendingAssistantText = accumulated)
                    },
                )

                val assistantMsg = ChatMessage(
                    sessionId = sessionId,
                    role = ChatMessage.Role.Assistant,
                    text = fullReply,
                )
                chatRepo.insertMessage(assistantMsg)

                _state.value = _state.value.copy(
                    messages = _state.value.messages + assistantMsg,
                    isThinking = false,
                    pendingAssistantText = "",
                    recognizerSupportsImages = rec.supportsImages(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "chat failed: ${e.message}", e)
                _state.value = _state.value.copy(
                    isThinking = false,
                    pendingAssistantText = "",
                    error = e.message ?: "未知錯誤",
                )
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { GemmaChatService.clearSession() }
        recognizer?.close()
    }

    private fun buildSystemPrompt(recordsContext: String): String = buildString {
        appendLine("你是 SilverBP 的 AI 健康助理，專門協助用戶理解他們的血壓數據。")
        appendLine("請用繁體中文回答，語氣親切專業，不要取代醫師診斷。")
        appendLine("如果問題超出血壓健康範疇，請簡短回應並提醒用戶諮詢醫師。")
        appendLine()
        appendLine("以下是用戶最近的血壓記錄：")
        appendLine()
        append(recordsContext)
    }
}
