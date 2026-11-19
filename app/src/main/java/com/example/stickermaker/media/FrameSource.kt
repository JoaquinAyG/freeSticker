package com.example.stickermaker.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Something we can pull timed frames out of: a video (MediaMetadataRetriever) or an animated GIF.
 * Not thread-safe; use from one thread at a time.
 */
interface FrameSource : Closeable {
    val durationMs: Long

    /** The frame showing at [timeUs]; [exact] = false allows a faster, approximate frame (scrubbing). */
    fun frameAt(timeUs: Long, exact: Boolean): Bitmap?

    companion object {
        /** Frames are decoded at most this big; the editor preview uses the same size so crops line up. */
        const val FRAME_DIM = 720

        fun isGif(context: Context, uri: Uri): Boolean = hasType(context, uri, "image/gif", ".gif")

        private fun isWebP(context: Context, uri: Uri): Boolean = hasType(context, uri, "image/webp", ".webp")

        private fun hasType(context: Context, uri: Uri, mime: String, extension: String): Boolean =
            context.contentResolver.getType(uri)?.equals(mime, ignoreCase = true) == true ||
                uri.lastPathSegment.orEmpty().endsWith(extension, ignoreCase = true)

        /** Reads the WebP header to see whether it is animated (cheap: 32 bytes). */
        fun isAnimatedWebP(context: Context, uri: Uri): Boolean = isWebP(context, uri) && runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(32)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                AnimatedWebPParser.isAnimated(header.copyOf(read))
            } ?: false
        }.getOrDefault(false)

        /** True for GIFs / WebPs with more than one frame (these go to the animated editor). */
        fun isAnimatedImage(context: Context, uri: Uri): Boolean = runCatching {
            isAnimatedWebP(context, uri) || (
                isGif(context, uri) &&
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) is
                    AnimatedImageDrawable
                )
        }.getOrDefault(false)

        fun open(context: Context, uri: Uri): FrameSource = when {
            isGif(context, uri) -> GifFrameSource(context, uri)
            isAnimatedWebP(context, uri) -> WebPFrameSource(context, uri)
            else -> VideoFrameSource(context, uri)
        }
    }
}

class VideoFrameSource(context: Context, uri: Uri) : FrameSource {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    override val durationMs: Long =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    override fun frameAt(timeUs: Long, exact: Boolean): Bitmap? = retriever.getScaledFrameAtTime(
        timeUs,
        if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        FrameSource.FRAME_DIM,
        FrameSource.FRAME_DIM,
    )

    override fun close() {
        runCatching { retriever.release() }
    }
}

/**
 * Decodes an animated GIF frame by frame. GIF frames build on the previous ones, so going
 * backwards restarts from the first frame; going forwards (how stickers are made) is cheap.
 */
class GifFrameSource(context: Context, uri: Uri) : FrameSource {
    private val decoder = StandardGifDecoder(SimpleBitmapProvider)
    private val frameStartsMs: LongArray
    private var currentIndex = -1
    private var currentFrame: Bitmap? = null

    override val durationMs: Long

    init {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't open the GIF")
        decoder.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
        val status = decoder.read(bytes)
        check(status == GifDecoder.STATUS_OK && decoder.frameCount > 0) { "This GIF can't be read" }
        frameStartsMs = LongArray(decoder.frameCount)
        var t = 0L
        for (i in 0 until decoder.frameCount) {
            frameStartsMs[i] = t
            t += delayOf(i)
        }
        durationMs = t
    }

    /** Browsers treat delays under 20 ms as 100 ms; do the same so GIFs play at the familiar speed. */
    private fun delayOf(i: Int): Long = decoder.getDelay(i).toLong().let { if (it < 20) 100L else it }

    override fun frameAt(timeUs: Long, exact: Boolean): Bitmap? {
        val t = (timeUs / 1000).coerceIn(0, max(0, durationMs - 1))
        var target = frameStartsMs.indexOfLast { it <= t }
        if (target < 0) target = 0
        if (target < currentIndex || currentFrame == null) {
            decoder.resetFrameIndex()
            currentIndex = -1
        }
        while (currentIndex < target) {
            decoder.advance()
            currentIndex = decoder.currentFrameIndex
            currentFrame = decoder.nextFrame ?: return currentFrame?.let { scaled(it) }
        }
        return currentFrame?.let { scaled(it) }
    }

    override fun close() {
        decoder.clear()
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) = Unit
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }
}

/** Returns a private copy of [frame] (decoders reuse their bitmaps), downscaled to [FrameSource.FRAME_DIM]. */
private fun scaled(frame: Bitmap): Bitmap {
    val largest = max(frame.width, frame.height)
    if (largest <= FrameSource.FRAME_DIM) return frame.copy(Bitmap.Config.ARGB_8888, false)
    val s = FrameSource.FRAME_DIM.toFloat() / largest
    return Bitmap.createScaledBitmap(
        frame, max(1, (frame.width * s).roundToInt()), max(1, (frame.height * s).roundToInt()), true,
    )
}

/**
 * Plays back an animated WebP frame by frame: each frame is decoded as a still WebP and
 * composited onto the canvas following the format's offset / blending / disposal rules.
 * Like GIFs, going backwards restarts from the first frame.
 */
class WebPFrameSource(context: Context, uri: Uri) : FrameSource {
    private val animation: AnimatedWebPParser.Animation
    private val frameStartsMs: LongArray
    private val canvasBitmap: Bitmap
    private val canvas: Canvas
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val rect = Rect()
    private var currentIndex = -1

    override val durationMs: Long

    init {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't open the WebP")
        animation = AnimatedWebPParser.parse(bytes)
        canvasBitmap = Bitmap.createBitmap(animation.canvasWidth, animation.canvasHeight, Bitmap.Config.ARGB_8888)
        canvas = Canvas(canvasBitmap)
        frameStartsMs = LongArray(animation.frames.size)
        var t = 0L
        animation.frames.forEachIndexed { i, f ->
            frameStartsMs[i] = t
            // Like browsers: very short or missing durations play at 100 ms.
            t += if (f.durationMs <= 10) 100 else f.durationMs
        }
        durationMs = t
    }

    override fun frameAt(timeUs: Long, exact: Boolean): Bitmap {
        val t = (timeUs / 1000).coerceIn(0, max(0, durationMs - 1))
        val target = frameStartsMs.indexOfLast { it <= t }.coerceAtLeast(0)
        if (target < currentIndex) {
            canvasBitmap.eraseColor(Color.TRANSPARENT)
            currentIndex = -1
        }
        while (currentIndex < target) {
            if (currentIndex >= 0) {
                val prev = animation.frames[currentIndex]
                if (prev.disposeToBackground) canvas.drawRect(frameRect(prev), clearPaint)
            }
            currentIndex++
            val frame = animation.frames[currentIndex]
            val bmp = BitmapFactory.decodeByteArray(frame.stillWebp, 0, frame.stillWebp.size) ?: continue
            if (frame.noBlend) canvas.drawRect(frameRect(frame), clearPaint)
            canvas.drawBitmap(bmp, frame.x.toFloat(), frame.y.toFloat(), null)
        }
        return scaled(canvasBitmap)
    }

    private fun frameRect(f: AnimatedWebPParser.Frame): Rect =
        rect.apply { set(f.x, f.y, f.x + f.width, f.y + f.height) }

    override fun close() = Unit
}
