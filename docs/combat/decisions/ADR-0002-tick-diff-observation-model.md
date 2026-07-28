# ADR-0002: Tick-based observation model instead of haven message-level push hooks

- Status: Accepted
- Date: 2026-07-27
- Decision owners: User (explicit choice via `AskUserQuestion`, this session, before implementation), implemented by Claude Code
- Related contract IDs: [BEH-SCOPE-001](../behavior-contract.md#lifecycle-and-scope)
- Related implementation: `src/nurgling/widgets/NCombatReactor.java` (`tick`), `src/nurgling/combat/CombatReactorController.java` (`tick`)
- Related evidence: [`EV-USER-DECISIONS`](../evidence.md), [`EV-PLAN`](../evidence.md)

## Context

The reactor needs to notice opening/IP/cooldown changes promptly. Two designs were considered before writing any code: (a) instrument `Fightview.java`/`Fightsess.java`'s `uimsg` handlers with small push-event hooks (extending the existing but unused `nurgling.plugins.CombatEvents` seam) so state changes fire immediately, or (b) poll from a widget's own `tick(dt)`, throttled to a modest cadence, diffing against the last snapshot to avoid redundant recomputation.

Option (a) is more truly "event-driven" and touches zero additional haven-file surface for buff-level changes (openings decay passively without any server message at all, per [MEC-OPEN-003](../mechanics.md#openings), so even a push-hook design would still need a periodic reconciliation tick as a safety net per the port brief's own §6.1). Option (b) requires no `Fightview.java`/`Fightsess.java` uimsg edits at all for this part, at the cost of up to one tick's latency (~100 ms).

## Decision

Poll via `Widget.tick(dt)` at a ~100 ms (10 Hz) cadence, throttled inside `NCombatReactor.tick`. The user was asked directly and chose this ("Tick-based diff polling (Recommended)") over touching `Fightview.java`/`Fightsess.java`'s message handlers for push events, explicitly weighing it against the brief's own framing that internal events "do not need wasteful 30-60Hz image scanning" — 10 Hz pure-logic polling is not in that category.

## Alternatives considered

- **Push hooks in `Fightview.java`/`Fightsess.java` uimsg handlers.** Rejected by explicit user choice — small additional surface on two files that already carry nurgling modifications and matter for every upstream `hafen` merge (see [`CLAUDE.md`](../../../CLAUDE.md)'s integration guidance), for a benefit (near-zero latency vs ~100 ms) the user judged not worth it here.
- **Even faster polling (e.g. 60 Hz, matching the AHK target).** Not proposed — nothing in the approved requirements calls for combat-reactor decisions faster than roughly human reaction time, and 10 Hz is already an order of magnitude cheaper than the AHK script's own 30 Hz pixel-scan target.

## Consequences

- Positive: zero additional edits to `Fightview.java`/`Fightsess.java` for this part (the only edits to those files are the unrelated, much smaller [ADR-0004](ADR-0004-dispatch-through-fightsess-protocol-path.md) extraction); bounded, predictable worst-case latency (~100 ms) regardless of server message volume.
- Negative: up to ~100 ms of staleness between a real state change and the reactor noticing it — acceptable per the user's explicit choice, but worth remembering when reasoning about [UNR-008](../unresolved.md#unr-008)'s relation-revision timing question.
- **Known gap, not grounds to reverse this decision:** the snapshot-diffing half of this design (`CombatSnapshot.sameDecisionState`, meant to skip redundant recomputation between ticks) was written but is never actually called — see [UNR-007](../unresolved.md#unr-007). The *cadence* decision this ADR records is still faithfully implemented; only the *diff-skip optimization* is dead code.

## Verification

[`VER-DIFF-001`](../verification.md) — confirms the cadence gate exists and the diff method is unused; [`VER-BUILD`](../verification.md).

## Supersedes / superseded by

None.
