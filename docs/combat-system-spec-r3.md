# Haven & Hearth Combat-System Specification

Last verified: 2026-07-25  
Revision: 1.2.0 — remaining final-audit corrections  
Applies to: `Haven_Combat_Reactor_Optimized_Audited.ahk`  
Reference script SHA-256: `6b04efc3474b638a434c14aab4fa838fcb508a72b9692a0e29f35ed770178405`

## Purpose and evidence rules

This file preserves combat mechanics that affect the screen-reading AutoHotkey automation. It is not a general strategy guide and it does not turn script behavior into a game rule.

Evidence labels used throughout the canonical package:

- **[Verified game mechanic]** Supported by official game/client material or current move data with no identified conflict.
- **[Community-documented mechanic]** Reported by a cited combat guide or current community material, but not independently established from current official server behavior.
- **[Current observable script behavior]** Established by reading the referenced AHK source.
- **[Existing user-approved requirement]** Explicitly requested or deliberately preserved by the user.
- **[Mechanics constraint]** A safety condition required to avoid contradicting a verified or historically verified mechanic; it is not automatically a user-approved requirement.
- **[Historical implementation decision]** A recorded prior design choice; it may or may not remain compliant.
- **[Auditor-recommended improvement]** Proposed safety or architecture work. It is not a user-approved requirement unless separately approved.
- **[Unresolved question]** Not verified, conflicting, asset-dependent, or not reproducible during this audit.

## Canonical recognition-state vocabulary

These terms have the same meaning in all four canonical files:

| State | Meaning |
|---|---|
| `confirmed_zero` | The observed game value is positively established as zero by a valid detector. A missing match alone is not sufficient. |
| `valid_not_found` | A search ran successfully (`ErrorLevel = 1`) but the requested template/pixel was not found. This describes the search result, not the game value. |
| `display_absent` | The relevant display or combat UI is positively established to be absent by a dedicated validity/absence rule. |
| `search_error` | The search could not be performed (`ErrorLevel = 2`), such as an invalid image, inaccessible screen region, or other command failure. |
| `unknown` | Available evidence cannot establish a valid value or absence. |
| `stale` | A previously valid observation is no longer safe because of age, target change, focus/UI transition, or intervening combat state. |
| `unavailable` | A move, template, asset, weapon requirement, or UI capability is established as unavailable; this is not interchangeable with a failed search. |

The current script does not implement this complete state model. In particular, its `cachedTwoIP := true` fallback after both single-digit searches fail is an unsafe current inference, never a confirmed `2+ IP` reading.

## Canonical terminology

| Term | Meaning | Evidence |
|---|---|---|
| Combat relation | Combat state between the player and one opponent; the official client stores one `Fightview.Relation` per opponent. | **[Verified game mechanic]** |
| Current target/relation | The selected relation. Targeted actions and opponent-side openings/IP refer to it. | **[Verified game mechanic]** |
| Opening | A percentage vulnerability. Attacks evaluate and may add openings; restorations reduce them. | **[Verified game mechanic]** |
| Initiative point (IP), “coin” | A relation-specific combat resource gained, required, spent, transferred, or granted by moves. | **[Verified game mechanic]** |
| Restoration | A move that reduces one or more of the player’s openings, sometimes with IP or opening side effects. | **[Verified game mechanic]** |
| Maneuver | A passive stance affecting attack/block weight or triggered effects; one is active at a time. | **[Verified game mechanic]** |
| Cooldown | Server-controlled delay after a move. Move-table units convert using `value × 0.06 seconds`. | **[Verified game mechanic]** |
| Queued action | A selected move waiting for range and/or cooldown; it can be highlighted and may cause approach. | **[Verified game mechanic]** |
| μ (mu) | Combat-school weighting used by move effects; documented multiplier range is 1–1.5. | **[Verified game mechanic]** |
| UA / MC | Unarmed Combat / Melee Combat ability and attack-weight families. | **[Verified game mechanic]** |

