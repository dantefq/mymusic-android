package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class PlaybackProgressTest {
    @Test fun subpixelPlaybackTicksKeepTheSameDrawingPosition() {
        val positions = (1000L..1040L step 16).map { progressPixels(it, 240_000L, 800) }
        assertEquals(1, positions.distinct().size)
        assertNotEquals(positions.first(), progressPixels(1300L, 240_000L, 800))
    }

    @Test fun invalidDurationsAndSeeksStayInsideTheRail() {
        assertEquals(0, progressPixels(500, 0, 800))
        assertEquals(0, progressPixels(500, -1, 800))
        assertEquals(0, progressPixels(-500, 1000, 800))
        assertEquals(800, progressPixels(1500, 1000, 800))
        assertEquals(0, progressPixels(500, 1000, 0))
    }

    @Test fun longDurationsDoNotOverflowAndSeekingRemainsAccurate() {
        assertEquals(400, progressPixels(Long.MAX_VALUE / 2, Long.MAX_VALUE, 800))
        assertEquals(200, progressPixels(60_000, 240_000, 800))
        assertEquals(800, progressPixels(240_000, 240_000, 800))
    }
}
