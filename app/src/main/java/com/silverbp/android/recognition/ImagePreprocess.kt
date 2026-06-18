package com.silverbp.android.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
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

fun decodeFileWithExif(file: File, maxDim: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    return try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        bitmap.rotateByExif(orientation)
    } catch (e: Exception) {
        bitmap
    }
}

/**
 * Smallest power-of-2 inSampleSize so the decoded max dimension is ≤ [maxDim].
 * Pure function (unit-tested); non-positive dimensions decode at full size.
 */
fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var inSampleSize = 1
    while (max(width, height) / inSampleSize > maxDim) inSampleSize *= 2
    return inSampleSize
}

/**
 * Decode a gallery/content [uri] to a software bitmap bounded to [maxDim].
 *
 * Uses [ImageDecoder] (API 28+; minSdk is 33) rather than [BitmapFactory]:
 * modern phones save album photos as HEIC/HDR (10-bit), which BitmapFactory
 * frequently fails to decode (returns null) — surfacing to the user as
 * "could not analyze" whenever a food photo is picked from the gallery.
 * ImageDecoder decodes HEIC/HDR/AVIF/WebP/JPEG/PNG, applies EXIF orientation
 * automatically, and — with a SOFTWARE allocator — yields a bitmap that can be
 * JPEG-compressed for the recognizer (HARDWARE bitmaps cannot be compressed).
 */
fun decodeUriWithExif(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(
                calculateInSampleSize(info.size.width, info.size.height, maxDim)
            )
        }
    } catch (e: Exception) {
        Log.w("ImagePreprocess", "decodeUriWithExif failed for $uri", e)
        null
    }
}
