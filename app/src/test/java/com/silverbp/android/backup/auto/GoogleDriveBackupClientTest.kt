package com.silverbp.android.backup.auto

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleDriveBackupClientTest {

    @Test
    fun `upload marks backup files with prefix and appProperties`() = runBlocking {
        val capturedBodies = ArrayList<String>()
        val client = driveClient { request ->
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            capturedBodies += buffer.readUtf8()
            jsonResponse(request, """{"id":"drive-id"}""")
        }

        val id = client.upload(byteArrayOf(1, 2, 3), "2026-06-19-1200", "token")

        assertEquals("drive-id", id)
        val body = capturedBodies.single()
        assertTrue(body.contains("\"name\":\"SilverBP-Backup-2026-06-19-1200.sbpbk\""))
        assertTrue(body.contains("\"mimeType\":\"application/octet-stream\""))
        assertTrue(body.contains("\"appProperties\""))
        assertTrue(body.contains("\"silverbpBackup\":\"v1\""))
    }

    @Test
    fun `listBackups returns only positively identified SilverBP backups`() = runBlocking {
        var query = ""
        var fields = ""
        val client = driveClient { request ->
            query = request.url.queryParameter("q").orEmpty()
            fields = request.url.queryParameter("fields").orEmpty()
            jsonResponse(
                request,
                """
                {
                  "files": [
                    {
                      "id": "good",
                      "name": "SilverBP-Backup-2026-06-19-1200.sbpbk",
                      "createdTime": "2026-06-19T12:00:00.000Z",
                      "size": "42",
                      "appProperties": { "silverbpBackup": "v1" }
                    },
                    {
                      "id": "bad-name",
                      "name": "Other-2026-06-19.sbpbk",
                      "createdTime": "2026-06-19T12:01:00.000Z",
                      "size": "42",
                      "appProperties": { "silverbpBackup": "v1" }
                    },
                    {
                      "id": "unmarked",
                      "name": "SilverBP-Backup-2026-06-19-1201.sbpbk",
                      "createdTime": "2026-06-19T12:02:00.000Z",
                      "size": "42"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val files = client.listBackups("token")

        assertTrue(query.contains("name contains 'SilverBP-Backup-'"))
        assertTrue(query.contains("appProperties has"))
        assertTrue(fields.contains("appProperties"))
        assertEquals(1, files.size)
        assertEquals("good", files.single().id)
    }

    @Test
    fun `deleteBackup refuses unmarked files before issuing HTTP delete`() = runBlocking {
        var deleteCalled = false
        val client = driveClient { request ->
            deleteCalled = true
            jsonResponse(request, """{}""", code = 204)
        }
        val unmarked = GoogleDriveBackupClient.DriveBackupFile(
            id = "unmarked",
            name = "SilverBP-Backup-2026-06-19-1200.sbpbk",
            createdTime = "",
            sizeBytes = 42L,
        )

        try {
            client.deleteBackup(unmarked, "token")
            fail("Expected deleteBackup to reject an unmarked file")
        } catch (t: IllegalArgumentException) {
            assertTrue(t.message.orEmpty().contains("not marked as a SilverBP backup"))
        }
        assertFalse(deleteCalled)
    }

    private fun driveClient(handler: (Request) -> Response): GoogleDriveBackupClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
        return GoogleDriveBackupClient(http)
    }

    private fun jsonResponse(request: Request, body: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
