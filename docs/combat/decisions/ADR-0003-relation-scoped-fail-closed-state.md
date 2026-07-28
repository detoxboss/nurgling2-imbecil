# ADR-0003: Relation-scoped and session-scoped state resolution with fail-closed unknown handling

- Status: Accepted
- Date: 2026-07-27 (extended same-day after a user-reported crash)
- Decision owners: User (fail-closed semantics, from the port brief), Claude Code (session-scoping fix, in direct response to a reported crash)
- Related contract IDs: [BEH-SCOPE-002](../behavior-contract.md#lifecycle-and-scope), [BEH-FAIL-001](../behavior-contract.md#fail-closed-handling), [BEH-UI-002](../behavior-contract.md#diagnosticsui-approved-minimum)
- Related implementation: `src/nurgling/combat/CombatReactorController.java`, `src/nurgling/widgets/NCombatReactor.java`
- Related evidence: [`EV-BRIEF`](../evidence.md), [`EV-CRASH-REPORT`](../evidence.md)

## Context

Two distinct scoping boundaries matter for this reactor: (1) the combat **relation** boundary — IP/openings are per-opponent, and stale data from a previous target must never leak onto a new one; (2) the **session** boundary — this client supports multiple simultaneous logged-in sessions (multi-account play), and this port was specifically requested partly to automate a two-character sparring setup. A reactor instance that resolves the wrong session's `GameUI` is not just wrong, it can act on a widget tree that's about to be (or already was) torn down.

Boundary (1) was designed in from the start, per the port brief. Boundary (2) was **not** originally treated as a distinct risk — until the user hit a real crash mid-session-switch: `NullPointerException` in the pre-existing (not this port's code) `haven.overlays.NTargetFight.tick`, caused by `nurgling.NUtils.getGameUI()` returning `null` momentarily during a session-tab switch, plus `"Uimsg to non-existent widget"` errors consistent with a request built from one session's data landing on a different/torn-down widget tree ([`EV-CRASH-REPORT`](../evidence.md)). `NCombatReactor.tick()` was calling that same global `NUtils.getGameUI()` helper — the identical bug class, in this port's own new code.

## Decision

1. **Relation scoping:** `CombatReactorController` tracks a monotonic `relationRevision`, bumped whenever the current relation's gob id changes; `CombatDecisionEngine` refuses to act (`chooseDefence`/`chooseAttack` both return "none") whenever `relationGobId == null` or any relevant value's `CombatOpeningState` is in an unsafe state ([`BEH-FAIL-001`](../behavior-contract.md#fail-closed-handling)).
2. **Session scoping:** `NCombatReactor.tick()` resolves its own owning `GameUI` via `getparent(GameUI.class)` — walking its own widget ancestry — instead of any global/thread-local lookup. This guarantees a reactor instance only ever acts on the session it structurally belongs to, regardless of which session tab currently has focus.
3. **Defense in depth, added the same day:** every reactor entry point (`tick`, `handleGlobalKey`, `draw`) is wrapped in `catch(Throwable)`, and a 3-consecutive-failure trip switch permanently disables further sending (until the user explicitly re-toggles the enable setting) — see [`BEH-UI-002`](../behavior-contract.md#diagnosticsui-approved-minimum). This does not replace the scoping fix above; it is a second, independent layer in case a *different*, not-yet-found bug has a similar effect.

## Alternatives considered

- **Keep using `NUtils.getGameUI()` and just null-check it.** Rejected — null-checking would have prevented the specific `NullPointerException` but not the deeper problem (a reactor instance silently acting on the wrong session's combat state whenever `getGameUI()` happens to resolve to whatever session is currently focused, which is wrong even when it doesn't crash).
- **A single global reactor instance instead of one per `NGameUI`.** Not considered seriously — would require an entirely different multi-session addressing scheme and contradicts how every other per-session widget in this codebase is structured.

## Consequences

- Positive: a reactor instance is now structurally incapable of acting on a different session's combat state; the specific crash class is fixed at its root, not just its symptom.
- Positive: the trip switch means a *future* undiscovered bug degrades to "reactor stops helping, player still has full manual control" rather than repeating a crash.
- Negative: none identified — `getparent(GameUI.class)` is the same pattern `Fightsess.added()` already used (`parent.getparent(GameUI.class).fv`), so this is not a novel or riskier idiom.
- A second, related latent bug was found and fixed in the same pass: `CombatReactorController`'s cached `CombatActionExecutor` was only ever constructed once, so if the underlying `Fightsess` widget were ever replaced (relogin, reconnect), the reactor would have kept sending to a dead widget — same failure class, different trigger. Now rebuilt whenever `gui.fsess` changes identity.

## Verification

[`VER-CRASH-FIX`](../verification.md) — root cause fixed and build verified; **user has not yet re-run the exact original scenario against the fixed code** ([UNR-014](../unresolved.md#unr-014)). [`VER-TRIP-SWITCH`](../verification.md) — manual code-trace only.

## Supersedes / superseded by

None.
