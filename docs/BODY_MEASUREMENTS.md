# Body weight and composition (ANA-03)

Open **Programas > Peso y composición corporal**.

## Recording

Each measurement stores:
- its date;
- its metric: body weight, body fat, lean mass, waist, or another named measure;
- its value and unit, limited to units valid for that metric;
- the **method** used. It is mandatory: scale, bioimpedance, DEXA, caliper, tape or other.

Optional context helps judge comparability: whether it was taken fasted, plus free notes. A context field the user leaves blank is never filled in. Values are validated: non-negative, percentages ≤ 100, and a unit that matches the metric. Measurements can be deleted individually.

Measurements live in an independent `measurements-v1.json`. Re-adding the same ID with identical content is a no-op; different content is rejected. Corrupt files are reported and never reset.

## Trends without false comparisons

A trend line is drawn only within one **metric + unit + method** series:
- the time axis is proportional to real dates;
- points run oldest to newest;
- the card shows the latest value and the change since the first measurement *of that series*.

When a metric has measurements from more than one method (for example bioimpedance and DEXA body fat), a warning states that they are not directly comparable, and each method is shown as its own series. Units are never converted either: kg and lb readings stay separate series, with a warning. Each chart's accessibility description lists its dated values.

Form state survives scrolling. Lazy-list items drop plain remembered state when they scroll off screen, so the form now uses saveable state. The same fix was applied to the exercise catalog editor.

## Interchange

Interchange v1 `TrainingContext.measurements` has no method field. Local measurements are therefore not added to exported context yet; doing so would drop the method. The export screen keeps stating that local context includes no body measurements.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 89 run, 0 failures, 1 skipped (the opt-in private test). `BodyMeasurementTest` adds 3 cases:
  - separate series for different methods and units, with mixed flags and no conversion;
  - validation of incoherent measurements;
  - repository idempotency, deletion, durability and corrupt-file handling.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `BodyMeasurementDeviceTest`: with a synthetic DEXA body-fat reading present, a bioimpedance reading entered through the real form is saved with metric, value, unit and method, read from disk. The screen shows the mixed-method warning and separate "Bioimpedancia" and "DEXA" series.
- Device-test helper: drags now start clear of text fields, because a slow drag on a field is a long press that opens the selection toolbar. Tests that start from the main screen wait for it before the first tap.
- Combined run of 9 device cases: 8 passed, including `WorkoutDeviceTest`. `ProgramDeviceTest` hit its intermittent assertion failure, seen in earlier runs before this change.

Physical-device testing was not performed.
