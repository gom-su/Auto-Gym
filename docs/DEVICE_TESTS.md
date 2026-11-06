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
- **The system document picker** must not be driven with these drags: it treats slow drags as long presses and enters selection mode. Tests tap picker items directly (`pickerClick`), scrolling only with the picker's own list.
- **Status messages** sit at the top of their screen's list. Assertions on them use `scrollToText` rather than checking only the viewport.

## Status (2026-11-06)

- **Full runs of 18 cases** (before `HistoryDeviceTest` was added, which passes alone): the 14 cases outside exchange and migration pass consistently, including `ProgramDeviceTest` and `WorkoutDeviceTest`, which were previously intermittent.
- **`ExchangeDeviceTest` and `MigrationDeviceTest`:**
  - They pass alone and in combined runs with `WorkoutDeviceTest`.
  - Two leftover-state problems were fixed. The floating workout panel left by earlier tests sat over their screens and intercepted taps, so both now stop the overlay service first. The save picker sometimes opened on a location that cannot hold files, so the export test now selects Downloads.
  - Late in the full 18-case run on this emulator they can still fail. One capture showed the app window with no content, a transitional state rather than a specific screen problem.
- **Physical-device testing** remains the user's acceptance step (ENT-01).
