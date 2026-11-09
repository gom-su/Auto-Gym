# Goals and adherence (ANA-04)

Open **Progreso > Adherencia**.

## Four separate categories

Every planned day of every saved week gets exactly one status. The categories are never merged:

| Status | Meaning |
| --- | --- |
| **Completado** | A finished session completed every prescribed set (extra sets don't count toward it). |
| **Parcial** | At least one prescribed set was completed, or a session for the day is still in progress (marked "en curso"). |
| **No realizado** | You explicitly declared the day not performed, or its sessions completed no set (e.g. every set omitted). |
| **Sin registro** | Nothing exists for the day. It is never assumed you didn't train. |

Recorded work always takes precedence over a declaration: a day with completed sets stays completed or partial even if it was marked. The mark can only be set or removed while the day has no session. It is stored locally in `week-annotations-v1.json`, by program, week and day ID.

Each day also shows its **prescribed sets** split three ways:
- completed;
- explicitly omitted;
- never reached (no record).

The split uses the day's most complete session. Extra sets and the cardio minutes recorded for the day are listed separately. Each week header counts days per status.

## Configurable goals

Weekly goals are optional: **sessions per week** (1–14) and **cardio minutes per week** (1–3,000). Empty means no goal, and nothing is defaulted. **Esta semana** shows progress for the current calendar week, Monday to Sunday:
- sessions with at least one completed set (sessions with nothing completed don't count);
- recorded cardio minutes against the goal.

Program v1 cannot prescribe cardio, so cardio adherence is measured against the weekly goal rather than a plan.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 92 run, 0 failures, 1 skipped (the opt-in private test). `AdherenceTest` adds 3 cases:
  - the four statuses with completed/omitted/unreached breakdowns, plus explicit marks that never override recorded work;
  - ended-early, in-progress and extra sets;
  - calendar-week goal progress counting only trained sessions and cardio, with goal validation.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `AdherenceDeviceTest` runs through the real screen: declare a day not performed, set goals 3 sessions / 90 min, and verify both in the file read from disk. The screen was also checked visually.
- Combined run of 10 device cases: 9 passed. `ProgramDeviceTest` hit its intermittent failure, seen before this change.

Physical-device testing was not performed.
