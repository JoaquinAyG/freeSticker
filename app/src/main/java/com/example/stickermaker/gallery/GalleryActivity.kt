package com.example.stickermaker.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.stickermaker.R
import com.example.stickermaker.databinding.ActivityGalleryBinding
import com.example.stickermaker.databinding.ItemMediaBinding
import com.example.stickermaker.editor.ImageEditorActivity
import com.example.stickermaker.editor.VideoEditorActivity
import com.example.stickermaker.packs.PackPicker
import com.example.stickermaker.ui.setupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Browse / search the phone's photos or videos and pick one to edit. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val isVideo by lazy { intent.getBooleanExtra(EXTRA_VIDEOS, false) }
    private var allItems = emptyList<MediaItem>()
    private var albumFilter: String? = null
    private val adapter = MediaAdapter { open(it.uri) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val systemPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { open(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupScreen(binding.root, binding.appBar)
        setTitle(if (isVideo) R.string.pick_video else R.string.pick_photo)

        binding.grid.layoutManager = GridLayoutManager(this, 4)
        binding.grid.adapter = adapter
        binding.searchInput.doAfterTextChanged { applyFilter() }
        binding.albumInput.setOnItemClickListener { parent, _, position, _ ->
            albumFilter = if (position == 0) null else parent.getItemAtPosition(position) as String
            applyFilter()
        }
        binding.grantButton.setOnClickListener { requestAccess() }
        binding.limitedBanner.setOnClickListener { requestAccess() }
        binding.systemPickerButton.setOnClickListener {
            val type = if (isVideo) ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else ActivityResultContracts.PickVisualMedia.ImageOnly
            systemPicker.launch(PickVisualMediaRequest(type))
        }

        // Ask unless we already have full access to this media type (photos and videos are
        // separate permissions, so "allow all" given on the photo screen doesn't cover videos).
        if (savedInstanceState == null && accessLevel() != Access.FULL) requestAccess()
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private enum class Access { NONE, PARTIAL, FULL }

    private fun mainPermission() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            if (isVideo) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun accessLevel(): Access = when {
        granted(mainPermission()) -> Access.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }

    /** Always asks for photos and videos together so the user only answers one prompt. */
    private fun requestAccess() {
        val perms = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    private fun refresh() {
        val access = accessLevel()
        binding.permissionPanel.isVisible = access == Access.NONE
        binding.limitedBanner.isVisible = access == Access.PARTIAL
        if (access == Access.NONE) {
            allItems = emptyList()
            adapter.submitList(emptyList())
            binding.emptyText.isVisible = false
            return
        }
        lifecycleScope.launch {
            allItems = withContext(Dispatchers.IO) {
                runCatching { if (isVideo) MediaRepository.queryAnimated(this@GalleryActivity) else MediaRepository.query(this@GalleryActivity, false) }.getOrDefault(emptyList())
            }
            val albums = allItems.mapNotNull { it.album }.distinct().sortedBy { it.lowercase() }
            val options = listOf(getString(R.string.all_albums)) + albums
            binding.albumInput.setSimpleItems(options.toTypedArray())
            if (albumFilter != null && albumFilter !in albums) albumFilter = null
            binding.albumInput.setText(albumFilter ?: options[0], false)
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = binding.searchInput.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        val album = albumFilter
        val filtered = allItems.filter { item ->
            (album == null || item.album == album) &&
                (query.isEmpty() ||
                    item.name.lowercase(Locale.getDefault()).contains(query) ||
                    item.album?.lowercase(Locale.getDefault())?.contains(query) == true)
        }
        adapter.submitList(filtered)
        binding.emptyText.isVisible = filtered.isEmpty() && !binding.permissionPanel.isVisible
    }

    private fun open(uri: Uri) {
        // On the photo screen the photo editor checks whether a GIF is animated and forwards it if so.
        val target = if (isVideo) VideoEditorActivity::class.java else ImageEditorActivity::class.java
        startActivity(
            Intent(this, target).setData(uri)
                .putExtra(PackPicker.EXTRA_TARGET_PACK, intent.getStringExtra(PackPicker.EXTRA_TARGET_PACK)),
        )
    }

    companion object {
        private const val EXTRA_VIDEOS = "videos"

        /** [targetPackId]: save the finished sticker straight into this pack. */
        fun intent(context: Context, videos: Boolean, targetPackId: String? = null) =
            Intent(context, GalleryActivity::class.java)
                .putExtra(EXTRA_VIDEOS, videos)
                .putExtra(PackPicker.EXTRA_TARGET_PACK, targetPackId)
    }
}

private class MediaAdapter(private val onClick: (MediaItem) -> Unit) :
    ListAdapter<MediaItem, MediaAdapter.Holder>(Diff) {

    class Holder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.binding.image.load(item.uri) { size(300) }
        val badge = when {
            item.isGif -> "GIF"
            item.durationMs > 0 -> (item.durationMs / 1000).let { s -> String.format(Locale.US, "%d:%02d", s / 60, s % 60) }
            else -> null
        }
        holder.binding.duration.visibility = if (badge != null) View.VISIBLE else View.GONE
        holder.binding.duration.text = badge
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(a: MediaItem, b: MediaItem) = a.uri == b.uri
        override fun areContentsTheSame(a: MediaItem, b: MediaItem) = a == b
    }
}
