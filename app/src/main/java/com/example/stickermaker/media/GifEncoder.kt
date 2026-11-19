package com.example.stickermaker.media

import java.io.OutputStream
import kotlin.math.max

/**
 * Minimal streaming animated-GIF encoder: every frame gets its own 256-color palette
 * (median-cut quantization) and transparency for pixels with alpha < 50%.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    loopCount: Int = 0,
) {
    private val indices = ByteArray(width * height)
    private val quantizer = MedianCutQuantizer()

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0x70) // no global color table, 8-bit color resolution
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        // NETSCAPE2.0 extension: loop forever
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1)
        writeShort(loopCount)
        out.write(0)
    }

    /** [argb] holds width*height non-premultiplied ARGB pixels (Bitmap.getPixels). */
    fun addFrame(argb: IntArray, delayMs: Int) {
        require(argb.size >= width * height)
        val q = quantizer.quantize(argb, width * height, indices)
        val transparent = q.transparentIndex >= 0

        // Graphic control extension
        out.write(0x21); out.write(0xF9); out.write(4)
        val disposal = if (transparent) 2 else 1
        out.write((disposal shl 2) or (if (transparent) 1 else 0))
        writeShort(max(2, (delayMs + 5) / 10)) // centiseconds
        out.write(if (transparent) q.transparentIndex else 0)
        out.write(0)

        // Image descriptor with a 256-entry local color table
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0x80 or 7)
        for (c in q.palette) {
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        LzwEncoder(indices, width * height).encode(out)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}

/** Median-cut color quantizer over a 15-bit (RGB555) histogram. */
class MedianCutQuantizer {
    class Result(val palette: IntArray, val transparentIndex: Int)

    private val hist = IntArray(32768)
    private val lookup = IntArray(32768)

    private class Box(val start: Int, val end: Int) {
        var population = 0L
        var longestAxis = 0
        var longestRange = 0
    }

    fun quantize(argb: IntArray, count: Int, outIndices: ByteArray): Result {
        hist.fill(0)
        var transparentCount = 0
        for (i in 0 until count) {
            val p = argb[i]
            if ((p ushr 24) < 128) transparentCount++ else hist[key(p)]++
        }
        val hasTransparent = transparentCount > 0
        val maxColors = if (hasTransparent) 255 else 256

        var n = 0
        for (h in hist) if (h > 0) n++
        val bins = IntArray(n)
        var j = 0
        for (i in hist.indices) if (hist[i] > 0) bins[j++] = i

        val palette = IntArray(256)
        if (n > 0) {
            val boxes = ArrayList<Box>(maxColors)
            boxes.add(makeBox(bins, 0, n))
            while (boxes.size < maxColors) {
                var bestIdx = -1
                var bestScore = 0L
                for ((idx, b) in boxes.withIndex()) {
                    if (b.end - b.start < 2) continue
                    val score = b.population * b.longestRange
                    if (score > bestScore) { bestScore = score; bestIdx = idx }
                }
                if (bestIdx < 0) break
                val box = boxes[bestIdx]
                val split = split(bins, box)
                boxes[bestIdx] = makeBox(bins, box.start, split)
                boxes.add(makeBox(bins, split, box.end))
            }
            for ((c, b) in boxes.withIndex()) {
                var r = 0L; var g = 0L; var bl = 0L; var total = 0L
                for (i in b.start until b.end) {
                    val bin = bins[i]
                    val w = hist[bin].toLong()
                    r += expand(channel(bin, 0)) * w
                    g += expand(channel(bin, 1)) * w
                    bl += expand(channel(bin, 2)) * w
                    total += w
                    lookup[bin] = c
                }
                palette[c] = ((r / total).toInt() shl 16) or ((g / total).toInt() shl 8) or (bl / total).toInt()
            }
        }

        val tIndex = if (hasTransparent) 255 else -1
        for (i in 0 until count) {
            val p = argb[i]
            outIndices[i] = if ((p ushr 24) < 128) tIndex.toByte() else lookup[key(p)].toByte()
        }
        return Result(palette, tIndex)
    }

    private fun makeBox(bins: IntArray, start: Int, end: Int): Box {
        val box = Box(start, end)
        val min = intArrayOf(31, 31, 31)
        val max = intArrayOf(0, 0, 0)
        var pop = 0L
        for (i in start until end) {
            val bin = bins[i]
            pop += hist[bin]
            for (ch in 0..2) {
                val v = channel(bin, ch)
                if (v < min[ch]) min[ch] = v
                if (v > max[ch]) max[ch] = v
            }
        }
        box.population = pop
        for (ch in 0..2) {
            val range = max[ch] - min[ch]
            if (range > box.longestRange) { box.longestRange = range; box.longestAxis = ch }
        }
        return box
    }

