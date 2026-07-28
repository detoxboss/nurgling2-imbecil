# ADR-0005: Synchronous single-shot send instead of a persistent action queue

- Status: Accepted
- Date: 2026-07-27
- Decision owners: Claude Code (design simplification during implementation), approved by user via full-plan approval
- Related contract IDs: [BEH-MANUAL-001](../behavior-contract.md#attack-recommendation-and-manual-trigger), [BEH-DEF-003](../behavior-contract.md#defense-automatic)
- Related implementation: `src/nurgling/combat/CombatActionExecutor.java`, `src/nurgling/combat/CombatReactorController.java`
- Related evidence: [`EV-PLAN`](../evidence.md), [`EV-BRIEF`](../evidence.md)

## Context

The port brief's §9 ("Action scheduler and client-message behavior") describes a request-queue model with per-request status (`proposed/accepted/sent/queued/executed/failed/cancelled`), priority arbitration between automatic defense and manual attack, and explicit timestamped staleness/cancellation rules — largely carried over from the AHK-era queue design the R3 documents (`EV-DECISIONS-R3`) catalog as having its own defects (LIFO priority-insert staleness, no timestamps, no target-identity binding).

## Decision

Do not build a persistent queue or multi-state lifecycle at all. Instead:

- `CombatActionExecutor.send(move, snapshot)` is a single synchronous call: revalidate against the given snapshot, then request+release immediately, returning only `SENT` or `REJECTED`.
- `CombatReactorController.tick` recomputes `chooseDefence` fresh every tick and tries each candidate in order, stopping at the first `SENT` — there is nothing to "queue" because a rejected candidate (e.g. still on cooldown) is simply retried fresh, from scratch, next tick.
- `onManualAttackTrigger` is likewise a single attempt against the latest snapshot; a rejection is not retried automatically — the user presses the key again.
- Defense and manual attack never explicitly arbitrate priority against each other via a shared lock or queue; because every send is a complete, self-contained request-then-release pair happening synchronously on the UI thread, the two call paths cannot meaningfully interleave mid-action in the first place.

This is a substantial simplification of the brief's originally sketched architecture, made during implementation and accepted by the user as part of the overall plan approval (the plan document itself already described this simplified design before the user approved it — see [`EV-PLAN`](../evidence.md)).

## Alternatives considered

- **Build the full request-queue model from the brief** (six-state lifecycle, explicit priority arbitration, staleness timestamps). Not built — judged unnecessary complexity given the synchronous single-shot design already satisfies the practical requirement (never overlap held actions, prioritize defense's latency) without needing to track in-flight state, since nothing is ever "in flight" for more than one synchronous call.

## Consequences

- Positive: no queue-staleness class of bug is possible, because nothing is ever queued — eliminates an entire category of the AHK-era defects (H2, H9 in `EV-DECISIONS-R3`) by construction rather than by careful timestamp/revalidation logic.
- Positive: green/blue tie revalidation ([BEH-DEF-003](../behavior-contract.md#defense-automatic)) falls out for free — see [ADR-0002](ADR-0002-tick-diff-observation-model.md) and [code-map.md](../code-map.md#defense-priority-and-tie-flow).
- Negative (accepted, documented as a variance): the brief's manual-attack retry semantics ("retain at most one pending manual attack... using newest valid recommendation") are not implemented — a rejected manual trigger is simply lost, not retried. See [BEH-MANUAL-001](../behavior-contract.md#attack-recommendation-and-manual-trigger)'s variance note.
- Negative (accepted, documented as a gap): without an explicit lifecycle, there is also no explicit place to hook a "cancel on disable/relation-change" step — this is exactly why [UNR-005](../unresolved.md#unr-005) exists as an open gap rather than being automatically solved by "no queue to clean up."

## Verification

[`VER-MANUAL-001`](../verification.md), [`VER-DEFENSE-TIES`](../verification.md) — both manual code-trace only.

## Supersedes / superseded by

Supersedes the AHK-era queue design described in `EV-DECISIONS-R3` (not a code supersession — no Java queue implementation ever existed to replace; this records that the brief's own queue sketch was deliberately not carried into the Java port).
