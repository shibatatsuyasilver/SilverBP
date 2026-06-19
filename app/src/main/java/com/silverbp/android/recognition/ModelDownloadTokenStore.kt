package com.silverbp.android.recognition

import android.content.Context
import com.silverbp.android.security.KeystoreStringCipher

/**
 * Short-lived storage for Hugging Face bearer tokens used by model downloads.
 * WorkManager inputData is persisted in its DB, so the worker receives only a
 * handle and resolves the Keystore-wrapped token immediately before use.
 */
object ModelDownloadTokenStore {
    private const val PREFS = "silverbp.model_download_tokens"
    private const val MODEL_DOWNLOAD_HF_HANDLE = "hf:model-download"

    fun put(context: Context, token: String?): String? {
        val trimmed = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        prefs(context).edit()
            .putString(MODEL_DOWNLOAD_HF_HANDLE, KeystoreStringCipher.encrypt(trimmed))
            .apply()
        return MODEL_DOWNLOAD_HF_HANDLE
    }

    fun resolve(context: Context, handle: String?): String? {
        val key = handle?.takeIf { it == MODEL_DOWNLOAD_HF_HANDLE } ?: return null
        return prefs(context).getString(key, null)
            ?.let { KeystoreStringCipher.decrypt(it) }
            ?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context, handle: String?) {
        val key = handle?.takeIf { it == MODEL_DOWNLOAD_HF_HANDLE } ?: return
        prefs(context).edit().remove(key).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
