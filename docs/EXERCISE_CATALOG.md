# Exercise catalog (ENT-08)

Open **Programas > Catálogo de ejercicios**. Each exercise is identified by its stable `exerciseId`, the same ID that program prescriptions, recorded sets and exported results already carry. The visible name is only a label.

## Behavior

- **Registration:** opening the catalog registers every exercise ID referenced by saved programs and recorded sessions, using the name it was referenced with. Nothing else is inferred: no movement, equipment or load basis. A reference under a different name never renames an existing entry; that name is kept as an alias.
- **Editing:** visible name, movement (optional family, e.g. "press horizontal"), variant, equipment, usual load basis and machine/setup notes (≤ 1,000 characters). The ID never changes.
- **Renaming** moves the previous name to **Nombres anteriores**. Renaming back to an earlier name removes it from that list instead of duplicating it.
- **Variants:** exercises with the same movement but different equipment, variant or setup are distinct IDs and are never merged.
  - Two entries sharing name, variant and equipment are flagged as ambiguous in the list.
  - Saving an edit that would make two exercises indistinguishable is rejected with the conflicting entry's name and ID.
- **Load basis:** informative only. It is never used to fill a prescription or a recorded set that lacks one. Bodyweight and machine equipment reject incompatible bases.
- **History:** each exercise's detail lists every recorded set across archived and current sessions, matched **by ID only**.
  - A rename (or different names in different programs) never splits or loses history.
  - Each set shows the name it was recorded under when that differs from the current one.
  - The built-in demo has no exercise IDs and is never attributed to a catalogued exercise.

## Storage and compatibility

`exercises-v1.json` is an independent, versioned DataStore file. Restore validates IDs, uniqueness, text limits and alias rules. Corrupt or future files report an error and are never reset. Program, session, history and interchange v1 contracts are unchanged: they already reference exercises by ID. In-session screens keep the name snapshotted when the day was loaded.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 72 run, 0 failures, 1 skipped (the opt-in private test). `CatalogTest` adds 5 cases:
  - registration without inference, and aliasing;
  - rename keeping history by ID, persisted and restorable;
  - variants and rejection of indistinguishable labels;
  - no attribution across IDs or to the demo;
  - invalid entries and a corrupt file.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `CatalogDeviceTest` runs through the real screens: an archived synthetic session, then Programas → Catálogo → rename → save. It checks the persisted catalog file and that the history list on the same screen still shows the set, labelled with its recorded name.
- Device test robustness:
  - My device tests now share one scroll helper that searches both directions in the tallest scrollable container.
  - Two existing assertions now wait or scroll instead of assuming the layout: `WorkoutDeviceTest` reading the overlay after closing the shade, and `ProgramDeviceTest` with more navigation entries above the fold.
- Final combined runs of `CatalogDeviceTest`, `ProgramDeviceTest`, `PlannedSessionDeviceTest`, `DeviationDeviceTest`, `SessionLifecycleDeviceTest` and `WorkoutDeviceTest`:
  - One run passed all 6 cases.
  - The other passed 5; `ProgramDeviceTest` had its intermittent "Cancelar importación" miss, seen before this change.

Physical-device testing was not performed.