## The four colors

| Color | Attack type | Opening/status | Script template | Current deck key assumption |
|---|---|---|---|---|
| Green | Striking | Off Balance | `combat\green.png` | `A`, assumed Quick Dodge |
| Blue | Backhanded | Dizzy | `combat\blue.png` | `D`, assumed Sidestep |
| Yellow | Sweeping | Reeling | `combat\yellow.png` | `W`, assumed Zig-Zag Ruse |
| Red | Oppressive | Cornered | `combat\red.png` | `W`, assumed Zig-Zag Ruse |

- **[Verified game mechanic]** Current move data maps Striking to Off Balance, Backhanded to Dizzy, Sweeping to Reeling, and Oppressive to Cornered.
- **[Verified game mechanic]** Quick Dodge reduces Striking, Sidestep reduces Backhanded, and Zig-Zag Ruse reduces Sweeping and Oppressive.
- **[Current observable script behavior]** Red and yellow remain separate bucket values for attack selection, then duplicate `W` sends are removed for defense.
- **[Unresolved question]** Public sources cannot confirm the user’s current deck bindings or template artwork.

## Combat initiation, targeting, and termination

### Initiation and targeting

- **[Verified game mechanic]** Attack selection, aggression by another player/animal, and consensual sparring can create combat relations.
- **[Verified game mechanic]** Multiple relations can exist; one is current. The client supports changing/cycling the current relation.
- **[Verified game mechanic]** A targeted action can remain queued while the character approaches or waits for cooldown; movement can cancel it.
- **[Verified game mechanic]** IP and opponent openings are relation-specific. A target change immediately makes cached relation-dependent observations suspect.
- **[Current observable script behavior]** The AHK script does not recognize target identity or a target-change indicator. Repeated scans merely converge on whatever appears in fixed ROIs.
- **[Auditor-recommended improvement]** Add target identity/change detection and invalidate relation-dependent caches. This is proposed work, not an existing user-approved requirement.

### Ending combat

- **[Community-documented mechanic]** Mutual peace, sufficient separation/time, and normal knockout are reported to end relations.
- **[Community-documented mechanic]** The cited guide reports that Murderous Rage prevents relations from closing on knockout.
- **[Verified game mechanic]** At audited revision `223f516c88e98bcbf54f5f18be7d05f3c29f0c70`, `Fightview.java` handles the server’s `del` message by removing a relation and clearing/reassigning current relation state as applicable.
- **[Unresolved question]** Client relation deletion proves that the server can end a relation, but does not independently prove every server-side cause listed by the community guide.
- **[Current observable script behavior]** No combat-end detector exists. A failed opening/template match is reduced to bucket `0`, not `display_absent`.
- **[Auditor-recommended improvement]** Confirmed combat-presence gating is proposed. It must not be presented as a user-approved requirement without later approval.

## Initiative points

- **[Verified game mechanic]** IP belongs to a combat relation. The official client stores player-against-opponent and opponent-against-player IP.
- **[Verified game mechanic]** Switching targets can expose a different IP total.
- **[Verified game mechanic]** Quick Barrage costs 0 IP and gains 1 IP only when the target’s Oppressive/red opening is strictly above 25%.
- **[Verified game mechanic]** Sting costs 2 IP.
- **[Verified game mechanic]** Zig-Zag Ruse gives every opponent in combat with the user 2 IP; Artful Evasion gives opponents 1 IP.
- **[Current observable script behavior]** During detected 0/1 suffix disambiguation, `cachedTwoIP` is conservative (`false`). If neither `ip0` nor `ip1` matches, the script assigns `cachedTwoIP := true`.
- **Risk:** `valid_not_found`, `display_absent`, and `search_error` can all reach that Boolean fallback. It is an unsafe inference, not confirmed `2+ IP`.
- **Affected components:** `cachedTwoIP`, `ScanNextIPStep()`, `FindIPTemplate()`, `ResetIPScan()`, `ipRegion`, IP templates.
- **Implementation status:** documented defect only; the AHK script is unchanged.
- **Recommended future correction:** implement explicit recognition state, fail closed on `search_error`/`unknown`, and bind readings to target identity.
- **[Unresolved question]** The guard templates cover only `10/11` through `90/91`. No audited source establishes a maximum displayable IP of 91 or 99. Values above 91 require testing if the game allows them.

