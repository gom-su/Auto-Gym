# Full local backup and restore

A full backup transfers local training data between Auto-Gym installations. It is independent of [interchange v1](JSON_EXCHANGE.md), which remains unchanged. Interchange is for exchanging context, plans and results with an agent; it does not preserve every local store.

## User flow

1. Open **Ajustes > Respaldo completo > Exportar respaldo completo**. The app reads all stores under their repository locks, validates a lossless encode/decode round trip, and opens Android `CreateDocument` with MIME `application/json` and the suggested name `auto-gym-respaldo-YYYY-MM-DD.json`. Canceling the picker leaves local data untouched. The snapshot is taken before the picker opens; later edits are not included.
2. Save the file somewhere safe. It contains personal measurements and training data in plain UTF-8 JSON; it is not encrypted. A provider may itself sync the selected destination. The app adds no network permission and does not upload files itself; `android:allowBackup="false"` stays in force.
3. On the target installation, open **Restaurar respaldo** and pick the JSON through Android `OpenDocument`. The reader accepts at most 64 MiB (67,108,864 bytes), checks strict UTF-8, parses and validates all stores, and reads the current device without writing anything.
4. Review the backup date, app version and the counts **respaldo / este dispositivo**. The warning explains that restore replaces all included stores and preferences rather than merging them. Canceling this review does not write any store.
5. Tap **Restaurar y reemplazar datos**, then **Sí, restaurar y reemplazar** in the confirmation dialog. An active or resting session on the target blocks restoration. If local data changed since the comparison, choose the file again to review the current state. Export is allowed during a workout, and a saved in-progress workout can be resumed on a target with no active session.
6. After success, repository flows publish the restored values and derived screens refresh as they are reopened. Runtime rest/pause marks are rebased onto the target device using wall time, even if the source and target boot counts coincide; expired rests then reconcile through the normal workout reducer. Records, edit history and prescribed data are preserved. Recording permissions are Android settings and must be granted separately on the target.

The ordinary **Importar** flow continues to handle programs, interchange and Obsidian files. Picking one as a backup explains that it belongs in Importar and replaces nothing.

## Format v1

The envelope is deliberately separate from every existing data contract:

```json
{
  "format": "auto-gym-backup",
  "backupVersion": 1,
  "createdAt": "2026-11-11T12:00:00Z",
  "app": { "versionName": "1.0.1", "versionCode": 2 },
  "sha256": "<64 lowercase hexadecimal characters>",
  "stores": { "<store key>": "<the store document, as a JSON object>" }
}
```

This is an illustrative envelope, not an importable fixture. All envelope fields, both app fields and all twelve stores below are required. `createdAt` is an ISO-8601 instant; `versionName` is nonblank (at most 100 characters), and `versionCode` is a positive integer. Unknown envelope fields, store keys and nested data fields are rejected. Store content is encoded and decoded using its own existing DataStore serializer and validation rules, including required/defaulted fields and supported schema versions. The legacy session document has no schemaVersion; its unknown fields are still rejected in a backup.

| Store key | Local file | Preserved data |
| --- | --- | --- |
| `session` | `workout.json` | Live/prepared/completed workout, source and executable plan, set values, records, all revisions, cardio, pauses and rest deadlines |
| `history` | `history-v1.json` | Archived sessions, including local details beyond interchange results |
| `programs` | `programs-v1.json` | Full program library, blocks, weeks, days and prescriptions |
| `planOutlines` | `plan-outlines-v1.json` | Active, inactive and replaced periodization plans |
| `exchanges` | `exchanges-v1.json` | Archived interchange packages |
| `proposals` | `proposals-v1.json` | Rules, approvals/rejections, explanations, expectations and manual outcomes |
| `measurements` | `measurements-v1.json` | Values, units, methods and optional measurement context |
| `exercises` | `exercises-v1.json` | Stable exercise IDs, names, aliases, equipment and setup |
| `planningAnswers` | `planning-answers-v1.json` | Answers and explicit unavailable responses |
| `weekAnnotations` | `week-annotations-v1.json` | Deloads, days not performed and weekly goals |
| `migrations` | `migrations-v1.json` | Original Obsidian documents, parser output and acknowledged issues |
| `settings` | `app-settings-v1.json` | App preferences, including the default logging mode |

