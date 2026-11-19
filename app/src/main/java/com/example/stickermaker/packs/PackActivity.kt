package com.example.stickermaker.packs

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.stickermaker.R
import com.example.stickermaker.databinding.ActivityPackBinding
import com.example.stickermaker.databinding.DialogNewPackBinding
import com.example.stickermaker.databinding.DialogStickerBinding
import com.example.stickermaker.databinding.ItemStickerBinding
import com.example.stickermaker.gallery.GalleryActivity
import com.example.stickermaker.ui.CheckerDrawable
import com.example.stickermaker.ui.setupScreen
import com.example.stickermaker.whatsapp.WhatsAppHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows one pack's stickers and sends the pack to WhatsApp. */
class PackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPackBinding
    private lateinit var packId: String
    private var pack: StickerPack? = null
    private val repo by lazy { PackRepository.get(this) }
    private val adapter by lazy { StickerGridAdapter({ repo.file(packId, it.fileName) }, ::showStickerDialog) }

    private val addPackLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, R.string.pack_added, Toast.LENGTH_SHORT).show()
            } else {
                val error = result.data?.getStringExtra(WhatsAppHelper.EXTRA_VALIDATION_ERROR)
                if (error != null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.whatsapp_error)
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen(binding.root, binding.appBar)

        packId = intent.getStringExtra(EXTRA_PACK_ID) ?: run {
            finish()
            return
        }
        binding.stickerGrid.layoutManager = GridLayoutManager(this, 4)
        binding.stickerGrid.adapter = adapter
        binding.addToWhatsAppButton.setOnClickListener { addToWhatsApp() }
        binding.newStickerButton.setOnClickListener {
            val p = pack ?: return@setOnClickListener
            if (p.stickers.size >= PackRepository.MAX_STICKERS) {
                Toast.makeText(this, R.string.pack_full, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(GalleryActivity.intent(this, videos = p.animated, targetPackId = p.id))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pack, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_rename -> { showRenameDialog(); true }
        R.id.action_delete -> { confirmDeletePack(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { repo.getPack(packId) }
            if (p == null) {
                finish()
                return@launch
            }
            pack = p
            title = p.name
            binding.packName.text = p.name
            binding.packInfo.text = resources.getQuantityString(
                R.plurals.pack_subtitle,
                p.stickers.size,
                p.stickers.size,
                getString(if (p.animated) R.string.type_animated else R.string.type_static),
            ) + " · " + p.publisher
            binding.tray.load(repo.file(p.id, p.trayFile))
            adapter.submitList(p.stickers)
            val fillers = Fillers.countFor(p)
            binding.addToWhatsAppButton.isEnabled = p.stickers.isNotEmpty()
            binding.hintText.isVisible = fillers > 0
            if (fillers > 0) {
                binding.hintText.text = resources.getQuantityString(R.plurals.filler_hint, fillers, fillers)
            }
        }
    }

    private fun addToWhatsApp() {
        val p = pack ?: return
        val apps = WhatsAppHelper.installedApps(this)
        when (apps.size) {
            0 -> MaterialAlertDialogBuilder(this)
                .setMessage(R.string.whatsapp_missing)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            1 -> launchAdd(p, apps[0])
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_whatsapp)
                .setItems(arrayOf(getString(R.string.whatsapp), getString(R.string.whatsapp_business))) { _, which ->
                    launchAdd(p, apps[which])
                }
                .show()
        }
    }

    private fun launchAdd(p: StickerPack, targetPackage: String) {
        try {
            addPackLauncher.launch(WhatsAppHelper.addPackIntent(this, p, targetPackage))
        } catch (_: ActivityNotFoundException) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.whatsapp_unsupported)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showStickerDialog(sticker: Sticker) {
        val b = DialogStickerBinding.inflate(layoutInflater)
        b.stickerImage.background = CheckerDrawable()
        b.stickerImage.load(repo.file(packId, sticker.fileName))
        MaterialAlertDialogBuilder(this)
            .setView(b.root)
            .setPositiveButton(R.string.delete) { _, _ -> confirmDeleteSticker(sticker) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun confirmDeleteSticker(sticker: Sticker) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_sticker_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.removeSticker(packId, sticker.fileName) }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog() {
        val p = pack ?: return
        val b = DialogNewPackBinding.inflate(layoutInflater)
        b.nameInput.setText(p.name)
        b.publisherInput.setText(p.publisher)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(b.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = b.nameInput.text?.toString().orEmpty()
                val publisher = b.publisherInput.text?.toString().orEmpty()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.renamePack(packId, name, publisher) }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePack() {
        val p = pack ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_pack_confirm, p.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deletePack(packId) }
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_PACK_ID = "pack_id"

        fun intent(context: Context, packId: String) =
            Intent(context, PackActivity::class.java).putExtra(EXTRA_PACK_ID, packId)
    }
}

private class StickerGridAdapter(
    private val fileFor: (Sticker) -> File,
    private val onClick: (Sticker) -> Unit,
) : ListAdapter<Sticker, StickerGridAdapter.Holder>(Diff) {

    class Holder(val binding: ItemStickerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sticker = getItem(position)
        holder.binding.image.load(fileFor(sticker))
        holder.binding.root.setOnClickListener { onClick(sticker) }
    }

    private object Diff : DiffUtil.ItemCallback<Sticker>() {
        override fun areItemsTheSame(a: Sticker, b: Sticker) = a.fileName == b.fileName
        override fun areContentsTheSame(a: Sticker, b: Sticker) = a == b
    }
}