## Openings

### Creation and reduction

- **[Verified game mechanic]** Combatants start with zero in each opening; attacks add openings with diminishing returns as the existing opening rises.
- **[Verified game mechanic]** Attack and defense/block weight affect opening gain. UA/MC, maneuver and move-specific weights contribute.
- **[Verified game mechanic]** A move may evaluate different attack types than the openings it creates. Full Circle evaluates Sweeping+Oppressive but creates Off Balance+Cornered.
- **[Verified game mechanic]** Restorations reduce specified openings, commonly multiplicatively. μ increases effects that list μ.

### Passive opening decay

- **[Community-documented mechanic]** The cited 2022 guide reports that openings slowly decrease while standing still in combat and that moving halts this restoration.
- **[Community-documented mechanic]** A cited 2024 discussion corroborates waiting safely for openings to fall.
- **[Unresolved question]** Current server implementation, exact rate, timing, movement definition, and exceptions were not independently verified from an official current source during this audit.
- **Automation consequence:** openings can change without a combat action. Cached or queued decisions can therefore become `stale`.

### Damage use and multi-opening formula

- **[Verified game mechanic—historical official documentation]** When the opening system was introduced, the official “Bumfights” announcement documented:

\[
O_\text{combined}=1-\prod_i(1-O_i)
\]

\[
D_\text{final}=D_\text{base}\times O_\text{combined}^2
\]

where each \(O_i\) is a matching opening expressed from 0 to 1.

- **[Community-documented mechanic]** The cited combat guide corroborates that one matching opening at 100% already supplies full opening-based damage; additional matching 100% openings cannot increase the combined value beyond 1.
- **[Unresolved question]** The current server implementation of this formula was not independently verified during this audit.
- **[Verified game mechanic—historical official documentation]** In the opening system documented by the official “Bumfights” announcement, only the attack’s matching attack types contribute; unrelated colors do not.
- **[Unresolved question]** Current-server evidence was not established during this audit for the matching-types-only rule or for whether ordinary attacks generally leave openings unconsumed unless a move explicitly states otherwise.
- **[Current observable script behavior]** The script does not compute this nonlinear formula. It uses `red + yellow` for Full Circle and `green + blue` for Sting, based on coarse probe buckets.
- **Risk:** the legacy sums can rank Full Circle and Sting differently from the documented nonlinear combined-opening values, especially when one side has one high opening and the other has several moderate openings.
- **Implementation status:** documented only; attack logic was not changed.

The Ring of Brodgar opening-gain formula is separate from the damage-combination formula. Its displayed root/exponent and worked example remain internally ambiguous; the script reads bars and does not reproduce opening gain.

## Combat-action categories

### Attacks

Attacks may evaluate one or more attack types, create different openings, require a weapon class/IP/range, hit one or more targets, and trigger shared cooldown.

### Restorations

| Move | Reduction | Base cooldown | Important side effect |
|---|---:|---:|---|
| Quick Dodge | `20% × μ` Striking | 25 = 1.50 s | None listed |
| Sidestep | `20% × μ` Backhanded | 25 = 1.50 s | None listed |
| Zig-Zag Ruse | `50% × μ` Sweeping and Oppressive | 50 = 3.00 s | Every opponent gains 2 IP |
| Artful Evasion | `20% × μ` all four | 40 = 2.40 s | Opponents gain 1 IP |

These values reflect the cited move table as checked 2026-07-25; server changes supersede the table.

### Maneuvers and special moves

