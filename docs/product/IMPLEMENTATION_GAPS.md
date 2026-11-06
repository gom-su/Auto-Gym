# Product requirements versus the existing prototype

Assessment date: 2026-10-30. This maps the existing source and verification reports to the broader product definition, updated with the [ENT-01 implementation and current test evidence](../PROGRAM_IMPORT.md#verified-on-2026-10-30).

| Requirement | Existing evidence | Remaining work |
| --- | --- | --- |
| ENT-01 variable plans | Variable library with JSON preview/save, ordered blocks/weeks/days/exercises/sets, stable IDs, independent atomic persistence | Target-phone manual acceptance; see [import contract](../PROGRAM_IMPORT.md). Recording still uses fixed demo |
| ENT-02 JSON exchange | Typed context/proposal/results v1; review/save, copy/file export, independent atomic archive, strict compatibility and round-trip checks; see [contract](../JSON_EXCHANGE.md) | ENT-02 delivery accepted with emulator evidence; physical testing waived by user on 2026-10-30. Proposal activation and real history remain separate requirements |
| ENT-03 Markdown migration | Workout v3 parser; original/source lines, separate objectives/results, ambiguity review and independent atomic offline archive; see [contract](../OBSIDIAN_MIGRATION.md) | Conversion into executable programs/history remains follow-up work; archive does not activate sessions. Delivery acceptance is emulator-only by user selection |
| ENT-04 one-tap overlay | Saved program days load as sessions; overlay/notification record a prefilled set in one tap with immediate persistence; finished/closed sessions archived; see [planned recording](../PLANNED_RECORDING.md) | Target-phone validation of permissions, accessibility and interruptions; emulator-only evidence |
| ENT-05 actual versus planned | Planned sessions keep the prescription in the plan snapshot; omit, add/remove extra and edit recorded sets with notes; stale-edit protection; v1 export as OMITTED/unprescribed; see [planned recording](../PLANNED_RECORDING.md#deviations-ent-05) | Full edit audit trail (only the last edit time is kept); target-phone validation; emulator-only evidence |
| ENT-06 real sessions | Rest timer and early finish | Warmups, cardio, pauses and complete session lifecycle |
| ENT-07 offline history | DataStore session restore, including force-stop evidence | Room history, migrations and recovery across multiple sessions |
| ENT-08 stable exercise identity | Display names in fixed demo | Exercise/variant/equipment IDs independent of labels |
| ENT-09 validation | Reducer bounds/stale commands; program-library IDs, units/bases, idempotency and conflict blocking | Complete validation across proposal/context/results packages and activation |
| ANA-01–05 | No product analytics | Comparable exercise history, charts, body measurements, goals and phase comparisons |
| AG-01–07 | No agent integration | Portable proposal cycle, deterministic progression, explicit approval, explanations and decision outcomes |
| INT-01–03 | No integrations | MCP, authenticated synchronization and advanced trend analysis |

Evidence: [prototype README](../../README.md), [verification report](../VERIFICATION.md), [latency investigation](../NOTIFICATION_LATENCY.md), and source under `app/src/`.

ENT-02 is accepted for its delivery with an explicit emulator-only validation exception. ENT-03 implements the user-selected archival migration/review scope with emulator-only acceptance; executable conversion remains follow-up work. GitHub Issues remain the source of execution status; this local ENT-03 implementation does not change their remote state. Existing prototype evidence is reusable but does not satisfy every full product acceptance criterion. Emulator verification is distinct from target physical-device verification. Recommendation outcome tracking concerns workout decisions, not training, fine-tuning, benchmarking, or evaluating AI models.
