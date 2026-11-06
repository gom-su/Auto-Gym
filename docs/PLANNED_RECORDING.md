# Recording planned days (ENT-04)

Open **Programas**, pick a saved program, a week and a day, then tap **Entrenar este día**. The day is loaded as the next session; the main screen shows the program, week and day, and **Iniciar — Notificación** / **Iniciar — Burbuja flotante** start it. The floating panel and the notification show the current exercise, its set number and the prefilled load and repetitions. A set without deviations is recorded with a single **Completar** tap, which is written to the session file before the UI reflects it.

The built-in demo is unchanged and remains the default whenever no planned day is loaded.

## Executable plan

A day is flattened, in prescription order, into `PlannedSet` entries that are snapshotted into the session (`WorkoutState.plan`) together with a `SessionSource` (program, block, week and version, day; IDs plus display names). Later edits to the library cannot change a session in progress.

| Prescription | Prefill / behavior |
| --- | --- |
| `reps` | Prefilled. Missing → 0, so the user must enter at least one repetition. |
| `rir` 0–4 | Prefilled. 5–10 → prefilled as 4+ (the recording scale). Missing → unrecorded (`null`), never a default. |
| `load` KG/LB | Prefilled in its own unit, no conversion. Steps: 2.5 kg, 5 lb. |
| `load` MACHINE_SETTING | Machine position, step 1. |
| `load` BODYWEIGHT | Bodyweight; only added external load (kg) is editable. |
| Missing `load` | Starts at 0 kg, shown as "carga sin indicar". |
| `restSeconds` | Rest timer. 0 → next set immediately. Missing → 90 s timer default, shown as such. |

The prescription is stored separately from what is recorded. Each `SetRecord` keeps its `prescriptionId`, and the screen shows "Real" next to "Prescrito". The last recorded load carries to the next set only when that set prescribes the same exercise, unit and load. A set that prescribes a different load prefills the new prescription.

## Deviations (ENT-05)

Planned sessions support deviations. The demo keeps its fixed proof-of-concept plan and rejects them.

| Action | Effect |
| --- | --- |
| **Omitir serie** (active set) | Records the position as `SKIPPED` and moves to the next set without rest. The prescription stays in the plan. |
| **Añadir serie extra de …** (any started phase) | Inserts an unprescribed set of the last recorded exercise as the next position. It is prefilled from that exercise's last real values. After the end, it reopens the session. |
| **Quitar serie extra** (unrecorded extra) | Removes it again; the next prescribed set is prefilled from its prescription. |
| **Editar serie N** (recorded set) | Changes real load, reps, RIR, omitted/completed status and notes (≤ 1,000 characters). The edit is bound to the snapshot it was opened from, so a stale save is rejected. Out-of-range values report an error. |

Recorded values never modify the prescription: the session plan keeps each prescribed set unchanged. Extra sets have no prescription ID and are marked `extra`. Edited records carry `editedAtMillis`. Restore validates that each record's prescription ID matches its position, that a completed set has at least one repetition, that notes are non-blank and within the limit, and that the demo has no deviations.

In v1 results:
- Omitted sets export as `OMITTED`, with no reps, RIR, load or completion time.
- Extra sets have a null `prescriptionId` and a note saying so.
- Notes and the edit marker go in the set notes.
- A session is `COMPLETED` only when every position was completed and the session wasn't ended early.

## Real sessions: warm-ups, pauses and cardio (ENT-06)

These features apply to planned sessions only; the demo rejects them.

- **Añadir aproximación de …** inserts a warm-up set before the next set to perform. During rest, it goes before the next set.
  - The warm-up is unprescribed and is prefilled from that set's prescription for the user to lower.
  - It is marked `warmup`, can be removed while unrecorded, and its load never carries into work sets.
  - It exports with a note, since v1 has no warm-up flag.
- **Pausar / Reanudar sesión:**
  - While paused, nothing advances: overlay and notification taps are inert, the notification shows "Sesión en pausa" without controls, and the rest timer does not run out.
  - On resume, the pause length is measured on the monotonic clock (wall clock after a reboot). The pending rest deadline is shifted by exactly that length, and `pausedMillis` accumulates.
  - Finishing or closing while paused closes the pause first.
  - The screen shows active time with pauses excluded.
- **Cardio** (any started phase, including after the end): modality, optional duration, optional distance with M/KM/MI, and notes.
  - At least one of duration, distance or notes is required.
  - Nothing is estimated or defaulted.
  - Entries are bound to their snapshot (stale adds are rejected) and can be removed.
  - They export as v1 `CardioResult` with the modality in the notes.
- **Partial sessions:** **Terminar entrenamiento** ends early. Status is `PARTIAL` unless every position was completed.

An interruption at any point restores from `workout.json` exactly (sets, cardio, pause mark, rest deadline). Restore validates cardio ranges and identities, that a pause exists only during an active set or rest, and that warm-ups are extra sets.