    /** Counting-sorts the box's bins along its longest axis and returns the population median index. */
    private fun split(bins: IntArray, box: Box): Int {
        val ch = box.longestAxis
        val counts = IntArray(33)
        for (i in box.start until box.end) counts[channel(bins[i], ch) + 1]++
        for (v in 1..32) counts[v] += counts[v - 1]
        val sorted = IntArray(box.end - box.start)
        for (i in box.start until box.end) {
            val b = bins[i]
            sorted[counts[channel(b, ch)]++] = b
        }
        System.arraycopy(sorted, 0, bins, box.start, sorted.size)

        val half = box.population / 2
        var acc = 0L
        var splitAt = box.start + 1
        for (i in box.start until box.end) {
            acc += hist[bins[i]]
            if (acc >= half) { splitAt = i + 1; break }
        }
        return splitAt.coerceIn(box.start + 1, box.end - 1)
    }

    private fun key(p: Int) = (((p shr 19) and 0x1F) shl 10) or (((p shr 11) and 0x1F) shl 5) or ((p shr 3) and 0x1F)
    private fun channel(bin: Int, ch: Int) = (bin shr (10 - 5 * ch)) and 0x1F
    private fun expand(v: Int) = (v shl 3) or (v shr 2)
}

/** GIF-flavoured LZW compression (variable code size, 12-bit max, hashed dictionary). */
private class LzwEncoder(private val pixels: ByteArray, private val count: Int) {
    private val initCodeSize = 8
    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private var nBits = 0
    private var maxcode = 0
    private var freeEnt = 0
    private var clearFlg = false
    private var initBits = 0
    private var clearCode = 0
    private var eofCode = 0
    private var curAccum = 0
    private var curBits = 0
    private val accum = ByteArray(256)
    private var aCount = 0
    private var curPixel = 0

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0) // block terminator
    }

    private fun nextPixel(): Int = if (curPixel >= count) -1 else pixels[curPixel++].toInt() and 0xFF

    private fun maxCode(bits: Int) = (1 shl bits) - 1

    private fun compress(bits: Int, os: OutputStream) {
        initBits = bits
        clearFlg = false
        nBits = initBits
        maxcode = maxCode(nBits)
        clearCode = 1 shl (bits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0
        curAccum = 0
        curBits = 0

        var ent = nextPixel()
        var hshift = 0
        var fcode = HSIZE
        while (fcode < 65536) { hshift++; fcode *= 2 }
        hshift = 8 - hshift

        htab.fill(-1)
        output(clearCode, os)

        outer@ while (true) {
            val c = nextPixel()
            if (c == -1) break
            fcode = (c shl BITS) + ent
            var i = (c shl hshift) xor ent
            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = HSIZE - i
                if (i == 0) disp = 1
                do {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer
                    }
                } while (htab[i] >= 0)
            }
            output(ent, os)
            ent = c
            if (freeEnt < MAXMAXCODE) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                htab.fill(-1)
                freeEnt = clearCode + 2
                clearFlg = true
                output(clearCode, os)
            }
        }
        output(ent, os)
        output(eofCode, os)
    }

    private fun output(code: Int, os: OutputStream) {
        curAccum = curAccum and MASKS[curBits]
        curAccum = if (curBits > 0) curAccum or (code shl curBits) else code
        curBits += nBits
        while (curBits >= 8) {
            charOut((curAccum and 0xFF).toByte(), os)
            curAccum = curAccum shr 8
            curBits -= 8
        }
        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = initBits
                maxcode = maxCode(nBits)
                clearFlg = false
            } else {
                nBits++
                maxcode = if (nBits == BITS) MAXMAXCODE else maxCode(nBits)
            }
        }
        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xFF).toByte(), os)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            flushChar(os)
        }
    }

    private fun charOut(c: Byte, os: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(os)
    }

    private fun flushChar(os: OutputStream) {
        if (aCount > 0) {
            os.write(aCount)
            os.write(accum, 0, aCount)
            aCount = 0
        }
    }

    private companion object {
        const val BITS = 12
        const val HSIZE = 5003
        const val MAXMAXCODE = 1 shl BITS
        val MASKS = intArrayOf(
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF,
            0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF,
        )
    }
}
