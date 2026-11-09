# Multi-week plans (periodization)

A **plan** (`PlanOutline`) is the general structure of a training cycle, typically 12 weeks:
- its goals and rules;
- the day distribution (A–D) with its exercises;
- its blocks;
- one entry per week: block, phase, microcycle, deload flag, set/rep/RIR targets and cardio.

A plan holds no loads and no results. Each concrete week is still a `WeekProposal` that the agent writes and you approve. The plan tells the agent **which week comes next and what that week must achieve**.

The AI proposes, the deterministic rules validate, the user approves. That applies twice: once to the plan, then to every week.

## Cycle

1. **Create the plan once.** Give the agent your periodization document and the [plan prompt](AGENT_PROMPTS.md#1-crear-el-plan-de-12-semanas). It writes a `PlanOutline`: either with the MCP tool `create_plan_outline` or as a pasted v1 JSON package.
2. **Import and activate it.** Import the file in **Agente > Intercambio JSON** (signed envelopes are accepted too). Open **Agente > Planes de periodización**, review the weeks and day templates, and tap **Activar plan**. Activating creates no weeks and changes nothing in the program. Only one plan is active at a time: activating another program's plan keeps the previous one as **Inactivo**, and you can activate it again later. Hoy follows the active plan.
3. **Each week:**
   1. Export the context (**Intercambio JSON > Preparar contexto local**). Besides programs and history, it now contains `outlines` and `nextWeeks`: for each active plan, the week to plan and its targets.
   2. The agent follows the [weekly prompt](AGENT_PROMPTS.md#2-generar-la-semana-siguiente). It reads `get_next_week_brief` (or `nextWeeks` without MCP) and proposes exactly that week.
   3. Review and approve it in **Propuestas de semana**. Approving week 1 creates the program. Approving a block's first week creates that block.
4. After week 12 has real records, the brief says `CYCLE_COMPLETE`. No week 13 is accepted; the cycle needs an evaluation, and a new cycle needs a new plan.

## Week numbering and the next-week brief

Week N of the plan is the N-th week of the program: blocks in order, weeks in order. Because weeks are only appended (ENT-09) and each must go into the plan's block for its number, numbering never shifts.

`PlanOutlines.brief` computes the brief deterministically from the plan, the saved program and the typed session results:

| Field | Meaning |
| --- | --- |
| `referenceWeekNumber` | Highest week with real records. A session counts only if it is completed or partial and has a completed or partial set or cardio entry. Declared "not performed" or empty sessions do not count. `null` when nothing was recorded. |
| `referenceSessionIds`, `referenceDaysWithoutData` | The recorded sessions of that week (IDs in the context history), and its days without records. |
| `weekNumber` | `referenceWeekNumber + 1` (1 when nothing was recorded). |
| `status` | `READY`: plan `weekNumber` now. `ALREADY_PLANNED`: that week is already in the program; record it first. `CYCLE_COMPLETE`: the last week has records; no new week. |
| `blockId`, `blockName`, `weekInBlock`, `weeksInBlock`, `target` | The plan entry for `weekNumber`: block, position in block, phase, microcycle, deload, targets, cardio. |
| `message` | The same in one Spanish sentence, shown in the app. |

## PlanOutline v1 contract

The payload goes in an interchange v1 package: `type: "PlanOutline"`, `outline: {...}`, with `context`, `proposal` and `results` null. The policy is as strict as the rest of [JSON exchange](JSON_EXCHANGE.md): unknown fields are rejected and every rule below is checked before saving.

| Object | Fields |
| --- | --- |
| Plan | `id`, positive `version`, `programId`, `programName`, `name`, `goals[]`, `rules[]`, non-empty `days[]`, `blocks[]` and `weeks[]` |
| Day template | `id` (for example `A`), `name`, optional `focus`, non-empty `exercises[]` |
| Template exercise | `exerciseId` (stable catalog ID, unique within the day), `name`, optional `sets` (1–50) and `notes` |
| Block | `id` (becomes the program's block ID), `name`, optional `focus` |
| Week | `number`, `blockId`, `phase`, `microcycle`, `deload`, `targets[]`, optional `cardio` and `notes` |
| Target | `label`, optional `exerciseIds` (empty means every exercise without a more specific target), `sets`, `repsMin`/`repsMax` (1–1000), `rirMin`/`rirMax` (0–10) |
| Cardio | `modality`, optional `minutes` (1–600), `sessions` (1–14), `rpeMin`/`rpeMax` (1–10), `notes` |

Rules:
- **Weeks:** 1–52, numbered 1..N in order without gaps.
- **Blocks:** each block's weeks are consecutive. Blocks are listed in the order they occur, and every block has at least one week.
- **Targets:** a target may only name exercises from the day templates, and no two targets of a week may cover the same exercise.
- **Ranges:** every minimum is at most its maximum.
- **Text:** names up to 200 characters; rules, goals and notes up to 10,000.

The fictitious example is [`plan-outline-v1.json`](../app/src/main/assets/examples/plan-outline-v1.json): 3 blocks × 4 weeks, with deloads at 4, 8 and 12.

## Activation

Activated plans are stored in `plan-outlines-v1.json`, one active plan per program.
- **Identical re-activation:** a no-op.
- **Newer version of the same plan ID:** replaces it; earlier versions are kept as replaced.
- **Another plan ID for the same program:** refused.
- **Plan that disagrees with saved weeks:** refused, for example a saved week in a different block, or more saved weeks than the plan has.

## Plan-aware proposal rules

When the proposal's program has an active plan, [proposal review](PROPOSAL_REVIEW.md) adds these checks against the plan week the proposal would become:

| Rule | Default |
| --- | --- |
| Week goes in the plan's block for its number (`OUTLINE_BLOCK`); no week beyond the plan (`OUTLINE_COMPLETE`); saved weeks agree with the plan (`OUTLINE_PROGRAM`) | **Block** (not configurable) |
| Previous week has no real records (`OUTLINE_NO_DATA`) | Warning |
| Reps outside the target range, RIR below the target minimum, more sets than the target (`OUTLINE_REPS`, `OUTLINE_RIR`, `OUTLINE_SETS`) | Warning |
| Exercise not in the day templates (`OUTLINE_EXERCISE`) | Warning |
| Deload week with more total sets than the base week (`OUTLINE_DELOAD`) | Warning |

The warnings share one setting, **Fuera de los objetivos del plan**, which can be set to off, warning or block. With a plan, the **base week** for comparisons is the previous week of the plan, skipping deloads, so week 5 is compared with week 3, not with the deload.

Cardio is prescribed by the plan week. `WeekPlan` v1 has no cardio field, so the agent states cardio adjustments in `reasons`.

## MCP tools

- **`create_plan_outline`:** writes a `PlanOutline` package to the outbox. It applies the same validation, and an active plan can only be revised with the same ID and a higher version.
- **`get_next_week_brief(programId)`:** returns the brief together with:
  - the plan's goals, rules, days and blocks;
  - the reference week with its recorded sessions;
  - the week before it, for trends.
- **`create_week_proposal`:** for a program with a plan, it accepts only a brief with status `READY` and the brief's block. The program and block may not exist yet; approval creates them.

## Verification (2026-11-07)

- `testDebugUnitTest`: 139 tests, 0 failures, 1 skipped. `PlanOutlineTest` (9 tests) covers:
  - the fixture;
  - invalid plans;
  - a full 12-week walk where each week is proposed, rule-checked, applied and trained, ending in `CYCLE_COMPLETE` with week 13 blocked;
  - real-data reference weeks;
  - every plan rule, and the deload base week;
  - context round-trip;
  - versioned activation;
  - approval of week 1 creating the program;
  - decoding the plan written by the MCP server.
- `python -m unittest discover -s mcp/tests`: 13 tests pass. The brief shipped in `mcp/examples/training-context-with-plan.json` is asserted equal to the app's computation.
- Not yet run: emulator device tests for the new **Planes de periodización** screen.
