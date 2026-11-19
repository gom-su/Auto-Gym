package com.example.workoutpoc.wearapp

import org.junit.Assert.assertEquals
import org.junit.Test

class WearRestWakeupTest {
    @Test fun longRestWakesTwoMinutesBeforeExpiry() {
        val deadline = 86_400_000L
        assertEquals(deadline - 120_000L, restWakeTime(deadline, exact = true))
    }

    @Test fun legacyWakeupWindowEndsTwoMinutesBeforeExpiry() {
        val deadline = 86_400_000L
        assertEquals(deadline - 120_000L, restWakeTime(deadline, exact = false) + LEGACY_ALARM_WINDOW_MS)
    }
}
