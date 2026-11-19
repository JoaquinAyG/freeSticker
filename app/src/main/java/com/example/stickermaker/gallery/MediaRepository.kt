package com.example.stickermaker.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val album: String?,
    val durationMs: Long,
    val mimeType: String = "",
    val dateAdded: Long = 0L,
) {
    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true)
}

/** Lists every photo or video on the device that the app can see, newest first. */
object MediaRepository {

    /** Videos plus animated GIFs (both can become animated stickers), newest first. */
    fun queryAnimated(context: Context): List<MediaItem> =
        (query(context, videos = true) + query(context, videos = false, gifsOnly = true))
            .sortedByDescending { it.dateAdded }

    fun query(context: Context, videos: Boolean, gifsOnly: Boolean = false): List<MediaItem> {
        val collection = if (videos) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (videos) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            if (gifsOnly) "${MediaStore.MediaColumns.MIME_TYPE} = ?" else null,
            if (gifsOnly) arrayOf("image/gif") else null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol = if (videos) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                items += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol).orEmpty(),
                    album = c.getString(albumCol),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                    mimeType = c.getString(mimeCol).orEmpty(),
                    dateAdded = c.getLong(dateCol),
                )
            }
        }
        return items
    }
}
