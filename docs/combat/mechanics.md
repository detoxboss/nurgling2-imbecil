---
doc_id: combat-mechanics
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Haven & Hearth combat-mechanics truth that constrains the reactor (opening/IP/cooldown/move semantics)"
---

# Combat mechanics

Canonical owner of **game-mechanics truth** only. Not a strategy guide, not a description of what the Java reactor does (see [behavior-contract.md](behavior-contract.md) and [code-map.md](code-map.md) for that), and not the AHK-era recognition/implementation detail (that stayed in the R3 documents this file migrates from).

Labels used below, in descending certainty:

- `[Verified game mechanic]`
- `[Community-documented mechanic]`
- `[Historically verified; current status unresolved]`
- `[Unresolved question]`

Labels are preserved exactly as classified in the R3 source documents ([EV-SPEC-R3](evidence.md), [EV-IFACE-R3](evidence.md)) — never upgraded. Where a claim constrains reactor behavior, its ID is referenced from [behavior-contract.md](behavior-contract.md).

## Relations and targeting

- **MEC-REL-001** `[Verified game mechanic]` — Combat state between the player and one opponent is a `Fightview.Relation`; multiple relations can exist, one is "current." Source: `haven.Fightview` (`lsrel`, `current`), corroborated by [EV-SPEC-R3](evidence.md).
- **MEC-REL-002** `[Verified game mechanic]` — IP and opponent openings are relation-specific; a target change immediately makes cached relation-dependent observations suspect. → constrains [BEH-SCOPE-002](behavior-contract.md#beh-scope-002).
- **MEC-REL-003** `[Community-documented mechanic]` — Mutual peace, sufficient separation/time, and normal knockout are reported to end relations; Murderous Rage is reported to prevent closing on knockout. Current-server cause coverage beyond client-observed relation deletion is unverified (see **MEC-UNR-003**).

## Openings

- **MEC-OPEN-001** `[Verified game mechanic]` — Four opening colors, each tied to one attack type and one named status: Striking/green → Off Balance, Backhanded/blue → Dizzy, Sweeping/yellow → Reeling, Oppressive/red → Cornered. Client representation: a `Buff` resource per color (`paginae/atk/offbalance`, `dizzy`, `reeling`, `cornered`) attached to the relevant `Bufflist`.
- **MEC-OPEN-002** `[Verified game mechanic]` — Combatants start at zero in each opening; attacks add openings with diminishing returns as the existing value rises. A move may evaluate different attack types than the openings it creates (e.g. Full Circle evaluates Sweeping+Oppressive but creates Off Balance+Cornered).
- **MEC-OPEN-003** `[Community-documented mechanic]` — Openings decrease slowly while standing still in combat; moving halts this restoration. Exact current rate/timing/exceptions unresolved (**MEC-UNR-001**). Consequence: a snapshot can go stale between reactor ticks without any action having occurred.
- **MEC-OPEN-004** `[Historically verified; current status unresolved]` — At introduction, official material documented a nonlinear combined-opening formula `O_combined = 1 - Π(1 - O_i)` and `D_final = D_base × O_combined²`, with only matching attack types contributing. Current-server conformance to this exact formula was not independently reverified (**MEC-UNR-002**). The reactor's attack truth table ([BEH-ATK-002](behavior-contract.md#beh-atk-002)) is a simplified linear-sum heuristic, not this formula, by explicit historical user approval — not a claim that the heuristic matches current game math.

## Initiative points (IP)

- **MEC-IP-001** `[Verified game mechanic]` — IP is relation-specific; the client tracks player-against-opponent (`Relation.ip`) and opponent-against-player (`Relation.oip`) separately.
- **MEC-IP-002** `[Verified game mechanic]` — Quick Barrage costs 0 IP; it gains 1 IP only when the target's Oppressive/red opening is strictly above 25%.
- **MEC-IP-003** `[Verified game mechanic]` — Sting costs 2 IP.
- **MEC-IP-004** `[Verified game mechanic]` — Zig-Zag Ruse grants every opponent currently in combat with the user 2 IP as a side effect; Artful Evasion grants opponents 1 IP.
- **MEC-IP-005** `[Unresolved question]` — Maximum displayable/possible IP is not established; values above the AHK-era template coverage (~91) were never tested against a live server.

## Cooldowns and action lifecycle

- **MEC-CD-001** `[Verified game mechanic]` — Move cooldown units convert at 0.06 seconds/unit; the client tracks per-action cooldown (`acool` message → `Fightsess.Action.cs/ct`) separately from a shared/global "opening window" cooldown (`atkc` message → `Fightview.atkcs/atkct`).
- **MEC-CD-002** `[Verified game mechanic]` — Normal client input releases a previously held combat action (sends `rel`) before sending the next `use`; release is fenced through the render/environment queue rather than sent immediately. Source: `haven.Fightsess` (`Release` inner class, `globtype`/`keyup`), pinned upstream revision [EV-FIGHTSESS-PINNED](evidence.md).
- **MEC-CD-003** `[Unresolved question]` — Sources conflict on an agility-based attack-cooldown cap; not independently resolved.

## The automated attack trio

| Move | Resource path | IP cost | Matching attack types | Openings created | Cooldown | Other |
|---|---|---:|---|---|---:|---|
| Quick Barrage | `paginae/atk/barrage` | 0 | Oppressive/red | +10% Cornered | 1.20 s | Any melee weapon; gains 1 IP only above 25% red |
| Full Circle | `paginae/atk/fullcircle` | 0 | Sweeping/yellow + Oppressive/red | +15% Off Balance, +5% Cornered | 2.40 s | Can affect other opponents in range besides current target |
| Sting | `paginae/atk/sting` | 2 | Striking/green + Backhanded/blue | +20% Dizzy, +10% Reeling | 3.00 s | Requires a pointed weapon |

- **MEC-ATK-001** `[Community-documented mechanic]` — Values above from the cited move table as checked 2026-07-25 ([EV-SPEC-R3](evidence.md)); server changes supersede the table.
- **MEC-STING-001** `[Community-documented mechanic]` — Sting requires a pointed weapon. **Not enforced by the current reactor** — see [BEH-STING-002](behavior-contract.md#beh-sting-002) and [UNR-001](unresolved.md#unr-001).

## Restorations (defense moves)

| Move | Resource path | Reduces | Cooldown | Side effect |
|---|---|---|---:|---|
| Quick Dodge | `paginae/atk/qdodge` | 20%×μ Striking | 1.50 s | None listed |
| Sidestep | `paginae/atk/sidestep` | 20%×μ Backhanded | 1.50 s | None listed |
| Zig-Zag Ruse | `paginae/atk/zigzag` | 50%×μ Sweeping and Oppressive | 3.00 s | Every opponent in combat gains 2 IP (MEC-IP-004) |

- **MEC-DEF-001** `[Verified game mechanic]` — Quick Dodge reduces Striking, Sidestep reduces Backhanded, Zig-Zag Ruse reduces both Sweeping and Oppressive.

## Multi-opponent behavior

- **MEC-MULTI-001** `[Verified game mechanic]` — Each opponent has a separate relation/IP/opening/last-action state; Full Circle can affect the primary target and other opponents in range; Zig-Zag Ruse/Artful Evasion grant IP to every opponent, not just the current one.
- **MEC-MULTI-002** `[Unresolved question]` — Client structure strongly indicates player-side openings are one shared vulnerability set while current-opponent-side openings belong to the selected relation, but this was not independently verified against current server behavior.

## Player/opponent health

- **MEC-HP-001** `[Unresolved question]` — No player or opponent soft/hard HP field was located anywhere in the audited `haven`/`nurgling` combat classes (`Fightview`, `Fightsess`, `NFightsess`, `Relation`). Treat as **UNAVAILABLE** in [observable-state.md](observable-state.md); do not design future rule-builder operands around an assumed HP value until a concrete source class is identified.

## Unresolved mechanics register (carried from R3)

- **MEC-UNR-001** — Passive opening-decay exact rate/timing/movement-definition. Carried from R3 `combat-system-spec-r3.md` §"Passive opening decay."
- **MEC-UNR-002** — Current-server conformance to the nonlinear opening-combination damage formula. Carried from R3 §"Damage use and multi-opening formula."
- **MEC-UNR-003** — Full current-server-side combat-end cause coverage beyond client-observed relation deletion.
- **MEC-UNR-004** — Maximum IP / values above ~91 (= **MEC-IP-005**).
- **MEC-UNR-005** — Agility-based cooldown cap conflict (= **MEC-CD-003**).

Full detail, evidence quotes, and resolution history for each of these live in [unresolved.md](unresolved.md); this section only indexes which mechanics claims they qualify.

## See also

- [behavior-contract.md](behavior-contract.md) — which of these mechanics became an approved reactor requirement, and how.
- [observable-state.md](observable-state.md) — whether/how the client actually exposes each value to Nurgling code.
- [evidence.md](evidence.md) — source registry (`EV-*` IDs referenced above).
