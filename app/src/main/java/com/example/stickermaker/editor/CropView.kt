package com.example.stickermaker.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.stickermaker.media.StickerRenderer
import com.example.stickermaker.media.StickerShape
import com.example.stickermaker.ui.CheckerDrawable
import com.example.stickermaker.ui.dp
import kotlin.math.max
import kotlin.math.min

/** Snapshot of the crop the user chose; maps any same-aspect source frame into the output. */
class CropSpec(
    private val bitmapWidth: Int,
    private val bitmapHeight: Int,
    private val drawMatrix: Matrix,
    private val frame: RectF,
) {
    fun matrix(srcWidth: Int, srcHeight: Int, outWidth: Int, outHeight: Int) = Matrix().apply {
        setScale(bitmapWidth.toFloat() / srcWidth, bitmapHeight.toFloat() / srcHeight)
        postConcat(drawMatrix)
        postTranslate(-frame.left, -frame.top)
        postScale(outWidth / frame.width(), outHeight / frame.height())
    }
}

/**
 * Shows an image behind a square sticker frame. Pinch to zoom, drag to pan, double-tap to
 * switch between "fill" (crop) and "fit" (whole image, transparent margins).
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onCropChanged: (() -> Unit)? = null

    var shape: StickerShape = StickerShape.SQUARE
        set(value) {
            field = value
            invalidate()
        }

    /** When false the whole image is shown without the sticker frame (e.g. "keep original shape"). */
    var cropEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var bitmap: Bitmap? = null
    private val fitMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val frame = RectF()
    private val values = FloatArray(9)
    private val tmpRect = RectF()
    private val overlayPath = Path()

    private val checker = CheckerDrawable(context.dp(10f).toInt())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = context.dp(2f)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            val current = currentScale()
            val target = (current * detector.scaleFactor).coerceIn(fitScale(b), fillScale(b) * 10f)
            val f = target / current
            drawMatrix.postScale(f, f, detector.focusX, detector.focusY)
            constrain()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            drawMatrix.postTranslate(-distanceX, -distanceY)
            constrain()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val b = bitmap ?: return false
            if (currentScale() > fitScale(b) * 1.01f) resetToFit() else resetToFill()
            return true
        }
    })

    /**
     * Sets the image. With [keepTransform] (used when scrubbing video frames) the current
     * zoom/position is kept even if the new bitmap has a different pixel size.
     */
    fun setBitmap(b: Bitmap, keepTransform: Boolean = false) {
        val old = bitmap
        bitmap = b
        if (keepTransform && old != null) {
            drawMatrix.preScale(old.width.toFloat() / b.width, old.height.toFloat() / b.height)
            invalidate()
        } else {
            resetToFill()
        }
    }

    fun resetToFill() = bitmap?.let { resetTo(fillScale(it)) }

    fun resetToFit() = bitmap?.let { resetTo(fitScale(it)) }

    /** Zooms so [rect] (in bitmap pixels, e.g. a cut-out subject) fills the sticker frame with a small margin. */
    fun focusOn(rect: RectF) {
        val b = bitmap ?: return
        if (frame.isEmpty || rect.isEmpty) return
        val s = min(frame.width() / (rect.width() * 1.12f), frame.height() / (rect.height() * 1.12f))
            .coerceIn(fitScale(b), fillScale(b) * 10f)
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(frame.centerX() - rect.centerX() * s, frame.centerY() - rect.centerY() * s)
        invalidate()
        onCropChanged?.invoke()
    }

    fun snapshot(): CropSpec? {
        val b = bitmap ?: return null
        if (frame.isEmpty) return null
        return CropSpec(b.width, b.height, Matrix(drawMatrix), RectF(frame))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = context.dp(16f)
        val side = min(w, h) - 2 * pad
        frame.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        resetToFill()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!cropEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            onCropChanged?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return
        if (!cropEnabled) {
            bitmap?.let { b ->
                val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
                fitMatrix.setScale(s, s)
                fitMatrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
                canvas.drawBitmap(b, fitMatrix, bitmapPaint)
            }
            return
        }
        checker.setBounds(frame.left.toInt(), frame.top.toInt(), frame.right.toInt(), frame.bottom.toInt())
        checker.draw(canvas)
        bitmap?.let { canvas.drawBitmap(it, drawMatrix, bitmapPaint) }

        val shapePath = StickerRenderer.shapePath(shape, frame)
        overlayPath.reset()
        overlayPath.fillType = Path.FillType.EVEN_ODD
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addPath(shapePath)
        canvas.drawPath(overlayPath, dimPaint)
        canvas.drawPath(shapePath, framePaint)
    }

    private fun resetTo(scale: Float) {
        val b = bitmap ?: return
        if (frame.isEmpty) return
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(frame.centerX() - b.width * scale / 2f, frame.centerY() - b.height * scale / 2f)
        invalidate()
        onCropChanged?.invoke()
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun fillScale(b: Bitmap) = max(frame.width() / b.width, frame.height() / b.height)

    private fun fitScale(b: Bitmap) = min(frame.width() / b.width, frame.height() / b.height)

    /** Keeps the frame covered when zoomed in, and the image inside the frame when zoomed out. */
    private fun constrain() {
        val b = bitmap ?: return
        tmpRect.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        drawMatrix.mapRect(tmpRect)
        val dx = axisCorrection(tmpRect.left, tmpRect.right, frame.left, frame.right)
        val dy = axisCorrection(tmpRect.top, tmpRect.bottom, frame.top, frame.bottom)
        drawMatrix.postTranslate(dx, dy)
    }

    private fun axisCorrection(imgStart: Float, imgEnd: Float, frameStart: Float, frameEnd: Float): Float {
        val bigger = imgEnd - imgStart >= frameEnd - frameStart
        return when {
            bigger && imgStart > frameStart -> frameStart - imgStart
            bigger && imgEnd < frameEnd -> frameEnd - imgEnd
            !bigger && imgStart < frameStart -> frameStart - imgStart
            !bigger && imgEnd > frameEnd -> frameEnd - imgEnd
            else -> 0f
        }
    }
}
