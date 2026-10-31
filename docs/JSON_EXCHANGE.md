# JSON interchange (ENT-02)

Open **Programas → Intercambio JSON**. Import a UTF-8 file or paste JSON, review its complete content, then explicitly save. Saved entries can be selected, copied or exported through Android's document picker. Canceling review or a picker does not import anything. Errors offer retry; a failed destination write can leave an incomplete destination file and never reports success.

**Preparar contexto local** includes all saved programs and the available demo snapshot. **Preparar resultados locales** includes that demo snapshot alone. No historical sessions, phase, rules, goals or measurements are invented: unavailable collections are empty and phase is null. Both actions prepare a review, without saving or sharing automatically. Review the content before choosing a destination or copying to the system clipboard.

## Contract v1

Root: required `schemaVersion: 1`, `type`, stable `importId`, and ISO-8601 `createdAt` with timezone. Exactly one matching payload (`context`, `proposal`, `results`) must be present and non-null. Other payloads may be null or omitted. Types are `TrainingContext`, `WeekProposal`, `TrainingResults`.

| Payload | Supported fields |
| --- | --- |
| TrainingContext | `programs` (ENT-01 TrainingProgram array), nullable `phase`, `history` (TrainingResults), `rules` and `goals` (text arrays), optional `measurements` (defaults to empty) |
| Measurement | `id`, `measuredAt`, `metric`, finite nonnegative `value`, explicit `unit` (metric/unit labels are preserved without conversion) |
| WeekProposal | `id`, positive integer `version`, `programId`, `blockId`, `source`, `week` (ENT-01 WeekPlan), `differences` and `reasons` (text arrays) |
| TrainingResults | `sessions` array; optional nullable legacy `demo` |
| SessionResult | `id`, nullable `sessionPlanId`, `status`, nullable `startedAt`/`endedAt`/`notes`, `sets`, `cardio` |
| SetResult | `id`, nullable `prescriptionId`, reusable `exerciseId`, `status`, nullable `reps`/`rir`/`load`/`completedAt`/`notes` |
| CardioResult | `id`, `status`, nullable `durationSeconds`/`distance`/`distanceUnit`/`notes` |
| DemoSnapshot | full existing `WorkoutState` snapshot; `loadUnit: KG`, `loadBasis: UNSPECIFIED`, `loadScale: 10` |

All table fields are required unless marked optional, even where null is accepted. Status: `COMPLETED`, `PARTIAL`, `NOT_PERFORMED`, `OMITTED`. Missing performance values remain null, zero remains zero, and status is independent of those values. Results never overwrite prescriptions. References identify external planning entities; they need not exist in this installation. Proposal hierarchy is validated using the [ENT-01 contract](PROGRAM_IMPORT.md); imported proposals remain unapproved archive entries, even if their referenced program exists.

Set loads use ENT-01 magnitude/unit/basis without conversion: KG/LB with TOTAL/PER_SIDE/PER_HAND; BODYWEIGHT and MACHINE_SETTING with matching basis. Performed reps accept 0–1000, RIR 0–10. Cardio uses explicit seconds (0–604800) and distance in M/KM/MI; distance value and unit are supplied together or both null. Generic measurement and distance values must be finite, 0–1e9. Text is nonblank when present, up to 10,000 characters. IDs follow ENT-01 syntax; session IDs are unique in results; set/cardio IDs are unique within each session; measurement IDs are unique within context. Dates parse as instants; session end cannot precede start. Arrays may be empty where no records exist.

Legacy demo uses integer tenths of kilograms and preserves *all* original snapshot fields, including revision, records, early finish and rest deadlines. The original demo never recorded load basis or stable exercise IDs, so its basis is explicitly UNSPECIFIED and it is not converted into generalized session history. Its deadlines are historical data, never restarted when imported. Only the current demo session exists locally; this feature is not a complete workout-history implementation.

Strict policy: unknown fields, enum values, types, versions or missing required fields are rejected before saving; no unsupported fields are silently discarded. Input/output is limited to 1,048,576 UTF-8 bytes per package. Malformed UTF-8 files are rejected. Future versions require an explicit migration. Null and omitted *optional* fields have the same semantic meaning; whitespace, key order and omission style are not preserved. This is semantic round-trip compatibility, not byte-for-byte formatting preservation.

## Storage, privacy and identity

