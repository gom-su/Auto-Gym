# Week proposal review: validate, compare, approve (AG-02, AG-05, AG-06)

The AI proposes, the deterministic rules validate, and the user decides. Nothing an agent sends changes the program without an explicit approval.

## Cycle without MCP (AG-06)

1. **Agente > Intercambio JSON > Preparar contexto local**: copy or export the v1 `TrainingContext` (programs, archived sessions as typed results) for an external agent.
2. The agent returns a v1 `WeekProposal` package. Paste or import it in **Intercambio JSON** and save it. Saving only archives it: the program is unchanged.
3. **Agente > Propuestas de semana**: every saved proposal is listed with its status. A proposal stays **Pendiente de tu decisión** until you explicitly approve or reject it (AG-02).
4. Open it to see:
   - the rule results (blocks and warnings);
   - the computed changes against the base week;
   - the agent's stated changes and reasons;
   - the proposed volume.
5. **Aprobar y añadir semana** re-validates at decision time, then appends the week to the saved program through the ENT-09 merge (never replacing or reordering saved weeks). The week can then be trained with **Entrenar este día**. **Rechazar propuesta** changes nothing.

## Focused planning questions (AG-01)

Every question offers three predefined radio-button choices plus **Otra**, which reveals a free-text field. Select an answer and save explicitly; choosing a radio never saves by itself. A blank custom answer cannot be saved. The same control is used by dynamic AI questions for weeks and 12-week cycles. Equipment questions also use three choices; **Otra** accepts remaining equipment types or a description. Existing enum-based equipment answers remain compatible.

**Agente > Preguntas de planificación** lists the gaps that matter for planning:
- the current goal and phase;
- injuries or restrictions;
- availability;
- for each catalogued exercise whose equipment is unknown, which equipment it uses.

Each question says why it matters. Answer what you know, or tap **No disponible**. A blank answer is rejected, and nothing is filled in by assumption. An equipment answer also completes the catalog entry, so that gap closes at its source. **Cambiar respuesta** reopens a question.

Nothing blocks. Context export works with any number of open questions. The exported v1 `TrainingContext` takes its **phase**, **goals** and **rules** only from the answers:
- answered questions become the phase, goals or rules;
- "not available" items are passed as explicit rules ("Dato no disponible (indicado por el usuario): …"), so the agent does not assume them;
- unanswered questions are simply absent.

Answers are stored in a validated `planning-answers-v1.json`: each answer is either a value or "not available", never both or neither.

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
| With an active plan: week in the plan's block, no week beyond the plan | **Block** | No |
| With an active plan: reps, RIR, sets, exercises or deload volume off the plan week's targets; previous week without records | Warning | Severity ([details](PLAN_OUTLINE.md#plan-aware-proposal-rules)) |

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

## Explained changes (AG-07)

The review lists every relevant change against the base week as a card:

| Kind | When |
| --- | --- |
| **Aumento** / **Reducción** | Same exercise ID with more or fewer sets, a higher or lower maximum load (same unit and basis), more or fewer prescribed reps, or a lower or higher minimum RIR (closer to failure counts as an increase in effort). Percentages are shown where there is a base. |
| **Sustitución** | A removed exercise is replaced at the same day and position by a new one. |
| **Nuevo ejercicio** / **Ejercicio retirado** | The remaining additions and removals. |

Each card carries:
- **Evidence:** the exercise's most recent recorded exposure on the device (date, top load × reps, sets, minimum RIR), or "Sin historial registrado". For a substitution, it is the replaced exercise's history.
- **Agent's reason:** the proposal reasons that mention the exercise by name or ID. When none does, the card says **"Sin motivo específico del agente"** and nothing is inferred.

A configurable rule, **Cambio sin motivo del agente** (warning by default), flags each unexplained change.

## Later evaluation of recommendations (AG-04)

On an **approved** proposal, **Evaluación posterior** records what the recommendation should achieve:
- an exercise from the proposed week;
- a metric: top load, reps or volume;
- a direction: increase, maintain or reduce;
- a window of 1–84 days.

The window starts at the decision time; expectations cannot be added to pending or rejected proposals.

Each expectation is evaluated against data recorded on the device. The **baseline** is the latest exposure before the decision; the **observation** is the latest exposure inside the window. Both must be in the same unit and basis group and outside deload weeks.

| Outcome | When |
| --- | --- |
| **Pendiente** | The window is still open. Any observation so far is shown as provisional. |
| **Inconclusa** | The window closed without a comparable baseline or observation, or the value is unchanged when an increase was expected. It is never guessed. |
| **Favorable** / **Desfavorable** | Increase: the observation is above / below the baseline. Maintain: it stays within 2.5 % below the baseline, or does not. Reduce: it is below the baseline, or is not. |

You can close an expectation manually as favorable, unfavorable or inconclusive (for example after an injury), with a note. A manual outcome is labelled "manual"; **Automático** returns it to the computed result. The proposals list summarizes all expectations by outcome. Expectations are stored with the decisions in `proposals-v1.json` and validated on restore; each must belong to an approved decision.

## Decisions and traceability

`proposals-v1.json` stores one decision per import ID and per proposal ID and version. Each decision records:
- its status, time, program, block and week;
- the computed changes;
- the agent's stated changes and reasons;
- the warnings accepted.

Decisions also store the explained changes (AG-03). **Historial de decisiones** on the proposals list shows every decision newest first: date, status, proposal and version, program/block/week, each change with its reason and evidence, the agent's reasons, and the number of accepted warnings. A decided proposal shows this record as it was at decision time, followed by its validation under the current rules.

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

### AG-01 (2026-11-06)

- Unit tests: 101 run, 0 failures, 1 skipped. `PlanningQuestionsTest` adds 3 cases:
  - gaps open until answered or marked unavailable, with nothing invented and only answers in the context; blank answers rejected; reopening works;
  - answers producing a valid v1 `TrainingContext`;
  - answer validation, and corrupt files never reset.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `QuestionsDeviceTest`: through the real screen, it answers the phase question and marks restrictions "No disponible". It verifies both in the file read from disk, then checks that the context the export uses carries the phase and the explicit unknown.

### AG-04 (2026-11-06)

- Unit tests: 98 run, 0 failures, 1 skipped. `RecommendationEvaluationTest` adds 2 cases:
  - **Outcomes:** all four are reached, deload weeks are excluded as observations, the manual override and its note are kept, and other metrics are evaluated.
  - **Repository rules:** expectations only for approved recommendations, start at the decision, validation, manual outcomes and persistence.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `EvaluationDeviceTest` adds an expected result to an approved proposal through the real screen. The expectation is in the file read from disk and shows "Pendiente (automático)".

### AG-07 / AG-03 (2026-11-06)

- Unit tests: 96 run, 0 failures, 1 skipped. `ProposalExplainTest` adds 2 cases:
  - classification of increases, reductions, a substitution and a removal, with no invented reasons and "no history" evidence;
  - reasons attached only to the changes they mention, with evidence from a recorded session.
- `ProposalReviewTest` expectations now include the unexplained-change warning, and decisions keep their explanations.
- `assembleDebug` passes. `lintDebug`: 0 errors, 65 warnings.
- `ProposalDeviceTest` now also checks the explained "Aumento · Press ficticio: carga máxima 3 → 4" card and the approved decision in **Historial de decisiones**. It passed, but took 9.5 minutes, mostly scrolling a decision history that grew across repeated emulator runs.

Physical-device testing was not performed.