- **[Verified game mechanic]** One maneuver is active at a time; maneuvers can change weights or add triggered effects.
- **[Verified game mechanic]** Take Aim, Think, Dash, and Opportunity Knocks use nonstandard IP/opening behavior.
- **Automation scope:** the current script neither identifies nor changes maneuvers and is not a general move solver.

## Automated attack trio

| Move | Key | IP | Matching attack types | Openings created | Base damage | Base cooldown | Other |
|---|---|---:|---|---|---:|---:|---|
| Quick Barrage | `;` | 0 | Oppressive/red | +10% Cornered | 25% weapon | 20 = 1.20 s | Any melee weapon; gains 1 IP only above 25% red |
| Full Circle | `,` | 0 | Sweeping/yellow + Oppressive/red | +15% Off Balance, +5% Cornered | 100% weapon | 40 = 2.40 s | Primary and other opponents in range |
| Sting | `Q` | 2 | Striking/green + Backhanded/blue | +20% Dizzy, +10% Reeling | 125% weapon | 50 = 3.00 s | Requires a pointed weapon |

- **[Community-documented mechanic]** The guide recommends the Quick Barrage → Full Circle → Sting sequence for its documented MC deck.
- **[Existing user-approved requirement]** Preserve the current attack truth table unless the user deliberately changes strategy.
- **[Historical implementation decision]** The legacy truth table was simplified without intending to change decisions.
- **[Current observable script behavior]** At startup, `nextAttack` initializes to the Quick Barrage key before the first completed attack scan. The global `E` hotkey can therefore request Quick Barrage during that startup interval.
- **[Current observable script behavior]** All bucket values zero selects Quick Barrage.
- **Rationale:** this is an opener/deck-flow choice, not a damage-maximizing choice. With zero matching red opening, Quick Barrage has no opening-derived damage; it creates red and can later grant IP, but only when red becomes strictly greater than 25%.
- **[Unresolved question]** The coarse bucket scheme cannot confirm the exact 25% threshold, so it cannot determine precisely when Quick Barrage will grant IP.
- **[Current observable script behavior]** The red+yellow and green+blue sums are a legacy selection heuristic, not the official nonlinear damage formula.
- **[Unresolved question]** The script does not verify the active deck or weapon compatibility.

## Cooldowns, queuing, and timing-sensitive mechanics

- **[Verified game mechanic]** Move cooldown units convert at `0.06 seconds` per unit. The client tracks per-action and shared cooldowns.
- **[Verified game mechanic]** Selected actions can wait for range/cooldown; movement can cancel a queued move.
- **[Verified game mechanic]** Client action input releases a held combat action before a new action is used.
- **[Community-documented mechanic]** Deck changes and hand-item changes are documented by community sources to impose cooldowns; exact current values should be rechecked after server changes.
- **[Unresolved question]** Sources conflict on agility-based attack cooldown caps.
- **[Current observable script behavior]** A 600 ms local Sting throttle suppresses duplicates; it is not the game cooldown and does not prove availability/execution.
- **[Current observable script behavior]** When `QueueTap()` accepts a Sting request, the script immediately starts the 600 ms local throttle, sets `cachedTwoIP := false` as presumed IP consumption, and recomputes `nextAttack`. These mutations occur on queue admission, before actual key-down and without confirmed game execution.
- **[Current observable script behavior]** Distinct tied defenses can both be queued from one scan without revalidation between them.
- **Risk:** passive decay, the first defense, target change, cooldown, or execution failure can make later queued actions stale.
- **Auditor-recommended improvements:** timestamps/stale rejection, revalidation before later tied automatic defenses, per-action execution confirmation, and one-frame capture. None is an existing user-approved requirement.

## Multi-opponent behavior

- **[Verified game mechanic]** Each opponent has separate relation/IP/opening/last-action state; one relation is current.
- **[Verified game mechanic]** Full Circle can affect the primary target and other opponents in range.
- **[Verified game mechanic]** Zig-Zag Ruse and Artful Evasion grant IP to opponents.
- **[Unresolved question]** Official-client structure strongly indicates that player-side openings are shared vulnerabilities while current-opponent-side openings belong to the selected relation, but this interpretation was not independently verified from current server behavior during this audit.
- **[Current observable script behavior]** The decision engine evaluates only the current opponent ROI and cannot count multiple same-color tiles because one `ImageSearch` returns only the first match.

