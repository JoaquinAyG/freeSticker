package com.example.stickermaker.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReader

class EncoderTest {

    @Test
    fun gifRoundTripsThroughImageIo() {
        val w = 40
        val h = 30
        val out = ByteArrayOutputStream()
        val gif = GifEncoder(out, w, h)
        // Frame 1: horizontal gradient with a transparent block in the corner.
        val f1 = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x < 5 && y < 5) 0x00000000 else (0xFF shl 24) or ((x * 6) shl 16) or (y * 8 shl 8) or 0x40
        }
        // Frame 2: solid color.
        val f2 = IntArray(w * h) { 0xFF12AB34.toInt() }
        // Frame 3: random noise (forces many LZW dictionary resets).
        val rnd = java.util.Random(1)
        val f3 = IntArray(w * h) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        gif.addFrame(f1, 100)
        gif.addFrame(f2, 100)
        gif.addFrame(f3, 100)
        gif.finish()

        val reader: ImageReader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(3, reader.getNumImages(true))

        val img1 = reader.read(0)
        assertEquals(w, img1.width)
        assertEquals(h, img1.height)
        assertEquals("transparent corner", 0, img1.getRGB(1, 1) ushr 24)
        assertClose(f1[20 * w + 30], img1.getRGB(30, 20))

        val img2 = reader.read(1)
        assertClose(f2[0], img2.getRGB(10, 10))

        // Large noisy frame to exercise code-size growth and table clears.
        val big = 300
        val bigOut = ByteArrayOutputStream()
        val bigGif = GifEncoder(bigOut, big, big)
        val noise = IntArray(big * big) { (0xFF shl 24) or rnd.nextInt(0xFFFFFF) }
        bigGif.addFrame(noise, 50)
        bigGif.finish()
        val bigImg = ImageIO.read(ByteArrayInputStream(bigOut.toByteArray()))
        var maxErr = 0
        for (y in 0 until big step 7) for (x in 0 until big step 7) {
            maxErr = maxOf(maxErr, colorError(noise[y * big + x], bigImg.getRGB(x, y)))
        }
        assertTrue("decoded noise frame too far from source: $maxErr", maxErr < 200)
    }

    @Test
    fun animatedWebPContainerIsWellFormed() {
        val frames = listOf("frame_alpha.webp", "frame_opaque.webp", "frame_lossless.webp").map { name ->
            javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        }
        val writer = AnimatedWebPWriter(64, 64)
        frames.forEachIndexed { i, f -> writer.addFrame(f, 100 + i * 50) }
        val data = writer.build()

        assertEquals("RIFF", String(data, 0, 4, Charsets.US_ASCII))
        assertEquals(data.size - 8, le32(data, 4))
        assertEquals("WEBP", String(data, 8, 4, Charsets.US_ASCII))
        assertEquals("VP8X", String(data, 12, 4, Charsets.US_ASCII))
        assertEquals(0x12, data[20].toInt() and 0xFF) // animation + alpha
        assertEquals(3, writer.frameCount)

        // Write for external verification (Pillow / browsers).
        File("build/test-output").mkdirs()
        File("build/test-output/anim.webp").writeBytes(data)
    }

    @Test
    fun animatedWebPRoundTripsThroughParser() {
        val names = listOf("frame_alpha.webp", "frame_opaque.webp", "frame_lossless.webp")
        val frames = names.map { javaClass.classLoader!!.getResourceAsStream(it)!!.readBytes() }
        val writer = AnimatedWebPWriter(64, 64)
        frames.forEachIndexed { i, f -> writer.addFrame(f, 100 + i * 50) }
        val data = writer.build()

        assertTrue(AnimatedWebPParser.isAnimated(data.copyOf(32)))
        assertTrue(!AnimatedWebPParser.isAnimated(frames[1].copyOf(32))) // plain still WebP

        val anim = AnimatedWebPParser.parse(data)
        assertEquals(64, anim.canvasWidth)
        assertEquals(64, anim.canvasHeight)
        assertEquals(3, anim.frames.size)
        assertEquals(listOf(100, 150, 200), anim.frames.map { it.durationMs })
        anim.frames.forEach {
            assertEquals(0, it.x); assertEquals(0, it.y)
            assertEquals(64, it.width); assertEquals(64, it.height)
            assertTrue(it.noBlend)
            assertTrue(!it.disposeToBackground)
            assertEquals("RIFF", String(it.stillWebp, 0, 4, Charsets.US_ASCII))
        }
        // The lossless frame is unchanged by the round trip.
        assertTrue(anim.frames[2].stillWebp.contentEquals(frames[2]))

        // Save the extracted stills for external verification (Pillow).
        File("build/test-output").mkdirs()
        anim.frames.forEachIndexed { i, f -> File("build/test-output/still_$i.webp").writeBytes(f.stillWebp) }
    }

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun colorError(a: Int, b: Int): Int =
        Math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            Math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
            Math.abs((a and 0xFF) - (b and 0xFF))

    private fun assertClose(expected: Int, actual: Int) {
        val err = colorError(expected, actual)
        assertTrue("color %08x vs %08x (err $err)".format(expected, actual), err <= 24)
    }
}
