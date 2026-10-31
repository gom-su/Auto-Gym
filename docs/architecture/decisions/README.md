# Architecture decisions

Product definition 1.0 confirms the authority boundary: the external agent proposes, deterministic domain rules validate, the user approves, and Kotlin executes and persists. The local Android application is the source of truth. JSON exchange comes before MCP.

The suggested module layout, exact schemas, and detailed progression policies are proposals pending design. The prototype's current DataStore storage does not establish the planned Room architecture.

For each future accepted technical decision, add a numbered Markdown file with context, decision, status, consequences, linked issue, and implementation evidence. Supersede decisions explicitly and preserve their history.

- [0001 — ENT-01 variable program library](0001-variable-program-library.md): implemented ordered aggregate, strict import and independent DataStore storage; relational history remains future work.
