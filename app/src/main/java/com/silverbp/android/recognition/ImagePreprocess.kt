package com.silverbp.android.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max

/**
 * Match iOS BPRecognition pipeline:
 *   downsample → CIExposureAdjust(EV-0.3) → CIColorControls(contrast 1.35, saturation 0.4)
 * Favours 7-segment shape over chroma so the VLM vision encoder gets crisper digit edges.
 */
fun Bitmap.preprocessForOcr(maxDim: Int = 1024): Bitmap {
    val maxSide = max(width, height)
    val resized = if (maxSide > maxDim) {
        val scale = maxDim.toFloat() / maxSide
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else this

    // Contrast 1.35 around midpoint 0.5: x' = c*x + (0.5 - 0.5*c) → in 0..255 scale, offset = (0.5 - 0.5*c)*255
    val c = 1.35f
    val t = (0.5f - 0.5f * c) * 255f
    val contrast = ColorMatrix(floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    ))
    val saturation = ColorMatrix().apply { setSaturation(0.4f) }
    contrast.postConcat(saturation)
    // EV -0.3 ≈ multiplier 0.81 on RGB channels
    val exposure = ColorMatrix().apply { setScale(0.81f, 0.81f, 0.81f, 1f) }
    contrast.postConcat(exposure)

    val out = createBitmap(resized.width, resized.height)
    Canvas(out).drawBitmap(resized, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(contrast) })
    return out
}

fun Bitmap.rotateByExif(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return this
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun decodeFileWithExif(file: File): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    return try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        bitmap.rotateByExif(orientation)
    } catch (e: Exception) {
        bitmap
    }
}

fun decodeUriWithExif(context: Context, uri: Uri): Bitmap? {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { 
        BitmapFactory.decodeStream(it)
    } ?: return null
    return try {
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            val exif = ExifInterface(it)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        } ?: ExifInterface.ORIENTATION_UNDEFINED
        bitmap.rotateByExif(orientation)
    } catch (e: Exception) {
        bitmap
    }
}
