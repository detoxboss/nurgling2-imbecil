# ADR-0004: Dispatch through the existing `Fightsess`/`NFightsess` protocol path

- Status: Accepted
- Date: 2026-07-27
- Decision owners: User (approved via plan review), implemented by Claude Code
- Related contract IDs: [MEC-CD-002](../mechanics.md#cooldowns-and-action-lifecycle)
- Related implementation: `src/haven/Fightsess.java` (`requestUse`, `releaseHeld`), `src/haven/NFightsess.java` (`requestAction`, `releaseAction`), `src/nurgling/combat/CombatActionExecutor.java`
- Related evidence: [`EV-SRC-FIGHTSESS-DIFF`](../evidence.md), [`EV-SRC-NFIGHTSESS-DIFF`](../evidence.md)

## Context

Normal keyboard combat input in `Fightsess.globtype`/`keyup` builds and sends a specific `use`→(eventually)`rel` `wdgmsg` sequence, tracking a single `held` slot and a `holdgrab` key-grab, with the `rel` message deliberately fenced through the render/environment queue (`Release` inner class) rather than sent immediately — the existing code comment calls this "a bit ugly, but release messages do need to be properly sequenced with use messages in some way." The reactor needs to send the exact same kind of request.

## Decision

Extract the existing inline logic from `Fightsess.globtype`'s key-hit branch and `Fightsess.keyup` into two `protected` methods, `requestUse(int slot)` and `releaseHeld()`, with `globtype`/`keyup` now calling them instead of containing the logic inline. This is a pure extract-method refactor — the bodies are unchanged, only relocated and parameterized (the hardcoded loop variable `n`/`fn` becomes the `slot` parameter). `NFightsess` (nurgling's own subclass, already the registered `fsess` widget factory target) then exposes `public void requestAction(int slot)` / `public void releaseAction()` as thin public delegates, since `nurgling.combat` lives in a different package and cannot call `protected` members of a `haven`-package class directly.

`CombatActionExecutor.send` calls `requestAction` immediately followed by `releaseAction` — mimicking an instantaneous keyboard tap (no "hold duration" concept exists for the reactor, unlike a physical key-hold), while reusing the exact same `Release`-fenced protocol sequencing real input relies on.

## Alternatives considered

- **Reimplement the `use`/`rel` sequence independently in `nurgling.combat`.** Rejected — this is precisely the kind of protocol-sequencing subtlety (the render-queue fencing comment above) that's easy to get subtly wrong from outside, and doubling the code path doubles the chance of the two diverging on a future upstream merge.
- **Call `Fightsess.globtype` directly with a synthesized `GlobKeyEvent`.** Rejected — synthesizing a fake AWT key event to drive UI dispatch is exactly the kind of "simulate unrelated OS keystrokes" approach the port brief explicitly said to avoid (§4.2: "Preserve that ordering rather than simulating unrelated OS keystrokes").

## Consequences

- Positive: exactly one shared code path for the `use`/`rel` sequence, for both real keyboard input and the reactor; a future upstream merge that changes this sequencing only needs to be reconciled once.
- Positive: the diff is small and mechanical (45 insertions / 26 deletions, confirmed by [`EV-SRC-FIGHTSESS-DIFF`](../evidence.md)) and was re-read byte-for-byte this session specifically to confirm no behavior change for normal keyboard play — see [`VER-KEYBOARD-REGRESSION`](../verification.md).
- Negative: this is the **one** change to a file (`Fightsess.java`) that also matters for every future upstream `hafen` merge (`CLAUDE.md`'s 107-file nurgling-modified list). Kept deliberately minimal (extract-method only, no new logic) to limit that ongoing cost.
- Negative (accepted): `requestAction`/`releaseAction` reuse `Fightsess`'s single `held`/`holdgrab` state, meaning the reactor and real keyboard input share one "currently held action" slot. This is correct per [MEC-CD-002](../mechanics.md#cooldowns-and-action-lifecycle) (the client only ever holds one action at a time regardless of source) but means a bug in one path's release logic can affect the other's held-state bookkeeping — see [UNR-005](../unresolved.md#unr-005) for the specific known gap this creates (no explicit release-on-disable path).

## Verification

[`VER-KEYBOARD-REGRESSION`](../verification.md), [`VER-HELD-RELEASE`](../verification.md) — both manual diff/code-trace only.

## Supersedes / superseded by

None.
