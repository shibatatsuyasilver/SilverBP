package com.silverbp.android.recognition.chat

import android.content.Context
import com.silverbp.android.di.ServiceLocator
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.settings.UserSettings
import kotlinx.coroutines.flow.first

object ChatRecognizerFactory {
    suspend fun current(context: Context): ChatRecognizer {
        val s: UserSettings = ServiceLocator.userSettings.flow.first()
        return when (s.recognitionBackend) {
            RecognitionBackend.Cloud -> GeminiCloudChatRecognizer(
                apiKey = s.geminiApiKey,
                modelId = s.geminiModel.ifBlank { GeminiCloudChatRecognizer.DEFAULT_MODEL },
            )
            RecognitionBackend.Local -> GemmaLocalChatRecognizer(context)
            RecognitionBackend.AICore -> AICoreChatRecognizer()
        }
    }
}
