package com.example.stickermaker.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.stickermaker.packs.StickerPack

object WhatsAppHelper {
    const val CONSUMER_PACKAGE = "com.whatsapp"
    const val BUSINESS_PACKAGE = "com.whatsapp.w4b"
    const val EXTRA_VALIDATION_ERROR = "validation_error"
    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun authority(context: Context) = context.packageName + ".stickercontentprovider"

    fun installedApps(context: Context): List<String> =
        listOf(CONSUMER_PACKAGE, BUSINESS_PACKAGE).filter { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent that opens WhatsApp's "Add sticker pack" screen for [pack]. */
    fun addPackIntent(context: Context, pack: StickerPack, targetPackage: String) =
        Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", authority(context))
            putExtra("sticker_pack_name", pack.name)
            setPackage(targetPackage)
        }

    /** Plain share of an image/GIF, optionally targeted at one app. */
    fun shareIntent(uri: Uri, mimeType: String, targetPackage: String?) =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            targetPackage?.let { setPackage(it) }
        }
}
