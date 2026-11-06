# Week proposal review: validate, compare, approve (AG-02, AG-05, AG-06)

The AI proposes, the deterministic rules validate, and the user decides. Nothing an agent sends changes the program without an explicit approval.

## Cycle without MCP (AG-06)

1. **Programas > Intercambio JSON > Preparar contexto local**: copy or export the v1 `TrainingContext` (programs, archived sessions as typed results) for an external agent.
2. The agent returns a v1 `WeekProposal` package. Paste or import it in **Intercambio JSON** and save it. Saving only archives it: the program is unchanged.
3. **Programas > Propuestas de semana**: every saved proposal is listed with its status. A proposal stays **Pendiente de tu decisión** until you explicitly approve or reject it (AG-02).
4. Open it to see:
   - the rule results (blocks and warnings);
   - the computed changes against the base week;
   - the agent's stated changes and reasons;
   - the proposed volume.
5. **Aprobar y añadir semana** re-validates at decision time, then appends the week to the saved program through the ENT-09 merge (never replacing or reordering saved weeks). The week can then be trained with **Entrenar este día**. **Rechazar propuesta** changes nothing.

## Rules (AG-05)

Rules run as pure Kotlin over the proposal and the saved library. The **base week** is the last saved week of the target block. Comparisons are per stable exercise ID across the whole week.

| Rule | Default | Configurable |
| --- | --- | --- |
| Program and block exist; week ID is new; no reuse of saved IDs; the program stays valid | Block | No (integrity) |
| Load increase over the base (same unit and basis) above N % (default 10 %) | Warning | Severity and N |
| Set increase per exercise above N % (default 50 %) | Warning | Severity and N |
| Unit or load-basis change for an exercise (loads not comparable) | **Block** | Severity |
| Sets prescribed at RIR 0 | Warning | Severity |
| Exercise ID unknown to programs, catalog and history | Warning | Severity |

Each rule can be set to off, warning or block. Settings are stored in `proposals-v1.json`, and every proposal is re-evaluated with the current settings.

- **Blocking issues:** a proposal with any blocking issue cannot be approved. Resolve it with a corrected proposal (new version or import ID) or, for configurable rules, by deliberately changing the rule.
- **Warnings** allow approval; they are recorded as accepted in the decision.

Computed changes cover:
- days added or removed;
- exercises added or removed;
- set counts;
- repetition ranges;
- minimum RIR;
- maximum load per unit and basis.

## Decisions and traceability

`proposals-v1.json` stores one decision per import ID and per proposal ID and version. Each decision records:
- its status, time, program, block and week;
- the computed changes;
- the agent's stated changes and reasons;
- the warnings accepted.

A decided proposal shows this record as it was at decision time, followed by its validation under the current rules.

- **Idempotency:** decisions are final; approving twice returns the same decision. If the app stops after the week was appended but before the decision was saved, the next approval detects the identical week and only records the decision, so nothing is duplicated.
- **Durability:** corrupt or future files are reported and never reset. Interchange v1 contracts are unchanged.

## Verification (2026-11-06, API 36 emulator)

- Unit tests: 82 run, 0 failures, 1 skipped (the opt-in private test). `ProposalReviewTest` adds 5 cases:
  - pending evaluation with differences and warnings, with no change to the program;
  - explicit approval appending once, with a traceable decision and the new week trainable;
  - a blocking rule preventing approval until a corrected version, plus configurable severities;
  - integrity always blocking (existing week, missing program, reused IDs);
  - rejection, and an interrupted approval completing without duplication and surviving a restart.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `ProposalDeviceTest` runs through the real UI: paste a proposal → save the exchange (program unchanged) → **Propuestas de semana** → pending with the load warning → **Aprobar y añadir semana** → decision saved and the week present in the program file read from disk.
- In two full runs of the 7 device cases (proposal, catalog, deviations, session lifecycle, planned session, program, workout), the first passed all 7. The second passed 6; `ProgramDeviceTest` hit a stale-node error in its own helper, an intermittent failure seen before this change.
- Device tests were made independent of each other: the proposal test uses its own synthetic program, and the scroll helper re-finds the tallest container.

Physical-device testing was not performed.
