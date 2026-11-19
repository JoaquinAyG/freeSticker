package com.example.stickermaker.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class StickerShape { SQUARE, ROUNDED, CIRCLE }

data class StickerStyle(
    val shape: StickerShape = StickerShape.SQUARE,
    val border: Boolean = false,
    val caption: String = "",
    /** The source has its background removed: the outline then follows the subject's shape. */
    val cutout: Boolean = false,
)

/**
 * Draws a source image (photo or video frame) into an output bitmap with the chosen shape,
 * white outline and caption. One renderer is reused for every frame of an animation.
 */
class StickerRenderer(
    val width: Int,
    val height: Int,
    private val style: StickerStyle,
    applyShape: Boolean = true,
) {
    val output: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(output)
    private val unit = min(width, height) / STICKER_SIZE.toFloat()
    private val shape = if (applyShape) style.shape else StickerShape.SQUARE

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderWidth = 16f * unit
    private val shapeRect = RectF(0f, 0f, width.toFloat(), height.toFloat()).apply {
        if (style.border) inset(borderWidth / 2f, borderWidth / 2f)
    }
    private val shapePath = shapePath(shape, shapeRect)

    /** Everything outside the shape (inverse fill) is cleared to transparent. */
    private val outsidePath: Path? = if (shape == StickerShape.SQUARE) null else {
        Path(shapePath).apply { fillType = Path.FillType.INVERSE_WINDING }
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = borderWidth
    }

    // Outline that traces a cut-out subject: the subject's alpha is stamped in white at
    // offsets around a circle (a cheap dilation), then the subject is drawn on top.
    private val silhouetteOutline = style.cutout && style.border
    private val layer: Bitmap? =
        if (silhouetteOutline) Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) else null
    private val layerCanvas: Canvas? = layer?.let { Canvas(it) }
    private val outlinePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
    private val outlineOffsets: List<PointF> = buildList {
        val radius = 11f * unit
        for (ring in listOf(1f, 0.5f)) {
            val steps = if (ring == 1f) 24 else 12
            for (k in 0 until steps) {
                val angle = 2.0 * Math.PI * k / steps
                add(PointF((cos(angle) * radius * ring).toFloat(), (sin(angle) * radius * ring).toFloat()))
            }
        }
    }

    private val caption = style.caption.trim()
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val textStroke = Paint(textFill).apply {
        color = Color.BLACK
        this.style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    init {
        if (caption.isNotEmpty()) {
            var size = 72f * unit
            textFill.textSize = size
            while (textFill.measureText(caption) > width * 0.9f && size > 18f * unit) {
                size -= 2f * unit
                textFill.textSize = size
            }
            textStroke.textSize = size
            textStroke.strokeWidth = size * 0.18f
        }
    }

    /** Renders [src] transformed by [matrix] (source pixels -> output pixels). Returns [output]. */
    fun render(src: Bitmap, matrix: Matrix): Bitmap {
        output.eraseColor(Color.TRANSPARENT)
        val target = layerCanvas ?: canvas
        layer?.eraseColor(Color.TRANSPARENT)
        target.drawBitmap(src, matrix, bitmapPaint)
        outsidePath?.let { target.drawPath(it, clearPaint) }
        if (layer != null) {
            val alpha = layer.extractAlpha()
            for (o in outlineOffsets) canvas.drawBitmap(alpha, o.x, o.y, outlinePaint)
            alpha.recycle()
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else if (style.border) {
            canvas.drawPath(shapePath, borderPaint)
        }
        if (caption.isNotEmpty()) {
            val y = height - 28f * unit - textFill.descent()
            canvas.drawText(caption, width / 2f, y, textStroke)
            canvas.drawText(caption, width / 2f, y, textFill)
        }
        return output
    }

    companion object {
        const val STICKER_SIZE = 512

        fun shapePath(shape: StickerShape, rect: RectF): Path = Path().apply {
            when (shape) {
                StickerShape.SQUARE -> addRect(rect, Path.Direction.CW)
                StickerShape.ROUNDED -> {
                    val r = rect.width() * 0.18f
                    addRoundRect(rect, r, r, Path.Direction.CW)
                }
                StickerShape.CIRCLE -> addOval(rect, Path.Direction.CW)
            }
        }
    }
}
