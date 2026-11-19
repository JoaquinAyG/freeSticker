package com.example.stickermaker.media

import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.max

/** Encodes stickers within WhatsApp's limits: static <= 100 KB, animated <= 500 KB, <= 10 s. */
object StickerEncoder {
    const val STATIC_LIMIT = 100 * 1024
    const val ANIMATED_LIMIT = 500 * 1024
    const val MAX_ANIMATION_MS = 10_000

    class EncodedAnimation(val data: ByteArray, val frameCount: Int)

    @Suppress("DEPRECATION")
    private val lossyFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    fun encodeWebP(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream(64 * 1024).also { bitmap.compress(lossyFormat, quality, it) }.toByteArray()

    fun encodeStatic(bitmap: Bitmap): ByteArray {
        for (q in 90 downTo 10 step 10) {
            val data = encodeWebP(bitmap, q)
            if (data.size <= STATIC_LIMIT) return data
        }
        error("Couldn't compress the sticker under 100 KB")
    }

    /**
     * Tries progressively lower quality, then drops frames, until the animation fits in 500 KB.
     * A few sample frames are encoded first to skip settings that obviously won't fit.
     */
    suspend fun encodeAnimated(
        store: FrameStore,
        frameDurationMs: Int,
        progress: suspend (Float) -> Unit,
    ): EncodedAnimation {
        val frame = Bitmap.createBitmap(store.width, store.height, Bitmap.Config.ARGB_8888)
        val qualities = intArrayOf(80, 65, 50, 40, 30, 20, 12)
        for (step in 1..6) {
            val indices = (0 until store.count step step).toList()
            if (indices.isEmpty()) break
            val duration = (frameDurationMs * step).coerceAtMost(MAX_ANIMATION_MS / indices.size).coerceAtLeast(8)
            val sampleEvery = max(1, indices.size / 6)
            val samples = indices.filterIndexed { i, _ -> i % sampleEvery == 0 }.take(6)

            for (q in qualities) {
                currentCoroutineContext().ensureActive()
                var sampleBytes = 0L
                for (idx in samples) {
                    store.read(idx, frame)
                    sampleBytes += encodeWebP(frame, q).size
                }
                val estimate = sampleBytes / samples.size * indices.size
                if (estimate > ANIMATED_LIMIT * 1.1) continue

                val writer = AnimatedWebPWriter(store.width, store.height)
                for ((k, idx) in indices.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    store.read(idx, frame)
                    writer.addFrame(encodeWebP(frame, q), duration)
                    progress((k + 1f) / indices.size)
                }
                val bytes = writer.build()
                if (bytes.size <= ANIMATED_LIMIT) return EncodedAnimation(bytes, indices.size)
            }
        }
        error("Couldn't fit the animation under 500 KB. Try a shorter clip or fewer frames per second.")
    }
}

/** Keeps rendered frames as raw pixels in a cache file so we can re-encode them at other qualities. */
class FrameStore(private val file: File, val width: Int, val height: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val frameBytes = width * height * 4
    private val buffer = ByteBuffer.allocate(frameBytes)
    var count = 0
        private set

    fun add(bitmap: Bitmap) {
        buffer.clear()
        bitmap.copyPixelsToBuffer(buffer)
        raf.seek(count.toLong() * frameBytes)
        raf.write(buffer.array(), 0, frameBytes)
        count++
    }

    fun read(index: Int, into: Bitmap) {
        raf.seek(index.toLong() * frameBytes)
        raf.readFully(buffer.array(), 0, frameBytes)
        buffer.rewind()
        into.copyPixelsFromBuffer(buffer)
    }

    override fun close() {
        raf.close()
        file.delete()
    }
}