## Automation-critical invariants

1. Never describe the unsafe Boolean IP fallback as confirmed `2+ IP`.
2. Keep all seven recognition states distinct in documentation and future designs.
3. Preserve colors independently until attack selection; deduplicate red/yellow only for the shared defensive key.
4. “Grouped scan” means one grouped decision batch in the current AHK script, not one coherent captured frame.
5. Do not overlap physically held combat keys.
6. A 30 ms hold is a minimum target; AHK pseudo-thread scheduling cannot guarantee exact release timing.
7. Bucket `0` is not automatically `confirmed_zero`.
8. Relation-dependent state becomes suspect on target change.
9. Passive opening decay can make observations/queued decisions stale without an intervening action.
10. Manual `E` remains the attack trigger in the current/user-approved behavior.

## Edge cases and unresolved questions

- Distinct top defensive colors may cause multiple serialized sends with no revalidation.
- Red/yellow ties deduplicate to one `W`.
- Search command errors are currently collapsed into ordinary negative readings.
- IP values above the `90/91` guard family are untested.
- A target may disappear, switch, be knocked out, peace, flee, or be deleted while caches remain.
- Full Circle can hit secondary targets not considered by the heuristic.
- Client mods can reposition, rescale, recolor, or replace every observed element.
- The exact current opening-gain exponent/root, passive-decay rate, maximum IP, agility cooldown cap, and current-server multi-opening formula remain unresolved.

## Source register

Verified or audited on 2026-07-25:

- Current script: `Haven_Combat_Reactor_Optimized_Audited.ahk`, SHA-256 above.
- Official combat-system announcement (“Bumfights”): https://www.havenandhearth.com/forum/viewtopic.php?p=673607
- Combat guide: https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160
- 2024 passive-decay corroboration: https://www.havenandhearth.com/forum/viewtopic.php?t=77003
- Ring of Brodgar combat: https://ringofbrodgar.com/wiki/Combat
- Ring of Brodgar combat moves, pinned audited revision: https://ringofbrodgar.com/index.php?title=Combat_moves&oldid=122547
- Official `Fightsess.java`, pinned audited revision: https://github.com/dolda2000/hafen-client/blob/bf45129905d4b1f03ebefe43b3237f8469453a42/src/haven/Fightsess.java
- Official `Fightview.java`, pinned audited revision: https://github.com/dolda2000/hafen-client/blob/223f516c88e98bcbf54f5f18be7d05f3c29f0c70/src/haven/Fightview.java
- Historical original 562-line script, 1,250-case harness/results, PNG assets, and calibration screenshots: not present in this canonical package and not reproducible during this audit.

## Revision history

- **1.2.0 — 2026-07-25:** Applied the remaining final-audit corrections: reclassified historical/current matching-type certainty, removed undefined strong-inference labels, corrected deck/hand-item authority, documented startup Quick Barrage and Sting queue-admission mutations, and pinned the Combat Moves revision.
- **1.1.0 — 2026-07-25:** Resolved C2, H1, H2, H3, H4, H5, M2, M3, M4, and M6. Added uniform recognition states, corrected formula evidence, passive decay, combat-end confidence, Quick Barrage rationale, current defect records, pinned source revisions, and reproducibility limits.
- **1.0.0 — 2026-07-25:** Initial canonical extraction.

## Future update instructions

1. Keep the evidence labels and do not promote community documentation or recommendations without evidence/approval.
2. Record exact script hash, source revision, assets, and test artifacts used.
3. Update requirements and implementation decisions whenever a mechanics correction changes expected behavior.
4. Preserve unresolved items until reproduced or verified; do not silently resolve them from absence of evidence.
