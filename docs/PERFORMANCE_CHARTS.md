# Performance charts and deload context (ANA-02)

Each unit/basis group in an exercise's catalog detail (see [exposures](EXPOSURE_COMPARISON.md)) now starts with a chart. Choose a metric with the chips:

- **Carga máxima:** top load of completed work sets, in the group's unit.
- **Repeticiones:** total repetitions of completed work sets.
- **Volumen:** load × reps of completed work sets, in the group's unit. It is only meaningful within the group.
- **RIR mínimo:** lowest recorded RIR.

Points are one per exposure, in time order and evenly spaced. The first and last dates are shown, together with the minimum and maximum of the series.

## Omitted sets are never zero

An exposure without a completed work set has **no value**: all sets omitted, or only warm-ups. Likewise, an exposure without a recorded RIR has no value for **RIR mínimo**. The line breaks at such a point instead of dropping to zero, and the legend says so. Warm-ups are never part of the plotted values.

## Deload weeks are distinguished

Program v1 has no deload field, and deloads are never inferred from names or lighter loads.

- **Marking:** open a saved program's week in **Programas** and turn on **Semana de descarga**. The whole row is the toggle and announces its state.
- **Storage:** the mark is stored locally in `week-annotations-v1.json`, keyed by program and week ID. It never changes the prescription or the program contract.
- **Display:** exposures from a marked week are drawn as hollow points on a shaded band, so a planned lighter week does not read as a regression. Their cards are labelled "· descarga". The chart's accessibility description lists every point with its date, value (or "sin dato") and deload status.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 86 run, 0 failures, 1 skipped (the opt-in private test). `PerformanceSeriesTest` adds 2 cases:
  - **Gaps and deloads:** an all-omitted session yields a gap for every metric; top load, repetitions and volume match their expected values; marking the week flags its exposures without changing any value; time order is kept.
  - **Deload marks:** they are idempotent, durable and validated (duplicates rejected without reset).
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `PerformanceChartDeviceTest` runs through the real UI: Programas → week → **Semana de descarga**. The mark is persisted, and the catalog chart for that exercise shows the deload description and the "· descarga" exposure card.
- The device-test drag helper dismisses the keyboard if a drag opened it, and the deload row is fully toggleable.
- In a combined run of 8 device cases, all 6 analytics, proposal, catalog and session cases passed. `ProgramDeviceTest` (stale node) and `WorkoutDeviceTest` (notification RIR step) hit intermittent failures seen before this change.

Physical-device testing was not performed.
