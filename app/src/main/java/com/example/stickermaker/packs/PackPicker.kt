package com.example.stickermaker.packs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stickermaker.R
import com.example.stickermaker.databinding.DialogNewPackBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Which pack should this sticker go into?" flow shared by both editors. */
object PackPicker {
    const val EXTRA_TARGET_PACK = "target_pack_id"

    fun chooseAndSave(
        activity: AppCompatActivity,
        animated: Boolean,
        data: ByteArray,
        emojis: List<String>,
        caption: String,
        tray: Bitmap,
        targetPackId: String? = null,
    ) {
        val repo = PackRepository.get(activity)

        fun save(packId: String) = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.addSticker(packId, data, emojis, caption, tray) }
            }
            result.onSuccess { showAdded(activity, it) }
                .onFailure {
                    Toast.makeText(activity, it.message ?: activity.getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                }
        }

        activity.lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { repo.listPacks() }
                .filter { it.animated == animated && it.stickers.size < PackRepository.MAX_STICKERS }
            // Started from a pack's "New sticker" button: save straight into it when it fits.
            if (targetPackId != null && packs.any { it.id == targetPackId }) {
                save(targetPackId)
                return@launch
            }
            if (packs.isEmpty()) {
                promptNewPack(activity, animated) { save(it) }
                return@launch
            }
            val labels = packs.map { "${it.name} (${it.stickers.size}/${PackRepository.MAX_STICKERS})" } +
                activity.getString(R.string.new_pack_option)
            MaterialAlertDialogBuilder(activity)
                .setTitle(if (animated) R.string.choose_animated_pack else R.string.choose_pack)
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < packs.size) save(packs[which].id)
                    else promptNewPack(activity, animated) { save(it) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun promptNewPack(activity: AppCompatActivity, animated: Boolean, onCreated: (String) -> Unit) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val b = DialogNewPackBinding.inflate(activity.layoutInflater)
        b.publisherInput.setText(prefs.getString("publisher", activity.getString(R.string.default_publisher)))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.new_pack)
            .setView(b.root)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = b.nameInput.text?.toString().orEmpty()
                val publisher = b.publisherInput.text?.toString().orEmpty()
                prefs.edit().putString("publisher", publisher).apply()
                activity.lifecycleScope.launch {
                    val pack = withContext(Dispatchers.IO) {
                        PackRepository.get(activity).createPack(name, publisher, animated)
                    }
                    onCreated(pack.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        b.nameInput.requestFocus()
    }

    private fun showAdded(activity: AppCompatActivity, pack: StickerPack) {
        val count = pack.stickers.size
        val message = if (count < PackRepository.MIN_STICKERS) {
            activity.resources.getQuantityString(R.plurals.added_need_more, count, count)
        } else {
            activity.resources.getQuantityString(R.plurals.added_to_pack_msg, count, count)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.added_to_pack, pack.name))
            .setMessage(message)
            .setPositiveButton(R.string.open_pack) { _, _ ->
                // Go back to the pack screen if it is already open underneath, instead of stacking another.
                activity.startActivity(
                    PackActivity.intent(activity, pack.id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }
}
