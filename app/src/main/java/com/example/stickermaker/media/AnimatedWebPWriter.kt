package com.example.stickermaker.media

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Builds an animated WebP file from individually encoded WebP frames.
 *
 * Android can only *encode* still WebP images (Bitmap.compress), so each frame is compressed
 * on its own and then its image chunks (ALPH / VP8 / VP8L) are wrapped into ANMF frame chunks
 * inside an extended (VP8X) RIFF container, as described in the WebP container spec.
 */
class AnimatedWebPWriter(
    private val width: Int,
    private val height: Int,
    private val loopCount: Int = 0,
) {
    private val frames = ByteArrayOutputStream()
    private var hasAlpha = false
    var frameCount = 0
        private set

    fun addFrame(webp: ByteArray, durationMs: Int) {
        require(
            webp.size >= 20 && ascii(webp, 0) == "RIFF" && ascii(webp, 8) == "WEBP",
        ) { "Not a WebP image" }

        val imageData = ByteArrayOutputStream()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val fourcc = ascii(webp, pos)
            val size = readLe32(webp, pos + 4)
            val dataStart = pos + 8
            val dataLen = min(size, webp.size - dataStart)
            when (fourcc) {
                "ALPH", "VP8 ", "VP8L" -> {
                    writeChunk(imageData, fourcc, webp, dataStart, dataLen)
                    if (fourcc != "VP8 ") hasAlpha = true
                }
            }
            pos = dataStart + size + (size and 1)
        }
        val body = imageData.toByteArray()
        check(body.isNotEmpty()) { "WebP frame has no image data" }

        val anmf = ByteArrayOutputStream(body.size + 16)
        writeLe24(anmf, 0) // X offset / 2
        writeLe24(anmf, 0) // Y offset / 2
        writeLe24(anmf, width - 1)
        writeLe24(anmf, height - 1)
        writeLe24(anmf, durationMs.coerceIn(1, 0xFFFFFF))
        anmf.write(0x02) // no blending (each frame fully replaces the canvas), no disposal
        anmf.write(body)
        val anmfBytes = anmf.toByteArray()
        writeChunk(frames, "ANMF", anmfBytes, 0, anmfBytes.size)
        frameCount++
    }

    fun build(): ByteArray {
        check(frameCount > 0) { "No frames" }
        val body = ByteArrayOutputStream(frames.size() + 64)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        val vp8x = ByteArrayOutputStream()
        vp8x.write(0x02 or (if (hasAlpha) 0x10 else 0)) // animation + alpha flags
        vp8x.write(0); vp8x.write(0); vp8x.write(0)
        writeLe24(vp8x, width - 1)
        writeLe24(vp8x, height - 1)
        vp8x.toByteArray().let { writeChunk(body, "VP8X", it, 0, it.size) }

        val anim = ByteArrayOutputStream()
        anim.write(byteArrayOf(0, 0, 0, 0)) // background color (BGRA): transparent
        writeLe16(anim, loopCount)
        anim.toByteArray().let { writeChunk(body, "ANIM", it, 0, it.size) }

        frames.writeTo(body)

        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(out, payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private companion object {
        fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

        fun readLe32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)

        fun writeLe16(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        fun writeLe24(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
        }

        fun writeLe32(out: ByteArrayOutputStream, v: Int) {
            writeLe16(out, v and 0xFFFF); writeLe16(out, (v ushr 16) and 0xFFFF)
        }

        fun writeChunk(out: ByteArrayOutputStream, fourcc: String, data: ByteArray, off: Int, len: Int) {
            out.write(fourcc.toByteArray(Charsets.US_ASCII))
            writeLe32(out, len)
            out.write(data, off, len)
            if (len and 1 == 1) out.write(0) // chunks are padded to even size
        }
    }
}
