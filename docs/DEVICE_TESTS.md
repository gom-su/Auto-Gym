# Device (instrumented) tests

Run them on the API 36 emulator (`Workout_API_36`) with the project JDK and SDK, for example:

```bash
./gradlew.bat connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.workoutpoc.ProgramDeviceTest"
```

## Shared UI helpers (`UiScroll.kt`)

Compose screens are long lazy lists, so tests must not assume a target is on screen.

- **`scrollToText(text, contains)`** searches down to the end, then back up.
  - Drags are slow, so the list doesn't keep moving after a match, and each drag starts clear of text fields: a slow drag that starts on a field is a long press, which opens the selection toolbar.
  - The keyboard is dismissed if a drag opens it.
  - A match is re-found once the list has settled, and the search stops at the end of the content.
- **`clickText(text)`** taps the node's *position*, retrying quickly. A focused text field (blinking cursor) keeps refreshing the accessibility tree, so node references go stale between finding and clicking.
- **`setFieldText(value, index)`** re-finds the text field on every attempt, confirms the value applied, and dismisses the keyboard if it opened. Setting text on a held field reference can hit a stale node.
- **The system document picker** must not be driven with these drags: it treats slow drags as long presses and enters selection mode. Tests tap picker items directly with the shared `pickerClick`, scrolling only with the picker's own list and confirming with **Select** if the picker entered selection mode.
- **Status messages** sit at the top of their screen's list. Assertions on them use `scrollToText` rather than checking only the viewport.

## Status (2026-11-07)

Regular suite: 21 cases in 19 classes. It excludes `NotificationLatencyTest` and `ExchangeFreshInstallTest`, which are run on their own ([latency](NOTIFICATION_LATENCY.md), [exchange](JSON_EXCHANGE.md)).

- **Latest full run on a warm emulator:** 16 of 21 passed, including every test added on 2026-11-06/07 (`HistoryDeviceTest` ×2, `MigrationConversionDeviceTest`, the edit-trail checks). The failures were in the system file picker (`ProgramDeviceTest`, `ExchangeDeviceTest`, `MigrationDeviceTest` ×2) and the overlay bubble drag in `WorkoutDeviceTest`.
- **Picker fix:** on a freshly booted emulator the picker can treat a tap on a file as a selection, leaving a **Select** button instead of opening it. The shared `pickerClick` in `UiScroll.kt` now presses that button, and `ProgramDeviceTest` indexes its copied file like the other picker tests. Afterwards the picker classes passed 4 of 5; the remaining case is the long `MigrationDeviceTest` file review, which fails intermittently at the warning checkbox and passes alone.
- **`WorkoutDeviceTest`:** its overlay and notification controls redraw on every state change. `control()` now retries stale nodes, and the bubble is tapped only after its window has settled at the dragged position (earlier, the tap could land on the old position). On a loaded emulator it still fails intermittently at a different step each time (a notification tap not applied, the rest countdown not yet in the shade). With leftover Clock/Settings tasks closed it passed one of the last two runs.
- **Run on a warm emulator.** Right after a reboot the app may not come to the front at the first launch; taps then land on the home screen, open other apps (Clock, Settings) and cascade into most of the run failing. A long session (hours) can also crash the emulator's system process (`INSTRUMENTATION_ABORTED: System has crashed`). Neither is an app failure: reboot, wait a few minutes, run one short test, then run the suite.
- **Physical-device testing** was not performed. The user waived it for ENT-01 on 2026-11-07, as for ENT-02; all requirements are closed with emulator-only evidence.
