# Architecture decisions

Product definition 1.0 confirms the authority boundary: the external agent proposes, deterministic domain rules validate, the user approves, and Kotlin executes and persists. The local Android application is the source of truth. JSON exchange comes before MCP.

The suggested module layout, exact schemas, and detailed progression policies are proposals pending design. The prototype's current DataStore storage does not establish the planned Room architecture.

For each future accepted technical decision, add a numbered Markdown file with context, decision, status, consequences, linked issue, and implementation evidence. Supersede decisions explicitly and preserve their history.

- [0001 — ENT-01 variable program library](0001-variable-program-library.md): implemented ordered aggregate, strict import and independent DataStore storage; relational history remains future work.

- [0002 — JSON interchange](0002-json-interchange.md): strict typed packages, independent archive, privacy and idempotency.

- [0005 — Offline persistence](0005-offline-persistence.md): atomic write-before-publish DataStore snapshots, no network permission; Room deferred to queryable history.

- [0004 — Planned-day sessions](0004-planned-day-sessions.md): sessions carry their own plan snapshot; archive-before-replace history; frozen v1 demo snapshot.

- [0003 — Obsidian migration archive](0003-obsidian-migration-archive.md): original-preserving Workout v3 parsing, explicit uncertainty and offline review independent of executable programs/history.
