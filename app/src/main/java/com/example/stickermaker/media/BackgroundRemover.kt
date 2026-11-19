package com.example.stickermaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A picture with its background made transparent, plus where the subject is. */
class Cutout(val bitmap: Bitmap, val subjectBounds: RectF?)

/**
 * Removes the background of photos / video frames on-device with ML Kit Subject Segmentation
 * (the model lives in Google Play services and is downloaded the first time it is needed).
 */
object BackgroundRemover {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build(),
        )
    }
    private val readyLock = Mutex()
    @Volatile
    private var ready = false

    /**
     * Makes sure the segmentation model is installed. Calls [onDownloading] if it has to be
     * fetched first (one time, a few MB). Throws if Play services can't provide it.
     */
    suspend fun ensureReady(context: Context, onDownloading: () -> Unit = {}) {
        if (ready) return
        readyLock.withLock {
            if (ready) return
            val client = ModuleInstall.getClient(context.applicationContext)
            if (!client.areModulesAvailable(segmenter).await().areModulesAvailable()) {
                onDownloading()
                client.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
                // installModules returns once the download is queued; wait for it to finish.
                var waitedMs = 0
                while (!client.areModulesAvailable(segmenter).await().areModulesAvailable()) {
                    if (waitedMs > 120_000) error("Timed out downloading the background-removal model")
                    delay(500)
                    waitedMs += 500
                }
            }
            ready = true
        }
    }

    /** Returns a copy of [source] where everything but the main subject is transparent. */
    suspend fun removeBackground(source: Bitmap): Cutout {
        val width = source.width
        val height = source.height
        val result = segmenter.process(InputImage.fromBitmap(source, 0)).await()
        val mask = result.foregroundConfidenceMask ?: error("No subject found")
        mask.rewind()

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val confidence = mask.get()
                // Soft edge: below 0.25 fully transparent, above 0.75 fully opaque.
                val a = ((confidence - 0.25f) * 2f).coerceIn(0f, 1f)
                val srcAlpha = pixels[i] ushr 24
                val alpha = (a * srcAlpha).toInt()
                pixels[i] = (alpha shl 24) or (pixels[i] and 0x00FFFFFF)
                if (confidence > 0.5f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        val bounds = if (maxX >= 0) RectF(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f) else null
        return Cutout(out, bounds)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
