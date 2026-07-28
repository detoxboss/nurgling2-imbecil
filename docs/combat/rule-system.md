---
doc_id: combat-rule-system
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Future configurable combat-rule system: what exists, what's approved, what's merely recommended"
---

# Rule system (future configurable rules)

Canonical owner of the **future** configurable rule-builder topic. Split strictly into five categories per the section headers below — do not let any later edit blur "recommended" into "approved" or "approved" into "implemented."

## 1. What is implemented now

**Nothing.** There is no configurable rule system in this codebase today. All defense/attack decisions are hardcoded in [`CombatDecisionEngine`](../../src/nurgling/combat/CombatDecisionEngine.java) as two fixed methods (`chooseDefence`, `chooseAttack`) implementing the one approved deck's truth table ([BEH-ATK-002](behavior-contract.md#attack-recommendation-and-manual-trigger), [BEH-DEF-001..003](behavior-contract.md#defense-automatic)). There is no `Rule`/`Condition` class, no serialization format, no UI to add/edit/reorder rules, anywhere in `src/nurgling/combat/**` or `src/nurgling/widgets/NCombatReactor.java`. Confirmed by direct source read, not inference.

## 2. What the user has approved for a future configurable rule system

**Nothing yet.** No conversation evidence shows the user requesting or approving a generalized rule-builder feature. The flat `Rule`/`Condition` schema in section 3 below originates from the *documentation-generation task's own instructions* (the prompt that produced this document set), not from a product requirement the user separately approved. Do not cite section 3 as user-approved scope for implementation without first getting that approval explicitly, the same way the reactor's own behavior required explicit approval (see [behavior-contract.md](behavior-contract.md)'s `[Approved requirement]` tag discipline).

## 3. Recommended first schema

`[Proposed behavior]` — documented for future reference only.

```text
Rule
  id
  enabled
  priority
  action              # a CombatMove, or a defensive move
  matchMode: ALL | ANY
  conditions[]

Condition
  operand
  operator            # == != < <= > >=
  numericValue
```

Rationale for the flat (non-nested) shape: `ALL`/`ANY` over a single flat condition list covers every rule the current hardcoded deck actually needs (the truth table in [BEH-ATK-002](behavior-contract.md#attack-recommendation-and-manual-trigger) is expressible as a small ordered list of flat `ALL` rules with a fallback). Nested boolean groups (`(A AND B) OR C`) add real complexity — validation, serialization depth, UI affordances for grouping — that nothing in the current approved behavior needs. Do not add nesting until a concrete rule requires it.

Use `ALL`/`ANY` only — never expose a separate `OR` alongside `ANY`; for a flat condition group they are the same thing and offering both invites confusing duplicate schema paths.

### Operand requirements (for any operand eventually proposed)

Every operand must define, before it is added to any schema: type, unit, valid range, target/relation scope (player-side vs current-relation-side vs global), update timing (event-driven vs polled, and at what cadence), and unknown-value policy (does a rule referencing this operand ever fire while the operand is `UNKNOWN`/`UNAVAILABLE`? Default answer, absent a specific approved exception: **no** — matches [BEH-FAIL-001](behavior-contract.md#fail-closed-handling)).

### Numeric operators

```text
==  !=  <  <=  >  >=
```

No string/enum-equality operators are proposed at this time (all currently-known operands are integers; see [observable-state.md](observable-state.md)).

### Undesigned behavior (explicitly not decided)

The following all remain `[Unresolved product decision]` and must not be implemented speculatively:

- **Evaluation timing** — every tick? Only on relevant-value change? Some hybrid?
- **Priority/conflict resolution** — numeric `priority` field breaks ties how, exactly, when multiple enabled rules match simultaneously?
- **First-match vs multi-fire** — does the engine stop at the first matching rule (like the current hardcoded truth table effectively does), or can multiple rules fire per tick?
- **Cooldown/queue interaction** — does a matched rule respect the same "revalidate before send" pattern as [BEH-DEF-003](behavior-contract.md#defense-automatic), or something else?
- **Validation** — what makes a `Rule`/`Condition` well-formed before it's allowed to run (e.g. an operand referencing an unavailable value, a `numericValue` outside the operand's valid range)?
- **Serialization versioning/migration** — how does a saved rule set survive a schema change?
- **Duplicate conditions** — same operand/operator/value twice in one rule: reject, dedupe, or allow (no-op)?
- **Contradictory conditions** — e.g. `IP < 2 AND IP >= 2` in one `ALL` rule: reject at validation time, or allow (rule simply never matches)?
- **UI add/remove/reorder** — no design exists yet.
- **Safe defaults** — what does a brand-new rule do before the user configures any conditions (nothing, by the same fail-closed default as everything else in this document set, unless separately approved)?

## 4. Unapproved ideas

Ideas that have surfaced in adjacent documents (the port brief's own "recommended Java-port improvement" framing, or general good practice) but are not scoped to any approved rule-builder work:

- Nested boolean expression groups (see rationale against this in section 3).
- Per-rule cooldown/rate-limiting independent of the underlying move's own server cooldown.
- A rule-authoring UI embedded in `NCombatReactor`'s diagnostics panel (no such panel design exists).
- Cross-relation rules (evaluating a non-current relation's state) — would require rethinking [BEH-SCOPE-002](behavior-contract.md#lifecycle-and-scope)'s relation-scoping invariant, not a small addition.

## 5. Operands blocked by unavailable client state

Any future rule operand drawing on a row marked `UNAVAILABLE` in [observable-state.md](observable-state.md) cannot be implemented without first wiring the underlying read (which may be small, e.g. reading `Relation.oip`, or may not exist at all, e.g. HP):

| Candidate operand | Blocked by | Effort to unblock |
|---|---|---|
| Opponent IP | `CombatStateAdapter` never reads `Relation.oip` | Small — field already exists client-side ([UNR-003](unresolved.md#unr-003)) |
| Global/shared cooldown-window remaining time | `CombatStateAdapter` never reads `Fightview.atkcs/atkct` | Small — field already exists client-side |
| Execution-confirmed (vs merely sent) | No consumption of `Fightview` "used"/"ruse" or `CombatEvents.fireUsed` | Medium — requires subscribing and correlating to relation/action identity ([UNR-006](unresolved.md#unr-006)) |
| Held/queued action slot | `Fightsess.use`/`useb` read but unused (`use`) or unread (`useb`) | Small ([UNR-004](unresolved.md#unr-004)) |
| Player HP / Opponent HP | No source class found anywhere in audited combat classes | Unknown — needs a fresh source investigation, not assumed to exist ([MEC-HP-001](mechanics.md#playeropponent-health)) |

## See also

- [behavior-contract.md](behavior-contract.md) — the one hardcoded rule set currently in force.
- [observable-state.md](observable-state.md) — full operand-availability table.
- [unresolved.md](unresolved.md) — `UNR-003`, `UNR-004`, `UNR-006`.
