# Obsidian migration archive (ENT-03)

Open **Ajustes → Migrar desde Obsidian**. Select a UTF-8 Markdown file or paste a week, inspect its structure, days, exercises, cardio, notes and warnings, then explicitly save. **Texto original con líneas** shows every original line. **Advertencias** allows individual warnings to be marked seen or left pending; seeing a warning never resolves its meaning. Saved migrations can be reopened and their review updated offline.

This delivery archives migration data. It does not add programs to the library, create executable workout history, approve proposals or activate sessions. Correct interpretations by editing the Markdown and importing it again. There is no in-app interpretation editor or migration export in this delivery.

## Parser v1 contract

Input is limited to 1,048,576 UTF-8 bytes. Invalid UTF-8, empty input and oversize files report errors. A BOM and CRLF line endings are supported without changing the retained source. The supported dialect is the two Workout v3 July week formats, represented by the synthetic [W1](../app/src/main/assets/examples/obsidian-week-v3-w1.md) and [W2](../app/src/main/assets/examples/obsidian-week-v3-w2.md) examples. Real source weeks remain outside version control.

The deterministic parser recognizes scalar frontmatter (program, week, block, week_in_block, phase, microcycle, created, status and title), week/day headings, warmup text, exercise tables, cardio bullet fields and agent opening/closing notes. Metadata retains its original scalar text; it is not converted to calendar instants or inferred program identities. Unrecognized metadata, sections, columns, malformed tables and unsupported formats are retained with warnings. Fenced code is retained without parsing its contents as workouts. This is a bounded dialect, not a general YAML/Markdown/Obsidian renderer; links, attachments, HTML and code are never executed or fetched.

| Input | Archived interpretation |
| --- | --- |
| `3×8–12 @ RIR 2–3` | Set count and independent repetition/RIR ranges; no chosen scalar |
| `2×10 c/l` | Explicit per-side repetition marker, with original objective |
| `12,11,10` / `2x12,1x16` | Ordered results, including results beyond the prescribed count |
| `3/2`, `22/27`, `?`, trailing empty token | Unknown result token; original retained, warning shown |
| Empty results / `0` | Empty collection / explicit zero, never inferred completion |
| `25 lb/hand`, `12.5 lb/lado`, `50 kg total` | Explicit unit and base, no conversion |
| `60 lb` | Magnitude and unit, unknown load base |
| `BW` | Bodyweight marker without invented body mass |
| `~20 kg`, mixed/list loads | Original retained with warning; no guessed assignment |
| `20–30 min` in cardio `Tiempo` | Explicit duration range; no selected duration or inferred actual completion |
| `Notas` / `Comentarios GPT` / `Comentarios usuario` | Unattributed / agent / user comments |
| `✅ / 🟡 / ❌` | Template, not a session status |

Only comma-separated results and explicit `NxM` repetition groups are expanded; slash notation is always left ambiguous, even when it might be a list. An individual result cell is bounded to 1,000 interpreted values plus an unknown overflow marker; its entire original is retained. Prescribed counts/repetitions accept 1–1,000; actual repetitions accept 0–1,000 and RIR accepts 0–10. Load magnitudes accept 0–100,000 when explicitly parseable. Unrepresentable values remain flagged in the original instead of being coerced.

`Carga` is kept separate from prescription and performed series: the heading does not establish whether it was planned or performed. When results are present, an additional warning makes this attribution uncertainty explicit. Count mismatches are not truncated or filled. RPE is never converted to RIR, names never become exercise-catalog identities, dates and completion are never invented. Free text remains local source/comments; clinical information is not converted to clinical models.

## Archive and compatibility

`MigrationDocument` stores schema/parser version, source display name, original Markdown, metadata, ordered days/exercises, separate prescription/results, cardio, comments, all source lines and stable line-addressed `MigrationIssue` records. Each issue stores its seen flag. The document identity is SHA-256 of `obsidian:1`, a NUL separator and the exact original UTF-8 text; element identity uses that document plus its source line. Filename changes do not create duplicates. Whitespace or line-ending edits do create a new immutable source document.

`migrations-v1.json` is an independent, versioned DataStore archive. Saving a document or changing a saved warning's seen flag is atomic. State is published after the write succeeds. Identical reimport preserves the first source name and existing review; edited source adds another document. Restoration validates the archive version, identities, parser version and deterministic interpretation against the original, allowing only review flags to differ. Corrupt, tampered or future archives report errors and are never reset to empty. `workout.json`, `programs-v1.json`, `exchanges-v1.json` and their JSON contracts are unchanged.

Preview and review state survive Activity rotation through the ViewModel. Unsaved review is explicitly temporary and may be lost on process death. Saved documents and seen flags survive force-stop. No source text or personal filenames are written to application logs.

## Converting a saved migration into a program week

A saved migration can be turned into a program v1 week from **Convertir en programa** in its review. The page shows the resulting program, block, week and counts, every value that is not converted, and any blocker, before anything is written. Pressing **Crear o ampliar programa** saves through the normal idempotent program import (ENT-09). Nothing is activated until a day is trained.

