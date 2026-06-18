package com.silverbp.android.recognition

import android.content.ContextWrapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val variant = ModelVariant(
        id = "test",
        displayNameRes = 0,
        filename = "model.bin",
        downloadUrl = "https://example.com/model.bin",
        approxSizeBytes = 6,
        supportsVision = true,
        supportsSpeculativeDecoding = false,
        notesRes = 0,
    )

    @Test
    fun `resumed HTTP 206 appends to existing partial`() = runTest {
        val interceptor = StaticDownloadInterceptor(
            code = 206,
            bytes = "def".toByteArray(),
        )
        val downloader = newDownloader(interceptor)
        partialFile(downloader).apply {
            parentFile?.mkdirs()
            writeText("abc")
        }

        downloader.download(variant).toList()

        assertEquals("bytes=3-", interceptor.rangeHeader)
        assertEquals("abcdef", downloader.targetFile(variant).readText())
        assertFalse(partialFile(downloader).exists())
    }

    @Test
    fun `resumed HTTP 200 truncates partial and restarts from zero`() = runTest {
        val interceptor = StaticDownloadInterceptor(
            code = 200,
            bytes = "abcdef".toByteArray(),
        )
        val downloader = newDownloader(interceptor)
        partialFile(downloader).apply {
            parentFile?.mkdirs()
            writeText("abc")
        }

        val progress = downloader.download(variant).toList()

        assertEquals("bytes=3-", interceptor.rangeHeader)
        assertEquals("abcdef", downloader.targetFile(variant).readText())
        assertEquals(6L, progress.last().bytesRead)
        assertEquals(6L, progress.last().total)
    }

    @Test
    fun `fresh download does not send range`() = runTest {
        val interceptor = StaticDownloadInterceptor(
            code = 200,
            bytes = "abcdef".toByteArray(),
        )
        val downloader = newDownloader(interceptor)

        downloader.download(variant).toList()

        assertNull(interceptor.rangeHeader)
        assertEquals("abcdef", downloader.targetFile(variant).readText())
    }

    private fun newDownloader(interceptor: StaticDownloadInterceptor): ModelDownloader =
        ModelDownloader(
            context = FilesContext(tmp.root),
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
        )

    private fun partialFile(downloader: ModelDownloader): File =
        downloader.targetFile(variant).let { File(it.parentFile, "${it.name}.part") }

    // ContextWrapper(null) is enough: ModelDownloader only ever reads filesDir,
    // which we override here (android.test.mock.MockContext isn't on the unit-test
    // classpath). Any other Context call would NPE, but none is made.
    private class FilesContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
    }

    private class StaticDownloadInterceptor(
        private val code: Int,
        private val bytes: ByteArray,
    ) : Interceptor {
        var rangeHeader: String? = null
            private set

        override fun intercept(chain: Interceptor.Chain): Response {
            rangeHeader = chain.request().header("Range")
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 206) "Partial Content" else "OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
    }
}
