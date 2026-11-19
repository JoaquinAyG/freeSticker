package com.example.stickermaker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.stickermaker.databinding.ActivityMainBinding
import com.example.stickermaker.databinding.ItemPackBinding
import com.example.stickermaker.gallery.GalleryActivity
import com.example.stickermaker.packs.PackActivity
import com.example.stickermaker.packs.PackRepository
import com.example.stickermaker.packs.StickerPack
import com.example.stickermaker.ui.setupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo by lazy { PackRepository.get(this) }
    private val adapter by lazy {
        PacksAdapter({ repo.file(it.id, it.trayFile) }) { startActivity(PackActivity.intent(this, it.id)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen(binding.root, binding.appBar, showUp = false)

        binding.packsList.layoutManager = LinearLayoutManager(this)
        binding.packsList.adapter = adapter
        binding.fromPhotoButton.setOnClickListener { startActivity(GalleryActivity.intent(this, videos = false)) }
        binding.fromVideoButton.setOnClickListener { startActivity(GalleryActivity.intent(this, videos = true)) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val packs = withContext(Dispatchers.IO) { repo.listPacks() }
            adapter.submitList(packs)
            binding.emptyText.isVisible = packs.isEmpty()
        }
    }
}

private class PacksAdapter(
    private val trayFor: (StickerPack) -> File,
    private val onClick: (StickerPack) -> Unit,
) : ListAdapter<StickerPack, PacksAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val pack = getItem(position)
        val ctx = holder.itemView.context
        holder.binding.tray.load(trayFor(pack))
        holder.binding.name.text = pack.name
        holder.binding.subtitle.text = ctx.resources.getQuantityString(
            R.plurals.pack_subtitle,
            pack.stickers.size,
            pack.stickers.size,
            ctx.getString(if (pack.animated) R.string.type_animated else R.string.type_static),
        )
        holder.binding.root.setOnClickListener { onClick(pack) }
    }

    private object Diff : DiffUtil.ItemCallback<StickerPack>() {
        override fun areItemsTheSame(a: StickerPack, b: StickerPack) = a.id == b.id
        override fun areContentsTheSame(a: StickerPack, b: StickerPack) = a == b
    }
}
