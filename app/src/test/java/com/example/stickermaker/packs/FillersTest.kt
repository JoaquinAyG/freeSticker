package com.example.stickermaker.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillersTest {

    private fun pack(count: Int, animated: Boolean = false) = StickerPack(
        id = "pack_1",
        name = "Test",
        publisher = "Me",
        animated = animated,
        imageDataVersion = 1,
        trayFile = PackRepository.TRAY_FILE,
        stickers = (1..count).map { Sticker("sticker_$it.webp", listOf("😀")) },
    )

    @Test
    fun emptyPackGetsNoFillers() {
        // Nothing to show (and no tray icon), so an empty pack is never sent.
        assertEquals(0, Fillers.countFor(pack(0)))
        assertTrue(Fillers.namesFor(pack(0)).isEmpty())
    }

    @Test
    fun packsBelowThreeAreToppedUpToThree() {
        assertEquals(2, Fillers.countFor(pack(1)))
        assertEquals(1, Fillers.countFor(pack(2)))
        assertEquals(PackRepository.MIN_STICKERS, pack(1).stickers.size + Fillers.countFor(pack(1)))
    }

    @Test
    fun packsWithThreeOrMoreGetNoFillers() {
        assertEquals(0, Fillers.countFor(pack(3)))
        assertEquals(0, Fillers.countFor(pack(30)))
    }

    @Test
    fun fillerNamesMatchPackTypeAndAreUnique() {
        assertEquals(listOf("filler_s1.webp", "filler_s2.webp"), Fillers.namesFor(pack(1)))
        assertEquals(listOf("filler_a1.webp", "filler_a2.webp"), Fillers.namesFor(pack(1, animated = true)))
    }

    @Test
    fun fillerNamesNeverCollideWithRealStickers() {
        assertTrue(Fillers.isFiller("filler_s1.webp"))
        assertFalse(Fillers.isFiller("sticker_1795115206674.webp"))
        assertFalse(Fillers.isFiller(PackRepository.TRAY_FILE))
    }
}
