package com.example.stickermaker.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object MediaUtils {

    /** Decodes an image (EXIF rotation applied), downscaled so its longest side is <= [maxDim]. */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val largest = max(w, h)
            if (largest > maxDim) {
                val scale = maxDim.toFloat() / largest
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
    }

    /** Copies a finished GIF into Pictures/StickerMaker so it shows up in the gallery. */
    fun saveGifToGallery(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "StickerMaker_${System.currentTimeMillis()}.gif")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/StickerMaker")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the file in the gallery")
        try {
            resolver.openOutputStream(uri)!!.use { os -> file.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        } finally {
            file.delete()
        }
        return uri
    }
}
