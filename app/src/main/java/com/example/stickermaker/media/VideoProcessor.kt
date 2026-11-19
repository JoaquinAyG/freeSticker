package com.example.stickermaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.example.stickermaker.R
import com.example.stickermaker.editor.CropSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Which part of the video to use and how to sample it. */
data class ClipParams(val startMs: Long, val endMs: Long, val fps: Int, val speed: Float) {
    val frameDurationMs: Int get() = (1000f / fps).roundToInt()
    private val sourceStepMs: Float get() = 1000f * speed / fps
    val frameCount: Int get() = ((endMs - startMs) / sourceStepMs).toInt().coerceAtLeast(0)
    val outputDurationMs: Long get() = frameCount.toLong() * frameDurationMs
    fun sourceTimeUs(index: Int): Long = ((startMs + index * sourceStepMs) * 1000).toLong()
}

class AnimatedStickerResult(val data: ByteArray, val firstFrame: Bitmap, val frameCount: Int)

object VideoProcessor {
    private suspend fun forEachFrame(
        context: Context,
        uri: Uri,
        clip: ClipParams,
        removeBackground: Boolean,
        onFrame: suspend (index: Int, frame: Bitmap) -> Unit,
    ) {
        if (removeBackground) BackgroundRemover.ensureReady(context)
        FrameSource.open(context, uri).use { source ->
            var last: Bitmap? = null
            for (i in 0 until clip.frameCount) {
                currentCoroutineContext().ensureActive()
                val raw = source.frameAt(clip.sourceTimeUs(i), exact = true)
                val frame = when {
                    raw == null -> last ?: continue
                    removeBackground -> BackgroundRemover.removeBackground(raw).bitmap
                    else -> raw
                }
                last = frame
                onFrame(i, frame)
            }
        }
    }

    suspend fun makeGif(
        context: Context,
        uri: Uri,
        clip: ClipParams,
        crop: CropSpec,
        style: StickerStyle,
        keepAspect: Boolean,
        maxSize: Int,
        progress: suspend (Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val file = File(context.cacheDir, "gif_${System.currentTimeMillis()}.gif")
        try {
            BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { os ->
                var renderer: StickerRenderer? = null
                var encoder: GifEncoder? = null
                var pixels = IntArray(0)
                forEachFrame(context, uri, clip, style.cutout) { i, frame ->
                    val r = renderer ?: run {
                        val (w, h) = if (keepAspect) fitWithin(frame.width, frame.height, maxSize) else maxSize to maxSize
                        StickerRenderer(w, h, style, applyShape = !keepAspect).also {
                            renderer = it
                            encoder = GifEncoder(os, w, h)
                            pixels = IntArray(w * h)
                        }
                    }
                    val matrix = if (keepAspect) {
                        Matrix().apply { setScale(r.width.toFloat() / frame.width, r.height.toFloat() / frame.height) }
                    } else {
                        crop.matrix(frame.width, frame.height, r.width, r.height)
                    }
                    val out = r.render(frame, matrix)
                    out.getPixels(pixels, 0, r.width, 0, 0, r.width, r.height)
                    encoder!!.addFrame(pixels, clip.frameDurationMs)
                    progress((i + 1f) / clip.frameCount)
                }
                (encoder ?: error(context.getString(R.string.error_no_frames))).finish()
            }
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    suspend fun makeAnimatedSticker(
        context: Context,
        uri: Uri,
        clip: ClipParams,
        crop: CropSpec,
        style: StickerStyle,
        progress: suspend (Float) -> Unit,
    ): AnimatedStickerResult = withContext(Dispatchers.Default) {
        val size = StickerRenderer.STICKER_SIZE
        val store = FrameStore(File(context.cacheDir, "frames_${System.currentTimeMillis()}.raw"), size, size)
        try {
            val renderer = StickerRenderer(size, size, style)
            var first: Bitmap? = null
            forEachFrame(context, uri, clip, style.cutout) { i, frame ->
                val out = renderer.render(frame, crop.matrix(frame.width, frame.height, size, size))
                if (first == null) first = out.copy(Bitmap.Config.ARGB_8888, false)
                store.add(out)
                progress(0.6f * (i + 1) / clip.frameCount)
            }
            val firstFrame = first ?: error(context.getString(R.string.error_no_frames))
            val encoded = StickerEncoder.encodeAnimated(store, clip.frameDurationMs) { p -> progress(0.6f + 0.4f * p) }
            AnimatedStickerResult(encoded.data, firstFrame, encoded.frameCount)
        } finally {
            store.close()
        }
    }

    private fun fitWithin(w: Int, h: Int, maxSize: Int): Pair<Int, Int> {
        val scale = maxSize.toFloat() / max(w, h)
        return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
    }
}
