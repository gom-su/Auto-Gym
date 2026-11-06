# Open product and technical questions

These are unresolved design questions from product definition 1.0, not accepted architecture decisions.

1. Minimum Android version and final division of responsibility between Activity, overlay, and notification. The prototype's minSdk 26 is an implementation fact, not a final product decision.
2. Initial exercise/variant/machine taxonomy and load increments.
3. Complete JSON schemas, compatibility policy, and validation severity.
4. Local backup/restore and eventual cross-device synchronization.
5. Initial deterministic progression rules, deload behavior, and program transitions.
6. Initial charts and minimum comparability conditions.
7. Conversion/correction of partial or inconsistent Markdown imports into executable plans/history. ENT-03's initial archive uses explicit seen/pending review and source-file corrections, as recorded in [decision 0003](../architecture/decisions/0003-obsidian-migration-archive.md).

Resolve a question in its relevant issue. Record accepted architecture decisions in `docs/architecture/decisions/`; link the decision and implementation evidence rather than silently treating a recommendation as approved.
