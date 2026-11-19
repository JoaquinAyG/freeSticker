package com.example.stickermaker.media

import java.io.ByteArrayOutputStream

/**
 * Splits an animated WebP into its frames (the reverse of [AnimatedWebPWriter]).
 *
 * Each frame's image data (ALPH + VP8, or VP8L) is re-wrapped as a standalone still WebP that
 * Android's regular decoder can read. Compositing the frames onto the canvas (offsets, blending,
 * disposal) is done by [WebPFrameSource].
 */
object AnimatedWebPParser {

    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        /** true: frame replaces the pixels under it; false: alpha-blended over them. */
        val noBlend: Boolean,
        /** true: frame area is cleared to transparent before the next frame is drawn. */
        val disposeToBackground: Boolean,
        /** The frame as a standalone still WebP file. */
        val stillWebp: ByteArray,
    )

    class Animation(val canvasWidth: Int, val canvasHeight: Int, val frames: List<Frame>)

    /** True if [header] (the first ~32 bytes of a file) is an animated WebP. */
    fun isAnimated(header: ByteArray): Boolean =
        header.size >= 21 &&
            ascii(header, 0) == "RIFF" && ascii(header, 8) == "WEBP" && ascii(header, 12) == "VP8X" &&
            (header[20].toInt() and 0x02) != 0

    fun parse(data: ByteArray): Animation {
        require(isAnimated(data)) { "Not an animated WebP" }
        var canvasW = 0
        var canvasH = 0
        val frames = mutableListOf<Frame>()
        var pos = 12
        val end = minOf(data.size, 8 + le32(data, 4))
        while (pos + 8 <= end) {
            val fourcc = ascii(data, pos)
            val size = le32(data, pos + 4)
            val body = pos + 8
            require(size >= 0 && body + size <= data.size) { "Truncated WebP" }
            when (fourcc) {
                "VP8X" -> {
                    canvasW = le24(data, body + 4) + 1
                    canvasH = le24(data, body + 7) + 1
                }
                "ANMF" -> frames += parseFrame(data, body, size)
            }
            pos = body + size + (size and 1)
        }
        require(canvasW > 0 && canvasH > 0 && frames.isNotEmpty()) { "Animated WebP has no frames" }
        return Animation(canvasW, canvasH, frames)
    }

    private fun parseFrame(data: ByteArray, start: Int, size: Int): Frame {
        val x = le24(data, start) * 2
        val y = le24(data, start + 3) * 2
        val w = le24(data, start + 6) + 1
        val h = le24(data, start + 9) + 1
        val duration = le24(data, start + 12)
        val flags = data[start + 15].toInt()
        // Collect the frame's image chunks.
        var alph: Pair<Int, Int>? = null
        var image: Triple<String, Int, Int>? = null
        var pos = start + 16
        val end = start + size
        while (pos + 8 <= end) {
            val fourcc = ascii(data, pos)
            val len = le32(data, pos + 4)
            when (fourcc) {
                "ALPH" -> alph = (pos + 8) to len
                "VP8 ", "VP8L" -> image = Triple(fourcc, pos + 8, len)
            }
            pos += 8 + len + (len and 1)
        }
        val (kind, imgOff, imgLen) = image ?: error("Frame without image data")
        return Frame(
            x = x, y = y, width = w, height = h, durationMs = duration,
            noBlend = (flags and 0x02) != 0,
            disposeToBackground = (flags and 0x01) != 0,
            stillWebp = wrapStill(data, w, h, alph, kind, imgOff, imgLen),
        )
    }

    /** Builds a standalone still WebP from one frame's chunks. */
    private fun wrapStill(
        data: ByteArray, w: Int, h: Int, alph: Pair<Int, Int>?, kind: String, imgOff: Int, imgLen: Int,
    ): ByteArray {
        val body = ByteArrayOutputStream(imgLen + 64)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        if (alph != null && kind == "VP8 ") {
            // Lossy + separate alpha needs the extended header with the alpha flag.
            val vp8x = ByteArray(10)
            vp8x[0] = 0x10
            putLe24(vp8x, 4, w - 1)
            putLe24(vp8x, 7, h - 1)
            chunk(body, "VP8X", vp8x, 0, vp8x.size)
            chunk(body, "ALPH", data, alph.first, alph.second)
        }
        chunk(body, kind, data, imgOff, imgLen)
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val sizeBytes = ByteArray(4)
        putLe32(sizeBytes, 0, payload.size)
        out.write(sizeBytes)
        out.write(payload)
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, fourcc: String, src: ByteArray, off: Int, len: Int) {
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        val sizeBytes = ByteArray(4)
        putLe32(sizeBytes, 0, len)
        out.write(sizeBytes)
        out.write(src, off, len)
        if (len and 1 == 1) out.write(0)
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
    private fun le24(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)
    private fun le32(b: ByteArray, at: Int) = le24(b, at) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun putLe24(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte(); b[at + 2] = (v shr 16).toByte()
    }
    private fun putLe32(b: ByteArray, at: Int, v: Int) {
        putLe24(b, at, v); b[at + 3] = (v ushr 24).toByte()
    }
}
