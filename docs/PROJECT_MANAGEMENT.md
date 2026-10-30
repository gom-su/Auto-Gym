# Managing Auto-Gym in GitHub

Use the [Auto-Gym repository](https://github.com/gom-su/Auto-Gym) and [product development Project](https://github.com/users/gom-su/projects/1). Requirements have stable source IDs; Issues hold work status, pull requests hold implementation review, and versioned Markdown holds product and architecture context.

## Workflow

Backlog → Ready → In progress → Review → Validation → Done.

Move an issue to Ready only after required design decisions and dependencies are resolved. Use a `blocked` label for blockers. A merged pull request does not automatically establish target-device validation. Split a parent requirement into linked sub-issues when implementation work is defined; do not create empty tasks simply to inflate activity.

## Classification

- Milestone: MVP, V1, or V2.
- Area label: training, analytics, planning, or integration.
- Priority: initial phase ordering is inherited from the source; finer priorities remain planning decisions.
- Delivery iteration: the suggested sequence in [ROADMAP](../ROADMAP.md), with dates unset until agreed.

Board, backlog table, and roadmap should display the same issues. Phase and area are represented by milestones and labels to avoid maintaining duplicate metadata. Dependencies are proposals until confirmed during implementation planning.

## Pull requests

Reference the requirement issue, describe acceptance criteria covered, and attach relevant test/device evidence. A parent issue closes only when its complete acceptance criteria and definition of done are met. Preserve normal commits and authorship; do not rewrite history or manufacture activity.

## Public and local files

Publish source code, build instructions, license, product requirements, synthetic examples, and reviewed non-personal test evidence. Keep original private documents, submission instructions, real Markdown workouts, exports, body measurements, database files, credentials, signing keys, SDK/JDK installations, build output, and raw audit reports locally under ignored paths.

Use `.local/`, `private/`, or `personal-data/` for private material. `.gitignore` does not remove already tracked files or purge history. The existing checked-in screenshots/reports relate to the fixed synthetic demo; future evidence must be reviewed before publication. Never upload real workout or body data as a test fixture.

## Sources and status

The public product definition is derived from the locally retained source document version 1.0, dated 2026-10-30. The source-derived JSON illustration is replaced with explicitly fictitious data. Original DOCX metadata and submission instructions are not published. No application, certification, ownership attestation or third-party eligibility approval is implied by this repository setup.
