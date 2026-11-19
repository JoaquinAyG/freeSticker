package com.example.stickermaker.packs

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.example.stickermaker.media.AnimatedWebPWriter
import com.example.stickermaker.media.StickerRenderer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * WhatsApp only accepts packs with at least 3 stickers. Packs with 1–2 real stickers are topped
 * up with blank "filler" stickers when WhatsApp reads them. Fillers are never stored in the pack:
 * as soon as the pack has 3 real stickers they disappear.
 *
 * Each filler is fully transparent except one alpha=1 pixel at a different spot, so the files
 * are not byte-identical (in case WhatsApp de-duplicates identical stickers).
 */
object Fillers {
    private const val PREFIX = "filler_"
    const val EMOJI = "⬜"
    const val ACCESSIBILITY_TEXT = "Empty sticker"

    /** How many blank stickers [pack] needs to reach WhatsApp's minimum (none for an empty pack). */
    fun countFor(pack: StickerPack): Int =
        if (pack.stickers.isEmpty()) 0 else (PackRepository.MIN_STICKERS - pack.stickers.size).coerceAtLeast(0)

    fun namesFor(pack: StickerPack): List<String> =
        (1..countFor(pack)).map { i -> "$PREFIX${if (pack.animated) "a" else "s"}$i.webp" }

    fun isFiller(fileName: String) = fileName.startsWith(PREFIX)

    /** The file for a filler name, generated on first use. Null if the name isn't a valid filler. */
    @Synchronized
    fun file(context: Context, fileName: String): File? {
        val match = Regex("""${PREFIX}([as])(\d)\.webp""").matchEntire(fileName) ?: return null
        val animated = match.groupValues[1] == "a"
        val index = match.groupValues[2].toInt()
        if (index !in 1..PackRepository.MIN_STICKERS) return null
        val dir = File(context.filesDir, "fillers").apply { mkdirs() }
        val file = File(dir, fileName)
        if (!file.exists() || file.length() == 0L) file.writeBytes(if (animated) animated(index) else static(index, 0))
        return file
    }

    private fun static(index: Int, row: Int): ByteArray {
        val size = StickerRenderer.STICKER_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixel(index, row, 0x01000000) // practically invisible
        return encodeLossless(bmp)
    }

    private fun animated(index: Int): ByteArray {
        val size = StickerRenderer.STICKER_SIZE
        val writer = AnimatedWebPWriter(size, size)
        writer.addFrame(static(index, 0), 500)
        writer.addFrame(static(index, 1), 500)
        return writer.build()
    }

    @Suppress("DEPRECATION")
    private fun encodeLossless(bmp: Bitmap): ByteArray {
        // Lossless keeps the single faint pixel exactly (on Android 10, WEBP at quality 100 is lossless).
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.WEBP
        }
        return ByteArrayOutputStream().also { bmp.compress(format, 100, it) }.toByteArray()
    }
}
