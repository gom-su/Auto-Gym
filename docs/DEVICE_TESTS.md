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
- **The system document picker** must not be driven with these drags: it treats slow drags as long presses and enters selection mode. Tests tap picker items directly (`pickerClick`), scrolling only with the picker's own list.
- **Status messages** sit at the top of their screen's list. Assertions on them use `scrollToText` rather than checking only the viewport.

## Status (2026-11-06, full run of 18 cases)

15 pass, including `ProgramDeviceTest` (3 of 3 isolated runs plus the full run) and `WorkoutDeviceTest`. Both were the previously intermittent ones.

The two `ExchangeDeviceTest` cases and one `MigrationDeviceTest` case still fail intermittently on this emulator, as they did before this session's changes, around the system picker and long archive lists. The features they cover are also covered by unit tests and by the other device tests. Physical-device testing remains the user's acceptance step (ENT-01).
