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

Physical-device testing was not performed.
