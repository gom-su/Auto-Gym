# ENT-01: variable program library

Implementation decision, 2026-10-30. Requirement: [ENT-01 / issue 1](https://github.com/gom-su/Auto-Gym/issues/1).

Store a program as an ordered aggregate: program → blocks → versioned weeks → days → exercise prescriptions → individual set prescriptions. Each level has a stable ID; exercise identity (`exerciseId`) is separate from a particular occurrence (`id`). Names and list positions are not identities. IDs of owned entities are unique inside a program; exercise references may repeat across days and weeks. Separate programs have separate ID namespaces.

The first contract is `ProgramPackage`, schema version 1. It is a prescription library import, not a `WeekProposal` or approval/activation protocol. Review and explicitly save the entire imported program. No imported week becomes active. The original demo continues to use its existing fixed prescription and session storage; connecting variable plans to recording remains later work.

Use an independent atomic DataStore file `programs-v1.json` for this small aggregate library, reusing the installed persistence technology. Do not migrate or clear `workout.json`. Room and relational execution/history storage remain planned under ENT-07; this decision does not replace that future design. Strict schema version and field validation prevents silently reading a newer contract and discarding its data. No destructive corruption handler is installed.

The repository serializes operations, writes before publishing, treats an identical program ID/content as already imported, and blocks differing content under an existing ID. Updating/replacing an existing program is intentionally a later review/versioning operation. Import failure leaves saved content intact. All data in the public fixture is fictitious.

The file picker reads at most 1 MiB plus one byte on an IO dispatcher. Parsing and validation run away from the main thread. Missing optional values remain null, while explicit load, RIR and rest zeros remain zero. Ranges in this first contract are representation limits, not progression rules or exercise recommendations.

See [contract and manual acceptance](../../PROGRAM_IMPORT.md). Target-phone acceptance remains distinct from emulator evidence.