`exchanges-v1.json` is an independent versioned DataStore archive. Each save is atomic. Same importId and equal supported content is a no-op; changed content under an existing ID fails without replacement. Archive content is validated on restoration; corrupt/future archives report an error and are never reset to empty. Neither `workout.json` nor `programs-v1.json` is replaced by an imported exchange. Cross-package updates, activation, proposal approval and relational history are separate requirements.

Local context generation contains no body measurements. Re-exporting imported context excludes measurements by default and requires the explicit checkbox to include them. A redacted export receives a deterministic derived importId because its content changed; repeated redacted exports retain the same identity. Saving the reviewed original preserves all fields, including measurements; redaction applies only to copying/exporting. Copying sends content to the Android system clipboard. Keep real files and personal measurements outside public evidence.

## Synthetic examples and acceptance

Examples under `app/src/main/assets/examples/`: `training-context-v1.json`, `week-proposal-v1.json`, `training-results-v1.json`. They are designed examples, not the illustrative unversioned contract from product definition section 8.2.

1. Import each example, review and save. Reimport to verify no duplicates; change a note under the same ID to verify conflict rejection.
2. Select each saved exchange, export a JSON file, and import that file in a clean test installation. Compare decoded supported fields, including program hierarchy, proposal version/source/reasons, statuses, zero/null values, units, dates, notes and cardio.
3. Force-stop/reopen and export again. Cancel import/export, rotate during review, reject unsupported version/unknown field and confirm workout/program data remain intact.
4. For privacy, add a fictitious measurement to a context. Verify default exported/clipboard JSON omits it and uses a different stable importId; explicitly select inclusion to recover an identical full export.

Physical Android target acceptance and publication status must be recorded separately from emulator evidence.

## Verification evidence (2026-10-30)

- `testDebugUnitTest`: 28 tests, 0 failures; seven exchange tests exercise all three payloads, public fixtures, zero/null, notes, units, legacy snapshot fields, privacy, idempotency, conflicting imports, failed writes, clean-store import and real-file recreation. Existing program and workout tests also pass.
- `assembleDebug`: builds the standard `com.example.workoutpoc` APK. `lintDebug`: 0 errors, 67 existing warnings, none in the exchange sources.
- API 36 emulator: both `ExchangeDeviceTest` tests pass, including actual clipboard inspection proving measurement exclusion by default and exact opt-in export, plus local context/results generation. `documentExportReimportIntoCleanStoreAndPreserveSession` passes through the real Android OpenDocument/CreateDocument pickers, saves/reopens a proposal, rotates, exports a file, compares every decoded field and rejects invalid JSON. Programs and workout snapshot remain unchanged.
- `ExchangeFreshInstallTest`: 1 test, 0 failures/skips. A separate APK with test-only application ID `com.example.workoutpoc.exchangeqa` starts with an empty archive and imports the original installation's actual exported file via Android's document picker. All supported fields equal the source. No existing app data is cleared. The test-only ID was supplied by a local Gradle init script, not a production build change.
- `python scripts/verify_exchange_restore.py --serial emulator-5554 --package com.example.workoutpoc.exchangeqa`: PASS; the archived proposal remains equal after force-stop/relaunch and is restored into the UI. Its screenshot was visually inspected. The verifier can also target a connected physical test device; it never clears data.
- ProgramDeviceTest and WorkoutDeviceTest pass together with the export/import device test in a three-test regression run. Notification/overlay recording remains intact.

Raw local evidence is retained under `.local/ent-02-verification/` and `.local/ent-02-restoration/`. The checked-in summary contains synthetic test metadata only. Emulator results establish the portable JSON behavior, not Samsung/One UI physical acceptance. Delivery acceptance is emulator-only by explicit user authorization on 2026-10-30. Physical Samsung/One UI testing was not performed and is waived for this ENT-02 delivery; the recorded emulator results are not physical-device evidence. Publication and review are tracked in PR #25.

### Repeat clean-install acceptance

Export an exchange through the normal installation into Downloads, then run (using its actual filename):

```powershell
./scripts/verify_exchange_fresh_install.ps1 -Serial emulator-5554 -SourceFileName ent-02-export-EXAMPLE.json
```

The script builds a uniquely identified test APK, checks its archive is initially empty, imports the actual exported file through the document picker, compares supported fields and verifies force-stop restoration. Existing installations are preserved, the test installation remains available for inspection, and normal APK build outputs are restored afterward. Source files and private evidence remain local; no export is uploaded. Supply the serial of a connected test phone for phone testing, then complete the manual checks above.
