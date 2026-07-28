---
doc_id: combat-observable-state
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "What Nurgling/hafen can authoritatively observe about combat state, and what the current reactor actually reads"
---

# Observable state

Canonical owner of **what is actually observable**, and separately, **what the current reactor code actually reads**. These are not the same thing: some fields are `KNOWN` to the client but `UNAVAILABLE` to the reactor only because nothing reads them yet — that distinction is called out explicitly per row, since conflating "the client can't provide this" with "we haven't wired it up" would misdirect future rule-builder design.

State semantics used below (exactly [BEH-FAIL-001](behavior-contract.md#fail-closed-handling)'s vocabulary, applied to what the *current adapter* actually produces, per [`CombatOpeningState`](../../src/nurgling/combat/CombatOpeningState.java)):

- **KNOWN** — an authoritative current value exists and the adapter surfaces it.
- **UNKNOWN** — a source exists in the client, but the adapter does not currently establish a value from it (either because it isn't read at all, or because the enum state for "can't tell" is never actually produced — noted per row).
- **UNAVAILABLE** — the client does not expose the value reliably, or no source class was found.

| State | Type/unit/range | Scope | Exact source | Update trigger | Unknown/unavailable semantics | Consumers |
|---|---|---|---|---|---|---|
| Combat presence | boolean | session | `Fightview.lsrel` non-empty AND `Fightview.current != null` AND `NFightsess` present | recomputed every adapter build | `false` whenever any part is absent — never conflated with "combat present but unknown" | `CombatStateAdapter.build`, `CombatDecisionEngine.chooseDefence/chooseAttack` |
| Current relation identity | `long` gob id, or absent | session | `Fightview.current.gobid` | `"cur"`/`"new"`/`"del"` server messages via `Fightview.uimsg` | **KNOWN** whenever a current relation exists; otherwise `relationGobId=null` and a monotonic `relationRevision` counter (owned by `CombatReactorController`, not the adapter) increments | `CombatReactorController.tick`, `CombatDecisionEngine` (gates all decisions) |
| Player IP (against current relation) | `int`, unbounded (server-authoritative; no known client-side max) | relation | `Fightview.Relation.ip` | `"new"`/`"upd"` server messages | **KNOWN** whenever a relation exists; `DISPLAY_ABSENT` (unsafe) when no current relation. Max IP value is otherwise unresolved — [MEC-IP-005](mechanics.md#initiative-points-ip) | `CombatDecisionEngine.chooseAttack`, Sting gating in `CombatReactorController.onManualAttackTrigger` |
| Opponent IP (against player) | `int` | relation | `Fightview.Relation.oip` | same as above | **KNOWN to the client**, but **UNAVAILABLE to the reactor** — `CombatStateAdapter` never reads `.oip` at all (confirmed: zero references in `src/nurgling/combat/*`). Not required by any approved requirement today; flagged as a future rule-builder operand candidate | none currently — [UNR-003](unresolved.md#unr-003) |
| Player openings (4 colors) | `int`, `Buff.ameter()` raw value (not independently unit-verified beyond "raw meter value", commonly percentage-like) | shared (player-side, not per-relation) | `Fightview.buffs` `Buff` children matched by resource name (`paginae/atk/{offbalance,dizzy,reeling,cornered}`) | Buff attach/detach + `Buff.ameter()`'s own `AttrCache`, read by adapter on each tick | Buff present → **KNOWN** (`CONFIRMED_ZERO` state label, used generically for "a positively read value," see caveat below). Buff absent from list → **KNOWN as safe zero** (`VALID_NOT_FOUND`, since the protocol removes zero-value opening buffs rather than sending zero). `Buff.ameter()` returning `-1` (its own internal error sentinel) → `SEARCH_ERROR` (unsafe). **`UNKNOWN`/`STALE` states are defined in the enum but never actually produced by current code** — see caveat | `CombatDecisionEngine.chooseDefence` |
| Target/opponent openings (4 colors) | same as above | current relation | `Fightview.current.buffs` (same resource-name matching) | same as above | same as above | `CombatDecisionEngine.chooseAttack` |
| Action/deck slots (per configured move) | `int` slot index 0-9, or unavailable | session (live action bar) | `Fightsess.actions[]`, resolved fresh every snapshot by matching `Action.res` against each `CombatMove.resourceName` | `"act"` server message (slot assignment changes) | Move not found in current deck → **UNAVAILABLE** (`ActionState.UNAVAILABLE`, slot=-1). Move found → **KNOWN** | `CombatActionExecutor.send` |
| Per-action cooldown readiness | boolean (`now >= Action.ct`) | session, per slot | `Fightsess.Action.cs/ct` | `"acool"` server message | **KNOWN** whenever the move is in the deck; defaults to "ready" (`cs=ct=0.0`) before any `acool` has ever been received for that slot, which is correct (nothing on cooldown yet) | `CombatActionExecutor.send` (rejects if not ready) |
| Global/shared "opening window" cooldown | `double` timestamps `atkcs`/`atkct` | session | `Fightview.atkcs/atkct` (`"atkc"` server message) | server-driven | **KNOWN to the client** (used by vanilla `Fightsess`/`NFightsess` HUD rendering) but **UNAVAILABLE to the reactor** — `CombatStateAdapter` never reads it. Not the same thing as per-action cooldown; see [MEC-CD-001](mechanics.md#cooldowns-and-action-lifecycle) | none currently |
| Held/selected action slot | `int` slot index or -1 | session | `Fightsess.use` | `"use"` server message | **KNOWN** — read into `CombatSnapshot.heldSlot` — but **write-only**: no consumer reads `heldSlot` anywhere (`CombatDecisionEngine`, `CombatActionExecutor`, and `CombatReactorController` never inspect it). Confirmed via source grep, zero read sites outside `CombatSnapshot` itself | none (dead field) — [UNR-004](unresolved.md#unr-004) |
| Secondary queued slot | `int` slot index or -1 | session | `Fightsess.useb` | `"use"` server message (second arg) | **KNOWN to the client, UNAVAILABLE to the reactor** — never read by `CombatStateAdapter` at all (only `.use` is read) | none — [UNR-004](unresolved.md#unr-004) |
| Execution acknowledgement | none distinct from "sent" | n/a | Closest available signals: `Fightview` `"used"`/`"ruse"` server messages (own/opponent last-move tracking) and the unused `nurgling.plugins.CombatEvents.fireUsed` pub/sub seam | server-driven | **UNAVAILABLE to the reactor by design-gap** — neither signal is consumed; `CombatActionExecutor.send` returns `SENT` the instant the local `wdgmsg`s are dispatched, with no upgrade to "executed." See [BEH-EXECCONFIRM-001](behavior-contract.md#execution-confirmation) | none — [UNR-006](unresolved.md#unr-006) |
| Player soft/hard HP | — | — | **No source class found** in any audited file (`Fightview`, `Fightsess`, `NFightsess`, `Relation`, `NGob`) | — | **UNAVAILABLE** — do not design UI/rule-builder operands assuming this exists. See [MEC-HP-001](mechanics.md#playeropponent-health) | none |
| Opponent soft/hard HP | — | — | Same as above | — | **UNAVAILABLE** | none |
| Reactor enabled flag (config) | boolean | client-local, persisted | `NConfig.Key.combatReactorEnabled` | user toggles setting | **KNOWN** always (defaults `false`) | `NCombatReactor.configEnabled/effectiveEnabled`, `CombatReactorSettings` |
| Reactor safety trip state | boolean, in-memory only (not persisted) | client-local, per widget instance | `NCombatReactor.tripped`/`consecutiveFailures` | 3 consecutive uncaught exceptions from `tick`/`handleGlobalKey` | **KNOWN**, resets only when the user flips `combatReactorEnabled` off→on | `NCombatReactor.effectiveEnabled`, diagnostics draw |

## Important caveat: `CONFIRMED_ZERO` is used generically, not literally

[`CombatOpeningState.CONFIRMED_ZERO`](../../src/nurgling/combat/CombatOpeningState.java) is documented in its own javadoc as "the relation/widget positively reports numeric zero," matching the R3/brief seven-state vocabulary literally. **The current adapter does not honor that literal meaning** — `CombatStateAdapter` assigns `CONFIRMED_ZERO` to *every* positively-read value regardless of whether the number is actually zero (e.g. an IP of 5, or an opening reading of 40, both get labeled `CONFIRMED_ZERO`). This is harmless to current decision logic (only `isSafeZero()`/`isUnsafe()` classification matters there, and both are correct), but it means the state label itself cannot be trusted literally by any future code that checks `state == CONFIRMED_ZERO` expecting "this value is really 0." Recorded as a source-verified naming defect, not a behavioral one — see [UNR-002](unresolved.md#unr-002).

`STALE` and `UNKNOWN` are declared in the enum (matching the brief's seven-state model) but are **never constructed anywhere in the current adapter** — confirmed by source grep (zero call sites). No code path currently produces them. See [UNR-002](unresolved.md#unr-002).

## Future rule-builder operand candidates blocked by current UNAVAILABLE state

See [rule-system.md](rule-system.md#5-operands-blocked-by-unavailable-client-state) for the authoritative list; summarized here for cross-reference: opponent IP, global/shared cooldown window, execution acknowledgement, held/queued slot, and both HP fields.

## See also

- [mechanics.md](mechanics.md) — what these values mean in game terms.
- [code-map.md](code-map.md) — the exact classes/methods that produce and consume each row.
- [unresolved.md](unresolved.md) — `UNR-002` through `UNR-006`, the gaps this file documents.