- **Blockers:** pending warnings (review or acknowledge them first), an exercise without an explicit set count, or no day with exercises.
- **Exact values only:** a set gets reps or RIR only when the objective has one exact number. A range such as 8–12 leaves the field unset instead of picking a bound. A load is kept only with an exact magnitude, a kg/lb unit and a basis (`total`, `/lado`, `/mano`); `BW` becomes bodyweight. Approximate loads (`~20 kg`) and loads without a basis stay unset.
- **Not converted, and listed as such:** cardio (program v1 has none), days without exercises, "por lado" in the objective, and recorded results (actual reps and RIR are not turned into history). All of it stays in the migration archive.
- **Identifiers:** the program ID comes from the `program` metadata (accents folded, e.g. `obsidian-programa-ficticio-faro`), the block from `block` and the week from `week`. Successive weeks of the same program therefore append to one program, and converting the same week again changes nothing. Without `block` or `week`, the block is named «Bloque sin número» and the week is identified by the migration, and the page says so.

Verified by `MigrationConversionTest` (unit) and `MigrationConversionDeviceTest` (API 36 emulator).

## Acceptance and repeat verification

1. Import each synthetic example through Android's real document picker; inspect both days and the separate objectives/results/comments. Cancel a review and verify no save occurs.
2. Mark a warning seen, rotate, save with pending warnings, reopen, and change a saved warning's seen flag. Confirm the original interpretation is unchanged.
3. Reimport the identical file and verify no duplicate or review reset. Edit the source and verify it creates another document.
4. Cancel the picker; try invalid UTF-8, oversized input and an unsupported older Markdown format. The archive must remain intact and unsupported content must show warnings.
5. Run `python scripts/verify_migration_restore.py --serial emulator-5554` after a synthetic import. It compares the complete persisted archive before/after force-stop and UI browsing, including original text, parsed fields and seen flags. Screenshots and summaries remain in `.local/ent-03-restoration/`.

Run the unit tests, `assembleDebug`, `lintDebug`, `MigrationDeviceTest`, and the existing program/exchange/workout device regressions. Use `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` for connected tests when the installation is needed for the subsequent force-stop check.

Private JVM acceptance can be enabled by setting `ENT03_PRIVATE_WORKOUT_DIR` to the local Workout v3 folder before running `testDebugUnitTest --rerun-tasks` with the project's JDK/SDK selected. Force the rerun because changing an environment variable alone does not invalidate Gradle's cached test result. It reads W1/W2 locally and validates structure without printing or publishing source contents; without that environment variable the private test is explicitly skipped.

Delivery acceptance for ENT-03 is emulator-only by explicit user selection on 2026-10-30. Samsung/One UI physical testing is not required for this delivery and must not be reported as performed. See the verification evidence below for actual results.

## Verified on 2026-10-30

- `testDebugUnitTest`: 39 tests, 0 failures/errors/skips. Eleven migration tests cover both synthetic dialects, retained source/line associations, separate objectives/results, ranges and unknowns, explicit authors, unsupported input, bounded result expansion, identity, concurrent idempotency, write/review failures, actual-file restoration and corrupt/future/tampered snapshots. The opt-in private test validated both local Workout v3 source weeks without publishing them.
- `assembleDebug`: standard debug APK builds. `lintDebug`: 0 errors, 67 existing warnings, none in migration sources.
- API 36 emulator: both `MigrationDeviceTest` cases pass through the actual Android document picker, including cancel, invalid UTF-8, oversize input, pasted unsupported Markdown, source review, preview rotation, seen flags, explicit save, duplicate preservation and saved review updates. Programs, exchanges and the workout snapshot remain unchanged during migration.
- `ProgramDeviceTest` and both `ExchangeDeviceTest` cases pass in the same combined six-test run as the migration cases. The existing workout test failed that combined run at notification repetition adjustment (state remained at 8 reps after the preceding weight change). Its isolated repeat then passed the full notification/overlay/seven-set journey on the same APK. The combined invocation therefore failed overall; these are separate passing results, not a claim of a clean six-test invocation. Workout production/test code was not changed. Existing stale-revision handling requires waiting for the published UI between actions; the combined failure is consistent with that timing boundary, but its cause was not established by this delivery.
- After final display-label cleanup, both migration device cases pass again alongside the 39 unit tests, `assembleDebug` and `lintDebug`.
- `python scripts/verify_migration_restore.py --serial emulator-5554`: PASS. Saved synthetic documents, their exact source, interpretation and review remained identical after force-stop, new Activity launch and UI browsing. Warning, day/exercise and original-text screens were visually inspected.

Raw reports, captures and failed/successful run logs remain local under `.local/ent-03-verification/` and `.local/ent-03-restoration/`. The [public verification summary](evidence/obsidian-migration/verification.json) contains synthetic test metadata only. No physical-device testing or remote publication was performed for this implementation.
