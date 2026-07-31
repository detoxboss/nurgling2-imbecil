---
doc_id: combat-unresolved
revision: 2
status: current
last_verified: 2026-07-28
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Open questions and known gaps for the combat reactor, with stable IDs"
---

# Unresolved items

Canonical owner of **open questions**. Resolved items are kept as short tombstones (never deleted) linking to whatever document now owns the final answer.

<a id="unr-001"></a>
## UNR-001 — Sting pointed-weapon eligibility not checked

- **Question:** Should the reactor verify a pointed weapon is equipped before allowing Sting, per [MEC-STING-001](mechanics.md#the-automated-attack-trio)?
- **Why it matters:** Sending Sting with the wrong weapon equipped either fails silently server-side or (worst case) behaves unexpectedly; no weapon-type registry exists anywhere in this client to check against.
- **Present evidence:** No `pointed`/weapon-type registry found in `src/haven` or `src/nurgling` during the original investigation (two `Explore` passes, this session, prior to implementation). The user explicitly chose to skip this check for now, matching historical AHK behavior (`EV-USER-DECISIONS`).
- **What would resolve it:** The user supplying the exact item names/aliases that count as "pointed" for their deck, so a small `NParser.checkName`-style check against `NUtils.getEquipment()` can be added — the same idiom used elsewhere in this codebase for name-based item checks.
- **Affected:** [BEH-STING-002](behavior-contract.md#attack-recommendation-and-manual-trigger), [MEC-STING-001](mechanics.md#the-automated-attack-trio).
- **Status:** Open. **Owner:** user (needs to supply weapon list). **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-002"></a>
## UNR-002 — `CombatOpeningState` does not fully match its own documented 7-state model

- **Question:** Should `CONFIRMED_ZERO` be split into a literal "confirmed zero" state plus a separate "confirmed known value" state, and should `UNKNOWN`/`STALE` actually be produced somewhere?
- **Why it matters:** `CombatOpeningState.CONFIRMED_ZERO`'s javadoc says "positively reports numeric zero," but `CombatStateAdapter` assigns it to every positively-read value regardless of whether it's actually zero. Separately, a `Buff` whose resource name can't yet be resolved is silently treated as "not present" (→ `VALID_NOT_FOUND`, a safe zero) rather than `UNKNOWN`, which could under-report a real nonzero opening during a resource-name-resolution timing window. `STALE` is never produced at all — nothing currently marks an old snapshot as stale before it ages out via the relation-revision mechanism.
- **Present evidence:** `EV-GREP-AUDIT` confirms zero construction sites for `CombatOpeningState.UNKNOWN`/`STALE` and zero call sites checking specifically for a literal-zero-only interpretation of `CONFIRMED_ZERO`. See [VER-STATE-001](verification.md) and [VER-ADAPTER-001](verification.md).
- **What would resolve it:** Either (a) rename `CONFIRMED_ZERO` to something like `CONFIRMED` and add a real zero-specific check only where the truth table actually needs to distinguish "confirmed exactly 0" from "confirmed some other value" (currently nothing does), or (b) leave as-is if a future reviewer confirms no code path ever needs the literal distinction. Also needs a decision on whether `Buff` resource-name-resolution timing gaps are frequent enough in practice to require an explicit `UNKNOWN` branch in `readOpening`.
- **Affected:** [observable-state.md](observable-state.md#important-caveat-confirmed_zero-is-used-generically-not-literally), `CombatStateAdapter.readOpening`.
- **Status:** Open. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-003"></a>
## UNR-003 — Opponent IP (`Relation.oip`) not read by the adapter

- **Question:** Should the reactor read and expose opponent-against-player IP?
- **Why it matters:** No current approved requirement needs it, but it's a natural future rule-builder operand and the underlying field already exists client-side.
- **Present evidence:** `EV-GREP-AUDIT` — zero `.oip` references anywhere in `src/nurgling/combat/**`.
- **What would resolve it:** A product decision on whether any future rule should condition on opponent IP, then a small `CombatStateAdapter` addition.
- **Affected:** [observable-state.md](observable-state.md), [rule-system.md](rule-system.md#5-operands-blocked-by-unavailable-client-state).
- **Status:** Open, low priority. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-004"></a>
## UNR-004 — Held slot is write-only; queued slot (`useb`) is never read

- **Question:** Should `heldSlot`/a new `queuedSlot` field actually be consulted by decision or executor logic, or removed if truly unneeded?
- **Why it matters:** Dead/write-only state is a maintenance hazard — a future contributor may assume `heldSlot` is load-bearing because it's threaded through `CombatSnapshot`'s constructor and equality check, when in fact nothing reads it.
- **Present evidence:** `EV-GREP-AUDIT` — `heldSlot` has no read site outside `CombatSnapshot` itself; `Fightsess.useb` has no read site at all in the combat package.
- **What would resolve it:** Either find a real use (e.g. detecting "an action is already selected/queued by something else" before sending) or delete the dead field/simplify.
- **Affected:** [observable-state.md](observable-state.md), [code-map.md](code-map.md).
- **Status:** Open. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-005"></a>
## UNR-005 — No explicit release/cancel path on disable, relation-change, or combat-end

- **Question:** Does the current synchronous-send design's implicit safety (every `send()` is a self-contained request+release pair) actually cover the brief's explicit cancellation requirement, or is a real gap present?
- **Why it matters:** [BEH-CANCEL-001](behavior-contract.md#lifecycle-and-scope) is an approved requirement. If `releaseAction()` ever throws mid-pair (already caught, logged, and reported as `SENT` with a reason — see `CombatActionExecutor.send`), the underlying `Fightsess.held` state has no other code path that will ever clear it; the widget/session would need a fresh `requestAction` call (from either the reactor or normal keyboard play) to naturally reset it.
- **Present evidence:** Source re-read this session; confirmed via [code-map.md](code-map.md#relation-change-and-combat-end-cancellation) — no `releaseAction()` call exists anywhere outside `CombatActionExecutor.send`'s own pairing.
- **What would resolve it:** Add an explicit release call in `CombatReactorController.tick` (or `NCombatReactor`) triggered on disable-transition and relation-change, independent of whether a `send()` pair completed cleanly.
- **Affected:** [BEH-CANCEL-001](behavior-contract.md#lifecycle-and-scope), [VER-CANCEL-001](verification.md).
- **Status:** Open — **flagged as the highest-priority functional gap found in this audit**, given the user's stated permadeath stakes; a stuck-held action is a state glitch, not a crash, but is still worth closing. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-006"></a>
## UNR-006 — No execution-confirmation signal is consumed

- **Question:** Should the reactor subscribe to `CombatEvents.fireUsed` (or the `Fightview` "used"/"ruse" messages) to upgrade `SENT` to a real "executed" status?
- **Why it matters:** [BEH-EXECCONFIRM-001](behavior-contract.md#execution-confirmation) is an approved requirement; the port brief explicitly warned against treating "sent" as "executed."
- **Present evidence:** `EV-GREP-AUDIT` — zero `CombatEvents` references anywhere in `src/nurgling/combat/**` or `NCombatReactor.java`. This was explicitly planned (`EV-PLAN`) but not implemented.
- **What would resolve it:** Implement `CombatEvents.UsedListener` in `CombatReactorController`, correlate `fireUsed`'s resource identity + relation to the most recent `SENT` request, and add a genuine `EXECUTED`/`unconfirmed` distinction to `CombatActionExecutor.Result`.
- **Affected:** [BEH-EXECCONFIRM-001](behavior-contract.md#execution-confirmation), [rule-system.md](rule-system.md#5-operands-blocked-by-unavailable-client-state).
- **Status:** Open. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-007"></a>
## UNR-007 — Snapshot diffing (`sameDecisionState`) is dead code

- **Question:** Should `CombatReactorController.tick` actually call `CombatSnapshot.sameDecisionState` to skip redundant recomputation, matching the originally stated tick-based-diff design ([ADR-0002](decisions/ADR-0002-tick-diff-observation-model.md)), or should the unused method simply be removed?
- **Why it matters:** Purely a documentation/design-fidelity gap today (recomputing cheap pure logic at ~10 Hz costs nothing meaningful), but it's exactly the kind of "looks load-bearing, isn't" trap that wastes a future reader's time.
- **Present evidence:** `EV-GREP-AUDIT` — zero call sites for `sameDecisionState` anywhere in the codebase.
- **What would resolve it:** Either wire it in (call it each tick, skip `chooseDefence`/executor work when unchanged) or delete it and correct the design docs to say "always recompute, no diffing."
- **Affected:** [code-map.md](code-map.md#snapshot-acquisition-and-tick-frequency), [VER-DIFF-001](verification.md).
- **Status:** Open, cosmetic/low priority. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-008"></a>
## UNR-008 — Manual trigger has no explicit relation-revision equality check

- **Question:** Is reusing the latest tick's `CombatSnapshot` (≤~100 ms old) an acceptable substitute for the port brief's explicit "confirm the recommendation belongs to the same relation revision" step?
- **Why it matters:** In the narrow window between a relation change and the next tick, a manual attack could theoretically fire against a just-stale snapshot. Bounded to ~100 ms by the tick cadence, but not zero.
- **Present evidence:** Source re-read this session; `CombatReactorController.onManualAttackTrigger` uses `lastSnapshot` directly with no revision-equality assertion.
- **What would resolve it:** A product decision on whether the ~100 ms bound is acceptable, or whether an explicit check (re-derive current relation id at trigger time and compare to `lastSnapshot.relationGobId`) should be added.
- **Affected:** [BEH-MANUAL-001](behavior-contract.md#attack-recommendation-and-manual-trigger), [VER-MANUAL-001](verification.md).
- **Status:** Open, low priority given the small bound. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-009"></a>
## UNR-009 — Diagnostics text overlaps multi-session tab-switch buttons

- **Question:** Where should the diagnostics readout be repositioned?
- **Why it matters:** User-reported visual overlap with the top-left multi-session character-tab buttons; raised as a possible (though the user considered it unlikely) contributing factor to the crash investigated this session. The crash's actual root cause was unrelated (see [ADR-0003](decisions/ADR-0003-relation-scoped-fail-closed-state.md)), but the overlap itself is still real and unaddressed.
- **Present evidence:** User report, this session. `NCombatReactor` draws at local `(0,0)` within its `NDraggableWidget` wrapper; no repositioning has been done.
- **What would resolve it:** Either reposition the default draw origin (e.g. bottom-left) or rely on the existing `NDraggableWidget` drag affordance and just tell the user to move it once.
- **Affected:** [code-map.md](code-map.md#diagnostics-rendering).
- **Status:** Open, cosmetic. **Owner:** unassigned. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

## UNR-010 through UNR-013 — Carried-forward mechanics unknowns

Tombstoned here as pointers only; full detail lives in [mechanics.md](mechanics.md#unresolved-mechanics-register-carried-from-r3) to avoid duplication:

- **UNR-010** = [MEC-UNR-001](mechanics.md#unresolved-mechanics-register-carried-from-r3) — passive opening-decay exact rate/timing.
- **UNR-011** = [MEC-UNR-002](mechanics.md#unresolved-mechanics-register-carried-from-r3) — current-server conformance to the nonlinear damage formula.
- **UNR-012** = [MEC-UNR-004](mechanics.md#unresolved-mechanics-register-carried-from-r3) — maximum IP / values above ~91.
- **UNR-013** = [MEC-UNR-005](mechanics.md#unresolved-mechanics-register-carried-from-r3) — agility-based cooldown cap conflict.

<a id="unr-014"></a>
## UNR-014 — In-client manual verification not yet performed against the fixed code

- **Question:** Does the multi-session crash fix (`VER-CRASH-FIX`) and the safety trip switch (`VER-TRIP-SWITCH`) actually hold up under real combat, including the specific tab-switch-mid-fight sequence that caused the original crash?
- **Why it matters:** Every `MANUAL PENDING` row in [verification.md](verification.md) depends on this; per task constraints, the assistant cannot perform or claim in-game verification itself.
- **Present evidence:** None yet — the user's crash report predates the fix; no post-fix in-game session has been reported back.
- **What would resolve it:** The user deliberately repeating the two-session combat + tab-switch scenario and reporting the result.
- **Affected:** Every `MANUAL PENDING` row in [verification.md](verification.md).
- **Status:** Open, **blocks calling any manual-check row "passing."** **Owner:** user. **Opened:** 2026-07-27. **Last reviewed:** 2026-07-27.

<a id="unr-015"></a>
## UNR-015 — Bots-menu icon is a placeholder (borrowed art + wrong tooltip)

- **Question:** When will the "Combat Reactor" bots-menu entry get its own icon art and correct tooltip text?
- **Why it matters:** `resources/src/nurgling/bots/icons/combatreactor/` is currently a byte-for-byte duplicate of `.../combatdist/`'s `.res` files. The menu entry is fully functional (correct click behavior, correct popup), but visually shows Combat Distance Tool's icon and its tooltip ("Combat Distance Tool" / "Manage combat kiting distance") instead of Combat Reactor's own.
- **Present evidence:** Confirmed by direct inspection this session that Haven's bots-menu icon format (`{u,d,h}.res`, each with binary `image_0.data`/`.png` and, for `u.res`, binary `tooltip_N.data` layers) is not safely hand-authorable as plain text — it's normally produced by an external resource-editing tool, not written directly in this Java source tree. `ant jar` compiles the duplicated placeholder without error.
- **What would resolve it:** The user authoring real icon art + tooltip text via their normal resource workflow and replacing the files under `resources/src/nurgling/bots/icons/combatreactor/`.
- **Affected:** [BEH-UI-003](behavior-contract.md#diagnosticsui-approved-minimum), the "Bots-menu icon (placeholder)" row in [code-map.md](code-map.md).
- **Status:** Open, cosmetic only (not a functional defect). **Owner:** user. **Opened:** 2026-07-28. **Last reviewed:** 2026-07-28.

## See also

- [verification.md](verification.md) — status matrix these items feed into.
- [behavior-contract.md](behavior-contract.md) — the requirements these gaps qualify.
- [changes/CHANGELOG.md](changes/CHANGELOG.md) — when each item was opened.
