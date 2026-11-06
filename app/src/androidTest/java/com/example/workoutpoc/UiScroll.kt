package com.example.workoutpoc

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail

/**
 * Finds a node whose text starts with [text], dragging the screen down to the end and then back up.
 * Drags are slow (no fling), so content does not keep moving after a match; the match is re-found once the list
 * has settled before it is returned. Node scroll actions are avoided: Compose lazy lists do not expose them
 * reliably, and single-line text fields are scrollable nodes too.
 */
fun UiDevice.scrollToText(text: String, contains: Boolean = false): UiObject2 {
    val selector = if (contains) By.textContains(text) else By.textStartsWith(text)
    fun settled(): UiObject2? { waitForIdle(); SystemClock.sleep(250); return try { findObject(selector) } catch (_: StaleObjectException) { null } }
    wait(Until.findObject(selector), 1_500)?.let { return settled() ?: it }
    val x = displayWidth / 2
    val low = (displayHeight * 0.70).toInt(); val high = (displayHeight * 0.40).toInt()
    for (down in listOf(true, false)) {
        var previous: List<String>? = null
        for (step in 0 until 20) {
            if (down) drag(x, low, x, high, 60) else drag(x, high, x, low, 60)
            // A drag that starts on a text field can focus it; a shown keyboard then hides the rest of the screen.
            if (hasObject(By.pkg("com.google.android.inputmethod.latin"))) { pressBack(); waitForIdle() }
            settled()?.let { return it }
            val visible = try { findObjects(By.textStartsWith("")).mapNotNull { it.text } } catch (_: StaleObjectException) { null }
            if (visible != null && visible == previous) break
            previous = visible
        }
    }
    fail("Missing: $text")
    throw AssertionError()
}

/** Scrolls to and taps [text], retrying when Compose recycles the node between finding and tapping it. */
fun UiDevice.clickText(text: String) {
    repeat(3) {
        try { scrollToText(text).click(); waitForIdle(); return } catch (_: StaleObjectException) { SystemClock.sleep(200) }
    }
    fail("Could not tap: $text")
}
