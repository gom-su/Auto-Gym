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
    val distance = (displayHeight * 0.3).toInt()
    // A slow drag that starts on a text field reads as a long press (selection toolbar): start clear of fields.
    fun freeY(candidates: List<Double>): Int {
        val fields = try { findObjects(By.clazz("android.widget.EditText")).map { it.visibleBounds } } catch (_: StaleObjectException) { emptyList() }
        return candidates.map { (displayHeight * it).toInt() }.firstOrNull { y -> fields.none { y in (it.top - 20)..(it.bottom + 20) } }
            ?: (displayHeight * candidates.first()).toInt()
    }
    for (down in listOf(true, false)) {
        var previous: List<String>? = null
        // Long lists (archives grow with every run) may need many drags; the loop stops early at the end anyway.
        for (step in 0 until 60) {
            if (down) { val y = freeY(listOf(0.72, 0.78, 0.66, 0.84, 0.6, 0.9, 0.54, 0.94)); drag(x, y, x, y - distance, 60) }
            else { val y = freeY(listOf(0.40, 0.34, 0.46, 0.28, 0.52, 0.2, 0.58, 0.14)); drag(x, y, x, y + distance, 60) }
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
    // Tap the node's position rather than the node: a focused text field (blinking cursor) keeps refreshing the
    // accessibility tree, so a node reference can go stale between finding it and clicking it.
    fun tapIfVisible(): Boolean {
        // Fast path: find and read the position in one go, many times, so a refresh rarely lands in between.
        val end = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < end) {
            try {
                val node = findObject(By.textStartsWith(text)) ?: return false
                val bounds = node.visibleBounds
                click(bounds.centerX(), bounds.centerY()); waitForIdle(); return true
            } catch (_: StaleObjectException) { SystemClock.sleep(50) }
        }
        return false
    }
    if (tapIfVisible()) return
    repeat(4) {
        try { scrollToText(text) } catch (_: StaleObjectException) { }
        if (tapIfVisible()) return
    }
    fail("Could not tap: $text")
}

/**
 * Sets the text of the [index]-th text field on screen, re-finding it on each attempt (Compose refreshes nodes) and
 * confirming the value applied; then dismisses the keyboard if it covers the screen.
 */
fun UiDevice.setFieldText(value: String, index: Int = 0) {
    val probe = value.take(20)
    repeat(6) {
        try {
            val fields = wait(Until.findObjects(By.clazz("android.widget.EditText")), 5_000)
            val field = fields?.getOrNull(index) ?: return@repeat
            field.text = value
            val end = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < end) {
                val current = try { findObjects(By.clazz("android.widget.EditText")).getOrNull(index)?.text } catch (_: StaleObjectException) { null }
                if (current != null && current.startsWith(probe)) {
                    SystemClock.sleep(400)
                    if (hasObject(By.pkg("com.google.android.inputmethod.latin"))) { pressBack(); waitForIdle() }
                    return
                }
                SystemClock.sleep(150)
            }
        } catch (_: StaleObjectException) { SystemClock.sleep(200) }
    }
    fail("Could not set text field $index")
}
