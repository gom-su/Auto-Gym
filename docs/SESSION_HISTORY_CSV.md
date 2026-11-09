# Session history CSV

**Progreso > Sesiones > Exportar CSV** writes every recorded session to a file you choose with the system save dialog. Nothing leaves the device otherwise: the app has no network permission (decision 0005).

## Format

- UTF-8, comma separated, CRLF line ends, RFC 4180 quoting (fields with commas, quotes or line breaks are quoted; quotes are doubled).
- Text that starts with `=`, `+`, `-`, `@`, tab or carriage return and is not a number is prefixed with `'`, so spreadsheets do not run it as a formula.
- One row per recorded set (`set`, `extra_set`, `warmup_set`) and one per cardio entry (`cardio`). Newest session first; sets in recording order.
- Times are ISO-8601 UTC. Loads and distances use a dot as decimal separator. RIR 4 is written `4+`, as in the app.
- Only recorded data is written. An omitted set has no load, reps or RIR. Unprescribed sets have no prescribed values. Fields never entered stay empty, never defaulted.

## Columns

| Column | Meaning |
| --- | --- |
| `session_id` | Stable session identity |
| `started_at`, `ended_at` | Session start and end (empty while running) |
| `program`, `week`, `day` | Names from the session's program snapshot; `Demo` for the demo session |
| `session_status` | `COMPLETED`, `PARTIAL` or `NOT_PERFORMED` (adherence rules), with ` (IN_PROGRESS)` or ` (ENDED_EARLY)` |
| `row_type` | `set`, `extra_set`, `warmup_set` or `cardio` |
| `exercise`, `exercise_id` | Exercise name and stable catalog ID |
| `set_number`, `set_status` | Position within the exercise; `COMPLETED` or `SKIPPED` |
| `load`, `unit`, `basis` | Recorded load with unit (`KG`, `LB`, `MACHINE_SETTING`, `BODYWEIGHT`) and load basis |
| `reps`, `rir` | Recorded repetitions and RIR |
| `prescribed_load`, `prescribed_reps`, `prescribed_rir` | The prescription for planned sets |
| `edited_at`, `previous_versions` | Last edit time and number of earlier versions kept in the audit trail |
| `notes` | Set notes, or cardio notes on cardio rows |
| `cardio_modality`, `duration_seconds`, `distance`, `distance_unit` | Cardio entry fields as entered |

The CSV is a one-way export for spreadsheets and backups; it is not an interchange format and cannot be imported back. For agent interchange use the versioned JSON exchange.

Verified by `HistoryTest` (unit) and `HistoryDeviceTest.csv_export_writes_the_recorded_sets_through_the_save_dialog` (API 36 emulator).
