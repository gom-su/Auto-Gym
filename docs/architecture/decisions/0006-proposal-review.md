# 0006: Proposals are reviewed by pure rules and applied only by explicit approval

Status: accepted for AG-02, AG-05 and AG-06; emulator evidence only.

Imported `WeekProposal` packages were archived but could not be validated, compared or applied. Following the architectural rule (AI proposes, deterministic engine validates, user approves, Kotlin persists):

- `ProposalRules.evaluate` is a pure function of the proposal, the saved library, known exercise IDs and the rule settings.
  - Integrity rules always block.
  - Progression and coherence rules have user-configurable severity and thresholds.
  - The base week is the last saved week of the target block; comparisons use stable exercise IDs.
- Decisions live in an independent `proposals-v1.json`, together with the rule settings: one per import ID and per proposal version, and final once made. They store the computed changes, the agent's stated changes and reasons, and the accepted warnings, for retrospective review.
- Approval re-validates, then appends the week through the existing idempotent program merge (ENT-09), so it can never replace, reorder or duplicate saved weeks, before recording the decision. The two writes are not one transaction. An identical, already appended week is detected, and only the decision is recorded.
- No proposal activates a session; approved weeks are trained through the normal **Entrenar este día** flow.

Replacing a saved week (same ID) is intentionally not supported: a revision must use a new week ID. See [proposal review](../../PROPOSAL_REVIEW.md).
