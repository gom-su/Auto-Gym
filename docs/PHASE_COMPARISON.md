# Comparisons by phase (ANA-05)

In an exercise's catalog detail, **Comparación por semana y fase** summarizes each planned week's work on that exercise:
- program · block · week;
- top load, completed work sets, reps and volume.

It is grouped by unit and load basis (never mixed, see [exposures](EXPOSURE_COMPARISON.md)).

## Phases and filters

Program v1 has no phase field, so a program's **blocks** act as its phases. Filter by **program** and by **block (fase)**, and choose whether **deload** weeks are visible. Filters only narrow what is compared; they never change data.

## Comparable conditions

- Only completed work sets count. Warm-ups and omitted sets never become zeros.
- Each week is compared with the **previous comparable week**: same unit and basis, inside the current filter, and not a deload. The row shows top-load and volume changes and names the week it is compared with.
- **Deload weeks** are those you marked in Programas (ANA-02). They are listed, labelled "descarga" and never compared, so a planned reduction is not reported as a regression. The next normal week is compared with the last normal week before the deload.
- The first comparable week under a filter says so instead of showing a change.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 94 run, 0 failures, 1 skipped (the opt-in private test). `PhaseComparisonTest` adds 2 cases, using a two-block program with a marked deload in between:
  - **Deload handling:** the deload is listed without deltas, and the following block's week is compared with the pre-deload week (+5 kg). Warm-ups and an omitted set are excluded. Without the mark, the same week would read as a −40 kg drop.
  - **Filters:** filtering by block, program and deload visibility.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `PerformanceChartDeviceTest` now also checks, through the real screen, that the comparison section lists the marked deload week as not compared.
- Combined run of 10 device cases: 8 passed. `PerformanceChartDeviceTest` missed the program list because every drag start overlapped the tall JSON field. After widening the drag start heights, it passed alone. `ProgramDeviceTest` failed: once with its intermittent assertion, and once with an Android system watchdog timeout.

Physical-device testing was not performed.
