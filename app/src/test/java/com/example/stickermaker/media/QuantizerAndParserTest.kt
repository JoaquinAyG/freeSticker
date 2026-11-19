package com.example.stickermaker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class QuantizerAndParserTest {

    // ---------------- MedianCutQuantizer ----------------

    @Test
    fun fewColorsArePreservedExactlyEnough() {
        val colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val pixels = IntArray(400) { colors[it % 4] }
        val indices = ByteArray(pixels.size)
        val result = MedianCutQuantizer().quantize(pixels, pixels.size, indices)

        assertEquals(-1, result.transparentIndex)
        for (i in pixels.indices) {
            val got = result.palette[indices[i].toInt() and 0xFF]
            assertTrue("pixel $i", colorDistance(pixels[i], got) <= 12) // within RGB555 rounding
        }
    }

    @Test
    fun transparentPixelsUseReservedIndex() {
        val pixels = IntArray(100) { if (it < 50) 0x00000000 else 0xFF336699.toInt() }
        val indices = ByteArray(pixels.size)
        val result = MedianCutQuantizer().quantize(pixels, pixels.size, indices)

        assertEquals(255, result.transparentIndex)
        for (i in 0 until 50) assertEquals(255, indices[i].toInt() and 0xFF)
        for (i in 50 until 100) assertTrue((indices[i].toInt() and 0xFF) < 255)
    }

    @Test
    fun manyColorsAreReducedToAtMost256() {
        val rnd = java.util.Random(7)
        val pixels = IntArray(200 * 200) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        val indices = ByteArray(pixels.size)
        val result = MedianCutQuantizer().quantize(pixels, pixels.size, indices)

        assertEquals(256, result.palette.size)
        assertEquals(-1, result.transparentIndex)
        val used = indices.map { it.toInt() and 0xFF }.toSet()
        assertTrue("uses most of the palette (${used.size})", used.size > 200)
    }

    // ---------------- AnimatedWebPParser ----------------

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun stillAndGarbageFilesAreNotAnimated() {
        assertFalse(AnimatedWebPParser.isAnimated(resource("frame_opaque.webp")))
        assertFalse(AnimatedWebPParser.isAnimated(resource("frame_alpha.webp"))) // VP8X but no animation flag
        assertFalse(AnimatedWebPParser.isAnimated(ByteArray(0)))
        assertFalse(AnimatedWebPParser.isAnimated("GIF89a not a webp at all".toByteArray()))
    }

    @Test
    fun parsingAStillImageFails() {
        try {
            AnimatedWebPParser.parse(resource("frame_opaque.webp"))
            fail("expected an error")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun truncatedAnimationFails() {
        val writer = AnimatedWebPWriter(64, 64)
        writer.addFrame(resource("frame_opaque.webp"), 100)
        writer.addFrame(resource("frame_lossless.webp"), 100)
        val full = writer.build()
        try {
            AnimatedWebPParser.parse(full.copyOf(full.size - 10))
            fail("expected an error")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun frameOffsetsBlendingAndDisposalAreRead() {
        // Hand-built animation: 100x80 canvas, one 64x64 lossless frame at (10, 20),
        // 70 ms, alpha-blended, disposed to background.
        val vp8l = resource("frame_lossless.webp").let { it.copyOfRange(12, it.size) } // "VP8L" chunk
        val anmf = ByteArrayOutputStream().apply {
            le24(5); le24(10) // x/2, y/2
            le24(63); le24(63) // width-1, height-1
            le24(70) // duration
            write(0x01) // blend (bit 1 clear) + dispose (bit 0 set)
            write(vp8l)
        }.toByteArray()
        val vp8x = ByteArrayOutputStream().apply {
            write(0x12); write(0); write(0); write(0)
            le24(99); le24(79)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray())
            chunk("VP8X", vp8x)
            chunk("ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))
            chunk("ANMF", anmf)
        }.toByteArray()
        val file = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray()); le32(body.size); write(body)
        }.toByteArray()

        val anim = AnimatedWebPParser.parse(file)
        assertEquals(100, anim.canvasWidth)
        assertEquals(80, anim.canvasHeight)
        val f = anim.frames.single()
        assertEquals(10, f.x)
        assertEquals(20, f.y)
        assertEquals(64, f.width)
        assertEquals(64, f.height)
        assertEquals(70, f.durationMs)
        assertFalse(f.noBlend)
        assertTrue(f.disposeToBackground)
        assertTrue(f.stillWebp.contentEquals(resource("frame_lossless.webp")))
    }

    private fun ByteArrayOutputStream.le24(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Int) {
        le24(v); write((v ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.chunk(fourcc: String, data: ByteArray) {
        write(fourcc.toByteArray()); le32(data.size); write(data)
        if (data.size % 2 == 1) write(0)
    }

    private fun colorDistance(a: Int, b: Int) =
        Math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            Math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
            Math.abs((a and 0xFF) - (b and 0xFF))
}
