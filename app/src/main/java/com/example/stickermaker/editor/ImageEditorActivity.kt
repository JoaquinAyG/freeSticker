package com.example.stickermaker.editor

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stickermaker.R
import com.example.stickermaker.databinding.ActivityImageEditorBinding
import com.example.stickermaker.media.BackgroundRemover
import com.example.stickermaker.media.Cutout
import com.example.stickermaker.media.FrameSource
import com.example.stickermaker.media.MediaUtils
import com.example.stickermaker.media.StickerEncoder
import com.example.stickermaker.media.StickerRenderer
import com.example.stickermaker.packs.PackPicker
import com.example.stickermaker.packs.Sticker
import com.example.stickermaker.ui.CheckerDrawable
import com.example.stickermaker.ui.setupScreen
import com.example.stickermaker.ui.sourceUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Crop a photo into a 512x512 static WhatsApp sticker. */
class ImageEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageEditorBinding
    private lateinit var styleControls: StyleControls
    private var original: Bitmap? = null
    private var cutout: Cutout? = null
    private var previewJob: Job? = null
    private var removeJob: Job? = null

    /** The picture the sticker is made from: the cut-out when "Remove background" is on. */
    private val source: Bitmap?
        get() = if (styleControls.removeBackground) cutout?.bitmap ?: original else original

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen(binding.root, binding.appBar)

        val uri = intent.sourceUri()
        if (uri == null) {
            finish()
            return
        }

        styleControls = StyleControls(binding.styleControls, ::onRemoveBackgroundChanged) {
            binding.cropView.shape = styleControls.shape
            updatePreview()
        }
        binding.preview.background = CheckerDrawable(12)
        binding.cropView.onCropChanged = { updatePreview() }
        binding.fitButton.setOnClickListener { binding.cropView.resetToFit() }
        binding.fillButton.setOnClickListener { binding.cropView.resetToFill() }
        binding.addButton.setOnClickListener { saveSticker() }
        binding.addButton.isEnabled = false

        lifecycleScope.launch {
            val animated = withContext(Dispatchers.IO) { FrameSource.isAnimatedImage(this@ImageEditorActivity, uri) }
            if (animated) {
                // Animated GIFs / WebPs are edited like videos (trim, speed, animated sticker / GIF).
                startActivity(
                    Intent(this@ImageEditorActivity, VideoEditorActivity::class.java)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(PackPicker.EXTRA_TARGET_PACK, intent.getStringExtra(PackPicker.EXTRA_TARGET_PACK)),
                )
                finish()
                return@launch
            }
            val bmp = withContext(Dispatchers.IO) {
                runCatching { MediaUtils.loadBitmap(this@ImageEditorActivity, uri) }.getOrNull()
            }
            if (bmp == null) {
                Toast.makeText(this@ImageEditorActivity, R.string.error_image, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            original = bmp
            binding.cropView.setBitmap(bmp)
            binding.addButton.isEnabled = true
            updatePreview()
        }
    }

    private fun onRemoveBackgroundChanged(enabled: Boolean) {
        val photo = original ?: return
        if (!enabled) {
            binding.cropView.setBitmap(photo, keepTransform = true)
            updatePreview()
            return
        }
        cutout?.let { showCutout(it); return }

        val popup = ProgressPopup(this, getString(R.string.removing_background), indeterminate = true) {
            removeJob?.cancel()
        }
        removeJob = lifecycleScope.launch {
            try {
                BackgroundRemover.ensureReady(this@ImageEditorActivity) {
                    runOnUiThread { popup.setMessage(getString(R.string.downloading_model)) }
                }
                val result = withContext(Dispatchers.Default) { BackgroundRemover.removeBackground(photo) }
                if (result.subjectBounds == null) error(getString(R.string.error_no_subject))
                cutout = result
                if (styleControls.removeBackground) showCutout(result)
            } catch (e: CancellationException) {
                styleControls.removeBackground = false
                throw e
            } catch (e: Exception) {
                styleControls.removeBackground = false
                MaterialAlertDialogBuilder(this@ImageEditorActivity)
                    .setMessage(getString(R.string.error_background, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                popup.dismiss()
            }
        }
    }

    private fun showCutout(result: Cutout) {
        binding.cropView.setBitmap(result.bitmap, keepTransform = true)
        result.subjectBounds?.let { binding.cropView.focusOn(it) }
        updatePreview()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updatePreview() {
        val src = source ?: return
        val spec = binding.cropView.snapshot() ?: return
        val style = styleControls.style
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(60)
            val size = 256
            val bmp = withContext(Dispatchers.Default) {
                StickerRenderer(size, size, style).render(src, spec.matrix(src.width, src.height, size, size))
            }
            binding.preview.setImageBitmap(bmp)
        }
    }

    private fun saveSticker() {
        val src = source ?: return
        val spec = binding.cropView.snapshot() ?: return
        val style = styleControls.style
        val emojis = Sticker.DEFAULT_EMOJIS
        binding.addButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val size = StickerRenderer.STICKER_SIZE
                    val rendered = StickerRenderer(size, size, style)
                        .render(src, spec.matrix(src.width, src.height, size, size))
                    StickerEncoder.encodeStatic(rendered) to rendered
                }
            }
            binding.addButton.isEnabled = true
            result.onSuccess { (data, rendered) ->
                PackPicker.chooseAndSave(
                    this@ImageEditorActivity, false, data, emojis, style.caption, rendered,
                    intent.getStringExtra(PackPicker.EXTRA_TARGET_PACK),
                )
            }.onFailure { e ->
                MaterialAlertDialogBuilder(this@ImageEditorActivity)
                    .setMessage(e.message ?: getString(R.string.error_generic))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
