---
doc_id: combat-behavior-contract
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Intended/approved automation behavior for the combat reactor, independent of Java implementation"
---

# Behavior contract

Canonical owner of **intended behavior**. What the Java actually does is [code-map.md](code-map.md)'s job, not this file's; where the two disagree, that is recorded here as noncompliance with a link into [verification.md](verification.md), never by silently rewriting the requirement.

Labels:

- `[Approved requirement]` — explicit user approval exists (either from the historical AHK-era R3 documents' "existing user-approved requirement" tag, or explicit approval given during this port, e.g. via the plan approval or an in-session choice).
- `[Derived requirement]` — follows necessarily from an approved requirement plus a verified mechanic ([mechanics.md](mechanics.md)), not separately approved on its own.
- `[Proposed behavior]` — recommended, not approved.
- `[Unresolved product decision]` — needs the user's decision.

## Lifecycle and scope

<a id="beh-enable-001"></a>
- **BEH-ENABLE-001** `[Approved requirement]` — The reactor has an explicit enable/disable state (`NConfig.Key.combatReactorEnabled`), off by default. When disabled it sends nothing. Evidence: plan approval ([EV-PLAN](evidence.md)), port brief §10.
<a id="beh-scope-001"></a>
- **BEH-SCOPE-001** `[Approved requirement]` — No automatic action (defense or attack) is authorized unless combat is present *and* a current relation exists. Source: port brief §4.1.
<a id="beh-scope-002"></a>
- **BEH-SCOPE-002** `[Approved requirement]` — IP and openings are read and acted on relative to the *current* relation only; changing relation must not let stale IP/openings/pending work from the old relation leak onto the new one. Constrained by [MEC-REL-002](mechanics.md#relations-and-targeting). Source: port brief §4.2, §9.4.
<a id="beh-cancel-001"></a>
- **BEH-CANCEL-001** `[Approved requirement]` — Pending/held automatic work must be cancelled and any held action safely released on: reactor disable, relation change, combat ending, widget/session teardown. Source: port brief §9.4, §11.
  - **Noncompliance:** [VER-CANCEL-001](verification.md) — no code path explicitly releases a held action on disable/relation-change/combat-end. The implementation relies entirely on every `send()` call being a synchronous request-then-release pair; if `releaseAction()` itself throws mid-pair, nothing else ever attempts to clear the resulting held state. See [UNR-005](unresolved.md#unr-005).

## Defense (automatic)

<a id="beh-def-001"></a>
- **BEH-DEF-001** `[Approved requirement]` — Defense is automatic; find the player's own highest-value opening (`>0`) and fire its restoring move. Source: port brief §6.2, R3 automation-requirements §"Existing user-approved requirements" #5.
<a id="beh-def-002"></a>
- **BEH-DEF-002** `[Approved requirement]` — Red and yellow both map to Zig-Zag Ruse; a red/yellow tie deduplicates to exactly one Zig-Zag request, never two. Source: port brief §6.2 step 7; R3 requirement #8.
<a id="beh-def-003"></a>
- **BEH-DEF-003** `[Approved requirement]` — A green/blue tie is preserved as **two separate candidates**, each **revalidated against the freshest snapshot immediately before it is actually sent**, rather than blindly queued. This explicitly *supersedes and replaces* the historical AHK-era "send every tied key with no revalidation" policy that R3 recorded as merely historically compliant, not as a mechanic to preserve. Evidence: user selected "Revalidate before send (Recommended)" when asked directly during this port (this session, prior to implementation) — see [EV-USER-DECISIONS](evidence.md).
<a id="beh-def-004"></a>
- **BEH-DEF-004** `[Derived requirement]` — Unknown/stale/unavailable player-opening state authorizes no automatic defense (fail-closed). Derived from [BEH-FAIL-001](#beh-fail-001).

## Attack recommendation and manual trigger

<a id="beh-atk-001"></a>
- **BEH-ATK-001** `[Approved requirement]` — The attack recommendation is continuously (re-)computed but only ever *fired* when the user presses the configured manual key; the reactor never fires an attack on its own. Source: port brief §1, §7, §8; R3 requirement #6.
<a id="beh-atk-002"></a>
- **BEH-ATK-002** `[Approved requirement]` — Exact truth table (must not be replaced by the nonlinear damage formula per [MEC-OPEN-004](mechanics.md#openings) without a deliberate strategy change):

  ```text
  if R==0 and Y==0 and G==0 and B==0: Quick Barrage
  else if not (IP>=2 confirmed):
      if FC>0 and FC>=STING: Full Circle
      else: Quick Barrage
  else:  # IP>=2 confirmed
      if STING>FC: Sting
      else if FC>0: Full Circle
      else: Quick Barrage
  # FC = target red + target yellow; STING = target green + target blue
  ```

  Source: port brief §7.1; R3 automation-requirements §"Approved attack-selection policy." Ties (`FC==STING`) favor Full Circle; Sting requires *strict* `STING>FC`.
<a id="beh-atk-003"></a>
- **BEH-ATK-003** `[Approved requirement]` — All-zero openings select Quick Barrage as an opener/deck-flow choice, not a damage-maximizing pick (constrained by [MEC-IP-002](mechanics.md#initiative-points-ip)).
<a id="beh-manual-001"></a>
- **BEH-MANUAL-001** `[Approved requirement]` (implemented in a simplified form — see the two variance notes below) — On the manual trigger: re-verify the recommendation against the latest snapshot, confirm the configured action is present, and (for Sting) reconfirm IP≥2, then send once. Source: port brief §8.
  - **Implementation variance:** the brief's step "confirm the recommendation belongs to the same relation revision" ([port brief §8](../haven-combat-reactor-nurgling-port-brief.md)) is not implemented as an explicit revision-equality check; the code instead reuses whatever `CombatSnapshot` the last tick produced (≤~100 ms old). Practically bounded by tick cadence, not exact. See [UNR-008](unresolved.md#unr-008).
  - **Implementation variance:** the brief's queue semantics ("retain at most one pending manual attack... newest valid recommendation") are not implemented; a rejected manual trigger is not queued or retried — the user must press the key again. This was a deliberate simplification enabled by the synchronous single-shot send design (see [ADR-0005](decisions/ADR-0005-synchronous-single-shot-action-dispatch.md)), not an oversight, but it is a real behavioral difference from the brief's literal wording.
<a id="beh-sting-001"></a>
- **BEH-STING-001** `[Approved requirement]` — Sting is eligible only when relation-scoped IP is *positively confirmed* ≥2; unknown, absent, stale, or relation-mismatched IP must never authorize it. Constrained by [MEC-IP-003](mechanics.md#initiative-points-ip) (also independently a mechanics constraint under the R3 documents' own vocabulary, not only a user-approved requirement). Source: port brief §4.5, §7; R3 mechanics constraint.
<a id="beh-sting-002"></a>
- **BEH-STING-002** `[Approved requirement]` (a deliberate, approved *deviation* from the underlying mechanic, not an oversight) — The pointed-weapon requirement ([MEC-STING-001](mechanics.md#the-automated-attack-trio)) is **not currently checked**. This was an explicit, deliberate user choice made during this port ("Skip weapon check for now (Recommended)" — matches historical AHK behavior, which also never checked weapon type). Not a defect; tracked as an open extension point at [UNR-001](unresolved.md#unr-001).

## Fail-closed handling

<a id="beh-fail-001"></a>
- **BEH-FAIL-001** `[Approved requirement]` — Unknown, stale, display-absent, search-error, or unavailable state must never be treated as zero and must never authorize an automatic action. Source: port brief §5. See [observable-state.md](observable-state.md) for which states the current adapter actually produces.

## Non-goals (explicitly out of scope)

`[Approved requirement]` per port brief §2 — the reactor does **not**:

- pick targets, start combat, or end combat;
- chase, flee, pathfind, manage stamina/equipment, or change decks/maneuvers;
- fire an offensive attack without the manual trigger;
- solve arbitrary combat decks (it is narrow to this one three-move/three-defense deck);
- calculate exact damage;
- assume a sent action was accepted/executed unless client/server state confirms it (see [BEH-EXECCONFIRM-001](#beh-execconfirm-001) below — currently unconfirmed by design, not by omission).

## Execution confirmation

<a id="beh-execconfirm-001"></a>
- **BEH-EXECCONFIRM-001** `[Derived requirement]` — "Sent" must not be conflated with "executed"; range/cooldown/weapon/movement/relation-change/server rejection can all still prevent the intended effect. Source: port brief §4.7, §9.2.
  - **Current status:** the implementation has exactly two request outcomes, `SENT` and `REJECTED` ([code-map.md](code-map.md)); there is no `accepted`/`queued`/`executed`/`failed`/`cancelled` distinction the plan originally described, and no consumption of `CombatEvents.fireUsed` or the `Fightview` "used"/"ruse" server acknowledgement to upgrade "sent" to "executed." This is a known, documented gap — see [UNR-006](unresolved.md#unr-006).

## Diagnostics/UI (approved minimum)

<a id="beh-ui-001"></a>
- **BEH-UI-001** `[Approved requirement]` — Expose at minimum: enabled state, current target/relation presence, four player openings, four opponent openings, current IP, recommended attack + reason, last sent action, last rejection reason, a safe stop. Source: port brief §10.
<a id="beh-ui-002"></a>
- **BEH-UI-002** `[Derived requirement]` — derived from the user's explicit, emphatic statement that a crash mid-combat is unacceptable in a permadeath game (an approved requirement in substance — "never crash" — even though it was stated as prose reacting to an incident, not a spec bullet); the specific trip-switch mechanism below is Claude's design response to that goal, not something the user specified directly. A repeated-failure trip switch: after 3 consecutive uncaught exceptions from the reactor's own tick/hotkey handling, the reactor **latches itself off** (independent of the config toggle) and shows a persistent on-screen warning until the user explicitly re-toggles the enable setting. The user reviewed and explicitly approved of this hardening after it was implemented ("okay good"), which is retroactive confirmation of the approach, not prior specification of it. See [code-map.md](code-map.md) and [ADR-0003](decisions/ADR-0003-relation-scoped-fail-closed-state.md).

## Known current noncompliance (index)

| ID | Summary | Verification entry |
|---|---|---|
| BEH-CANCEL-001 | No explicit release/cancel path on disable/relation-change/combat-end | [VER-CANCEL-001](verification.md) |
| BEH-EXECCONFIRM-001 | Only SENT/REJECTED exist; no execution acknowledgement consumed | [VER-EXEC-001](verification.md) |
| BEH-MANUAL-001 | No explicit relation-revision check on manual trigger; no retry queue | [VER-MANUAL-001](verification.md) |

## See also

- [mechanics.md](mechanics.md) — the mechanics each requirement above is constrained by.
- [code-map.md](code-map.md) — where each requirement is (or isn't) implemented.
- [verification.md](verification.md) — pass/fail/untested status per requirement.
- [unresolved.md](unresolved.md) — open product decisions.
