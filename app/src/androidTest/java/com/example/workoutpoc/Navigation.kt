package com.example.workoutpoc

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.fail

/** Waits for the app's first screen (Hoy and its bottom bar); dragging before it is drawn would drag the launcher. */
fun UiDevice.awaitHome() {
    // An earlier test may have left the notification shade open over the app.
    executeShellCommand("cmd statusbar collapse")
    if (!wait(Until.hasObject(By.text("Hoy")), 10_000)) fail("Hoy did not appear")
    waitForIdle()
}

/** Taps a bottom-bar tab. Its label can also be a screen title (Hoy), so the lowest match on screen is the tab. */
fun UiDevice.openTab(label: String) {
    val end = SystemClock.elapsedRealtime() + 5_000
    while (SystemClock.elapsedRealtime() < end) {
        try {
            val tab = findObjects(By.text(label)).maxByOrNull { it.visibleBounds.top }
            if (tab != null) { val b = tab.visibleBounds; click(b.centerX(), b.centerY()); waitForIdle(); return }
        } catch (_: StaleObjectException) { }
        SystemClock.sleep(100)
    }
    fail("Missing tab: $label")
}

/** Opens Ajustes from the gear of the current tab. */
fun UiDevice.openSettings() {
    val gear = wait(Until.findObject(By.desc("Ajustes")), 5_000) ?: run { fail("Missing Ajustes"); return }
    gear.click(); waitForIdle()
}

/** Opens the session from Hoy: the running session's card, or the row of a prepared or finished one. */
fun UiDevice.openSessionFromHoy() {
    awaitHome()
    for (label in listOf("Volver a la sesión", "Sesión preparada", "Ver resumen de")) {
        if (hasObject(By.textStartsWith(label))) { clickText(label); return }
    }
    clickText("Volver a la sesión")
}

/**
 * Shows the demo session. Relaunching an Activity that already shows it keeps it; otherwise the demo opens from
 * Ajustes, which is the only way to it.
 */
fun UiDevice.openDemo() {
    if (wait(Until.hasObject(By.textStartsWith("Iniciar demo")), 1_500) || hasObject(By.text("Entrenamiento terminado"))) return
    // From another screen, Back first returns to the tabs.
    repeat(3) { if (!hasObject(By.desc("Ajustes"))) { pressBack(); waitForIdle() } }
    openSettings()
    clickText("Abrir demo")
    // Not "Demo": Ajustes has a section of that name. The demo session's own controls prove it opened.
    if (!wait(Until.hasObject(By.textStartsWith("Iniciar demo")), 5_000) && !hasObject(By.text("Entrenamiento terminado")))
        fail("Demo did not open")
    waitForIdle()
}
