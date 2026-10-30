# Data model and integrity

Status: proposed design from product definition 1.0; not an implemented database schema.
The complete requirements remain in [product definition section 6](PRODUCT_DEFINITION.md#6-modelo-de-datos-y-reglas-de-integridad).

| Domain | Proposed entities |
| --- | --- |
| Planning | Program, TrainingBlock, WeekPlan, SessionPlan |
| Exercise identity | Exercise, ExerciseVariant, EquipmentProfile |
| Prescription | ExercisePrescription, SetPrescription, CardioPrescription |
| Execution | WorkoutSession, ExercisePerformance, SetPerformance, CardioPerformance |
| Progress | BodyMeasurement, TrainingGoal |
| Proposals and audit | WeekProposal, ValidationIssue, Approval, TrainingDecision, RecommendationEvaluation |

## Required invariants

- Keep prescribed and performed values separate.
- Use stable IDs independent of display names.
- Store load magnitude, unit, and basis (total, per side, per hand, bodyweight, machine setting).
- Distinguish missing, omitted, not performed, and zero values.
- Version weeks and proposals; approval applies only to the reviewed version.
- Keep provenance and rule IDs/versions for validation and decisions.
- Importing the same package twice must not duplicate weeks or sets.

## Proposed state transitions

Proposal: draft/imported → validation → requires correction or ready for review → approved/rejected. Only approved proposals can become active weeks.

Session: planned → in progress → completed/partial/not performed. Deliberate corrections preserve relevant history.

## Implementation boundary

The prototype uses DataStore for a single fixed demo session. Room, schema migrations, the entities above, and portable JSON contracts remain future work. Do not interpret the illustrative JSON in the product definition as an accepted schema.
