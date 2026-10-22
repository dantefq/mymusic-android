package com.example.mymusic

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class WidgetStateTest {
    @Test fun bufferingStillOffersPauseRatherThanSendingPlayAgain() {
        assertTrue(widgetRequestsPause(true, Player.STATE_BUFFERING, true))
        assertTrue(widgetRequestsPause(true, Player.STATE_READY, true))
        assertFalse(widgetRequestsPause(false, Player.STATE_READY, true))
        assertFalse(widgetRequestsPause(true, Player.STATE_ENDED, true))
        assertFalse(widgetRequestsPause(true, Player.STATE_READY, false))
    }
    @Test fun progressIsBoundedForUnknownAndChangedDurations() {
        assertEquals(0, WidgetState(positionMs = 1000).progress)
        assertEquals(500, WidgetState(positionMs = 5000, durationMs = 10000).progress)
        assertEquals(1000, WidgetState(positionMs = Long.MAX_VALUE, durationMs = 10000).progress)
        assertEquals(0, WidgetState(positionMs = -1, durationMs = 10000).progress)
    }
    @Test fun clockAdvancesOnlyDuringActualNormalSpeedPlayback() {
        assertTrue(WidgetState(mediaId = "1", requested = true, advancing = true).clockRuns)
        assertFalse(WidgetState(mediaId = "1", requested = true).clockRuns)
        assertFalse(WidgetState(mediaId = "1", requested = true, advancing = true, speed = 1.5f).clockRuns)
        assertFalse(WidgetState(advancing = true).clockRuns)
    }
    @Test fun timeFormattingSupportsLongAudiobooksAndInvalidPositions() {
        assertEquals("0:00", formatWidgetTime(-10))
        assertEquals("2:03", formatWidgetTime(123000))
        assertEquals("12:01:02", formatWidgetTime(43262000))
    }
}
