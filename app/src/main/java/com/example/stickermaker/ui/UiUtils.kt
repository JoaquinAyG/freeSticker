package com.example.stickermaker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.stickermaker.databinding.AppBarBinding
import java.util.Locale
import kotlin.math.max

/**
 * Every screen draws edge-to-edge (mandatory on new Android versions), so: use the layout's own
 * toolbar, push the toolbar below the status bar, and keep content above the navigation bar / keyboard.
 */
fun AppCompatActivity.setupScreen(root: View, appBar: AppBarBinding, showUp: Boolean = true) {
    enableEdgeToEdge()
    setSupportActionBar(appBar.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(showUp)
    val left = root.paddingLeft
    val right = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(left + bars.left, 0, right + bars.right, bottom + max(bars.bottom, ime.bottom))
        appBar.root.updatePadding(top = bars.top)
        insets
    }
}

/** The media uri an editor was opened with: either from our gallery (data) or a share intent (EXTRA_STREAM). */
fun Intent.sourceUri(): Uri? = data ?: IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)

fun formatSeconds(seconds: Float): String = String.format(Locale.US, "%.1fs", seconds)

fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

/** A FrameLayout that is always as tall as it is wide (grid cells). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Grey checkerboard used behind stickers so transparency is visible. */
class CheckerDrawable(cellPx: Int = 24) : Drawable() {
    private val paint = Paint().apply {
        val tile = Bitmap.createBitmap(cellPx * 2, cellPx * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(tile)
        c.drawColor(0xFFE6E6E6.toInt())
        val dark = Paint().apply { color = 0xFFC8C8C8.toInt() }
        c.drawRect(0f, 0f, cellPx.toFloat(), cellPx.toFloat(), dark)
        c.drawRect(cellPx.toFloat(), cellPx.toFloat(), cellPx * 2f, cellPx * 2f, dark)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
