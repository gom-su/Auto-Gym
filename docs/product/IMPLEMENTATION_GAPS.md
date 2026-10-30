# Product requirements versus the existing prototype

Assessment date: 2026-10-30. This maps the existing source and checked-in verification reports to the broader product definition. No Android tests were rerun for this documentation change.

| Requirement | Existing evidence | Remaining work |
| --- | --- | --- |
| ENT-01 variable plans | Fixed demo: 3 exercises, 7 sets | Programs, blocks, variable weeks and days |
| ENT-02 JSON exchange | Internal DataStore snapshot | Versioned public contracts, review, import/export and round-trip compatibility |
| ENT-03 Markdown migration | None in prototype | Parser, ambiguity review, synthetic public fixtures and private local validation |
| ENT-04 one-tap overlay | WindowManager controls and emulator UI test | Connect to real variable plans; validate target phone, permissions, accessibility and interruptions |
| ENT-05 actual versus planned | Weight, reps, RIR editing and undo | Full independent prescription/execution, notes, add/omit/edit sets and audit |
| ENT-06 real sessions | Rest timer and early finish | Warmups, cardio, pauses and complete session lifecycle |
| ENT-07 offline history | DataStore session restore, including force-stop evidence | Room history, migrations and recovery across multiple sessions |
| ENT-08 stable exercise identity | Display names in fixed demo | Exercise/variant/equipment IDs independent of labels |
| ENT-09 validation | Reducer bounds and stale/duplicate command protection | Units/bases, package identity, import idempotency and conflict handling |
| ANA-01–05 | No product analytics | Comparable exercise history, charts, body measurements, goals and phase comparisons |
| AG-01–07 | No agent integration | Portable proposal cycle, deterministic progression, explicit approval, explanations and decision outcomes |
| INT-01–03 | No integrations | MCP, authenticated synchronization and advanced trend analysis |

Evidence: [prototype README](../../README.md), [verification report](../VERIFICATION.md), [latency investigation](../NOTIFICATION_LATENCY.md), and source under `app/src/`.

All 24 product requirements remain open. Existing prototype evidence is reusable but does not satisfy each full product acceptance criterion. Emulator verification is distinct from target physical-device verification. Recommendation outcome tracking concerns workout decisions, not training, fine-tuning, benchmarking, or evaluating AI models.
