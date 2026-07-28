# ADR-0006: Flat `ALL`/`ANY` schema for a future configurable rule system

- Status: **Proposed** (not approved — do not implement against this ADR without first getting explicit user approval)
- Date: 2026-07-27
- Decision owners: none yet — schema originates from this documentation task's own instructions, not a user product request
- Related contract IDs: none (no approved requirement exists for a configurable rule system at all — see [rule-system.md](../rule-system.md))
- Related implementation: none (nothing in `src/nurgling/combat/**` implements this)
- Related evidence: this documentation task's own generating instructions (not separately registered as an `EV-*` evidence ID, since it is process/tasking, not a mechanics/behavior source)

## Context

A future configurable combat-rule builder is a plausible next step after this reactor (letting a user define conditions/actions without editing Java), but **no such feature has been requested or approved**. This ADR exists only to record a considered *default* schema shape, so that if/when the feature is approved, there is a documented starting point rather than an ad hoc reinvention — and so that no future session mistakes "a schema was written down somewhere" for "the user asked for this."

## Decision (proposed, not adopted)

A flat rule/condition model:

```text
Rule { id, enabled, priority, action, matchMode: ALL | ANY, conditions[] }
Condition { operand, operator (== != < <= > >=), numericValue }
```

No nested boolean groups (`(A AND B) OR C`) until a concrete rule is shown to need one. See [rule-system.md](../rule-system.md) for the full undesigned-behavior list (evaluation timing, priority/conflict resolution, validation, serialization versioning, etc.) — none of it is decided by this ADR.

## Alternatives considered

Not evaluated in depth, since no approval exists yet to build against. Nested expression trees, a full DSL, and a visual node-graph editor are all plausible future alternatives that would need their own comparison once the feature is actually scoped.

## Consequences

If adopted later: a small, easy-to-validate schema that covers the current hardcoded truth table's shape, at the cost of not supporting compound boolean expressions without a schema migration.

If never adopted: no cost — nothing depends on this ADR today.

## Verification

Not applicable — nothing to verify; no implementation exists.

## Supersedes / superseded by

None. Do not mark this `Accepted` without a recorded user approval evidence entry in [evidence.md](../evidence.md), per this document set's own authority rules.
