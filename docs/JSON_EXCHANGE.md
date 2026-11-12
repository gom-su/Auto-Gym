# JSON interchange (ENT-02)

Open **Agente → Intercambio JSON** (also in **Ajustes**). Import a UTF-8 file or paste JSON, review its complete content, then explicitly save. Saved entries can be selected, copied or exported through Android's document picker. Canceling review or a picker does not import anything. Errors offer retry; a failed destination write can leave an incomplete destination file and never reports success.

**Preparar contexto local** includes saved programs, typed session history, active and previous cycle outlines, next-week briefs, planning answers and dated local measurements with their method and unit. The context never includes the demo snapshot: its sets are fixed synthetic data, so an agent must not plan from them (packages that carry one are still read). **Preparar resultados locales** includes recorded session results and the available demo snapshot. Missing data is never invented. Both actions prepare a review, without saving or sharing automatically. Review the content before choosing a destination or copying to the system clipboard.

## Contract v1

Root: required `schemaVersion: 1`, `type`, stable `importId`, and ISO-8601 `createdAt` with timezone. Exactly one matching payload (`context`, `proposal`, `results`, `outline`) must be present and non-null. Other payloads may be null or omitted. Types are `TrainingContext`, `WeekProposal`, `TrainingResults`, `PlanOutline`.

