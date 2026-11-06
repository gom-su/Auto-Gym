# Comparison between exposures (ANA-01)

Open **Programas > Catálogo de ejercicios** and select an exercise. Its history is grouped into **exposures**: one session's work on that exercise, across every week and program, matched by the stable exercise ID. Renames don't split it (ENT-08).

## What each exposure shows

- **Date, program, week and day** of the session.
- **Summary over completed work sets only:**
  - top load with the best repetitions at that load;
  - number of work sets and total repetitions;
  - minimum RIR.
- **Every recorded set** as recorded: load × reps @ RIR. Warm-ups, extra sets and omitted sets are labelled, and so is the name a set was recorded under when it differs from the current one.

Warm-ups and omitted sets are listed but never counted in the summary, so exposures stay comparable.

## Units are never mixed

Exposures are grouped by **load unit and load basis**. For example, kg · total, kg · por lado, lb · total, posición de máquina, or peso corporal (+ kg). Values are only ever compared inside one group; nothing is converted between units. A session that recorded an exercise in two units appears in both groups. Groups and exposures are ordered newest first.

The exercise's variant, equipment and setup come from its catalog entry, shown above the history. Non-equivalent variants are separate exercise IDs and therefore separate histories. The built-in demo has no exercise IDs and never appears.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 84 run, 0 failures, 1 skipped (the opt-in private test). `ExposuresTest` adds 2 cases:
  - **Grouping:** exposures span weeks and two programs, and kg total, kg per side and lb never share a group. Warm-ups and omitted sets are listed but not counted. Top load, best reps, set and rep counts, volume and minimum RIR are checked.
  - **Exclusion:** other exercise IDs and the demo never appear.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `CatalogDeviceTest` checks the grouped view through the real screen: the "kg · total" group, and a set line with the name it was recorded under after a rename.
- Device-test robustness:
  - The shared scroll helper now uses slow, fling-free drags and re-finds the node after the list settles.
  - **Guardar ejercicio** sits at the top of the editor, so it stays reachable above the keyboard.
  - `ProgramDeviceTest` dismisses the keyboard after editing.
- Two combined runs of all 7 device cases (proposal, catalog, deviations, session lifecycle, planned session, program, workout):
  - The first passed all 7.
  - In the second, the first 5 passed, then `ProgramDeviceTest` stalled in its document-picker/scroll flow and was stopped. The workout case did not run.

Physical-device testing was not performed.
