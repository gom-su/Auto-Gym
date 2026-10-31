# 0002 — Versioned context, proposal and results interchange

Date: 2026-10-30. Implementation decision for ENT-02.

Use a discriminated ExchangePackage v1 with exactly one typed payload and a stable importId. Reuse the implemented ENT-01 planning hierarchy and explicit load units/basis. Reject unknown data rather than discarding it. Preserve performed and prescribed data separately and preserve legacy demo provenance instead of inferring missing load basis or exercise identity.

Persist reviewed exchanges atomically in an independent versioned archive. Imports never approve/activate a proposal, overwrite a library plan or restore executable timer state. An archive is the portable boundary for future proposal/history consumers; it does not implement those workflows. Same-ID identical imports are idempotent; same-ID conflicts require a new source identity and cannot replace content implicitly.

Context generation exports only locally available training data. Body measurements in imported context require explicit selection for subsequent export/copy. Redaction changes package identity deterministically. Android Storage Access Framework handles file access without broad storage permission.

See [contract, compatibility and acceptance procedure](../../JSON_EXCHANGE.md). The illustrative product JSON is not an accepted legacy schema; unsupported versions require migration rather than reset.
