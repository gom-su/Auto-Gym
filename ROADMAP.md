# Auto-Gym roadmap

Auto-Gym is evolving from a fixed offline workout notification/overlay proof of concept into a personal Kotlin training application. This roadmap describes planned work, not shipped functionality or committed delivery dates.

## Delivery phases

| Milestone | Scope | Requirement IDs |
| --- | --- | --- |
| MVP | Variable plans, portable JSON, Markdown migration, recording, offline history, identity and validation | ENT-01–ENT-09 |
| V1 | Analytics and the external-agent proposal/review cycle | ANA-01–ANA-05; AG-01–AG-07 |
| V2 | MCP, authenticated synchronization and advanced trends | INT-01–INT-03 |

## Suggested implementation sequence

| Iteration | Demonstrable result | Dependency |
| --- | --- | --- |
| 0 · Base | Domain model, versioned JSON, import tests and Room | Agree data contracts |
| 1 · Recording | Active week, prefilled sets, overlay and offline persistence | Base |
| 2 · History | Exercise results, cardio and session status | Recording |
| 3 · AI cycle | Export context, import, validate, compare and explicitly approve | Base through history |
| 4 · Analytics | Longitudinal charts, body composition and adherence | Comparable history |
| 5 · Learning from training decisions | Traceable decisions and later workout outcome review | Proposal cycle and analytics |
| 6 · MCP | Authorized tools and synchronization | Stable contracts and rules |

These iterations preserve the source document's suggested sequence; they do not redefine the MVP/V1/V2 milestone scope. Analytics and body-composition progress retain equal product importance.

## Planning and completion

The [GitHub issues](https://github.com/gom-su/Auto-Gym/issues) hold acceptance criteria and execution status. See the [backlog](docs/product/BACKLOG.md), [full product definition](docs/product/PRODUCT_DEFINITION.md), [implementation gaps](docs/product/IMPLEMENTATION_GAPS.md), and [open decisions](docs/product/OPEN_QUESTIONS.md).

A feature is done only when its behavior, validation, error handling, relevant automated checks, persistence/compatibility, understandable UI text, and manual verification on the target Android device are complete. Existing emulator reports do not establish physical-device validation.

No fabricated effort totals, usage claims, contribution history or delivery dates are included. The backlog describes substantial remaining work; estimates can be added after task breakdown.
