---
doc_id: combat-code-map
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Current implementation topology of the combat reactor: classes, methods, responsibilities, and data flow"
---

# Code map

Canonical owner of **current implementation topology**. Class/method names are cited instead of line numbers (line numbers rot; the plan and prior chat used them, this file deliberately does not). All paths below were verified to exist against `HEAD 9d7404fa0 + uncommitted worktree` on 2026-07-27.

## Components

| Component | Exact path | Class/method | Responsibility | Reads | Sends/writes | Contract IDs | Verification |
|---|---|---|---|---|---|---|---|
| Opening/action validity states | `src/nurgling/combat/CombatOpeningState.java` | `enum CombatOpeningState` | Seven-state validity vocabulary (`CONFIRMED_ZERO`, `VALID_NOT_FOUND`, `DISPLAY_ABSENT`, `SEARCH_ERROR`, `UNKNOWN`, `STALE`, `UNAVAILABLE`) + `isSafeZero()`/`isUnsafe()` classifiers | — | — | BEH-FAIL-001 | [VER-STATE-001](verification.md) |
| Move identity | `src/nurgling/combat/CombatMove.java` | `enum CombatMove` | Canonical move identity by resource path, not keybind/slot | — | — | MEC-ATK-001, MEC-DEF-001 | — |
| Immutable snapshot | `src/nurgling/combat/CombatSnapshot.java` | `class CombatSnapshot`, nested `Value`, `ActionState` | One consistent point-in-time view; `sameDecisionState(other)` diff comparator | — | — | BEH-SCOPE-002 | [VER-DIFF-001](verification.md) |
| State adapter | `src/nurgling/combat/CombatStateAdapter.java` | `static CombatSnapshot build(GameUI, long, boolean)`; `readOpening`, `readActions`, `resolveResName` | Read-only translation from live `Fightview`/`Fightsess` fields into a `CombatSnapshot` | `GameUI.fv` (`Fightview`), `GameUI.fsess` (`NFightsess`), `Fightview.buffs`/`current.buffs` (`Buff` children), `Fightview.Relation.ip`, `Fightsess.actions[]` | (none — pure read) | BEH-SCOPE-001/002, BEH-FAIL-001, observable-state rows | [VER-ADAPTER-001](verification.md) |
| Decision engine | `src/nurgling/combat/CombatDecisionEngine.java` | `static List<CombatMove> chooseDefence(CombatSnapshot)`; `static AttackRecommendation chooseAttack(CombatSnapshot)` | Pure defense-tie policy and attack truth table; no I/O | `CombatSnapshot` fields only | (none) | BEH-DEF-001/002/003, BEH-ATK-001/002/003, BEH-STING-001 | [VER-TRUTH-TABLE](verification.md), [VER-DEFENSE-TIES](verification.md) |
| Action executor | `src/nurgling/combat/CombatActionExecutor.java` | `class CombatActionExecutor(NFightsess)`; `Result send(CombatMove, CombatSnapshot)` | Revalidates deck presence + cooldown readiness against the snapshot, then sends via `NFightsess`; `Status` is only `SENT`/`REJECTED` | `CombatSnapshot.action(move)` | `NFightsess.requestAction(slot)` then `NFightsess.releaseAction()` (both `try`/`catch(Throwable)`-wrapped) | BEH-EXECCONFIRM-001 | [VER-EXEC-001](verification.md) |
| Reactor controller | `src/nurgling/combat/CombatReactorController.java` | `void tick(GameUI, boolean)`; `void onManualAttackTrigger(boolean)`; accessors `snapshot()/recommendation()/lastRejection()/lastActionAttempted()` | Orchestrates: relation-revision bump on gob-id change, snapshot rebuild every call (no diffing — see below), executor rebuild on `Fightsess` identity change, defense-candidate loop, manual-trigger validation | `GameUI.fv/fsess` (via adapter) | Delegates all sends to `CombatActionExecutor` | BEH-SCOPE-002, BEH-CANCEL-001 (partial), BEH-MANUAL-001 | [VER-RELATION-SWITCH](verification.md), [VER-CANCEL-001](verification.md) |
| Reactor widget | `src/nurgling/widgets/NCombatReactor.java` | `tick(double)`, `handleGlobalKey(Widget.GlobKeyEvent)`, `draw(GOut)`/`drawUnsafe`, `handleFailure` | Owns tick cadence (~100 ms gate), the `E` hotkey (`kb_attack`), diagnostics rendering, and the safety trip switch | `getparent(GameUI.class)` (own ancestry, **not** `NUtils.getGameUI()`), `NConfig.Key.combatReactorEnabled` | Calls into `CombatReactorController` | BEH-ENABLE-001, BEH-UI-001/002 | [VER-CRASH-FIX](verification.md), [VER-BUILD](verification.md) |
| Settings panel | `src/nurgling/widgets/nsettings/CombatReactorSettings.java` | `class CombatReactorSettings extends Panel`; `load()`/`save()` | Enable checkbox bound to `NConfig.Key.combatReactorEnabled`; static label naming the current hotkey (rebind happens in the standard Keybinds screen, not here — no `KeyMatch.Capture` widget exists in this panel, unlike the plan's original description) | `NConfig.get` | `NConfig.set` + `NConfig.needUpdate()` | BEH-ENABLE-001 | [VER-SETTINGS-001](verification.md) |
| Settings registration | `src/nurgling/widgets/NSettingsWindow.java` | one added line in the `bots` `SettingsCategory` block | Registers `CombatReactorSettings` under Settings → Bots, plain-string title (no `L10n` key added, matching the existing "Icon Generator" precedent in the same file) | — | — | BEH-ENABLE-001 | — |
| Config key | `src/nurgling/NConfig.java` | `Key.combatReactorEnabled` enum constant + default `false` | Persisted enable flag | — | — | BEH-ENABLE-001 | — |
| GameUI wiring | `src/nurgling/NGameUI.java` | field `combatReactor`; construction in the heavy-widgets init block (same pattern as `autoLogoutWidget`); one added branch at the top of `globtype(GlobKeyEvent)` | Attaches the widget (wrapped in `NDraggableWidget`) and routes the global `E` key to it before any other `NGameUI` hotkey handling | — | — | BEH-ENABLE-001 | [VER-HOTKEY-001](verification.md) |
| Shared protocol path (extract-method) | `src/haven/Fightsess.java` | `protected void requestUse(int slot)`, `protected void releaseHeld()` (extracted from the pre-existing inline bodies of `globtype`/`keyup`) | The one shared-with-real-keyboard-input code path: builds/sends the `use` `wdgmsg` (with `Maptest` hit/no-hit variants), tracks `held`/`holdgrab`, and fires the fenced `rel` via the existing `Release` inner class | `MapView`, `ui.mc`, `ui.modflags()` | `wdgmsg("use", ...)`, `wdgmsg("rel", ...)` (via `Release`) | MEC-CD-002 | [VER-KEYBOARD-REGRESSION](verification.md) |
| Bot-facing wrapper | `src/haven/NFightsess.java` | `public void requestAction(int slot)`, `public void releaseAction()` | Public delegation to the protected extracted methods above, so `nurgling.combat` (a different package) can call them without touching `Fightsess`'s internal `held`/`holdgrab` state directly | — | delegates to `requestUse`/`releaseHeld` | MEC-CD-002 | [VER-KEYBOARD-REGRESSION](verification.md) |
| Unused pub/sub seam | `src/nurgling/plugins/CombatEvents.java` | `interface UsedListener`, `fireUsed(Fightview, Indir<Resource>)` | Pre-existing (not created by this port) hook fired by `Fightview.uimsg("used", ...)` on the player's own action use | — | — | (none — not wired into the reactor) | [UNR-006](unresolved.md#unr-006) |

## Widget/controller lifecycle

1. `NGameUI.initHeavyWidgets()`-equivalent block constructs `new NCombatReactor()`, wraps it in `NDraggableWidget`, and assigns `NGameUI.combatReactor`. One instance per `NGameUI` (i.e. **one per session** in multi-session play — see [ADR-0003](decisions/ADR-0003-relation-scoped-fail-closed-state.md) for why that matters).
2. `NCombatReactor` owns a `CombatReactorController` for its whole lifetime (constructed inline as a field initializer, never rebuilt).
3. `CombatReactorController` lazily builds one `CombatActionExecutor`, rebuilding it whenever `gui.fsess` changes identity (see below).

## Snapshot acquisition and tick frequency

- `NCombatReactor.tick(dt)` accumulates `dt` and only proceeds past a `0.1` s (~10 Hz) gate — this is the "tick-based diff polling" cadence from [ADR-0002](decisions/ADR-0002-tick-diff-observation-model.md).
- **Variance from plan/prior description:** despite `CombatSnapshot.sameDecisionState(CombatSnapshot)` existing specifically to diff two snapshots and skip redundant recomputation, **it has zero call sites anywhere in the codebase** (confirmed by source-wide grep). `CombatReactorController.tick` unconditionally rebuilds the snapshot and re-evaluates defense every ~100 ms regardless of whether anything changed. This does not violate any approved requirement (100 ms recompute of cheap pure logic is not "wasteful 30-60Hz image scanning" per the brief's own framing) but is a real gap between the stated design and the shipped code — see [UNR-007](unresolved.md#unr-007).
- Each `GameUI` resolved via `getparent(GameUI.class)` from the widget's own position in the tree — **not** `nurgling.NUtils.getGameUI()`. This was a defect found and fixed this session: `NUtils.getGameUI()` resolves whichever session is globally focused/thread-local, which is wrong for a widget that must act only for the session it structurally belongs to. See [ADR-0003](decisions/ADR-0003-relation-scoped-fail-closed-state.md) and [VER-CRASH-FIX](verification.md).

## Decision flow

```text
NCombatReactor.tick(dt)
  -> CombatReactorController.tick(gui, effectiveEnabled)
       -> relation-revision bump if gob id changed
       -> CombatStateAdapter.build(gui, revision, enabled) -> CombatSnapshot
       -> CombatDecisionEngine.chooseAttack(snapshot)   [stored, not sent]
       -> rebuild CombatActionExecutor if gui.fsess identity changed
       -> if enabled && combatPresent:
            CombatDecisionEngine.chooseDefence(snapshot) -> List<CombatMove>
            for each candidate in order:
              CombatActionExecutor.send(candidate, snapshot)
              if SENT: stop (only one defense send per tick)
              if REJECTED: record reason, try next candidate
```

## Defense priority and tie flow

There is no persistent priority queue. `chooseDefence` is recomputed fresh every tick; the loop above tries each tied candidate **in the order `chooseDefence` returns them** (Zig-Zag first if red/yellow tied, then Quick Dodge, then Sidestep) and stops at the first successful send. A candidate whose move is still on cooldown from a just-sent sibling is naturally skipped by `CombatActionExecutor`'s own readiness check — this is how [BEH-DEF-003](behavior-contract.md#defense-automatic)'s revalidation is actually achieved: **implicitly, via per-move server cooldown gating on the next tick**, not via an explicit revalidation step or timestamped queue entry as the port brief's own architecture section originally sketched. Implemented differently from the plan's literal wording, but behaviorally equivalent to (arguably safer than) the stated requirement.

## Manual attack flow

```text
NGameUI.globtype(ev)
  -> NCombatReactor.handleGlobalKey(ev)      [checked before all other NGameUI hotkeys]
       -> if ev matches kb_attack:
            CombatReactorController.onManualAttackTrigger(effectiveEnabled)
              -> reject if disabled / no snapshot yet / no recommendation
              -> reject if STING and IP unsafe or < 2
              -> CombatActionExecutor.send(recommendation.move, lastSnapshot)
```

No queueing: a rejected manual trigger is simply rejected (reason recorded for diagnostics); the user must press the key again.

## Action request/release path (down to `wdgmsg`)

`CombatActionExecutor.send` → `NFightsess.requestAction(slot)` → `Fightsess.requestUse(slot)` → (releases any currently-held slot via the existing `Release` mechanism, then) `Maptest` hit/no-hit → `wdgmsg("use", slot, 1, ui.modflags(), ...)` → sets `held=slot` and grabs keys. Then `CombatActionExecutor.send` immediately calls `NFightsess.releaseAction()` → `Fightsess.releaseHeld()` → fires the fenced `Release` → `wdgmsg("rel", slot)`. This mimics an instantaneous keyboard tap (request immediately followed by release), not a held key — appropriate since the reactor has no concept of "hold duration," only "fire once."

## Action acceptance/sent/queued/executed semantics actually implemented

Only two: `CombatActionExecutor.Status.SENT` and `Status.REJECTED`. `SENT` means both `wdgmsg` calls completed without throwing; it is not upgraded to "queued" or "executed" by any later signal. See [BEH-EXECCONFIRM-001](behavior-contract.md#execution-confirmation) and [UNR-006](unresolved.md#unr-006).

## Event subscription and cleanup

None. `CombatEvents.UsedListener` (pre-existing seam) is never subscribed to by anything in `nurgling.combat` or `NCombatReactor`. There is no cleanup/unsubscribe path because there is no subscription. See [UNR-006](unresolved.md#unr-006).

## Relation change and combat-end cancellation

`CombatReactorController.tick` bumps `relationRevision` and clears `lastRejectionReason` when the current relation's gob id changes, and `CombatDecisionEngine` gates all decisions on `relationGobId != null` / `combatPresent`. **What is not implemented:** any explicit call to release a held action when the reactor is disabled, when the relation changes mid-hold, or when combat ends — see [BEH-CANCEL-001](behavior-contract.md#lifecycle-and-scope)'s noncompliance note and [UNR-005](unresolved.md#unr-005).

## Threading/UI-thread assumptions

`NCombatReactor.tick`/`draw`/`handleGlobalKey` all run on the same thread the rest of the `Widget` tree ticks/draws/dispatches keys on (no separate bot thread, unlike `BotExecutor`-driven bots elsewhere in this repo). This was a deliberate simplification: no `ThreadLocalUI` binding is needed because the widget always resolves its own `GameUI` structurally (see above), so there is no cross-thread hazard by construction — as long as `getparent(GameUI.class)` continues to be used instead of any global/thread-local lookup.

## Settings and keybinding persistence

- Enable flag: `NConfig.Key.combatReactorEnabled`, boolean, default `false`, persisted through the same `NConfig` mechanism as every other bot toggle in this repo.
- Manual-attack key: `haven.KeyBinding.get("combat-reactor-attack", KeyMatch.forcode(KeyEvent.VK_E, 0))` in `NCombatReactor.kb_attack` — persisted/rebindable through the client's own standard keybinding registry (same mechanism vanilla remappable keys use), **not** through a dedicated `KeyMatch.Capture` widget in `CombatReactorSettings` (the plan originally described one; the shipped settings panel only displays the current binding as a label and points the user at the standard Keybinds screen instead).

## Diagnostics rendering

`NCombatReactor.drawUnsafe` (called from `draw`, itself wrapped in `try/catch(Throwable)`): draws the tripped-state warning banner unconditionally (even if the user has since disabled the setting), then — only if `configEnabled()` and a combat-present snapshot exists — draws enabled state, current attack recommendation + reason, both 4-color opening sets (via `Value.safeAmount()`, i.e. unsafe states render as `0` **on screen only**, never internally treated as 0 by the decision engine — see [observable-state.md](observable-state.md) for the internal-vs-display distinction), IP (or `"unknown"` if unsafe), last sent move, and last rejection reason.

**Known open item (not yet fixed):** the user reported the diagnostics text visually overlaps the multi-session tab-switch buttons in the top-left corner. Not repositioned as of this revision — see [UNR-009](unresolved.md#unr-009).

## Current limitations and source/plan variances (index)

| Variance | Classification | Detail |
|---|---|---|
| `FightWnd`/`NFightWnd` deck-slot lookup (plan) vs `Fightsess.actions[]` (code) | Implemented differently, behaviorally equivalent (arguably more correct — live action bar, not the loadout editor) | Confirmed zero `FightWnd` references anywhere in `src/nurgling/combat/**` |
| `CombatEvents.UsedListener` subscription (plan) | Planned but not implemented | [UNR-006](unresolved.md#unr-006) |
| Per-request lifecycle states `proposed/accepted/sent/queued/executed/failed/cancelled` (plan) vs `SENT/REJECTED` (code) | Planned but not implemented | [UNR-006](unresolved.md#unr-006) |
| `sameDecisionState`-based diffing (plan/prior description) vs always-recompute (code) | Documentation-only discrepancy (no behavioral requirement violated) | [UNR-007](unresolved.md#unr-007) |
| `KeyMatch.Capture` rebind widget in settings panel (plan) vs label-only + standard Keybinds screen (code) | Implemented differently, behaviorally equivalent | — |
| Explicit relation-revision check on manual trigger (brief) vs reuse of latest tick snapshot (code) | Implementation defect (minor, bounded by ~100 ms) | [UNR-008](unresolved.md#unr-008) |
| Explicit release/cancel on disable/relation-change/combat-end (brief) vs none (code) | Implementation defect | [UNR-005](unresolved.md#unr-005) |
| `STALE`/`UNKNOWN` states (brief's 7-state model) vs never-produced in adapter (code) | Implementation defect | [UNR-002](unresolved.md#unr-002) |

## See also

- [behavior-contract.md](behavior-contract.md) — which requirement each component serves.
- [observable-state.md](observable-state.md) — the data each component reads, in detail.
- [verification.md](verification.md) — test/manual-check status referenced above.
- [decisions/](decisions/README.md) — why the architecture looks like this.
