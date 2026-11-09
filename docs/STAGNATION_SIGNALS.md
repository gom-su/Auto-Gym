# Stagnation signals (INT-03)

Open **Progreso > Estancamiento**.

## Rule

For each catalogued exercise, every unit and load-basis group is evaluated separately (see [exposures](EXPOSURE_COMPARISON.md)):

1. **Comparable exposures** are those with completed work sets that are not in a deload week. Warm-ups and omitted sets never count.
2. The **window** is the last N comparable exposures: N = 3–6, default 4.
3. The window must span at least the minimum duration (7–28 days, default 14). There must also be at least one earlier comparable exposure, which provides the **reference**: the best point before the window, meaning the highest top load, then the most reps at that load.
4. An **alert** is raised when no exposure in the window beats the reference: neither a higher top load nor more reps at the same top load.

Without enough comparable exposures, or with a window that is too short, **no alert is raised**. The group is listed under **Sin datos suficientes** with the exact reason.

## What every alert states

- **Period:** the first and last date of the window.
- **Data used:** the reference and every window exposure, with date, program/week/day, top load × reps and minimum RIR. Every number in the explanation can be checked against these points.
- **Explanation:** the rule in plain language, with the reference value and the dates.
- **Limitations:**
  - only completed work sets count;
  - there is no 1RM estimate and no total-volume view;
  - the number of deload exposures excluded;
  - window exposures without recorded RIR;
  - whether the window had more RIR margin than the reference, which may be a deliberate choice of effort rather than stagnation.

Alerts are information only. They change nothing in programs or sessions.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 105 run, 0 failures, 1 skipped (the opt-in private test). `StagnationTest` adds 4 cases:
  - **Alert:** an alert with its period, reference, 4-point window and checkable explanation.
  - **No alert on progress:** more reps or more load in the window prevents an alert.
  - **Insufficient data:** too few exposures, or too short a window, is reported as insufficient.
  - **Exclusions and limitations:** deloads are excluded and counted, kg and lb are evaluated apart, and RIR limitations are stated.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `StagnationDeviceTest`: on the emulator all sessions fall within one day, so the real screen lists the exercise under **Sin datos suficientes** with its reason and raises no alert. Alert content is covered by the unit tests.

Physical-device testing was not performed.
