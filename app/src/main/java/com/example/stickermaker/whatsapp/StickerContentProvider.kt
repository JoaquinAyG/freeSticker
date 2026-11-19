package com.example.stickermaker.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.stickermaker.packs.Fillers
import com.example.stickermaker.packs.PackRepository
import com.example.stickermaker.packs.Sticker
import com.example.stickermaker.packs.StickerPack
import java.io.FileNotFoundException

/**
 * The contract WhatsApp uses to read third-party sticker packs
 * (same URIs and column names as WhatsApp's official sample app).
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)
    private val repo by lazy { PackRepository.get(context!!) }

    override fun onCreate(): Boolean {
        authority = WhatsAppHelper.authority(context!!)
        matcher.addURI(authority, METADATA, CODE_ALL_PACKS)
        matcher.addURI(authority, "$METADATA/*", CODE_SINGLE_PACK)
        matcher.addURI(authority, "$STICKERS/*", CODE_STICKERS)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", CODE_STICKER_FILE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = when (matcher.match(uri)) {
            CODE_ALL_PACKS -> packCursor(repo.listPacks())
            CODE_SINGLE_PACK -> packCursor(listOfNotNull(repo.getPack(uri.lastPathSegment.orEmpty())))
            CODE_STICKERS -> stickerCursor(repo.getPack(uri.lastPathSegment.orEmpty()))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (matcher.match(uri) != CODE_STICKER_FILE) throw IllegalArgumentException("Unknown URI: $uri")
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("Bad URI: $uri")
        val packId = segments[1]
        val fileName = segments[2]
        val pack = repo.getPack(packId) ?: throw FileNotFoundException("No pack $packId")
        // Only serve files that belong to the pack (no path tricks).
        val file = when {
            fileName == pack.trayFile || pack.stickers.any { it.fileName == fileName } -> repo.file(packId, fileName)
            fileName in Fillers.namesFor(pack) -> Fillers.file(context!!, fileName)
            else -> null
        } ?: throw FileNotFoundException("No file $fileName")
        if (!file.exists()) throw FileNotFoundException("Missing $fileName")
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length(),
        )
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_ALL_PACKS -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_STICKER_FILE -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun packCursor(packs: List<StickerPack>): MatrixCursor {
        val cursor = MatrixCursor(
            arrayOf(
                PACK_ID, PACK_NAME, PACK_PUBLISHER, PACK_ICON, ANDROID_LINK, IOS_LINK,
                PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY, LICENSE_AGREEMENT,
                IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_PACK,
            ),
        )
        packs.forEach { p ->
            cursor.addRow(
                arrayOf<Any>(
                    p.id, p.name, p.publisher, p.trayFile, "", "",
                    "", "", "", "",
                    p.imageDataVersion.toString(), 0, if (p.animated) 1 else 0,
                ),
            )
        }
        return cursor
    }

    private fun stickerCursor(pack: StickerPack?): MatrixCursor {
        val cursor = MatrixCursor(arrayOf(STICKER_FILE, STICKER_EMOJI, STICKER_ACCESSIBILITY))
        pack?.stickers?.forEach { s ->
            cursor.addRow(arrayOf<Any>(s.fileName, s.emojis.ifEmpty { Sticker.DEFAULT_EMOJIS }.joinToString(","), s.caption))
        }
        // Blank stickers so packs with 1-2 stickers reach WhatsApp's minimum of 3.
        pack?.let { Fillers.namesFor(it) }?.forEach { name ->
            cursor.addRow(arrayOf<Any>(name, Fillers.EMOJI, Fillers.ACCESSIBILITY_TEXT))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_ALL_PACKS = 1
        private const val CODE_SINGLE_PACK = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKER_FILE = 4

        private const val PACK_ID = "sticker_pack_identifier"
        private const val PACK_NAME = "sticker_pack_name"
        private const val PACK_PUBLISHER = "sticker_pack_publisher"
        private const val PACK_ICON = "sticker_pack_icon"
        private const val ANDROID_LINK = "android_play_store_link"
        private const val IOS_LINK = "ios_app_download_link"
        private const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        private const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        private const val PRIVACY_POLICY = "sticker_pack_privacy_policy_website"
        private const val LICENSE_AGREEMENT = "sticker_pack_license_agreement_website"
        private const val IMAGE_DATA_VERSION = "image_data_version"
        private const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        private const val ANIMATED_PACK = "animated_sticker_pack"

        private const val STICKER_FILE = "sticker_file_name"
        private const val STICKER_EMOJI = "sticker_emoji"
        private const val STICKER_ACCESSIBILITY = "sticker_accessibility_text"
    }
}