**`sync-v1.json` is deliberately excluded.** Its pairing secrets never leave the device in this plain backup. Existing target keys stay unchanged; a clean target needs new pairing. Android permissions, generated exports, caches and external source files are also outside the backup.

### Checksum

`sha256` is SHA-256 over a canonical recursive walk of `stores` only; metadata is not covered. The walk writes UTF-8 tokens into the digest, without whitespace or closing delimiters:

- Null: `n`.
- A string: `s<byte-length>:` followed by the unescaped string's UTF-8 bytes.
- Another primitive (number or boolean): `l<byte-length>:` followed by its literal text's UTF-8 bytes. Numbers are not parsed or reformatted.
- An array: `[<element-count>:` followed by each element's walk, preserving order.
- An object: `{<entry-count>:` followed by entries in ascending Kotlin string key order (UTF-16 code units). Each key is `k<byte-length>:` plus its UTF-8 bytes, followed by the value's walk.

Whitespace and object key order do not affect the checksum. Changing a numeric literal's representation does. The checksum detects accidental damage; it is not a signature or an authenticity check. It is checked before any store is decoded. Export rereads its own encoded result and refuses any snapshot that would lose information on restoration.

## Persistence guarantees and limits

- Invalid/truncated JSON, invalid UTF-8, wrong formats, unknown/future backup versions, unknown/missing stores, unknown data fields, checksum mismatches and store validation failures abort before replacement. Future versions ask the user to update the app. No corruption handler resets a file to an empty default.
- Restore rereads **every current store** before any replacement. A corrupt or future-version current store aborts and stays byte-identical, including when the workout would otherwise reconcile an expired rest.
- Export and restore lock all participating repositories. Proposal decisions are locked before their dependencies; the workout lock is taken last and held throughout the transaction, so timer, overlay, notification and in-app workout commands cannot race the session check or restoration.
- Each DataStore update is an atomic file replacement (temporary file + rename). The write order is proposals, programs, plan outlines, exchanges, measurements, catalog, planning answers, week annotations, migrations, settings, history, then session. The live state is published after history and session writes succeed.
- If a write throws, attempted stores are restored in reverse order from the preflight snapshot. If the session write fails, its previous history is restored first. Lifecycle cancellation is deferred once replacement starts. Rollback failures are surfaced explicitly and never reported as success or as complete preservation.
- This is **not a single filesystem transaction across twelve files**: process death, power loss, or a persistent storage failure during restore can interrupt the overall operation. Individual files remain atomic; automatic crash recovery of a multi-file restore is not implemented. Keep the backup and restore again before recording new data if restoration was interrupted. A corrupt local file still requires recovery outside this restore flow.
- Files created through Android document providers cannot be guaranteed atomic by the app: a failed export may leave an incomplete destination. Local stores are untouched, and that incomplete file cannot pass backup validation.

## Verification

`BackupTest` uses rich synthetic data with edited sets and revisions, cardio, measurement methods, aliases, answers, all plan states, archived exchanges, approvals and expectations. It verifies lossless codec round trips and transfer between two independent sets of empty Okio stores, then reads all twelve files independently. It covers invalid formats/versions/fields/checksums, active sessions, stale confirmation, every corrupt current store, injected mid-restore/session-write failures, lifecycle cancellation and explicit rollback-failure reporting. The manifest guard remains in `OfflinePersistenceTest`.

Run `testDebugUnitTest` and `assembleDebug`. After the tests generate the synthetic `app/build/backup-fixture.json`, run this check on a **disposable emulator** (it uninstalls the selected app twice):

```powershell
python scripts/verify_full_backup.py --serial emulator-5560 --adb C:/path/to/sdk/platform-tools/adb.exe --allow-clean-install
```

Prefer a temporary `-read-only -no-snapshot` AVD instance to protect saved emulator data. The check refuses physical devices. It exercises Android document selection, preview/cancel/confirm, export and restore after a clean install, comparing all included stores and reopening after force-stop. Evidence is written under `.local/full-backup` (ignored by Git).

Verified on 2026-11-11: `testDebugUnitTest` passed (160 passed, 1 pre-existing skipped; 11 backup tests), and `assembleDebug` succeeded. The document-provider flow preserved all 12 stores, both cancellation paths wrote nothing, and the restored data survived force-stop. Unit coverage also checks transfer of rest/pause marks between devices with coincident boot counts. Emulator evidence does not establish physical-device behavior.
