package com.example.stickermaker.packs

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Stores sticker packs in app-private storage:
 *   files/packs/<packId>/pack.json, tray.png, sticker_*.webp
 * Methods are synchronized because WhatsApp reads through the content provider on binder threads.
 */
class PackRepository private constructor(context: Context) {

    private val root = File(context.filesDir, "packs").apply { mkdirs() }

    fun packDir(packId: String) = File(root, packId)

    fun file(packId: String, fileName: String) = File(packDir(packId), fileName)

    @Synchronized
    fun listPacks(): List<StickerPack> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { read(it.name) }
            ?.sortedByDescending { it.id }
            .orEmpty()

    @Synchronized
    fun getPack(packId: String): StickerPack? = read(packId)

    @Synchronized
    fun createPack(name: String, publisher: String, animated: Boolean): StickerPack {
        var id = "pack_${System.currentTimeMillis()}"
        while (packDir(id).exists()) id += "_1"
        packDir(id).mkdirs()
        val pack = StickerPack(
            id = id,
            name = name.trim().ifEmpty { "My stickers" }.take(128),
            publisher = publisher.trim().ifEmpty { "Me" }.take(128),
            animated = animated,
            imageDataVersion = 1,
            trayFile = TRAY_FILE,
            stickers = emptyList(),
        )
        write(pack)
        return pack
    }

    /** Saves [data] (a 512x512 WebP) into the pack. [trayBitmap] becomes the tray icon if the pack has none yet. */
    @Synchronized
    fun addSticker(
        packId: String,
        data: ByteArray,
        emojis: List<String>,
        caption: String,
        trayBitmap: Bitmap,
    ): StickerPack {
        val pack = read(packId) ?: error("Pack not found")
        check(pack.stickers.size < MAX_STICKERS) { "This pack is full ($MAX_STICKERS stickers)" }
        val name = "sticker_${System.currentTimeMillis()}.webp"
        file(packId, name).writeBytes(data)
        val tray = file(packId, pack.trayFile)
        if (!tray.exists()) writeTray(tray, trayBitmap)
        val updated = pack.copy(
            stickers = pack.stickers + Sticker(name, emojis, caption),
            imageDataVersion = pack.imageDataVersion + 1,
        )
        write(updated)
        return updated
    }

    @Synchronized
    fun removeSticker(packId: String, fileName: String): StickerPack? {
        val pack = read(packId) ?: return null
        file(packId, fileName).delete()
        val updated = pack.copy(
            stickers = pack.stickers.filterNot { it.fileName == fileName },
            imageDataVersion = pack.imageDataVersion + 1,
        )
        write(updated)
        return updated
    }

    @Synchronized
    fun renamePack(packId: String, name: String, publisher: String) {
        val pack = read(packId) ?: return
        write(pack.copy(
            name = name.trim().ifEmpty { pack.name }.take(128),
            publisher = publisher.trim().ifEmpty { pack.publisher }.take(128),
            imageDataVersion = pack.imageDataVersion + 1,
        ))
    }

    @Synchronized
    fun deletePack(packId: String) {
        packDir(packId).deleteRecursively()
    }

    private fun read(packId: String): StickerPack? = runCatching {
        StickerPack.fromJson(JSONObject(File(packDir(packId), PACK_JSON).readText()))
    }.getOrNull()

    private fun write(pack: StickerPack) {
        val dir = packDir(pack.id).apply { mkdirs() }
        val tmp = File(dir, "$PACK_JSON.tmp")
        tmp.writeText(pack.toJson().toString())
        val target = File(dir, PACK_JSON)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun writeTray(file: File, source: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(source, TRAY_SIZE, TRAY_SIZE, true)
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
        const val TRAY_FILE = "tray.png"
        private const val TRAY_SIZE = 96
        private const val PACK_JSON = "pack.json"

        @Volatile
        private var instance: PackRepository? = null

        fun get(context: Context): PackRepository =
            instance ?: synchronized(this) {
                instance ?: PackRepository(context.applicationContext).also { instance = it }
            }

        /** Tests get a fresh app (and files dir) each time, so they drop the cached instance. */
        @VisibleForTesting
        fun resetForTests() {
            instance = null
        }
    }
}
