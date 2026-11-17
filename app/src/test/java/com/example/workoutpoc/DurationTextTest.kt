package com.example.workoutpoc

import com.example.workoutpoc.history.durationText
import com.example.workoutpoc.history.parseDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Times typed in the past-session editor: m:ss, h:mm:ss or plain seconds. */
class DurationTextTest {
    @Test fun `typed times are read as seconds, minutes and hours`() {
        assertEquals(95_000L, parseDuration("95"))
        assertEquals(95_000L, parseDuration("1:35"))
        assertEquals(3_723_000L, parseDuration("1:02:03"))
        assertEquals(0L, parseDuration("0:00"))
        assertEquals(95_000L, parseDuration(" 1:35 "))
    }

    @Test fun `anything else is not a time`() {
        listOf("", "1:95", "1::5", "a:10", "1:2:3:4", "-5", "1.5", "25:00:01").forEach { assertNull(it, parseDuration(it)) }
    }

    @Test fun `shown times read back as the same seconds`() {
        listOf(0L, 5_000L, 95_000L, 3_723_000L).forEach { assertEquals(it, parseDuration(durationText(it))) }
        // Milliseconds are dropped from what is shown, so an untouched field is never sent back as a change.
        assertEquals("1:35", durationText(95_999L))
    }
}