## Session safety and archive

- A session in progress (active set or rest) is never replaced. **Entrenar este día** refuses with a message until it is finished or closed.
- Before a finished session is replaced by another day, or any session with confirmed sets is closed or reset, it is archived to `history-v1.json`. A session closed before completion is archived as ended early. Archiving happens before the live snapshot is replaced. It is idempotent by session ID, so a crash between the two steps can never duplicate or lose a session.
- `workout.json` keeps its pre-ENT-04 shape as a subset, so older demo snapshots restore unchanged. Both files are validated against their own plan (indices, ranges, units, RIR) on restore. Corrupt files report errors and are never reset to empty.

History browsing, warmups, cardio and pauses are separate requirements (ENT-06); archiving guarantees that confirmed sets are kept.

## Interchange

The v1 JSON contract is unchanged. The legacy `demo` snapshot is now a frozen `DemoStateV1` type that mirrors the original fields exactly, so it can't drift with the recording model. It is exported only for the built-in demo. Archived and current planned sessions are exported as v1 `SessionResult`s:

- `sessionPlanId` is the day ID, and each set carries its `prescriptionId` and `exerciseId`.
- Status is `COMPLETED` only when every planned set was recorded without an early finish; otherwise it is `PARTIAL`.
- Loads are reported in the recorded unit. A load with no prescribed base gets no invented base: the typed load is omitted and the value is stated in the set notes. RIR 4 is noted as "4 o más".

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 49 run, 0 failures, 1 skipped (the opt-in private Obsidian test). `RecordingTest` adds 10 cases:
  - conversion, one-tap recording, and restore from a new DataStore instance;
  - unstated reps and RIR, zero rest and unit steps;
  - repeated-deviation carry-over;
  - never replacing an in-progress session, plus archiving;
  - history idempotency and tamper rejection;
  - legacy snapshot restore;
  - v1 export validity and the frozen demo field set.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings (previously 67), none new in kind.
- `PlannedSessionDeviceTest` passed in all 5 runs. The flow goes Programas → day → **Entrenar este día** → floating panel → one **Completar** tap with the overlay over the launcher. The test then reads `workout.json` from disk to check that the record and its prescription ID are already persisted.
- Existing device tests on this emulator are flaky, with or without this change:
  - With this change, every existing case passed at least once in combined or isolated runs, except `ExchangeDeviceTest.documentExportReimportIntoCleanStoreAndPreserveSession`.
  - That case fails the same way on unmodified `main`: its picker file does not appear. Unmodified `main` failed 2 of 6 cases in a comparable combined run.
  - Failures seen were picker files not appearing, scroll targets not found, and a button below the fold.
  - The device tests were made independent of each other: the new test resets its session and reuses `ProgramDeviceTest`'s fixture identity, and one `ProgramDeviceTest` assertion now scrolls.

### ENT-05 (2026-11-06, same emulator)

- Unit tests: 56 run, 0 failures, 1 skipped (the opt-in private test). `DeviationTest` adds 7 cases:
  - omission and extra-set insertion and removal;
  - reopening after the end;
  - edits, including stale-edit and range rejection;
  - demo isolation;
  - storage invariants;
  - v1 export of omitted and extra sets.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `DeviationDeviceTest` runs through the real Activity: omit, complete, add an extra after the end, record it, then edit a set with a note, and compare the result with `workout.json` read from disk.
  - Two combined runs of `DeviationDeviceTest`, `PlannedSessionDeviceTest`, `ProgramDeviceTest` and `WorkoutDeviceTest` passed all 4 tests both times.
  - A separate exchange/migration run gave the same results as unmodified `main` (two known picker/flaky failures).

### ENT-06 (2026-11-06, same emulator)

- Unit tests: 63 run, 0 failures, 1 skipped (the opt-in private test). `SessionLifecycleTest` adds 7 cases:
  - a paused session surviving a new process across a 10-minute gap, with exact rest/active time on resume and inert stale taps;
  - a pause across a reboot;
  - warm-up insertion and the no-carry rule;
  - cardio validation and stale safety;
  - demo isolation;
  - finishing or closing while paused;
  - v1 export.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `SessionLifecycleDeviceTest` passes through the real UI: warm-up, set, cardio dialog, pause, resume, pause.
- `scripts/verify_session_restore.py --serial emulator-5554`: PASS.
  - It force-stops the process for real and confirms it is dead.
  - After 2 s it relaunches; the persisted session is identical and the UI shows "Sesión en pausa".
  - **Reanudar sesión** keeps the set, the cardio and the position, and shifts the rest deadline by exactly the measured pause (22.7 s in that run).
- Combined run of 7 device cases: 6 passed. `DeviationDeviceTest` missed a scroll target in that run and then passed alone twice. The program, workout, planned-session and both migration cases passed.

Physical-device testing was not performed.
