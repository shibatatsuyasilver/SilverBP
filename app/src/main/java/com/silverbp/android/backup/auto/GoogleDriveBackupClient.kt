package com.silverbp.android.backup.auto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal Drive v3 REST client for the SilverBP backup feature.
 *
 * Uses 5 endpoints, all against the user's `appDataFolder` (hidden per-app
 * private space):
 *  - multipart upload  (POST /upload/drive/v3/files?uploadType=multipart)
 *  - list              (GET /drive/v3/files?spaces=appDataFolder…)
 *  - delete            (DELETE /drive/v3/files/{id})
 *  - download          (GET /drive/v3/files/{id}?alt=media)
 *  - whoami            (GET /drive/v3/about?fields=user) — gives us email +
 *    permissionId so we don't need the OAuth Web Client ID / id_token path.
 *
 * Hand-rolled because `google-api-services-drive` would pull Guava +
 * j2objc-annotations + the whole google-api-client tree (>2 MB APK weight)
 * for these five calls.
 *
 * Caller passes an [OkHttpClient] so we share the project's existing
 * connection pool / interceptors.
 */
class GoogleDriveBackupClient(private val http: OkHttpClient) {

    data class DriveBackupFile(
        val id: String,
        val name: String,
        /** RFC 3339 timestamp from Drive (e.g. `2026-05-27T08:00:00.000Z`). */
        val createdTime: String,
        val sizeBytes: Long,
    )

    data class DriveUser(
        val email: String,
        /** Stable identifier for the Google account (not the email). */
        val permissionId: String,
    )

    /**
     * Upload [bytes] as `application/octet-stream` to appDataFolder with the
     * given file name. Returns the new file's Drive id.
     */
    suspend fun upload(
        bytes: ByteArray,
        fileName: String,
        accessToken: String,
    ): String = withContext(Dispatchers.IO) {
        val metaJson = JSONObject().apply {
            put("name", fileName)
            put("parents", JSONArray().put("appDataFolder"))
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(BACKUP_MEDIA_TYPE.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$UPLOAD_BASE/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive upload HTTP ${resp.code}: ${resp.peekErrorText()}")
            }
            val payload = resp.body?.string().orEmpty()
            JSONObject(payload).getString("id")
        }
    }

    /**
     * List existing backup files in appDataFolder, newest first. Page size
     * capped at 100 — for the auto-backup feature with a 5-file retention
     * cap, one page is always enough.
     */
    suspend fun listBackups(accessToken: String): List<DriveBackupFile> = withContext(Dispatchers.IO) {
        val url = "$API_BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", "100")
            .addQueryParameter("fields", "files(id,name,createdTime,size)")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive list HTTP ${resp.code}: ${resp.peekErrorText()}")
            }
            val payload = resp.body?.string().orEmpty()
            val files = JSONObject(payload).optJSONArray("files") ?: JSONArray()
            (0 until files.length()).map { i ->
                val f = files.getJSONObject(i)
                DriveBackupFile(
                    id = f.getString("id"),
                    name = f.getString("name"),
                    createdTime = f.optString("createdTime", ""),
                    // Drive returns `size` as a stringified Long.
                    sizeBytes = f.optString("size", "0").toLongOrNull() ?: 0L,
                )
            }
        }
    }

    suspend fun deleteFile(fileId: String, accessToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive delete HTTP ${resp.code}: ${resp.peekErrorText()}")
            }
        }
    }

    suspend fun downloadFile(fileId: String, accessToken: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/files/$fileId?alt=media")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive download HTTP ${resp.code}: ${resp.peekErrorText()}")
            }
            resp.body?.bytes() ?: throw IOException("Drive download empty body")
        }
    }

    /**
     * Fetch the authenticated user's email + stable permissionId. Used right
     * after [requestDriveToken] succeeds so we can show the linked email in
     * BackupScreen without going through CredentialManager.
     */
    suspend fun whoAmI(accessToken: String): DriveUser = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/about?fields=user")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive about HTTP ${resp.code}: ${resp.peekErrorText()}")
            }
            val payload = resp.body?.string().orEmpty()
            val user = JSONObject(payload).getJSONObject("user")
            DriveUser(
                email = user.optString("emailAddress", ""),
                permissionId = user.optString("permissionId", ""),
            )
        }
    }

    /** Best-effort peek at error body for logging; tolerates already-consumed streams. */
    private fun okhttp3.Response.peekErrorText(): String =
        runCatching { peekBody(1024).string() }.getOrElse { "<no body>" }

    companion object {
        private const val API_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val BACKUP_MEDIA_TYPE = "application/octet-stream"
    }
}
