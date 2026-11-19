package com.example.stickermaker.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipParamsTest {

    @Test
    fun normalSpeedSamplesOneFramePerOutputFrame() {
        val clip = ClipParams(startMs = 0, endMs = 3000, fps = 10, speed = 1f)
        assertEquals(100, clip.frameDurationMs)
        assertEquals(30, clip.frameCount)
        assertEquals(3000L, clip.outputDurationMs)
        assertEquals(0L, clip.sourceTimeUs(0))
        assertEquals(500_000L, clip.sourceTimeUs(5))
    }

    @Test
    fun startOffsetShiftsSourceTimes() {
        val clip = ClipParams(startMs = 1500, endMs = 2500, fps = 8, speed = 1f)
        assertEquals(125, clip.frameDurationMs)
        assertEquals(8, clip.frameCount)
        assertEquals(1_500_000L, clip.sourceTimeUs(0))
        assertEquals(1_625_000L, clip.sourceTimeUs(1))
    }

    @Test
    fun doubleSpeedHalvesOutputLength() {
        val clip = ClipParams(startMs = 0, endMs = 4000, fps = 10, speed = 2f)
        assertEquals(20, clip.frameCount)
        assertEquals(2000L, clip.outputDurationMs)
        assertEquals(200_000L, clip.sourceTimeUs(1)) // skips ahead 200 ms of source per frame
    }

    @Test
    fun halfSpeedDoublesOutputLength() {
        val clip = ClipParams(startMs = 0, endMs = 2000, fps = 10, speed = 0.5f)
        assertEquals(40, clip.frameCount)
        assertEquals(4000L, clip.outputDurationMs)
    }

    @Test
    fun emptyOrReversedRangeHasNoFrames() {
        assertEquals(0, ClipParams(1000, 1000, 10, 1f).frameCount)
        assertEquals(0, ClipParams(2000, 1000, 10, 1f).frameCount)
    }

    @Test
    fun tenSecondStickerLimitIsReachable() {
        // WhatsApp's 10 s animated-sticker limit: 10 s at 20 fps is exactly at the limit.
        val clip = ClipParams(0, 10_000, 20, 1f)
        assertEquals(200, clip.frameCount)
        assertEquals(10_000L, clip.outputDurationMs)
    }
}
