package com.silverbp.android.recognition

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads any of the [ModelCatalog] variants on first launch.
 * Each variant lives at `filesDir/models/<filename>` so multiple variants
 * can coexist (user can switch in Settings without re-downloading).
 *
 * Set the optional `bearerToken` for Hugging Face gated models.
 */
class ModelDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class Progress(val bytesRead: Long, val total: Long, val fraction: Float)

    fun targetFile(variant: ModelVariant): File =
        File(context.filesDir, "models/${variant.filename}")

    fun isDownloaded(variant: ModelVariant): Boolean =
        targetFile(variant).let { it.exists() && it.length() > 0 }

    /** True when an interrupted download left a resumable `.part` file on disk. */
    fun hasPartialDownload(variant: ModelVariant): Boolean =
        targetFile(variant).let { File(it.parentFile, "${it.name}.part") }
            .let { it.exists() && it.length() > 0 }

    /** Delete the model file and any leftover `.part`. Returns true if nothing remains on disk. */
    fun deleteVariant(variant: ModelVariant): Boolean {
        val target = targetFile(variant)
        val partial = File(target.parentFile, "${target.name}.part")
        runCatching { if (target.exists()) target.delete() }
        runCatching { if (partial.exists()) partial.delete() }
        return !target.exists() && !partial.exists()
    }

    fun download(
        variant: ModelVariant,
        sha256: String? = null,
        bearerToken: String? = null,
    ): Flow<Progress> = flow {
        val target = targetFile(variant)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")

        val builder = Request.Builder().url(variant.downloadUrl)
        if (!bearerToken.isNullOrBlank()) builder.addHeader("Authorization", "Bearer $bearerToken")
        if (partial.exists()) builder.addHeader("Range", "bytes=${partial.length()}-")
        val response = client.newCall(builder.build()).execute()
        check(response.isSuccessful) { "HTTP ${response.code}" }

        val body = response.body ?: error("empty body")
        val total = body.contentLength().let { if (it > 0) it + partial.length() else -1L }
        val out = FileOutputStream(partial, partial.exists())
        var read = partial.length()
        out.use { sink ->
            body.byteStream().use { src ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    read += n
                    val frac = if (total > 0) read.toFloat() / total else 0f
                    emit(Progress(read, total, frac))
                }
            }
        }
        if (sha256 != null) {
            val actual = sha256(partial)
            check(actual.equals(sha256, ignoreCase = true)) { "sha256 mismatch" }
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "rename failed" }
        emit(Progress(read, total, 1f))
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
