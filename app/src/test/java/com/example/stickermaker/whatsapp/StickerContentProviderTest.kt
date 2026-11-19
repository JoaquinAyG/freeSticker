package com.example.stickermaker.whatsapp

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.stickermaker.packs.Fillers
import com.example.stickermaker.packs.PackRepository
import com.example.stickermaker.packs.StickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException

/**
 * WhatsApp reads packs through this provider; these tests pin down the contract
 * (URIs, column names, values, fillers, file access) so it can't break silently.
 */
@RunWith(RobolectricTestRunner::class)
class StickerContentProviderTest {

    private lateinit var context: Context
    private lateinit var repo: PackRepository
    private lateinit var provider: StickerContentProvider
    private lateinit var authority: String
    private val tray = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PackRepository.resetForTests()
        repo = PackRepository.get(context)
        authority = WhatsAppHelper.authority(context)
        provider = Robolectric.buildContentProvider(StickerContentProvider::class.java).create(authority).get()
    }

    private fun uri(path: String) = Uri.parse("content://$authority/$path")

    private fun packWith(count: Int, animated: Boolean = false, name: String = "Test"): StickerPack {
        val pack = repo.createPack(name, "kino", animated)
        repeat(count) { repo.addSticker(pack.id, byteArrayOf(1, 2, 3), listOf("😀", "🔥"), "cap$it", tray) }
        return repo.getPack(pack.id)!!
    }

    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))

    @Test
    fun authorityMatchesTheManifest() {
        assertEquals("com.example.stickermaker.stickercontentprovider", authority)
    }

    @Test
    fun metadataListsAllPacksWithWhatsAppColumns() {
        val a = packWith(3, name = "Static")
        val b = packWith(1, animated = true, name = "Moving")
        provider.query(uri("metadata"), null, null, null, null).use { c ->
            assertEquals(2, c.count)
            listOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
                "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
                "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website", "image_data_version",
                "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            ).forEach { assertTrue("missing column $it", c.getColumnIndex(it) >= 0) }

            val rows = mutableMapOf<String, Pair<String, Int>>()
            while (c.moveToNext()) rows[c.string("sticker_pack_identifier")] =
                c.string("sticker_pack_name") to c.int("animated_sticker_pack")
            assertEquals("Static" to 0, rows[a.id])
            assertEquals("Moving" to 1, rows[b.id])
        }
    }

    @Test
    fun singlePackMetadataHasCurrentValues() {
        val pack = packWith(3)
        provider.query(uri("metadata/${pack.id}"), null, null, null, null).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(pack.id, c.string("sticker_pack_identifier"))
            assertEquals("kino", c.string("sticker_pack_publisher"))
            assertEquals(PackRepository.TRAY_FILE, c.string("sticker_pack_icon"))
            assertEquals(pack.imageDataVersion.toString(), c.string("image_data_version"))
            assertEquals(0, c.int("whatsapp_will_not_cache_stickers"))
        }
    }

    @Test
    fun unknownPackGivesEmptyResults() {
        provider.query(uri("metadata/nope"), null, null, null, null).use { assertEquals(0, it.count) }
        provider.query(uri("stickers/nope"), null, null, null, null).use { assertEquals(0, it.count) }
    }

    @Test
    fun stickersOfAFullPackHaveNoFillers() {
        val pack = packWith(3)
        provider.query(uri("stickers/${pack.id}"), null, null, null, null).use { c ->
            assertEquals(3, c.count)
            val names = mutableListOf<String>()
            while (c.moveToNext()) {
                names += c.string("sticker_file_name")
                assertEquals("😀,🔥", c.string("sticker_emoji"))
            }
            assertEquals(pack.stickers.map { it.fileName }, names)
        }
    }

    @Test
    fun smallPacksAreToppedUpWithFillersToThree() {
        val pack = packWith(1)
        provider.query(uri("stickers/${pack.id}"), null, null, null, null).use { c ->
            assertEquals(3, c.count)
            c.moveToPosition(0)
            assertEquals(pack.stickers.single().fileName, c.string("sticker_file_name"))
            for (i in 1..2) {
                c.moveToPosition(i)
                assertEquals("filler_s$i.webp", c.string("sticker_file_name"))
                assertEquals(Fillers.EMOJI, c.string("sticker_emoji"))
            }
        }
        val animated = packWith(2, animated = true)
        provider.query(uri("stickers/${animated.id}"), null, null, null, null).use { c ->
            assertEquals(3, c.count)
            c.moveToLast()
            assertEquals("filler_a1.webp", c.string("sticker_file_name"))
        }
    }

    @Test
    fun stickerTrayAndFillerFilesCanBeOpened() {
        val pack = packWith(1)
        val sticker = pack.stickers.single().fileName
        provider.openAssetFile(uri("stickers_asset/${pack.id}/$sticker"), "r").use { fd ->
            assertEquals(3L, fd.length)
        }
        provider.openAssetFile(uri("stickers_asset/${pack.id}/${PackRepository.TRAY_FILE}"), "r").use { fd ->
            assertTrue(fd.length > 0)
        }
        provider.openAssetFile(uri("stickers_asset/${pack.id}/filler_s1.webp"), "r").use { fd ->
            assertTrue(fd.length > 0)
        }
    }

    @Test
    fun filesOutsideThePackAreRefused() {
        val small = packWith(1)
        val full = packWith(3)
        listOf(
            "stickers_asset/${small.id}/pack.json", // metadata file, not an image
            "stickers_asset/${small.id}/sticker_missing.webp",
            "stickers_asset/${full.id}/filler_s1.webp", // full packs don't have fillers
            "stickers_asset/${small.id}/filler_a1.webp", // wrong filler type for a static pack
            "stickers_asset/nope/tray.png",
        ).forEach { path ->
            try {
                provider.openAssetFile(uri(path), "r")!!.close()
                fail("should refuse $path")
            } catch (_: FileNotFoundException) {
            }
        }
    }

    @Test
    fun mimeTypesFollowWhatsAppSample() {
        assertEquals("vnd.android.cursor.dir/vnd.$authority.metadata", provider.getType(uri("metadata")))
        assertEquals("vnd.android.cursor.item/vnd.$authority.metadata", provider.getType(uri("metadata/x")))
        assertEquals("vnd.android.cursor.dir/vnd.$authority.stickers", provider.getType(uri("stickers/x")))
        assertEquals("image/webp", provider.getType(uri("stickers_asset/x/a.webp")))
        assertEquals("image/png", provider.getType(uri("stickers_asset/x/tray.png")))
    }

    @Test
    fun unknownUrisAndWritesAreRejected() {
        try {
            provider.query(uri("something/else"), null, null, null, null)
            fail("unknown uri should throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            provider.delete(uri("metadata"), null, null)
            fail("provider is read-only")
        } catch (_: UnsupportedOperationException) {
        }
    }
}
