package com.silverbp.android.recognition

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Coarse multi-food localiser for the nutrition pipeline. Uses ML Kit's bundled
 * (offline, no download) object detector purely for **bounding boxes** — its
 * built-in 5-category classifier is too coarse to label foods, so we ignore the
 * labels and let Gemma identify each cropped region.
 *
 * This is a pragmatic "medium" segmentation: it separates *spatially distinct*
 * dishes (e.g. a tray of separate bowls) well, but not foods touching in one
 * bowl. Callers fall back to a whole-image pass when it returns < 2 regions, and
 * cap the region count to bound on-device inference latency (one Gemma call per
 * crop). Returns boxes largest-first; empty on failure / nothing found.
 */
object FoodRegionDetector {

    private val detector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build(),
        )
    }

    suspend fun regions(bitmap: Bitmap): List<Rect> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { objects ->
                val rects = objects.map { it.boundingBox }
                    .filter { it.width() > 0 && it.height() > 0 }
                    .sortedByDescending { it.width().toLong() * it.height().toLong() }
                cont.resume(rects)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
        cont.invokeOnCancellation { /* detector is reused; nothing per-call to close */ }
    }
}
