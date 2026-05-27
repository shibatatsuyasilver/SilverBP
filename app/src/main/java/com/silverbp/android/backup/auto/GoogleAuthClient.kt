package com.silverbp.android.backup.auto

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google's [Identity.getAuthorizationClient] for the Drive
 * `appDataFolder` scope.
 *
 * Why not CredentialManager + GoogleIdOption? Sign-in via CredentialManager
 * delivers a signed JWT id_token but requires an OAuth Web Client ID set up
 * in Google Cloud Console. We don't need a verified id_token — Drive's
 * `/about?fields=user` endpoint returns the authenticated user's email +
 * stable permissionId for free once we hold an access token. So we skip
 * CredentialManager and let [AuthorizationClient] both pick the account and
 * grant the Drive scope in one prompt.
 *
 * Usage:
 *  - First link: [requestDriveToken] from a Composable scope; if it returns
 *    [TokenResult.NeedsConsent], launch the IntentSender via an
 *    `ActivityResultLauncher`, then call [parseConsentResult] from the
 *    launcher's callback to obtain the granted access token.
 *  - Background worker: call [requestDriveToken] with the previously linked
 *    email; if it returns [TokenResult.NeedsConsent] the worker fails with a
 *    friendly "請重新連結 Google" error — interactive consent can't run from
 *    a background context.
 */
class GoogleAuthClient(context: Context) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val driveScope = Scope(DRIVE_APPDATA_SCOPE)

    sealed class TokenResult {
        data class Granted(val accessToken: String) : TokenResult()
        /**
         * Caller must run [intentSender] via an ActivityResultLauncher.
         * After the launcher returns the Intent, hand it to
         * [parseConsentResult] to extract the granted token.
         */
        data class NeedsConsent(val intentSender: IntentSender) : TokenResult()
        /** User cancelled the consent dialog, or the response was malformed. */
        object Cancelled : TokenResult()
    }

    /**
     * Asks Google to authorize Drive `appDataFolder` for the given (or
     * default) account. Silent when the user has already granted consent
     * to this app+scope+account; otherwise returns [TokenResult.NeedsConsent]
     * so the UI can prompt.
     */
    suspend fun requestDriveToken(linkedAccountEmail: String? = null): TokenResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .apply {
                if (!linkedAccountEmail.isNullOrBlank()) {
                    setAccount(Account(linkedAccountEmail, GOOGLE_ACCOUNT_TYPE))
                }
            }
            .build()
        val result = try {
            client.authorize(request).awaitTask()
        } catch (e: ApiException) {
            Log.w(TAG, "authorize() failed", e)
            throw e
        }
        return resultToToken(result)
    }

    /**
     * Decode the Intent returned by the consent ActivityResultLauncher.
     * Null Intent (or one without a token) means the user cancelled.
     */
    fun parseConsentResult(data: Intent?): TokenResult {
        if (data == null) return TokenResult.Cancelled
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            Log.w(TAG, "getAuthorizationResultFromIntent failed", e)
            return TokenResult.Cancelled
        }
        return resultToToken(result)
    }

    private fun resultToToken(result: AuthorizationResult): TokenResult {
        if (result.hasResolution()) {
            val pi = result.pendingIntent ?: return TokenResult.Cancelled
            return TokenResult.NeedsConsent(pi.intentSender)
        }
        val token = result.accessToken ?: return TokenResult.Cancelled
        return TokenResult.Granted(token)
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val TAG = "GoogleAuthClient"
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { e -> cont.resumeWithException(e) }
}
