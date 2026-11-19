package com.example.stickermaker.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.request.CachePolicy
import com.example.stickermaker.R
import com.example.stickermaker.databinding.ActivityVideoEditorBinding
import com.example.stickermaker.databinding.DialogPreviewBinding
import com.example.stickermaker.media.AnimatedStickerResult
import com.example.stickermaker.media.BackgroundRemover
import com.example.stickermaker.media.ClipParams
import com.example.stickermaker.media.FrameSource
import com.example.stickermaker.media.MediaUtils
import com.example.stickermaker.media.StickerEncoder
import com.example.stickermaker.media.VideoProcessor
import com.example.stickermaker.packs.PackPicker
import com.example.stickermaker.packs.Sticker
import com.example.stickermaker.ui.CheckerDrawable
import com.example.stickermaker.ui.setupScreen
import com.example.stickermaker.ui.formatSeconds
import com.example.stickermaker.ui.sourceUri
import com.example.stickermaker.whatsapp.WhatsAppHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToLong

/** Trim a video and turn it into a GIF (saved to the gallery) or an animated WhatsApp sticker. */
class VideoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoEditorBinding
    private lateinit var styleControls: StyleControls
    private lateinit var videoUri: Uri

    // Frame sources are not thread-safe: all preview access goes through one thread.
    private var frameSource: FrameSource? = null
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val frameDispatcher = frameExecutor.asCoroutineDispatcher()

    private var frameJob: Job? = null
    private var workJob: Job? = null
    private var lastSliderValue = 0f
    private var lastFrameSeconds = 0f
    private var removeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen(binding.root, binding.appBar)

        videoUri = intent.sourceUri() ?: run {
            finish()
            return
        }

        when {
            FrameSource.isGif(this, videoUri) -> setTitle(R.string.from_gif_title)
            FrameSource.isAnimatedWebP(this, videoUri) -> setTitle(R.string.from_webp_title)
        }

        styleControls = StyleControls(binding.styleControls, ::onRemoveBackgroundChanged) {
            binding.cropView.shape = styleControls.shape
        }

        binding.rangeSlider.setLabelFormatter { formatSeconds(it) }
        binding.rangeSlider.addOnChangeListener { _, value, fromUser ->
            updateRangeLabel()
            if (fromUser) {
                lastSliderValue = value
                showFrameAt(value, exact = false)
            }
        }
        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) = showFrameAt(lastSliderValue, exact = true)
        })
        binding.fpsGroup.setOnCheckedStateChangeListener { _, _ -> updateRangeLabel() }
        binding.speedGroup.setOnCheckedStateChangeListener { _, _ -> updateRangeLabel() }
        binding.keepAspectSwitch.setOnCheckedChangeListener { _, checked ->
            binding.cropView.cropEnabled = !checked
        }
        binding.gifButton.setOnClickListener { makeGif() }
        binding.stickerButton.setOnClickListener { makeSticker() }
        binding.gifButton.isEnabled = false
        binding.stickerButton.isEnabled = false

        loadVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        frameExecutor.execute { runCatching { frameSource?.close() } }
        frameExecutor.shutdown()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadVideo() {
        lifecycleScope.launch {
            val durationMs = withContext(frameDispatcher) {
                runCatching {
                    FrameSource.open(this@VideoEditorActivity, videoUri).also { frameSource = it }.durationMs
                }.getOrDefault(0L)
            }
            if (durationMs <= 0L) {
                Toast.makeText(this@VideoEditorActivity, R.string.error_video, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val durationSec = durationMs / 1000f
            binding.rangeSlider.valueFrom = 0f
            binding.rangeSlider.valueTo = durationSec
            binding.rangeSlider.values = listOf(0f, min(durationSec, 3f))
            updateRangeLabel()
            binding.gifButton.isEnabled = true
            binding.stickerButton.isEnabled = true
            showFrameAt(0f, exact = true)
        }
    }

    private fun showFrameAt(seconds: Float, exact: Boolean) {
        frameJob?.cancel()
        lastFrameSeconds = seconds
        frameJob = lifecycleScope.launch {
            if (!exact) delay(40)
            val raw: Bitmap = withContext(frameDispatcher) {
                runCatching { frameSource?.frameAt((seconds * 1_000_000).toLong(), exact) }.getOrNull()
            } ?: return@launch
            // While scrubbing show the raw frame (fast); once settled, show the cut-out if enabled.
            val bmp = if (exact && styleControls.removeBackground) {
                runCatching {
                    withContext(Dispatchers.Default) { BackgroundRemover.removeBackground(raw).bitmap }
                }.getOrElse { if (it is CancellationException) throw it else raw }
            } else {
                raw
            }
            binding.cropView.setBitmap(bmp, keepTransform = true)
        }
    }

    private fun onRemoveBackgroundChanged(enabled: Boolean) {
        if (!enabled) {
            showFrameAt(lastFrameSeconds, exact = true)
            return
        }
        val popup = ProgressPopup(this, getString(R.string.removing_background), indeterminate = true) {
            removeJob?.cancel()
        }
        removeJob = lifecycleScope.launch {
            try {
                BackgroundRemover.ensureReady(this@VideoEditorActivity) {
                    runOnUiThread { popup.setMessage(getString(R.string.downloading_model)) }
                }
                val raw = withContext(frameDispatcher) {
                    frameSource?.frameAt((lastFrameSeconds * 1_000_000).toLong(), exact = true)
                } ?: return@launch
                val cut = withContext(Dispatchers.Default) { BackgroundRemover.removeBackground(raw) }
                if (cut.subjectBounds == null) error(getString(R.string.error_no_subject))
                binding.cropView.setBitmap(cut.bitmap, keepTransform = true)
                cut.subjectBounds?.let { binding.cropView.focusOn(it) }
            } catch (e: CancellationException) {
                styleControls.removeBackground = false
                throw e
            } catch (e: Exception) {
                styleControls.removeBackground = false
                showMessage(getString(R.string.error_background, e.message ?: e.javaClass.simpleName))
            } finally {
                popup.dismiss()
            }
        }
    }

    private fun currentClip(): ClipParams {
        val v = binding.rangeSlider.values
        val fps = when (binding.fpsGroup.checkedChipId) {
            R.id.fps8 -> 8
            R.id.fps15 -> 15
            R.id.fps20 -> 20
            else -> 10
        }
        val speed = when (binding.speedGroup.checkedChipId) {
            R.id.speedHalf -> 0.5f
            R.id.speedDouble -> 2f
            else -> 1f
        }
        return ClipParams((v[0] * 1000).roundToLong(), (v.last() * 1000).roundToLong(), fps, speed)
    }

    private fun updateRangeLabel() {
        val values = binding.rangeSlider.values
        if (values.size < 2) return
        val clip = currentClip()
        binding.rangeLabel.text = getString(
            R.string.range_label,
            formatSeconds(values[0]),
            formatSeconds(values[1]),
            formatSeconds(clip.outputDurationMs / 1000f),
            clip.frameCount,
        )
    }

    private fun showMessage(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Runs heavy work with a cancelable progress dialog. */
    private fun runWork(title: Int, block: suspend (progress: suspend (Float) -> Unit) -> Unit) {
        if (workJob?.isActive == true) return
        val popup = ProgressPopup(this, getString(title), onCancel = { workJob?.cancel() })
        workJob = lifecycleScope.launch {
            try {
                block { f -> withContext(Dispatchers.Main) { popup.update(f) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(e.message ?: getString(R.string.error_generic))
            } finally {
                popup.dismiss()
            }
        }
    }

    private fun makeGif() {
        val clip = currentClip()
        if (clip.frameCount < 2) return showMessage(getString(R.string.error_clip_too_short))
        if (clip.outputDurationMs > 30_000) return showMessage(getString(R.string.error_gif_too_long))
        val crop = binding.cropView.snapshot() ?: return
        val style = styleControls.style
        val keepAspect = binding.keepAspectSwitch.isChecked
        val size = if (binding.gifSizeGroup.checkedChipId == R.id.size480) 480 else 320

        runWork(R.string.making_gif) { progress ->
            val file = VideoProcessor.makeGif(applicationContext, videoUri, clip, crop, style, keepAspect, size, progress)
            val sizeKb = (file.length() / 1024).toInt()
            val uri = withContext(Dispatchers.IO) { MediaUtils.saveGifToGallery(applicationContext, file) }
            showGifResult(uri, sizeKb)
        }
    }

    private fun showGifResult(uri: Uri, sizeKb: Int) {
        val view = DialogPreviewBinding.inflate(layoutInflater)
        view.previewImage.background = CheckerDrawable()
        view.previewImage.load(uri)
        view.previewInfo.text = getString(R.string.gif_saved, sizeKb)
        val whatsApp = WhatsAppHelper.installedApps(this).firstOrNull()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_title)
            .setView(view.root)
            .setNegativeButton(R.string.done, null)
            .setNeutralButton(R.string.share) { _, _ ->
                startActivity(Intent.createChooser(WhatsAppHelper.shareIntent(uri, "image/gif", null), null))
            }
        if (whatsApp != null) {
            builder.setPositiveButton(R.string.send_whatsapp) { _, _ ->
                try {
                    startActivity(WhatsAppHelper.shareIntent(uri, "image/gif", whatsApp))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent.createChooser(WhatsAppHelper.shareIntent(uri, "image/gif", null), null))
                }
            }
        }
        builder.show()
    }

    private fun makeSticker() {
        val clip = currentClip()
        if (clip.frameCount < 2) return showMessage(getString(R.string.error_clip_too_short))
        if (clip.outputDurationMs > StickerEncoder.MAX_ANIMATION_MS) {
            return showMessage(getString(R.string.error_sticker_too_long))
        }
        val crop = binding.cropView.snapshot() ?: return
        val style = styleControls.style
        val emojis = Sticker.DEFAULT_EMOJIS

        runWork(R.string.making_sticker) { progress ->
            val result = VideoProcessor.makeAnimatedSticker(applicationContext, videoUri, clip, crop, style, progress)
            showStickerResult(result, emojis, style.caption)
        }
    }

    private fun showStickerResult(result: AnimatedStickerResult, emojis: List<String>, caption: String) {
        val file = File(cacheDir, "preview_sticker.webp").apply { writeBytes(result.data) }
        val view = DialogPreviewBinding.inflate(layoutInflater)
        view.previewImage.background = CheckerDrawable()
        view.previewImage.load(file) {
            memoryCachePolicy(CachePolicy.DISABLED)
            diskCachePolicy(CachePolicy.DISABLED)
        }
        view.previewInfo.text = getString(R.string.sticker_info, result.data.size / 1024, result.frameCount)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preview_title)
            .setView(view.root)
            .setPositiveButton(R.string.add_to_pack) { _, _ ->
                PackPicker.chooseAndSave(
                    this, true, result.data, emojis, caption, result.firstFrame,
                    intent.getStringExtra(PackPicker.EXTRA_TARGET_PACK),
                )
            }
            .setNegativeButton(R.string.discard, null)
            .show()
    }
}
