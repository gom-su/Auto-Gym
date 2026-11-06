# 0003: Independent, reviewable Obsidian migration archive

Status: accepted for ENT-03 implementation; emulator-only delivery acceptance selected by the user on 2026-10-30.

The Workout v3 source contains ranges, per-side ambiguity, additional actual series and mixed-purpose load columns. ENT-01 prescriptions and ENT-02 results cannot represent all of this without losing information or inventing associations.

Store a versioned `MigrationDocument` with the exact original, deterministic parsed interpretation, source lines and warnings in an independent DataStore archive. Keep planning and performed values separate; preserve unsupported expressions and explicit attribution instead of inferring them. Review marks warnings seen; it does not correct or approve their interpretation. Source corrections create another document. Reimports use content/parser-derived identity and preserve saved review.

Use the existing Kotlin serialization, DataStore, coroutines and Compose stack without introducing Room or changing public program/exchange JSON. Validation reparses the source to verify stored interpretation and identity; only warning acknowledgement may change. Unsupported archive/parser versions require an explicit future migration, never a reset.

This delivery provides archival migration and offline review, not executable week conversion, history activation or a general Markdown editor. Follow-up conversion must explicitly handle unresolved associations and preserve source traceability. Contract and acceptance: [Obsidian migration](../../OBSIDIAN_MIGRATION.md).
