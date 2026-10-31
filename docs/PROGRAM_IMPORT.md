# Variable program import (ENT-01)

Implemented scope: independent variable programs, blocks, weeks, days, exercises and sets; file/pasted-JSON preview; explicit save; offline browsing and restoration. Imports do not activate a week or change the fixed demo workout. Export, Markdown migration, full proposal approval, program updates and variable-plan recording belong to later requirements.

## Try it

1. Open **Programas** from the workout screen.
2. Copy the fictitious [example](../app/src/main/assets/examples/variable-program-v1.json) to the phone's Downloads directory, then choose **Seleccionar archivo JSON**. Alternatively paste its contents and press **Revisar JSON**.
3. In **Revisar importación**, inspect **Semana 1** (2 days, one exercise per day, 2/1 sets) and **Semana 2** (1 day, two exercises, 1/3 sets). A second block includes a third week to check block separation. Exercise identity `squat` repeats with distinct prescriptions.
4. Press **Guardar programa**, open the saved program and inspect the same weeks and days. **Cancelar importación** saves nothing.
5. Force-stop and reopen the app; the library must retain all associations and prescribed values. The demo session is independent.
6. Reimport the same file: the app reports it already exists without duplicates. Change content under the same program ID: the app reports a conflict and preserves the saved program. Preview/cancel and malformed JSON must not change the library.

## ProgramPackage v1 contract

The root has required `schemaVersion: 1` and `program`. Unknown fields, unsupported versions, wrong types, unknown enum values and missing required fields are errors. This contract is separate from the illustrative `WeekProposal` in the product definition. File input is UTF-8 JSON, up to 1,048,576 bytes; pasted input has the same byte limit at validation.

| Object | Fields |
| --- | --- |
| Program | `id`, `name`, nonempty `blocks` |
| Block | `id`, `name`, nonempty `weeks` |
| Week | `id`, `name`, positive integer `version`, nonempty `days` |
| Day | `id`, `name`, nonempty `exercises` |
| Exercise prescription | occurrence `id`, reusable `exerciseId`, `name`, nonempty `sets` |
| Set prescription | `id`; optional nullable `reps`, `rir`, `load`, `restSeconds` |
| Load | finite nonnegative `value` (up to 100,000), `unit`, `basis` |

Names must be nonblank and at most 200 characters. IDs match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` and must be unique across owned objects in a program. `exerciseId` is a reference and may repeat. List order determines display/prescription order and has no fixed day, exercise or set count. Identical display names are allowed. A set's ID is independent of its displayed series number.

Optional fields may be omitted or null. `reps` is 1–1000, `rir` is 0–10 and `restSeconds` is 0–86400. Zero reps is not a completed/omitted set marker; this contract only stores prescriptions, not performance status. Missing values display as **sin indicar** rather than zero.

Load units `KG` and `LB` require basis `TOTAL`, `PER_SIDE` or `PER_HAND`. `MACHINE_SETTING` and `BODYWEIGHT` require their matching basis. No unit/base conversion or equivalence is inferred. A bodyweight value is a magnitude in bodyweight units; numeric machine settings are not kilograms. Additional formats require a future explicit contract revision.

## Persistence and compatibility

The independent `programs-v1.json` DataStore snapshot has an internal schema version. Existing `workout.json` files are unchanged. Exact reimport is a no-op; conflicting same-ID content is blocked without replacement, including changed week versions. No approval or activation is inferred. The library is published only after persistence succeeds; a disk/read/corruption error leaves existing storage intact and offers retry. Future schema versions require an explicit migration; they are not reset to empty.

## Target-phone acceptance

Run the steps above on the target Samsung/One UI device, verify readable content with its display/font settings, file picker cancellation, invalid JSON feedback, preview/cancel/save, all hierarchy associations, rotation, offline use and force-stop restoration. Also confirm opening/closing **Programas** during a demo session leaves its records intact. Emulator automation cannot close this physical-device requirement.

## Verified on 2026-10-30

- JDK 17.0.20.1, Gradle 8.13, Android SDK 36 and the WHPX API 36 emulator were checked locally.
- `testDebugUnitTest`: 21 tests pass, including 8 new program tests. The synthetic fixture verifies variable counts, order, repeated exercise references, independent prescriptions, missing versus zero values and JSON round-trip. Negative cases cover versions, fields, IDs, empty collections, values, units and oversized input. Repository tests cover concurrent duplicate import, conflict preservation, failed writes and real-file restoration; incomplete/corrupt/future snapshots fail without resetting the library.
- `assembleDebug`: APK builds and installs on the API 36 emulator.
- `lintDebug`: 0 errors, 67 existing warnings; none refer to the new planning files.
- `ProgramDeviceTest`: passes. Imports through Android's real document picker, reviews two different weeks, saves, navigates variable counts/values, rotates, rejects invalid pasted JSON, cancels, blocks conflicting content and reports identical reimports.
- `WorkoutDeviceTest`: passes in a separate final run, including actual notification and floating-window controls, automatic rest, all seven demo sets, and a new assertion that opening/closing the library preserves an active session. The test helper now locates the actual compact notification and explicitly expands it for rest controls instead of assuming it is expanded.
- `python scripts/verify_program_restore.py`: PASS on `emulator-5554`; 1 saved synthetic program / 3 weeks remained identical after force-stop, relaunch and UI browsing. The restored UI screenshot was visually inspected. Raw logs and screenshot remain local under `.local/ent-01-verification/` and `.local/ent-01-restoration/`.

The program UI and workout regression passed in separate final checks; the latency benchmark was not rerun. No physical phone was connected during verification. On 2026-10-30 the user selected **"Solo emulador por ahora"** as the validation scope for this local delivery. Implementation and emulator verification are complete for that delivery; Samsung manual acceptance is deferred. ENT-01's full GitHub definition of done still requires target-phone acceptance, so the issue remains open. No remote issue state or publication was changed.