| Payload | Supported fields |
| --- | --- |
| TrainingContext | `programs` (ENT-01 TrainingProgram array), nullable `phase`, `history` (TrainingResults), `rules` and `goals` (text arrays), optional `measurements` (defaults to empty), optional `outlines` (active plans), `previousOutlines` (inactive/replaced cycle structures) and `nextWeeks` (the app's next-week brief per plan); all optional collections default to empty, so older contexts still decode |
| PlanOutline | multi-week plan structure; see [multi-week plans](PLAN_OUTLINE.md) |
| Measurement | `id`, `measuredAt`, `metric`, finite nonnegative `value`, explicit `unit`; optional nullable `method`, `label`, `fasted`, `timeOfDay`, `device`, `notes` (metric/unit labels and measurement provenance are preserved without conversion) |
| WeekProposal | `id`, positive integer `version`, `programId`, `blockId`, `source`, `week` (ENT-01 WeekPlan), `differences` and `reasons` (text arrays) |
| TrainingResults | `sessions` array; optional nullable legacy `demo` |
| SessionResult | `id`, nullable `sessionPlanId`, `status`, nullable `startedAt`/`endedAt`/`notes`, `sets`, `cardio`; optional nullable `programId` identifies the originating cycle |
| SetResult | `id`, nullable `prescriptionId`, reusable `exerciseId`, `status`, nullable `reps`/`rir`/`load`/`completedAt`/`notes` |
| CardioResult | `id`, `status`, nullable `durationSeconds`/`distance`/`distanceUnit`/`notes` |
| DemoSnapshot | full existing `WorkoutState` snapshot; `loadUnit: KG`, `loadBasis: UNSPECIFIED`, `loadScale: 10` |

All table fields are required unless marked optional, even where null is accepted. Status: `COMPLETED`, `PARTIAL`, `NOT_PERFORMED`, `OMITTED`. Missing performance values remain null, zero remains zero, and status is independent of those values. Results never overwrite prescriptions. Local results include `programId`; next-week references and proposal evidence use it with `sessionPlanId` so another cycle cannot satisfy this cycle's training evidence. Legacy results without program identity remain readable. References identify external planning entities; they need not exist in this installation. Proposal hierarchy is validated using the [ENT-01 contract](PROGRAM_IMPORT.md); imported proposals remain unapproved archive entries, even if their referenced program exists.

Set loads use ENT-01 magnitude/unit/basis without conversion: KG/LB with TOTAL/PER_SIDE/PER_HAND; BODYWEIGHT and MACHINE_SETTING with matching basis. Performed reps accept 0–1000, RIR 0–10. Cardio uses explicit seconds (0–604800) and distance in M/KM/MI; distance value and unit are supplied together or both null. Generic measurement and distance values must be finite, 0–1e9. Text is nonblank when present, up to 10,000 characters. IDs follow ENT-01 syntax; session IDs are unique in results; set/cardio IDs are unique within each session; measurement IDs are unique within context. Dates parse as instants; session end cannot precede start. Arrays may be empty where no records exist.

Legacy demo uses integer tenths of kilograms and preserves *all* original snapshot fields, including revision, records, early finish and rest deadlines. The original demo never recorded load basis or stable exercise IDs, so its basis is explicitly UNSPECIFIED and it is not converted into generalized session history. Its deadlines are historical data, never restarted when imported. Since ENT-04 the snapshot is a frozen `DemoStateV1` type with exactly these fields, exported only for the built-in demo; archived and current planned sessions are exported as typed `sessions` (see [planned recording](PLANNED_RECORDING.md)).

Strict policy: unknown fields, enum values, types, versions or missing required fields are rejected before saving; no unsupported fields are silently discarded. Input/output is limited to 1,048,576 UTF-8 bytes per package. Malformed UTF-8 files are rejected. Future versions require an explicit migration. Null and omitted *optional* fields have the same semantic meaning; whitespace, key order and omission style are not preserved. This is semantic round-trip compatibility, not byte-for-byte formatting preservation.

## Storage, privacy and identity

`exchanges-v1.json` is an independent versioned DataStore archive. Each save is atomic. Same importId and equal supported content is a no-op; changed content under an existing ID fails without replacement. Archive content is validated on restoration; corrupt/future archives report an error and are never reset to empty. Neither `workout.json` nor `programs-v1.json` is replaced by an imported exchange. Cross-package updates, activation, proposal approval and relational history are separate requirements.

Local context generation includes body measurements with their provenance. Copying/exporting local or imported context excludes measurements by default and requires the explicit checkbox to include them. In-app weekly and cycle generation include them, as stated beside the generation action. A redacted export receives a deterministic derived importId because its content changed; repeated redacted exports retain the same identity. Saving the reviewed original preserves all fields, including measurements; redaction applies only to copying/exporting. Copying sends content to the Android system clipboard. Keep real files and personal measurements outside public evidence.

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

## Campos añadidos por las mejoras de plan y entrenamiento

Estos campos tienen valor por defecto. Los paquetes anteriores siguen siendo válidos.

- `context.comments`: comentarios vigentes (sin `resolvedAtMillis`) del programa. Cada uno lleva `id`, `text`, `createdAtMillis`, `programId`, `weekId`, `sessionId`, `exerciseId` y `setIndex`. El agente los usa al generar la semana.
- `context.preferredUnits`: lista de `{ "exerciseId": …, "unit": "KG" | "LB" }`. Unidad en la que el usuario registró cada ejercicio.
- `SetPrescription.kind`: `"WORK"` (por defecto), `"WARMUP"` o `"APPROACH"`. Las series de calentamiento o aproximación van antes de las de trabajo de su ejercicio. Una serie de fuerza sin `load` bloquea la aprobación de la propuesta.
- `SessionPlan.cardio`: `{ "modality": …, "durationSeconds": … }`. Un día puede tener solo cardio, sin ejercicios de fuerza.

Una propuesta puede reemplazar una semana guardada solo con una aprobación explícita. Esa aprobación borra las sesiones registradas de esa semana.

### Versión del formato

- Esta app escribe los paquetes de intercambio con `schemaVersion: 2` y lee `1` y `2`. Los paquetes v1 siguen siendo válidos.
- Una app anterior que lea un paquete v2 lo rechaza por versión (`Se requiere 1`); la solución es actualizar la aplicación.
- Los programas (`ProgramPackage`) aceptan `1` y `2`. Los almacenes internos y el respaldo completo tienen versión propia y no cambian con esta regla.

## Decisión: números entre comillas (se mantiene)

La política estricta de la sección «Contract v1» aplica a campos desconocidos, valores de enumeración, versiones y campos obligatorios. Para los campos numéricos, la app usa el comportamiento por defecto de kotlinx.serialization, que acepta un número escrito entre comillas (por ejemplo `"restSeconds": "45"`) y lo decodifica como `45`. Esto afecta a todos los campos numéricos del intercambio, no solo a `restSeconds`.

Decisión: **se mantiene así**. Endurecer el decoder cambiaría la importación de archivos que hoy se aceptan y exigiría una migración de compatibilidad, que no compensa el riesgo.

Consecuencias:
- MCP rechaza una cadena donde el campo espera un número (por ejemplo `"restSeconds": "45"`) y acepta los números JSON decimales donde el campo los permite (por ejemplo `load.value: 22.5`). Así, el agente que escribe a través de MCP no puede introducir la forma entrecomillada.
- Un archivo escrito a mano con números entre comillas se importa en la app como el número equivalente; las reglas de rango se aplican sobre ese número.
- La diferencia también aparece al crear una propuesta o un plan mediante MCP: MCP valida los argumentos de la herramienta antes de escribir el paquete, así que la forma entrecomillada se rechaza y nunca llega a un archivo. Solo aparece en archivos escritos a mano o por otra herramienta, que la app acepta y MCP no valida.

Si se quiere cerrar más adelante, debe hacerse como cambio aparte con prueba de importación de los ejemplos existentes.
