package com.example.stickermaker.editor

import android.app.Activity
import androidx.core.widget.doAfterTextChanged
import com.example.stickermaker.R
import com.example.stickermaker.databinding.DialogProgressBinding
import com.example.stickermaker.databinding.StickerStyleControlsBinding
import com.example.stickermaker.media.StickerShape
import com.example.stickermaker.media.StickerStyle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** Wraps the shared shape / outline / caption / emoji controls. */
class StyleControls(
    private val b: StickerStyleControlsBinding,
    onRemoveBackgroundChanged: (Boolean) -> Unit = {},
    onChange: () -> Unit,
) {
    init {
        b.removeBgSwitch.setOnCheckedChangeListener { _, checked ->
            if (!settingProgrammatically) onRemoveBackgroundChanged(checked)
        }
        b.shapeGroup.setOnCheckedStateChangeListener { _, _ -> onChange() }
        b.borderSwitch.setOnCheckedChangeListener { _, _ -> onChange() }
        b.captionInput.doAfterTextChanged { onChange() }
    }

    val shape: StickerShape
        get() = when (b.shapeGroup.checkedChipId) {
            R.id.shapeRounded -> StickerShape.ROUNDED
            R.id.shapeCircle -> StickerShape.CIRCLE
            else -> StickerShape.SQUARE
        }

    private var settingProgrammatically = false

    /** Setting this does not trigger the change callback (used to undo a failed removal). */
    var removeBackground: Boolean
        get() = b.removeBgSwitch.isChecked
        set(value) {
            settingProgrammatically = true
            b.removeBgSwitch.isChecked = value
            settingProgrammatically = false
        }

    fun setRemoveBackgroundEnabled(enabled: Boolean) {
        b.removeBgSwitch.isEnabled = enabled
    }

    val style: StickerStyle
        get() = StickerStyle(shape, b.borderSwitch.isChecked, caption, cutout = removeBackground)

    val caption: String get() = b.captionInput.text?.toString()?.trim().orEmpty()
}

/** Non-cancelable progress dialog with a Cancel button. */
class ProgressPopup(
    activity: Activity,
    title: String,
    indeterminate: Boolean = false,
    onCancel: () -> Unit,
) {
    private val binding = DialogProgressBinding.inflate(activity.layoutInflater).apply {
        progressBar.isIndeterminate = indeterminate
        if (indeterminate) progressText.text = ""
    }
    private val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(title)
        .setView(binding.root)
        .setCancelable(false)
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .show()

    fun update(fraction: Float) {
        val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        binding.progressBar.setProgressCompat(pct, true)
        binding.progressText.text = "$pct%"
    }

    fun setMessage(text: String) {
        binding.progressText.text = text
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
