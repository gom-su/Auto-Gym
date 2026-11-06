package com.example.workoutpoc

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail

/**
 * Finds a node whose text starts with [text], scrolling the screen's scrollable container up and then down.
 * Compose recomposes the container, so it is re-found on every attempt; UiScrollable only searches forward.
 */
fun UiDevice.scrollToText(text: String, timeoutMillis: Long = 6_000): UiObject2 {
    val selector = By.textStartsWith(text)
    wait(Until.findObject(selector), 1_500)?.let { return it }
    val end = android.os.SystemClock.elapsedRealtime() + timeoutMillis
    while (android.os.SystemClock.elapsedRealtime() < end) {
        for (direction in listOf(Direction.UP, Direction.DOWN)) {
            // Single-line text fields are horizontally scrollable too: use the tallest scrollable container.
            val container = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() } ?: break
            try { container.scrollUntil(direction, Until.findObject(selector)) } catch (_: androidx.test.uiautomator.StaleObjectException) { }
            waitForIdle()
            findObject(selector)?.let { return it }
        }
        wait(Until.findObject(selector), 500)?.let { return it }
    }
    fail("Missing: $text")
    throw AssertionError()
}
