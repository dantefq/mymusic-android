package com.example.mymusic

import org.junit.Assert.*
import org.junit.Test

class LyricTimingTest {
    @Test fun repeatedTimestampsAndFractions() {
        val lines = parseLrc("[00:02.5][00:10.050]Repeat\n[00:05.12]Middle")
        assertEquals(listOf(2500L to "Repeat", 5120L to "Middle", 10050L to "Repeat"), lines)
        assertEquals(-1, lyricIndex(lines, 2499))
        assertEquals(0, lyricIndex(lines, 2500))
        assertEquals(1, lyricIndex(lines, 10049))
        assertEquals(2, lyricIndex(lines, 10050))
    }
    @Test fun offsetsLeadAndBackwardSeek() {
        val lines = parseLrc("[offset:2000]\n[00:05.00]First\n[00:08.00]\n[00:10.00]Next")
        assertEquals(3000L, lines.first().first)
        assertEquals(0, lyricIndex(lines, 2000, 1000))
        assertEquals(2, lyricIndex(lines, 9000))
        assertEquals(-1, lyricIndex(lines, 1000))
        assertEquals("", lines[1].second)
    }
}
