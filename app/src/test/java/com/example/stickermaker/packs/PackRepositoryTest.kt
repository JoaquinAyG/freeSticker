package com.example.stickermaker.packs

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PackRepositoryTest {

    private lateinit var repo: PackRepository
    private val tray = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    private val webp = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() {
        PackRepository.resetForTests()
        repo = PackRepository.get(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun createPackAppliesDefaultsAndLimits() {
        val blank = repo.createPack("   ", "", animated = false)
        assertEquals("My stickers", blank.name)
        assertEquals("Me", blank.publisher)
        assertEquals(1, blank.imageDataVersion)
        assertTrue(blank.stickers.isEmpty())

        val long = repo.createPack("x".repeat(300), "y".repeat(300), animated = true)
        assertEquals(128, long.name.length) // WhatsApp's limit
        assertEquals(128, long.publisher.length)
        assertTrue(long.animated)
    }

    @Test
    fun packIdsAreUniqueAndUseOnlyWhatsAppSafeCharacters() {
        val ids = (1..5).map { repo.createPack("p$it", "me", false).id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9_.\\- ]{1,128}"))) }
    }

    @Test
    fun addStickerStoresFileTrayAndBumpsVersion() {
        val pack = repo.createPack("Test", "Me", false)
        val updated = repo.addSticker(pack.id, webp, listOf("😂"), "hi", tray)

        assertEquals(1, updated.stickers.size)
        assertEquals(2, updated.imageDataVersion)
        val sticker = updated.stickers.single()
        assertTrue(sticker.fileName.endsWith(".webp"))
        assertTrue(repo.file(pack.id, sticker.fileName).readBytes().contentEquals(webp))
        assertTrue(repo.file(pack.id, PackRepository.TRAY_FILE).exists())
        assertEquals(listOf("😂"), sticker.emojis)
        assertEquals("hi", sticker.caption)
    }

    @Test
    fun packIsLimitedTo30Stickers() {
        val pack = repo.createPack("Full", "Me", false)
        repeat(PackRepository.MAX_STICKERS) { repo.addSticker(pack.id, webp, listOf("😀"), "", tray) }
        try {
            repo.addSticker(pack.id, webp, listOf("😀"), "", tray)
            fail("31st sticker should be rejected")
        } catch (_: IllegalStateException) {
        }
        assertEquals(30, repo.getPack(pack.id)!!.stickers.size)
    }

    @Test
    fun removeStickerDeletesFileAndBumpsVersion() {
        val pack = repo.createPack("Test", "Me", false)
        val withOne = repo.addSticker(pack.id, webp, listOf("😀"), "", tray)
        val name = withOne.stickers.single().fileName

        val after = repo.removeSticker(pack.id, name)!!
        assertTrue(after.stickers.isEmpty())
        assertFalse(repo.file(pack.id, name).exists())
        assertEquals(withOne.imageDataVersion + 1, after.imageDataVersion)
    }

    @Test
    fun editsBumpImageDataVersionSoWhatsAppRefreshes() {
        val pack = repo.createPack("Test", "Me", false)
        val v2 = repo.addSticker(pack.id, webp, Sticker.DEFAULT_EMOJIS, "", tray)
        assertEquals(Sticker.DEFAULT_EMOJIS, v2.stickers.single().emojis)

        repo.renamePack(pack.id, "Renamed", "Someone")
        val v3 = repo.getPack(pack.id)!!
        assertEquals("Renamed", v3.name)
        assertEquals("Someone", v3.publisher)
        assertEquals(v2.imageDataVersion + 1, v3.imageDataVersion)
    }

    @Test
    fun renameIgnoresBlankValues() {
        val pack = repo.createPack("Keep", "Author", false)
        repo.renamePack(pack.id, "  ", "")
        val after = repo.getPack(pack.id)!!
        assertEquals("Keep", after.name)
        assertEquals("Author", after.publisher)
    }

    @Test
    fun deletePackRemovesEverything() {
        val pack = repo.createPack("Gone", "Me", false)
        repo.addSticker(pack.id, webp, listOf("😀"), "", tray)
        repo.deletePack(pack.id)
        assertNull(repo.getPack(pack.id))
        assertFalse(repo.packDir(pack.id).exists())
        assertTrue(repo.listPacks().none { it.id == pack.id })
    }

    @Test
    fun listPacksIsNewestFirst() {
        val a = repo.createPack("A", "Me", false)
        Thread.sleep(5)
        val b = repo.createPack("B", "Me", false)
        assertEquals(listOf(b.id, a.id), repo.listPacks().map { it.id })
    }

    @Test
    fun corruptPackFileIsSkippedNotCrashing() {
        val good = repo.createPack("Good", "Me", false)
        val badDir = repo.packDir("pack_bad").apply { mkdirs() }
        badDir.resolve("pack.json").writeText("{ not json")
        assertEquals(listOf(good.id), repo.listPacks().map { it.id })
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val pack = StickerPack(
            id = "pack_9", name = "Ñandú 🐦", publisher = "kino", animated = true,
            imageDataVersion = 7, trayFile = "tray.png",
            stickers = listOf(Sticker("a.webp", listOf("😀", "👍🏽"), "Hola"), Sticker("b.webp", listOf("🔥"))),
        )
        assertEquals(pack, StickerPack.fromJson(JSONObject(pack.toJson().toString())))
    }
}
