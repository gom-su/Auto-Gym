# 0004: Sessions carry their own plan snapshot

Status: accepted for ENT-04 implementation; emulator-only evidence.

Recording was bound to a hard-coded demo plan, so the overlay and notification could not record imported program days. Store the executable plan inside the session (`WorkoutState.plan`, `SessionSource`); an empty plan means the built-in demo, which keeps pre-ENT-04 `workout.json` readable without a migration. Session identity, revision-checked commands and the single atomic DataStore snapshot are unchanged, so one-tap completion keeps its stale-tap protection and write-before-publish durability.

Prefills come only from the prescription: unstated repetitions start at 0, unstated RIR stays unrecorded, and loads keep their own unit without conversion. Records keep the prescription ID; the prescription itself remains in the session's plan snapshot.

Loading a day never replaces a session in progress. Sessions with confirmed sets are archived to an independent, idempotent `history-v1.json` before the live snapshot is replaced. The two writes are not one transaction; idempotency by session ID makes the crash window safe instead. Room remains future work.

The interchange v1 `demo` snapshot was the raw `WorkoutState`. It is now frozen as `DemoStateV1`, so that recording-model changes cannot alter the published v1 contract. Planned sessions use the existing typed `SessionResult` instead. Details: [planned recording](../../PLANNED_RECORDING.md).
