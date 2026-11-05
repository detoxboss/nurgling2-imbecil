# Capability registry: workstation & production automation — sessions 1-4

**Kind:** current (read and verified) / recovery-in-progress (coverage). Second capability area of
the Phase 3 modernization audit, picked per user direction after the bot lifecycle area closed out
(see [`capability-bot-lifecycle.md`](capability-bot-lifecycle.md)). Session 1 covered fuel-consuming
production workstations: ovens, kilns, stack furnaces/ore smelters, steel crucibles, cauldrons,
tarkilns, smoke sheds, and ash production. **Session 2 (below) widens scope past the fuel-loop bots**
to the full set of workstation/crafting-station interaction capabilities in the client, per explicit
instruction that the session-1 inventory was not comprehensive. Does **not** cover every bot in this
area in full depth — see "Not covered" at the end of each session's section.

## Phase 1: inventory

| Workstation | Bot(s) | Loop shape |
|---|---|---|
| Oven (baking) | `BakerAction` | Loop: wait-burnout → unload → refill (any dough) → refuel → relight |
| Kiln (bricks) | `BricksAction` | Loop: wait-burnout → unload → refill (clay) → refuel → relight |
| Kiln (block/board ash) | `BlockAshAction` | Loop: wait-burnout → unload → refill → refuel → relight |
| Kiln (bone ash) | `BoneAshAction` | Loop: wait-burnout → unload → refill → refuel → relight |
| Ore Smelter / Stack Furnace | `SmelterAction` | Loop: wait-burnout-**or**-bellows-lapse → (pump or unload) → refill → refuel → relight — the most developed of the batch |
| Steel crucible | `SteelAction` | **Single-shot**: open/close (to reset?), refuel, light, return `SUCCESS` — does **not** wait, unload, or loop at all |
| Cauldron (lye) | `LyeBoiler` | Loop, with a non-default `setFuelmod(5)` (fuel multiplier per item) |
| Cauldron (gelatin) | `GelatinAction` | Not read this session (see "Not covered") |
| Cauldron (fried fish) | `FriedFish` | Not fully read this session; confirmed to call `LightGob` |
| Tarkiln | `TarkilnAction` | Not fully read this session; confirmed to call `LightGob(16)` for its fire flag |
| Smoke shed / smokering | `SmokeringBot` | Uses `LightObject` (the general-purpose lighter, not `LightGob`) directly |

**Shared primitives used across these bots** (`src/nurgling/actions/**`, `src/nurgling/tools/**`,
`src/nurgling/tasks/**`):

- **`Container`** (`tools/Container.java`) — generic workstation wrapper; `Container.FuelLvl`
  attribute (`setMaxlvl`/`setAbsMaxlvl`/`setCredolvl`/`setFuelmod`/`setFueltype`/`setFuelZone`) is the
  shared model for "how much fuel, of what type, from which zone, this station wants." `Container.Space`
  is the shared "how much is in here" attribute, polled via `.update()`/`.getattr(...)`.
- **`FuelToContainers`** (`actions/FuelToContainers.java`) — generic refueling action every looped bot
  in the table calls with its own `Container.FuelLvl`-configured list.
- **`LightGob`** / **`LightObject`** (`actions/bots/LightObject.java`) — the shared, sophisticated
  lighting action (priority-ordered: embers → equipped torch → torchpost → ..., per-workstation
  `LightConfig` registry of fire/fuel/embers bits, model-attribute-confirmed). `LightGob` is referenced
  by name throughout the table above but not separately read this session — not yet confirmed whether
  it's a thin wrapper over `LightObject` or a distinct, older implementation (see "Not covered").
- **`FreeContainers`** — generic unload action, called after `WaitForBurnout` in every looped bot.
- **`WaitForBurnout`** / **`WaitForFirstBurnout`** / **`WaitBurnoutOrBellows`** (`tasks/**`) — the
  completion-detection tasks; see Phase 2.
- **`FuelZones`** (`tools/FuelZones.java`) — per-station-type fuel-pile area registry (one zone per
  burner type: smelter/steelbox/fforge/kiln/oven/cauldron/fireplace/crucible/tarkiln), replacing an
  older single shared fuel pile. Pure area/config plumbing, not fuel-detection logic.
- **`Validator`** (seen gating `BricksAction`/`SteelAction`) — pre-flight check (e.g.
  `.fuel(zone, "branch")`) before a bot proceeds; not read in depth this session.

## Phase 2: fuel and production intelligence — verified capabilities

**1. Fuel quantity/type required:** `Container.FuelLvl`'s per-bot configuration
(`setMaxlvl`/`setFueltype`/`setFuelmod`) is a **hardcoded constant per bot**, not derived from any
observed or server-reported capacity — e.g. `BakerAction` hardcodes oven `maxlvl=4`/`"branch"`,
`SmelterAction` hardcodes ore-smelter `maxlvl=12`/`credolvl=9`/`"branch"` vs stack-furnace
`maxlvl=12`/`credolvl=9`/`"coal"`, `LyeBoiler` hardcodes cauldron `maxlvl=40`/`absMaxlvl=50`/
`fuelmod=5`. These numbers were presumably derived from the wiki/live testing per workstation type,
mirroring `KilnFuelCatalog`'s own wiki-sourced table — **not verified against the server's actual
reported capacity for each gob**, which is a separate, unanswered question (see "Not covered").

**2. Can it observe actual fuel remaining?** **Yes, via `Container.FuelLvl`/`Container.Space`**,
which `.update()` and read current contents — this is real, observed state (what's physically in the
container), not an estimate. What's **not** observed is remaining *burn time* for that fuel load; no
bot reads anything like "X seconds of fuel left," only "N fuel items currently inside."

**3. Do existing client widgets/meters/overlays/protocol messages expose useful info?** **Yes — and
this is the single most important Phase 2 finding.** Two pieces of real, protocol-sourced,
item-level state already exist and are **currently used only for UI tooltips, never consumed by any
bot's decision logic**:

- **`GItem.MeterInfo`** (haven's own, read via `ItemInfo.find(GItem.MeterInfo.class, item.info())`) —
  a genuine 0-100% progress fraction the server reports per item, independent of fuel load. This is
  what `KilnFiringTip.resolvedPercent`/`NKilnInfo`/`NSmelterInfo` read to render the kiln/smelter
  inventory tooltip's progress bar and remaining-time label.
- **`gob.ngob.getModelAttribute()` bitmasks** — genuine, server-driven, per-workstation-gob state
  (fire lit, bellows idle/boosted, embers) read directly by `WaitForBurnout`/`WaitBurnoutOrBellows`/
  `WorkBellows`/`LightObject`. This one **is** consumed by bot logic already — it's the backbone of
  the entire "is it burning" check across the table in Phase 1.

**`NKilnInfo`/`NSmelterInfo`/`KilnFuelCatalog` exist only for Kiln and Smelter windows** — a grep of
`src/nurgling/iteminfo/` found no `NOvenInfo`, `NCauldronInfo`, or equivalent for any other
workstation type in the table. Even the **UI-level** tooling (not just bot logic) hasn't been extended
past these two types yet.

**4/5. How is production-completion detected — and does it rely on the fire going out?**
**Confirmed: every looped bot in the table detects completion exclusively via `WaitForBurnout`
(or its bellows-aware sibling `WaitBurnoutOrBellows` in `SmelterAction`) — i.e. "the fire went out,"
read from the gob's model-attribute flame bit. None of them reads the per-item `GItem.MeterInfo`
percent that `NKilnInfo`/`NSmelterInfo` already expose for exactly these workstation types.** This is
not a fixed delay and not a raw inventory-change poll — it is a real, server-driven, authoritative
signal (confirmed safe against the documented `WorkBellows`/`LightObject` mesh-bit edge cases, e.g.
the 65540 "burnt out but metal still inside" state) — but it answers "is this station still burning,"
not "is the item inside done." Those two questions only coincide when the fuel load is sized to burn
out at (or very near) the moment the batch finishes — which is exactly the assumption `BakerAction`'s
hardcoded `maxlvl=4` makes and does not verify against the actual recipe time for whatever dough was
loaded.

**6. What happens if another player adds fuel or changes contents mid-run?** **Not traced by reading
code this session** — no bot in the table re-validates its own "4 fuel units will burn out around when
the batch finishes" assumption against a changed fuel load; `Container.Space`/`Container.FuelLvl` are
read at the bot's own decision points, not continuously monitored for interference from outside the
bot's own actions. This is a plausible mechanism for the Baker bug (next section), not a confirmed one
— no live repro, no log of a real over-fueling incident read.

**7. Can finished items burn/spoil/disappear while waiting?** Confirmed true for baked goods by the
nature of baking (and this is the premise of the Baker investigation below); not independently
verified for kiln/furnace products (pottery/ash/metal don't obviously "spoil" the same way, though an
over-fired item could plausibly degrade — not checked).

**8. Interrupted production / disconnection / retry:** Not traced this session for this capability
area specifically — the general interrupt/cancellation mechanism already verified for the bot
lifecycle framework (`capability-bot-lifecycle.md` sessions 2-3: `NTask`/`criticalExit`,
`BotExecutor`'s single interrupt catch point) applies to every `Action` in this table the same way it
applies to any other bot, since none of these bots override that behavior. What's specific to this
capability area — e.g. "if the bot is killed mid-cycle with dough already in the oven and the fire
already lit, does anything clean that up on the next run" — was not traced.

## Phase 3: Baker bot investigation (priority)

**The bug mechanism, as far as static reading establishes it:** `BakerAction.run()`'s loop (lines
50-86) is: wait for `WaitForBurnout(lighted, 4)` → `FreeContainers` (unload) → refill with dough →
refuel → relight. **The wait condition is purely "oven fire flag cleared," with no check of the
baked item's own doneness.** `Container.FuelLvl.setMaxlvl(4)` hardcodes 4 branches worth of fuel for
every oven regardless of what's loaded. **If 4 branches' burn time outlasts the actual bake time for
the loaded dough** (different dough/recipe items plausibly bake for different durations — not
individually checked here, but the Phase 1 table's `KilnFuelCatalog` precedent shows exactly this
kind of per-item duration table exists and is maintained for kilns), **the pies finish baking and sit
fully done inside a still-burning oven until the fire itself goes out — exactly the window in which
continuing heat can burn an already-finished product.** This does not require "unexpected fuel added
by another player" to occur; a fixed, too-generous fuel constant is sufcient on its own. An outside
player topping off the oven's fuel mid-cycle would extend that same unsupervised-burn window further,
compounding rather than causing the defect.

**Why this specific finding is higher-confidence than a guess:** the exact same architecture gap — a
fuel-load constant picked once, with no per-item completion check — is confirmed in `BakerAction`
(dough), `BricksAction` (clay/brick), `BlockAshAction`/`BoneAshAction` (ash), and `SmelterAction`
(ore/steel) alike. **Every one of these bots could in principle overshoot the same way**; baked goods
are simply the item type where "overshoot" has an obvious, named failure mode (burning) rather than a
subtler one (over-fired pottery, wasted fuel, idle furnace time). `SmelterAction`'s own code comments
(read in Phase 1 grep) already document a related, previously-fixed bug in this family — fuel running
out *early* "trips the burnout wait and has FreeContainers pull half-smelted ore" — confirming this
class of fuel/completion-timing mismatch has caused at least one other, previously-handled defect in
a sibling bot, not just a single Baker-specific guess.

**What would close the gap, per the capability already sitting unused (Phase 2 finding #3):** ovens
would need their own `GItem.MeterInfo`-based completion check — the same mechanism `NKilnInfo`
already reads for kiln tooltips — rather than (or in addition to) `WaitForBurnout`. This is not
implemented or proposed as code in this session; flagged for the modernization backlog.

**Not reproduced live** — this is a static-reading-derived mechanism, consistent with the user's own
framing of the bug ("completion logic can cause finished products to burn when unexpected fuel is
added") and with `SmelterAction`'s documented sibling bug, but not confirmed against an actual
burned-pie incident or log.

## Phase 3: comparison matrix — sophistication and consistency

| Bot | Fuel sizing | Completion signal | Loop/self-sustain | Notable sophistication or gap |
|---|---|---|---|---|
| `BakerAction` | Fixed `maxlvl=4` | `WaitForBurnout` only | Yes | No per-item completion check — see Baker investigation above |
| `BricksAction` | Fixed `maxlvl=2` | `WaitForBurnout` only | Yes | Same gap class as Baker, lower-severity failure mode (pottery, not food) |
| `BlockAshAction`/`BoneAshAction` | Fixed `maxlvl=3/6/8` | `WaitForBurnout` only | Yes | Same gap class |
| `SmelterAction` | Fixed `maxlvl=12`, **`credolvl=9`** (refuel-trigger threshold distinct from max) | `WaitBurnoutOrBellows` (bellows-boost-aware) | Yes | **Most sophisticated bot in the table** — the only one with both a documented, debugged bellows-state model (`WorkBellows`'s mesh-bit comments) and a non-max refuel trigger; also the only bot whose code comments document a previously-fixed fuel-timing bug (early-burnout pulling half-smelted ore) |
| `SteelAction` | Fixed `maxlvl=15`, `absMaxlvl=18` | **None — single-shot, no wait, no unload, no loop** | No | Structurally inconsistent with every other bot in the table; not necessarily a bug (may be an intentional "light and walk away" design, paired with a separate manual/scenario-driven unload step), but not verified as intentional either — flagged, not fixed |
| `LyeBoiler` | Fixed `maxlvl=40`, **`fuelmod=5`** (fuel-per-item multiplier, documented inline: "branch gives 5 fuel, not 1") | `WaitForBurnout`-family (not individually re-verified this session) | Yes | Fuel-multiplier concept not seen in any other bot — real per-fuel-type potency differences exist and only this bot models them |

**Duplicate logic:** the wait→unload→refill→refuel→relight loop body is structurally repeated,
nearly line-for-line, across `BakerAction`/`BricksAction`/`BlockAshAction`/`BoneAshAction` — the kind
of candidate for shared functionality the task asked about (see Phase 4).

**Wasteful polling / overhead:** not specifically identified this session — `Container.update()`'s
call frequency and `WaitForBurnout`'s poll cadence were not profiled; flagged as unverified, not as a
finding.

## Phase 4: shared workstation abstraction — is one appropriate?

**Genuine differences found between workstation types, per the user's caution against one universal
controller:**

- **Fuel potency differs by type** (`LyeBoiler`'s `fuelmod=5` has no equivalent elsewhere) — a shared
  abstraction must keep this as a per-station parameter, not assume 1 fuel item = 1 fuel unit.
- **Completion signal differs by mechanism, not just by flag value**: plain fire-flag
  (`WaitForBurnout`, most bots) vs bellows-aware (`WaitBurnoutOrBellows`, stack furnaces specifically)
  — a shared abstraction needs this to be pluggable per station type, not a single check.
- **Refuel trigger differs**: most bots refuel only after full burnout; `SmelterAction` alone refuels
  at a `credolvl` threshold below max. A shared abstraction that assumed "always refuel to max from
  zero" would regress `SmelterAction`'s more sophisticated behavior.
- **Loop cardinality differs**: `SteelAction` doesn't loop at all; everything else does. Forcing a
  shared "run forever" abstraction onto `SteelAction` would be a behavior change, not a refactor,
  unless its current one-shot design is confirmed unintentional first.
- **Observed-completion tooling exists for only 2 of ~7 station types** (Phase 2 finding #3) — a
  shared abstraction that tried to use `GItem.MeterInfo`-based completion uniformly today would work
  for kiln/smelter items and silently have nothing to read for oven/cauldron/tarkiln items, since no
  `NOvenInfo`/`NCauldronInfo` equivalent exists yet to confirm the server even exposes an equivalent
  meter for those item types.

**Recommendation, consistent with the "avoid speculative abstraction layers" instruction and with
`docs/fork-sync-guide.md`'s low-conflict development policy:** do **not** build one universal
workstation controller. The better-supported move is **extracting the already-duplicated
wait→unload→refill→refuel→relight loop body** (confirmed near-identical across 4 bots) into a single
shared fork-owned helper parameterized by the differences found above (completion-signal strategy,
refuel-trigger threshold, fuel potency) — closer to "de-duplicate an existing pattern" than "design a
new abstraction." Extending `GItem.MeterInfo`-based completion checking to ovens specifically (for the
Baker fix) is a narrower, separate piece of work from that de-duplication, and should not be blocked
on it.

## Phase 5: upstream compatibility

**Every file read or referenced in this session — all 31 checked via `git diff upstream/master` — is
byte-identical to upstream.** This entire capability area, unlike bot lifecycle's bot/task layer
(which had a handful of fork-modified files), currently carries **zero fork divergence**. Any change
proposed here opens a new seam.

Upstream churn (commits on `upstream/master`, most recent first by relevance):

| File | Commits | Last upstream touch |
|---|---|---|
| `actions/bots/SmelterAction.java` | 31 | 2026-10-25 ("Smelter: walk back onto the smelters after unloading") |
| `tools/Container.java` | 30 | 2026-09-15 ("feat/fuel zones") |
| `actions/bots/BricksAction.java` | 9 | 2026-09-15 |
| `actions/bots/LightObject.java` | 8 | 2026-10-12 ("feat/smokeship smokering bot") |
| `actions/bots/BakerAction.java` | 7 | 2026-09-15 |
| `actions/bots/SteelAction.java` | 7 | 2026-09-15 |
| `tasks/WaitForBurnout.java` | 6 | 2025-11-22 |
| `tasks/WaitBurnoutOrBellows.java` | 2 | 2026-08-17 |
| `actions/WorkBellows.java` | 2 | 2026-08-17 |
| `tools/KilnFuelCatalog.java` / `iteminfo/NKilnInfo.java` | 1 each | 2026-09-08 ("feat/kiln and smelter helpers") — i.e. the meter-based observed-state tooling itself is **brand new**, about two months old at the time of this audit, not a mature/stable surface |

**Highest-risk files for any fork change:** `SmelterAction.java` and `Container.java` — both very
actively developed upstream (30+ commits each, most recent within the last two weeks of upstream
history relative to this audit). A fork change to either has real odds of landing adjacent to
upstream's own next commit. **Lower-risk:** `WaitForBurnout.java`/`WorkBellows.java`/
`WaitBurnoutOrBellows.java` (2-6 commits, months-old last touch) — a fork addition here is less likely
to collide soon, though per `fork-sync-guide.md`'s own caution, "rarely touched" is not a correctness
guarantee if upstream does revisit it.

**Recommended shape, per the task's preference for reuse over invasive rewrite:**

- **The Baker fix** (adding a meter-based completion check) should be built as a **small, additive,
  oven-specific addition** — e.g. an `NOvenInfo` parallel to `NKilnInfo` plus a narrow check inside
  `BakerAction`'s own loop condition — rather than editing `WaitForBurnout` itself (used by 5+ other
  bots) or `Container` (30-commit high-churn file). This keeps the change small and localized per
  `fork-sync-guide.md`'s low-conflict policy, and avoids the two highest-churn files entirely.
- **The de-duplication opportunity** (Phase 4) should be a **new, fork-owned shared helper** that the
  4 near-identical bot loops are migrated to call, not a rewrite of `BakerAction`/`BricksAction`/etc.
  in place — new file, narrow call-site changes in each bot, same shape as the bot-lifecycle area's
  own "fork-owned implementation, narrow shared hook" guidance.
- **`SmelterAction`/`Container.java` should not be touched speculatively.** Given their churn, any
  change there should be deferred until there's a concrete, specific need (e.g. the credo-threshold/
  bellows model is genuinely the best reference implementation to copy *patterns* from, not a file to
  edit directly for an unrelated fix).

**Regression risk:** since this area carries zero current divergence, any upstream change to these
files merges clean *today* — the risk, same as the bot lifecycle area's own framework files, is
purely semantic: if upstream changes `WaitForBurnout`'s flag semantics, `Container.FuelLvl`'s field
meanings, or `LightObject`'s `LightConfig` registry, every characterization in this document needs
re-verification against the new text before being relied on again.

## Not covered in this session

- `GelatinAction`, `TarkilnAction`, `FriedFish`, `SmokeringBot` — referenced/grepped but not read in
  full; their exact completion-detection and fuel-sizing logic is inferred from the shared-primitive
  calls found, not individually confirmed.
- Whether `LightGob` is a thin wrapper over `LightObject` or a separate, older implementation — not
  checked; `LightObject` itself was read in full (used directly by `SmokeringBot`), `LightGob` was
  not.
- `Validator`'s `.fuel(...)` pre-flight check — not read; unclear what it actually verifies before a
  bot proceeds (e.g. does it check fuel-zone existence, current stock, both, neither).
- Whether `Container.FuelLvl`'s hardcoded per-bot constants (`maxlvl`, etc.) were derived from and
  still match the server's actual per-gob capacity, or are a separate, unverified guess parallel to
  `KilnFuelCatalog`'s wiki-sourced table.
- Live reproduction of the Baker bug, or of fuel-interference from another player — both findings in
  this document are static-reading-derived, consistent with the user's framing and with
  `SmelterAction`'s documented sibling bug, but not reproduced.
- Interrupted-production/disconnection recovery specific to this capability area (Phase 2, item 8) —
  only the generic bot-lifecycle cancellation mechanism was confirmed to apply; nothing
  workstation-specific (e.g. "is there leftover dough/fuel state that needs cleanup on the next run")
  was traced.
- `SteelAction`'s single-shot design — flagged as an inconsistency, not confirmed as intentional or
  unintentional.
- Polling overhead / wasteful scanning — not profiled.

## Suggested session 2 scope (now session 1's own leftover — see below for what session 2 actually did)

Pick one: (a) read `GelatinAction`/`TarkilnAction`/`FriedFish`/`SmokeringBot` in full to complete the
Phase 1 inventory's loop-shape column with confirmed (not inferred) detail, or (b) design the
oven-specific meter-check addition for the Baker fix (Phase 3) in enough detail to size it properly
for the modernization backlog — without yet writing code, per the no-implementation constraint. Either
is a bounded next step; don't attempt a full `Validator`/`LightGob` trace plus both of the above in
one pass. **Still open** — session 2 widened scope instead (per explicit instruction) rather than
closing this out; both items remain available for a future session.

---

# Session 2: comprehensive workstation discovery (expanded scope)

**Why this session exists:** the session-1 inventory only covered bots using
`FuelToContainers`/`Container.FuelLvl`/`WaitForBurnout` — the fuel-loop production bots. The client has
a second, much larger workstation-interaction surface: the **generic crafting-window path**
(`NMakewindow`/craft recipes), which covers most "stand at a station and make something" interactions
that never touch a fuel loop at all. This section finds and classifies that surface using multiple
independent discovery approaches (bot/action grep, `NContext`'s own workstation registries, hitbox
registry, building-name registry), per the instruction not to declare the inventory complete from one
approach alone.

## Phase 1: comprehensive discovery — cross-checked against 4 independent sources

**Source 1 — `NContext`'s own workstation registry** (`src/nurgling/areas/NContext.java:114-142`,
VERIFIED_CODE). This is the authoritative client-side list of workstations the *generic* crafting path
(`Craft.java`, used by `AutocraftBot` and any recipe-driven bot) already knows how to walk to, and,
where relevant, which animation/pose the character takes:

| Resource (`gfx/terobjs/...`) | Build pagina | Pose taken (`workstation_map`) | `Specialisation.SpecName` (fuel/area routing) |
|---|---|---|---|
| `meatgrinder` | `paginae/bld/meatgrinder` | `gfx/borka/idle` | `meatgrinder` |
| `churn` | `paginae/bld/churn` | `gfx/borka/churnan-idle` | `churn` |
| `loom` | `paginae/bld/loom` | `gfx/borka/loomsit` | `loom` |
| `swheel` (spinning wheel) | `paginae/bld/swheel` | `gfx/borka/swheelan-idle` | `sswheel` |
| `ropewalk` | `paginae/bld/ropewalk` | `gfx/borka/idle` (no dedicated animation found) | `ropewalk` |
| `potterswheel` | `paginae/bld/potterswheel` | `gfx/borka/pwheelidle` | `potterswheel` |
| `crucible` | `paginae/bld/crucible` | *(none — `null`)* | `crucible` |
| `pow` (fireplace) | *(lit via inventory fire item)* | *(none)* | `pow` |
| `cauldron` | *(lit via inventory cauldron item)* | *(none)* | `boiler` |
| `anvil` | `paginae/bld/anvil` | *(none)* | `anvil` |

**Source 2 — fuel-zone registry** (`src/nurgling/tools/FuelZones.java`, session 1, VERIFIED_CODE):
smelter, steelbox (crucible-type), fforge (finery forge), kiln, oven, cauldron (`boiler`), fireplace
(`pow`), crucible, tarkiln. This is the fuel-consuming subset from session 1 — note it does **not**
overlap 1:1 with Source 1's list (e.g. `anvil`/`ropewalk`/`loom`/`churn`/`swheel`/`potterswheel` take
no fuel and aren't in `FuelZones`; `kiln`/`smelter`/`tarkiln`/`fforge` take fuel but aren't in
`workstation_map` at all — see the gap noted below).

**Source 3 — hitbox registry** (`src/nurgling/NHitBox.java`, VERIFIED_CODE, interaction-footprint data
only) lists collision/interaction boxes for a *wider* set than either list above, including
**`gfx/terobjs/quern`** and **`gfx/terobjs/smelter`** by name. `quern` appears **only** here — it has a
registered hitbox but no entry in `workstation_map`/`workstation_spec_map`/`FuelZones`. **This is a
confirmed gap, not a hypothesis:** the quern is a known, interaction-capable gob in the client's own
data, but no workstation-automation path (fuel-loop or generic-craft) currently addresses it.

**Source 4 — building-name / build-menu registry** (`src/nurgling/actions/bots/blueprint/BuildingNames.java`,
`BuildPaginae.java`, VERIFIED_CODE, used by the blueprint/construction bots, not production bots)
independently confirms names for `crucible`, `pow` ("Fireplace"), `kiln`, `smelter`, `tarkiln`, `anvil`,
`cauldron` ("Clay Cauldron"/"Metal Cauldron" — two distinct gob variants sharing one resource-name
prefix), `ropewalk`. This cross-check didn't surface any station absent from Sources 1-3, but it did
confirm the cauldron has (at least) two build-time variants — not separately modelled in
`workstation_map`'s single `cauldron` entry, which doesn't distinguish them.

**Coverage status, explicitly:**

- **Audited (code read, behavior traced):** fuel-loop bots (session 1, see table above);
  `Craft.java`/`UseWorkStation.java`/`PrepareWorkStation.java`/`PrepareCauldron.java` (this session, see
  Phase 2); `FollowAndPose` task (this session).
- **Partially audited (located, cross-referenced across sources, not individually read):** `meatgrinder`,
  `churn`, `loom`, `swheel`, `potterswheel`, `anvil`, `ropewalk` as *stations* — their entries in
  `workstation_map` are read, but the actual bots/recipes that drive them (e.g. whatever calls `Craft`
  with a loom recipe) were not individually traced this session.
- **Undiscovered/unknown:** querns (hitbox only, no automation path at all — confirmed gap, not
  absence-of-evidence); drying/processing structures beyond `TarkilnAction`/`SmokeringBot`
  (session 1's "not covered" list); whether there are additional gob types with a dedicated
  interaction handler but zero mention in any of the 4 sources above (not ruled out — a `src/haven/res`
  server-script-level search was not performed this session, see "Not covered").

## Phase 2: capability classification — the generic crafting path vs the fuel-loop path

**The single most important session-2 finding:** the client has **two structurally different
workstation-automation paths**, not one, and session 1 only investigated the second:

1. **The generic recipe/crafting-window path** (`Craft.java`, `UseWorkStation.java`,
   `PrepareWorkStation.java`, `PrepareCauldron.java` — all read in full this session, VERIFIED_CODE).
   This is what `AutocraftBot` and any `NMakewindow`-driven recipe use. It:
   - Walks to the station (`PathFinder`), optionally using a pre-shared barrel approach-point
     (`findNearestCommonApproachPoint`) when a barrel-based ingredient is involved.
   - For **position-changing stations** (loom, churn, spinning wheel, meatgrinder, ropewalk — anything
     with a non-null `pose` in `workstation_map`), issues a `click` on the gob, then adds a
     `FollowAndPose` task that waits for `gob.getattr(Following.class) != null && pose-match` —
     **this is a real, server-reported "the character is now attached to and working this station"
     signal (the `Following` attribute), not a client-side guess or fixed delay.** For stations with
     no pose (crucible, anvil, pow, cauldron), it instead waits on `IsMoving`/`WaitPose(idle)` — i.e.
     it assumes a simple walk-up-and-click interaction with no sustained attachment.
   - For **pow (fireplace)** and **cauldron**, it special-cases opening via `SelectFlowerAction("Open",...)`
     and waits for a *window* (`WaitWindow("Fireplace"/"Cauldron")`) — these two have a **dedicated
     inventory/workstation widget**, confirmed distinct from the plain-click stations.
   - Delegates ingredient/output logistics, barrel transfer, recipe execution (`mwnd.wdgmsg("make",1)`),
     and retry-on-error (`craftProc`, up to 3 retries, watching `gui.prog`/`getLastError()`) all through
     the normal crafting-window protocol — i.e. **the server's own crafting progress bar and error
     messages are the completion/failure signal here, not a model-attribute poll.** This is a materially
     different and, on the evidence read, more robust completion-detection mechanism than the fuel-loop
     path's "wait for fire to go out."
   - Handles **crucible fuel prep** (`PrepareWorkStation.fillCrucible`) as a one-time pre-check keyed off
     `getModelAttribute()&2` (a lit/fuelled bit) before the craft starts, not a sustained loop — structurally
     closer to `SteelAction`'s single-shot pattern (session 1) than to the Baker/Bricks/Smelter loop.

2. **The fuel-loop path** (session 1): bots that own their station end-to-end — light it, feed it,
   wait for fire-out, unload, repeat — with no normal crafting-window interaction at all (the "craft"
   happens passively inside the lit container over time, e.g. dough → bread).

**Position-changing interaction range — the specific question the task asked about.** Traced via
`FollowAndPose`/`UseWorkStation`: the client does **not** compute or re-verify an interaction range for
pose-locked stations at all once attached — it relies entirely on the server-reported `Following`
attribute as the gate. Before attachment, the normal `PathFinder`/click-range logic applies (based on
the gob's current, i.e. latest-received, `rc` — no evidence found of the client using a stale
"original" position; `Gob.rc` is updated on every server position message and all pathfinding/dist
reads consulted it live). **While attached and posed, the client issues no further position or range
checks for the duration of the pose** — it's purely waiting on the `Following`/pose signal. This means
**nearby container interactions (e.g. a barrel beside a loom) during a pose-locked state were not
found to have any special-cased range logic** — `UseWorkStation`'s barrel-approach-point logic
(`findNearestCommonApproachPoint`) runs *before* the station click, to position the character somewhere
that's in range of both gob and barrel simultaneously, not *during* the pose. Whether a barrel
interaction attempted *while* pose-locked would actually succeed server-side was not tested — this is
UNKNOWN / not traced (would need runtime observation, see "Not covered").

**Dedicated workstation widgets — confirmed inventory, this session:** `Fireplace` window, `Cauldron`
window (both via `WaitWindow`, `UseWorkStation.java:89,93`), `Kiln`/`Smelter` tooltips (session 1,
`NKilnInfo`/`NSmelterInfo` — these are *item*-level tooltips inside the general inventory, not a
station window). **No dedicated window class was found for** loom/churn/spinning
wheel/meatgrinder/ropewalk/potterswheel/anvil/crucible/quern — these all go through the normal
crafting-window (`Makewindow`/`NMakewindow`, `src/haven/Makewindow.java`) instead, confirmed by
`Craft.java`'s own code path (it reads `gui.craftwnd.makeWidget`, the shared crafting window, for every
station it handles that isn't pow/cauldron).

## Phase 2 (continued): quality dependencies, interference, and per-field classification

Per-station fields are recorded compactly below rather than per-station prose, since most of this
session's stations share the same mechanism (generic crafting window) and differ mainly in pose/widget,
already tabulated above.

| Field | Finding | Classification |
|---|---|---|
| Required positioning/range | `PathFinder` walk-to for all; pose-locked stations additionally wait on `Following` attribute | VERIFIED_CODE |
| Changes character position | Yes for loom/churn/swheel/meatgrinder/ropewalk (pose-locked via `Following`); no for crucible/anvil/pow/cauldron/quern (plain click-range) | VERIFIED_CODE |
| Dedicated inventory/widget | Yes: Fireplace, Cauldron windows. No (uses generic craft window): loom/churn/swheel/meatgrinder/ropewalk/potterswheel/anvil/crucible. Quern: UNKNOWN (no code reference found at all beyond the hitbox) | VERIFIED_CODE for the "yes" and most "no" cases; UNKNOWN for quern |
| Crafts via normal crafting interface | Yes, for every station except the fuel-loop bots (session 1) and crucible's one-time fuel pre-check | VERIFIED_CODE |
| Observable state passively vs on-interact | Model-attribute bits (fire/fuel/bellows/embers) are readable from any loaded gob without opening anything (passive) — confirmed session 1. `GItem.MeterInfo` requires the item to be a known item instance with info loaded, effectively "the item is visible/in an openable container" — not fully "passive" at a distance, not fully "interact-required" either; not pinned down further this session | VERIFIED_CODE (model attrs); INFERRED (meter-info visibility conditions) |
| Completion/failure/readiness determination | Fuel-loop path: fire-out model-attribute bit (session 1). Generic-craft path: crafting-window progress bar + `getLastError()` (this session) | VERIFIED_CODE, both paths |
| Other players modifying state during automation | Not traced for the generic-craft path this session (session 1 flagged this as unconfirmed for the fuel-loop path too) | UNKNOWN |
| Quality dependencies/restrictions | Not traced this session — `NMakewindow.Spec`/`Ingredient` carry per-ingredient data but whether/how quality thresholds gate a recipe was not read | UNKNOWN (flagged for Phase 3 wiki research, not yet done — see below) |
| Current automation limitations | Quern has zero automation path (confirmed gap). Cauldron's two build variants aren't distinguished in `workstation_map`. Generic-craft retry logic (`craftProc`) hardcodes 3 retries with no backoff/logging beyond `System.out` | VERIFIED_CODE |

## Phase 3: Ring of Brodgar wiki research — not performed this session

**Explicitly not done.** This session was entirely code-discovery (Phase 1-2); no web research against
`ringofbrodgar.com` was performed, so there is nothing yet classified as `WIKI_DOCUMENTED` in this
registry beyond session 1's pre-existing `KilnFuelCatalog` note (itself wiki-sourced, already flagged
there as unverified against World 16.2). **This is the primary gap for a session-3 continuation** —
per the task's own classification scheme, every quality-formula/fuel-potency/timing question flagged
`UNKNOWN` above is a candidate for `WIKI_DOCUMENTED` (pending a wiki read) or `VERIFIED_RUNTIME`
(pending live observation), not for silent resolution from code alone, since (per the task's own
caution) client code does not establish server-side formulas.

## Phase 4: modernization re-evaluation — session 2 additions only

Session 1's Phase 4/5 findings (reject one universal controller; de-duplicate the 4-bot fuel-loop body;
avoid `SmelterAction.java`/`Container.java`) stand unchanged. Session 2 adds:

- **The generic-craft path's retry/progress-bar completion detection (`Craft.craftProc`) is a more
  robust model than the fuel-loop path's fire-out check**, and is already proven across every
  non-fuel-loop station. This strengthens (does not newly establish) the session-1 Baker recommendation:
  the fix direction of "read real per-item/per-station state instead of inferring from a proxy signal"
  has a working precedent elsewhere in the same codebase, not just the kiln/smelter meter tooltips.
- **The quern gap is a genuine new opportunity, not a bug** — nothing is broken, a capability simply
  doesn't exist yet. Flagged for the backlog as a feature gap, distinct in kind from the Baker
  correctness issue.
- **No evidence supports building one shared "workstation controller" across both paths** — the
  generic-craft and fuel-loop paths solve different problems (bursty recipe execution with a progress
  bar vs sustained unattended production) with different, already-adequate mechanisms. Unifying them
  would be exactly the kind of speculative abstraction the task warned against.

## Phase 5/6: upstream compatibility — session 2 files

```
git diff upstream/master -- src/nurgling/actions/UseWorkStation.java src/nurgling/actions/PrepareWorkStation.java \
  src/nurgling/actions/PrepareCauldron.java src/nurgling/actions/bots/Craft.java \
  src/nurgling/tasks/FollowAndPose.java src/nurgling/areas/NContext.java src/nurgling/NHitBox.java
```

Not yet run this session (code-reading budget spent on discovery breadth per the task's own
"breadth-first before expensive deep dives" instruction) — **flagged as the first action item for a
session-3 continuation**, before any of the session-2 findings are used to scope a change. Given
`Craft.java`'s size (~1080 lines, clearly under active fork development — note the Russian-language
inline comments and the detailed `savedRecipeName`/`refreshMakeWidget` fire-lighting-recipe-restore
logic, which reads as fork-specific hardening around an upstream base) this file in particular should
be assumed likely fork-modified until checked, not assumed identical the way session 1's 31 files were.

## Not covered in session 2

- Ring of Brodgar wiki research (Phase 3) — entirely deferred, see above.
- Upstream diff/churn for every file read this session (Phase 5/6) — deferred, see above.
- Individual tracing of loom/churn/spinning-wheel/meatgrinder/ropewalk/potterswheel/anvil *bots or
  recipes* (as opposed to their `workstation_map` entries) — not read.
- Quern: confirmed as a gap, not investigated further (no code exists to read).
- Whether a barrel/container interaction attempted while pose-locked at a station actually succeeds
  server-side — would need runtime observation, not static reading.
- Quality-dependency mechanics anywhere in the generic-craft path — not traced.
- A `src/haven/res` (server-script) level search for additional gob/workstation types not referenced
  anywhere in `src/nurgling/**` — not performed; the 4-source cross-check in Phase 1 is multi-source
  within the fork's own code, not a search of the full upstream resource tree.

## Suggested session 3 scope (superseded — see Session 3 below, which completed all three)

In order of likely value: (1) run the upstream diff/churn check on the 7 session-2 files listed above
— cheap, mechanical, and currently the single biggest unverified claim in this document; (2) do the
Ring of Brodgar wiki pass (Phase 3) for the stations session 1 already covers in depth (oven/kiln/
smelter/cauldron) before extending to the session-2 stations, since that's where the Baker-fix backlog
item most needs a verified-vs-World-16.2 fuel/timing baseline; (3) only after both of those, consider
individually reading the loom/churn/spinning-wheel family. Don't attempt all three in one pass.

---

# Session 3: completing upstream verification, deferred implementations, wiki research, and quality

Per explicit instruction, this session completed the investigation through to a consolidated registry
rather than stopping after one phase. Covers: (1) the upstream diff/churn check deferred from session 2,
(2) `GelatinAction`/`TarkilnAction`/`FriedFish`/`SmokeringBot` read in full plus the loom/churn/
spinning-wheel family traced, (3) Ring of Brodgar wiki research performed via a local, offline,
zero-network method, (4) quality-dependent production traced end to end.

## 1. Upstream compatibility — session 2's 7 files, now verified

```
git diff upstream/master -- <file>  (0 lines for every file below)
git log upstream/master --oneline -- <file>
```

| File | Diff vs upstream | Upstream commits | Last upstream touch |
|---|---|---|---|
| `actions/UseWorkStation.java` | **Identical** | 10 | 2026-04-24 |
| `actions/PrepareWorkStation.java` | **Identical** | 13 | 2026-09-15 |
| `actions/PrepareCauldron.java` | **Identical** | 4 | 2026-09-15 |
| `actions/bots/Craft.java` | **Identical** | 47 | 2026-10-20 |
| `tasks/FollowAndPose.java` | **Identical** | 2 | 2025-03-07 |
| `areas/NContext.java` | **Identical** | 67 | 2026-10-20 |
| `NHitBox.java` | **Identical** | 68 | 2026-10-22 |

**Correction to session 2's own speculation:** session 2 flagged `Craft.java` as "likely fork-modified"
based on its size and inline Russian-language comments, explicitly warning not to assume it was
upstream-identical the way session 1's files were. **That speculation was wrong — `Craft.java` is
byte-identical to upstream.** The Russian comments, the `savedRecipeName`/`refreshMakeWidget`
fire-lighting-recipe-restore logic, and the whole prefilled/storage/barrel machinery are **upstream's
own code**, not fork additions. This is logged as a correction, not silently fixed, per the standing
instruction not to resolve contradictions quietly: a plausible-sounding structural inference (file
size + comment language suggesting fork work) was wrong, and only the actual diff command resolved it.
**General lesson for this audit:** file size, comment density, or comment language are not reliable
fork/upstream signals in this codebase — only `git diff upstream/master` is.

**All 7 files carry zero fork divergence**, same as every file checked in sessions 1-2. `Craft.java`
(47 commits), `NContext.java` (67 commits), and `NHitBox.java` (68 commits) are **the highest-churn
files found anywhere in this capability area across all three sessions** — all three are substantially
more actively developed upstream than `SmelterAction.java` (31) or `Container.java` (30), the
previous highest-churn files from session 1. **Any fork change touching `NContext.java`'s
`workstation_map`/`workstation_spec_map` (e.g. the quern gap, backlog item 9) inherits this as its
single biggest upstream-collision risk in the entire capability area** — more so than anything in the
fuel-loop path.

## 2. Deferred implementations, read in full

### `GelatinAction` — confirmed: generic-craft path, not fuel-loop

Uses `ncontext.workstation = new NContext.Workstation("gfx/terobjs/cauldron", null)` +
`PrepareWorkStation` (one-time fuel/water/fire prep, reusing the same cauldron prep as the fuel-loop
`LyeBoiler`) + `Craft(mwnd, 1, true)` in **prefilled mode** (ingredients already in inventory, skips
the normal `TakeItems2` ingredient-fetch step) for the actual craft. **This is the first confirmed
case of a bot mixing both paths**: fuel-loop-style one-time station prep, followed by generic-craft
execution — not a third mechanism, but evidence the two paths already compose at the call-site level
rather than being mutually exclusive architectures.

### `TarkilnAction` — confirmed: single-shot, same pattern as `SteelAction`

Checks `(tarkiln.ngob.getModelAttribute() & 4) != 0` to skip already-burning tarkilns, collects coal
*from the tarkiln itself* via `CollectFromGob` (pose `gfx/borka/bushpickan`), fills fuel via
`FillFuelTarkilns`, lights via `LightGob(flighted, 16)` (fire bit **16** — confirming `LightConfig`'s
fire-flag bit genuinely differs per station type: tarkiln=16 vs pow/cauldron=4, per session 1's
`LightObject` reading), and **returns without any wait/unload/loop** — structurally identical to
`SteelAction`'s single-shot design (session 1, backlog item 8, previously flagged as an unexplained
inconsistency).

**This resolves backlog item 8's open question, directionally:** two independent bots (`SteelAction`,
`TarkilnAction`) share the exact same "light and walk away, no loop" shape. Two independent
occurrences of the same structural choice is stronger evidence of **intentional design** than one
isolated instance — it now reads as a recognized pattern for productions that don't need active
babysitting (steel-making, tar-making), as opposed to the wait→unload→refill→refuel→relight loop used
by bots whose product needs removing promptly (bricks, ash, baked goods). **Still not proof of intent**
(no comment or test confirms it either way), but the confidence should move from THEORETICAL toward
PLAUSIBLE. Backlog item 8 updated below.

### `FriedFish` — a third, previously unidentified automation mechanism

This bot does **not** use `Container`/`Container.FuelLvl` (fuel-loop path) or `Craft`/`NMakewindow`
(generic-craft path) at all. It drives a **roasting-spit overlay** (`haven.res.gfx.terobjs.roastspit.Roastspit`,
a `Gob.Overlay` sprite attached to a fireplace gob) directly:

- Completion/content state is read from **`((Roastspit) overlay.spr).getContent()`** — a sprite-level
  string ("raw", presumably "cooked"/similar) — **not** a model-attribute bitmask and **not** a
  crafting-window progress bar. This is a third, independent observable-state mechanism, confirmed by
  reading the code, not inferred.
- Position-changing interaction here uses **two distinct poses** for two distinct actions on the same
  gob: `gfx/borka/roasting` (turning the spit, via `SelectFlowerAction("Turn",...)`) and
  `gfx/borka/carving` (removing cooked fish, via `SelectFlowerAction("Carve",...)`) — confirming pose
  names are per-*action*, not just per-*station*, which neither session 1 nor 2 had evidence for.
- Busy-station detection reuses the same `Following` attribute session 2 found for `FollowAndPose`
  (`borka.getattr(Following.class)` checked against each candidate spit's `tgt`), confirming that
  mechanism generalizes beyond the `workstation_map` pose-locked stations to this third mechanism too.
- Fuel/fire handling at the end of the batch reuses the session-1 fuel-loop primitives directly
  (`FillFuelPowOrCauldron`, `LightGob(flighted, 4)`) — i.e. this bot's *fire* is managed by the
  fuel-loop path even though its *production state* is read through the roastspit overlay, not
  `Container`. **This means the three mechanisms found across this whole investigation (fuel-loop,
  generic-craft, roastspit-overlay) are not mutually exclusive alternatives a station "picks one of" —
  a single bot can and does compose them.**

**Revised Phase 1 taxonomy (supersedes session 2's two-path framing):** there are at least **three**
distinct observable-completion mechanisms in this codebase, and they compose:
1. Model-attribute bitmask (fire/fuel/bellows/embers) — fuel-loop path, session 1.
2. Crafting-window progress bar + server error message — generic-craft path, session 2.
3. Sprite-overlay content string (`Roastspit.getContent()`) — this session, `FriedFish` only so far.

### `SmokeringBot` — confirmed tangential, not a workstation bot

Reads an **equipped item's tooltip** (`ItemInfo.Contents`/`ItemInfo.Name`, parsed with a regex for
"X kg of Pipestuff") as its only "fuel gauge," and a **sprite mesh-flag bit on the player's own
composite** (`Composite.cequ`, bit 2) as its "lit" signal — neither a gob model-attribute, nor a
crafting window, nor a roastspit-style overlay. This confirms it is not a fourth workstation mechanism
but a genuinely different category (equipped-tool production with no station gob at all) and is
correctly out of scope for a *workstation* capability registry beyond this note.

### Loom / churn / spinning-wheel / meatgrinder / ropewalk / potter's wheel — confirmed: no dedicated bot exists

Grepped for every call site that constructs `NContext.Workstation` for these resources, and for every
reference to their `Specialisation.SpecName` entries (`loom`, `churn`, `sswheel`, `ropewalk`,
`potterswheel`, `meatgrinder`) outside of the registry definitions themselves and the `Specialisation`
settings-UI labels. **Found none.** The only confirmed `NContext.Workstation` construction sites
outside `Craft.java`'s own generic path are `FFAction.java` (anvil) and `GelatinAction.java`
(cauldron, above). **This confirms session 2's inference directly (not just by absence in one grep):
these six stations have zero purpose-built fork bot. Their only automation route is the generic
`AutocraftBot` + a manually configured recipe that happens to specify one of them as its workstation.**
This is a genuine, named capability gap for each of the six, distinct in kind from the quern gap
(backlog item 9) in that these six at least have a *generic* path already; quern has none at all.

### Ordinary crucible vs. steel crucible — confirmed: two distinct gob resources, two distinct automation paths

`PrepareWorkStation.fillCrucible`/`Craft.java`/`IrrlightBot.CRUCIBLE_RES` all target
**`gfx/terobjs/crucible`** (the plain crucible — glassmaking, generic-craft path, one-time fuel
pre-check gated on `getModelAttribute()&2`, no loop). `SteelAction` targets
**`gfx/terobjs/steelcrucible`** exclusively (confirmed via `Finder.findGobs(smelters, new
NAlias("gfx/terobjs/steelcrucible"))`, session 1 bot, single-shot per above). **These are two
different gob resource names, not two configurations of the same object** — the client's own data
treats them as unrelated station types, and the two automation paths that cover them (generic-craft
for the plain crucible, a dedicated fuel-loop-style single-shot bot for the steel crucible) are
correspondingly unrelated code, not a shared implementation with a branch.

### Cauldron variants — correction to session 2

Session 2 noted the clay/metal cauldron build-pagina split (`paginae/bld/ccauldron` /
`paginae/bld/mcauldron`) as "not separately modelled... not confirmed whether this is a gap." **This
session resolves it: it is not a gap.** Both build paginae construct the **same** gob resource,
`gfx/terobjs/cauldron` (confirmed in `BuildPaginae.java`, both `known("gfx/terobjs/cauldron", ...)`
calls target the identical resource string). Once built, a clay and a metal cauldron are
indistinguishable to every automation path read in this audit — there is nothing to separately model
because the client itself does not distinguish them past construction. The Ring of Brodgar wiki
confirms this independently at the quality-formula level (see below): the Lye formula names a generic
"Cauldron" quality term, and `QualityModel.toolMatches` explicitly treats "Cauldron" as matching
either "clay cauldron" or "metal cauldron" by name (`QualityModel.java` line 85) — i.e. the fork's own
quality-planning code already encodes this equivalence, consistent with what the gob data shows.

## 3. Ring of Brodgar wiki research — performed via local, zero-network extraction

**Retrieval method used, and why:** per the instruction to use the least expensive retrieval method,
this session did **not** fetch any wiki page over the network. The fork already ships
`src/nurgling/craft/recipes.bin` — a 132 KB offline, checksummed (SHA-256), zlib-compressed binary
database of recipe data **extracted from cited Ring of Brodgar page revisions**, read at runtime by
`nurgling.craft.WikiRecipes` (confirmed by reading `WikiRecipes.java` in full, docstring: *"No network
dependency in the client"*). Each recipe record embeds its own source `url` (validated at load time to
start with `https://ringofbrodgar.com/`), a page-revision id (`oldid=...`), and a `revised` timestamp
— i.e. **the fork already maintains exactly the kind of sourced, dated wiki extract this phase asked
for**, and reading it directly is strictly more precise (exact `oldid` revision, not just "the wiki as
of some browsing session") and zero-cost compared to a fresh web fetch. A small local Python script
(`parse_recipes.py`, written to the session scratchpad, not committed — mirrors `WikiRecipes.read()`'s
own format exactly: magic header, zlib inflate, SHA-256 verify, then a string table + indexed records)
was used to decode it and search by keyword. This is the "local scripts for repetitive discovery"
approach the task asked for, applied to Phase 3 specifically.

**Database provenance, read directly from its own header (VERIFIED_CODE — this is what the file itself
declares, read by the exact validation logic the client runs):**

- Origin: `https://ringofbrodgar.com/`
- Catalog fetched/assembled: `2026-10-05T07:26:53Z` — **about one month before this audit** (today:
  2026-11-05), and matching exactly the `craft/` subsystem's own upstream introduction date (all of
  `QualityModel.java`/`WikiRecipes.java`/`AtlasCatalog.java`/`CraftFlow.java`/`NCraftFlow.java` are
  1-commit-each upstream files, last touched 2026-10-05 per the diff check above) — this is a very
  recent, actively-maintained data source, not a stale historical dump.
  the game is currently World 16.2 unless the wiki's own underlying page content is itself stale for
  World 16.2 — that is a question about the wiki's own content, not about this extraction, and is
  explicitly not resolved here (see classification note below).
- Attribution (verbatim from the file): *"Ring of Brodgar contributors; factual recipe data extracted
  from cited page revisions. No article prose or images included."*
- 1,430 total recipes in the bundled catalog.

**Classification note, per the task's own caution:** every fact below is sourced from this database,
which cites a specific wiki page revision (`oldid`) and date per recipe. This is **WIKI_DOCUMENTED**,
not **VERIFIED_RUNTIME** — it reflects what the cited Ring of Brodgar page said as of its cited
revision, not independent confirmation that World 16.2's live server still computes it that way. Each
per-recipe `revised` date below is given so staleness can be judged per-recipe rather than assumed
uniform.

**Findings, by station, with exact source URL and revision date:**

| Station/product | Quality formula (verbatim, as the page states it) | Source | Page revised |
|---|---|---|---|
| Kiln — Brick | `(2·qClay + qFuel + qKiln) / 4` | [Brick, oldid=123691](https://ringofbrodgar.com/index.php?title=Brick&oldid=123691) | 2026-08-13 |
| Kiln — Bone Ash | `(2·qBone + qFuel + qKiln) / 4` | [Bone Ash](https://ringofbrodgar.com/index.php?title=Bone_Ash) (via matched catalog) | — |
| Kiln — Clay Jar/Pipe/Earthenware Platter | `(2·qUnburnt + qFuel + qKiln) / 4`, with qUnburnt itself = `(3·qClay + qPottersWheel)/4` | [Clay Jar](https://ringofbrodgar.com/index.php?title=Clay_Jar&oldid=…) and sibling pages (matched catalog) | varies |
| Kiln building itself | `qKiln = qClay`; a kiln-fired product's own formula = `(2·qUnburntProduct + qFuel + qKiln) / 4` | [Kiln, oldid=121396](https://ringofbrodgar.com/index.php?title=Kiln&oldid=121396) | 2026-02-16 |
| Ore Smelter/Stack Furnace — Bar of Tin (representative of the smelted-bar family) | `(2·qOre + qSmelter + qAvgFuel) / 4` | [Bar of Tin, oldid=118049](https://ringofbrodgar.com/index.php?title=Bar_of_Tin&oldid=118049) | 2025-02-16 |
| Cauldron — Lye | `(2·qAsh + qCauldron + qWater) / 4` | [Lye, oldid=117436](https://ringofbrodgar.com/index.php?title=Lye&oldid=117436) | 2025-01-30 |
| Oven — Bread | **No formula documented**; `kind=process`, `expression="Bread Dough"`, `formulas=[]` — i.e. the page records bread's quality as inherited 1:1 from its Bread Dough input, with **no oven/fuel quality term at all** | [Bread, oldid=123948](https://ringofbrodgar.com/index.php?title=Bread&oldid=123948) | 2026-08-13 |
| Steel Crucible — Bar of Steel | **No formula documented** on this specific page (`formulas=[]`); ingredients listed generically as "Bar of Wrought Iron, Coal or Black Coal, Fuel" | [Bar of Steel, oldid=124796](https://ringofbrodgar.com/index.php?title=Bar_of_Steel&oldid=124796) | 2026-08-29 |
| Quern — Any Flour | No quality formula recorded; tool="Quern", requirement="Farming" | [Any Flour, oldid=117805](https://ringofbrodgar.com/index.php?title=Any_Flour&oldid=117805) | 2025-02-09 |

**This is the single most important Phase 3/4 finding, cross-validated across 5+ independently-cited
pages (Brick, Bone Ash, Clay Jar, Clay Pipe, Earthenware Platter all share the identical
`(2·x + qFuel + qKiln)/4` shape):** every **kiln-fired** and **smelted** product's wiki-documented
quality formula gives **fuel quality a direct, non-trivial weight (1 part in 4)** in the final
product's quality. **Bread is the documented exception** — its own page records no fuel or oven
quality term at all. **This directly answers Phase 4's question** ("investigate workstation quality,
fuel quality, ingredient quality, thresholds") with sourced, if not live-verified, evidence: fuel
quality is not a uniform mechanic across every workstation type — it matters for kiln/smelter
products specifically, per their own cited pages, and the Baker-fix backlog item (item 6) should **not**
assume an oven-quality/fuel-quality term exists for bread the way it does for kiln goods, since the
cited page gives no such formula. This narrows, rather than widens, the quality-automation opportunity
for the Baker fix specifically — it strengthens it for kiln/smelter bots instead.

**Contradiction, documented rather than silently resolved:** `Bar of Steel`'s own wiki page records no
formula (`formulas=[]`), while `Bar of Tin`'s (and, by the same `(2·qOre+qSmelter+qAvgFuel)/4` shape
confirmed across the matched catalog, every other *ordinary* smelted bar's) page does. Two readings are
equally consistent with the evidence: (a) steel genuinely has no equivalent documented formula on the
wiki (data gap in the source, not in this fork's extraction), or (b) steel's formula lives on a page
this extraction's keyword match didn't surface under "Bar of Steel" specifically. **Not resolved here**
— flagged as UNKNOWN, not assumed either way.

## 4. Quality-dependent production — traced end to end

**What exists and reads/selects by quality today (VERIFIED_CODE, confirmed by reading call sites, not
just the presence of a `quality` field):**

- `FreeInventory2` — per-item-name quality *floor* when deciding whether to keep or discard/relocate an
  item.
- `TakeItemsFromContainer` — an explicit `targetq` threshold parameter gating which items are taken.
- `SortContainersInArea`/`SortInventory` — quality-aware placement/stacking decisions.
- `MasterMiner` — explicitly tracks and prefers the *highest*-quality ore/item seen.
- `RabbitMaster`/`DuckMaster`/`KFC`/`CollectHighestQualitySilkwormEggs` — breeding/incubation bots that
  compare and select by quality threshold or maximum.
- `FetchStorageItemBot` — fetches a specific quality from storage, matched against a stored database
  record.

**This confirms the quality-threshold-filtering *pattern* is a mature, repeatedly-implemented idiom in
this fork** — not a capability that would need inventing, just reuse of an existing, proven shape
(`TakeItemsFromContainer`'s `targetq` parameter is the most directly reusable precedent for a
fuel-quality gate).

**What does not exist (confirmed by absence across every production bot read in sessions 1-3):** **no
fuel-loop bot** (`BakerAction`/`BricksAction`/`BlockAshAction`/`BoneAshAction`/`SmelterAction`/
`SteelAction`/`LyeBoiler`/`TarkilnAction`/`FriedFish`) and **no generic-craft call site**
(`Craft.java`'s `TakeItems2` ingredient fetch has no quality parameter in any of its call signatures
read) **filters or selects fuel or ingredients by quality at all.** Every fuel/ingredient take in this
entire capability area is quality-blind — it takes whatever matches the name/alias, not whatever meets
a quality floor.

**`QualityModel`/`AtlasCatalog`/`CraftFlow`/`NCraftFlow` — confirmed read-only planning tools, not a
runtime decision input for any bot.** Grepped every caller of these four classes outside the `craft/`
package itself: the only consumer is `actions/bots/blueprint/BuildRecipes.java` (blueprint/building
material-cost estimation — a planning display, not a runtime fuel/ingredient choice) plus
`widgets/NCraftFlow.java` itself (the player-facing crafting-chain planner widget). **No production bot
reads `QualityModel.of(recipe)` or consults the wiki-formula catalog to decide what to fetch.** This is
the gap, now precisely located: the fork already has (a) the wiki-sourced formula data showing which
workstations' quality depends on fuel quality, and (b) a proven quality-threshold-filtering code
pattern used elsewhere — but nothing connects the two for production bots.

**Opportunity, consistent with "avoid speculative abstraction" and "configurable, quality-aware
automation":** a fork-owned, opt-in fuel-quality floor parameter on the existing fuel-loop helper
(the same de-duplication target as backlog item 7) — reusing `TakeItemsFromContainer`'s `targetq`
shape — would directly serve the kiln/smelter bots where the wiki formula confirms it matters, without
touching the bread/oven path where the cited formula gives no reason to. This is deliberately narrower
than "quality-aware automation everywhere": the wiki evidence above argues against applying it
uniformly.

## 5. Consolidated registry — where everything now lives

Per the task's request not to introduce duplicate sources of truth, this consolidation is a map, not a
restatement:

- **A. Workstation identification and interaction capabilities:** session 2 Phase 1-2 (4-source
  discovery table, position/pose/widget table), extended by session 3 §2 above (loom/churn/etc. gap
  confirmed negative, crucible/steel-crucible split, cauldron-variant non-gap, roastspit as a third
  mechanism).
- **B. Fuel/heat and production mechanics:** session 1 Phase 2 (observed state, `Container.FuelLvl`,
  `GItem.MeterInfo`) + session 3 §3 (wiki-sourced quality formulas, which double as fuel/heat mechanics
  evidence since fuel quality appears inside the same formulas).
- **C. Quality formulas and material constraints:** session 3 §3's table (sourced, dated,
  WIKI_DOCUMENTED) + session 3 §4 (what's VERIFIED_CODE vs. absent).
- **D. Existing automation implementations:** session 1 Phase 1 table + session 2 Phase 1-2 (generic
  craft path) + session 3 §2 (the 4 previously-deferred bots, now fully read).
- **E. Verified protocol and state-observation capabilities:** session 1 Phase 2 finding #3
  (`GItem.MeterInfo`, model attributes) + session 2's `Following` attribute + session 3's
  `Roastspit.getContent()` — three independent, confirmed-real observation channels, cataloged
  together for the first time in session 3 §2's "revised Phase 1 taxonomy."
- **F. Unverified assumptions and required experiments:** consolidated "Not covered" list below
  (supersedes sessions 1-2's separate lists where overlapping).

## Not covered, after session 3 (consolidated — supersedes sessions 1-2's separate lists)

- **Live/runtime verification of any wiki-documented formula against actual World 16.2 server
  behavior** — everything in §3 is WIKI_DOCUMENTED, not VERIFIED_RUNTIME. No live repro was performed
  for any quality formula, the Baker bug, or fuel interference from another player.
  - `Bar of Steel`'s missing quality formula — not resolved (two equally plausible explanations, see §3).
- **Validator's `.fuel(...)` pre-flight check internals** — still not read (carried over from session 1).
- **Whether `LightGob` is a thin wrapper over `LightObject` or a separate implementation** — still not
  checked (carried over from session 1).
- **Polling overhead / wasteful scanning profiling** — not measured in any session.
- **A `src/haven/res` (server-script) level search for workstation types referenced nowhere in
  `src/nurgling/**`** — the 4-source-plus-wiki-catalog cross-check is still within the fork's own code
  and its bundled wiki extract, not an exhaustive resource-tree search. The wiki catalog's 1,430 total
  recipes is a strong but not exhaustive proxy for "every workstation the game has."
- **Whether a barrel/container interaction while pose-locked at a station succeeds server-side** —
  still UNKNOWN, needs runtime observation (carried over from session 2).
- **Whether any non-kiln/non-smelter workstation has its own quality formula that simply wasn't
  surfaced by this session's keyword searches** (the matched-catalog approach searched by name/
  ingredient/tool substring, not an exhaustive pass over all 1,430 recipes).

---

# Session 4: validating recipes.bin, Autocrafting vs. dedicated bots, and a correction

Per explicit instruction, this session does not treat "the architecture is mapped" as "the capability
area is audited." It validates session 3's wiki-sourced claims against their generation process and
the live wiki, builds the Autocrafting-vs-dedicated-bots comparison the task asked for, and applies the
new coverage-level methodology (`methodology.md`) retroactively to this whole capability area (see that
document's coverage table — not restated here).

## 1. recipes.bin: generation, schema, and provenance — traced, not assumed

Read `tools/wiki-recipes/README.txt`, `update_recipes.py`, and the `CraftAtlas.tick()` call site in
full (`compile_recipes.py` itself was not read line-by-line; its behavior is inferred from the README's
detailed schema/compiler-contract section plus `update_recipes.py`'s calls into it — flagged as
TRACED-via-documentation, not TRACED-via-source, for that one file).

**Confirmed, VERIFIED_CODE:**

- `recipes.bin` is **not** fetched live by the client. `CraftAtlas.tick()`'s `databaseCheck` (session 3
  misread this as a "live update mechanism" — it is not) only re-reads the **local file on disk** every
  5 seconds, picking up a replacement only if a maintainer has dropped a new `recipes.bin` next to the
  jar. The actual wiki fetch happens in a **separate, maintainer-run, offline pipeline**
  (`update_recipes.py`, which calls `fetch_wiki.Wiki` against the public read-only MediaWiki API) that
  is never invoked by a normal client build or by any player's running client. This matches
  `WikiRecipes.java`'s own docstring ("No network dependency in the client") exactly — confirmed from
  both ends now, not just the reader's side.
- The pipeline is incremental and auditable by design: it diffs a stored page-ID/revision snapshot,
  re-downloads only changed revisions, and writes a `changes.json` report (added/removed/changed
  records with before/after values) on every run, including `--check` dry runs. It installs atomically
  with `.bak` backups and uses a lock file against concurrent runs.
- **Explicit caveats the README states about its own data, which this audit had not previously
  surfaced:** "This is a snapshot of wiki knowledge, not a claim of server completeness." And, more
  consequentially: **"Server observations supersede wiki amounts, tools and quality modifiers."**
  There is a client-side "journal" of player-observed recipe data (referenced but not traced this
  session — see "Not covered") that can override the wiki-sourced values at runtime. **This means
  session 3's treatment of `recipes.bin`'s formulas as the final word on a recipe's quality mechanics
  was itself an oversimplification** — there may be a runtime override layer sitting on top of it that
  this audit has not yet looked at.
- **Schema note directly relevant to session 3's own mistake (see §2 below):** the README states the
  compiler "includes... workstation processing recipes, including baked/fired stages linked with `GM
  copy=`" — i.e. by design, a baked/fired product's quality formula is **expected** to be attached to
  the *workstation's* record (Oven, Kiln) rather than the *product's* record (Bread, Brick) in some
  cases. This is not a bug in the data; it's documented behavior of the extraction schema that session
  3 did not check before drawing a conclusion from a product-name keyword search.

## 2. Correction to session 3: the Oven/Bread quality formula is NOT absent

**Session 3 claimed:** "Oven — Bread: No formula documented; … the page records bread's quality as
inherited 1:1 from its Bread Dough input, with no oven/fuel quality term at all," and used this to
recommend narrowing backlog item 10 away from `BakerAction`.

**This was wrong, and is retracted here, not silently fixed.** Re-querying `recipes.bin` for the
**Oven** building record (not the Bread product record) found:

```
"formulas": [
 "\\frac{_{q}AvgBrick * 3 + _{q}AvgBoard + _{q}AvgBlock + _{q}AvgStone}{6}",
 "\\frac{_{q}Dough * 2 + _{q}Oven + _{q}AvgFuel}{4}"
]
```

— the second formula is exactly the "Baked Goods quality" formula, filed under Oven's own record
(`wiki:8226`, revised `2026-03-05T15:15:43Z`). **Session 3 searched for "Bread" and found nothing on
Bread's own record; it should have also checked Oven's record, per the schema note above, before
concluding the formula didn't exist.**

**Live-wiki cross-check (WebFetch, `https://ringofbrodgar.com/wiki/Oven`, fetched this session):**
confirms the identical formula ("Baked Goods Q = qDough·2 + qOven + qAvgFuel⁄4") and the identical page
revision timestamp (5 March 2026, 15:15) — i.e. `recipes.bin`'s Oven record is **current and
correctly captures this formula**; the error was entirely in session 3's search, not in the data.

**This also directly surfaced the live wiki's own explicit statement of the exact Baker bug mechanism
the user originally described**, in the page's own words: *"If you use more branches, then this will
turn your dough into ash unless you manually take it out in time."* **This upgrades the Baker bug
finding (backlog item 6) from PLAUSIBLE/code-derived to WIKI_DOCUMENTED, with the failure mode stated
directly by the primary source** — still not VERIFIED_RUNTIME (no live repro performed), but
meaningfully stronger evidence than session 1-3 had. The live page also confirms `BakerAction`'s
hardcoded `maxlvl=4` constant matches the documented fixed requirement ("each baked good needs 4
branches' worth... the item still requires 4 branches" regardless of count or door-opening) — the
constant itself is not wrong; the bug is that a wood block (session-1 `KilnFuelCatalog`-adjacent unit:
5 branches' worth) or any fuel load sized past that fixed 4-branch requirement burns on past
completion with nothing to stop it.

**Backlog item 10 is corrected below** (session 3's narrowing of it away from `BakerAction` is
reversed): fuel quality now has wiki-documented, revision-matched evidentiary support for the oven/
baked-goods family specifically, not just kiln/smelter.

**A second, independent new mechanic surfaced only by the live wiki, present in neither `recipes.bin`
nor any prior session's documentation:** the Kiln page states that, since a cited 2023 update, "ovens
and similar objects no longer reset progress when they burn out. Instead, each item tracks the average
fuel quality it saw while burning." **This is a distinct mechanic from the ash-burning warning** —
burnout no longer destroys in-progress quality tracking, it just averages fuel quality across however
many separate burns a batch sees. This is recorded as WIKI_DOCUMENTED and flagged for the backlog as a
mechanic worth factoring into any future Baker-fix design (a bounded-wait-and-collect fix doesn't need
to treat a mid-batch burnout as destructive to quality the way session 1 implicitly assumed when
calling a fuel-timing mismatch "a bug class" shared with `SmelterAction`'s early-burnout issue —
`SmelterAction`'s documented problem was about *physically* pulling half-smelted ore via
`FreeContainers`, not about quality loss; these are two different failure modes that should not be
conflated going forward).

## 3. recipes.bin vs. live wiki — representative comparison (4 pages fetched this session)

| Page | `recipes.bin` revision cited | Live page's own "last revised" | Match? | Gap found |
|---|---|---|---|---|
| [Kiln](https://ringofbrodgar.com/index.php?title=Kiln&oldid=121396) | 2026-02-16 | 2026-02-16, 07:11 | **Yes, exact** | Per-item fuel-unit/burn-time table (e.g. Brick: 2 fuel units; Clay Jar family: 12 units) and the kiln's 25-slot (5×5) item capacity are on the live page but **structurally outside `recipes.bin`'s schema** — the format stores quality formulas and ingredient/output lists, not burn-time tables. Confirms (independently of session 1's own finding) that `KilnFuelCatalog.java`'s separate hand-maintained table is necessary — it isn't redundant with `recipes.bin`, it covers a category of fact `recipes.bin` was never designed to hold. The 2023 burnout-quality-averaging mechanic (§2) is also absent from `recipes.bin` — it's prose/mechanics explanation, not a recipe fact. |
| [Oven](https://ringofbrodgar.com/index.php?title=Oven&oldid=121883) | 2026-03-05 | 2026-03-05, 15:15 | **Yes, exact** | None in the formula itself (§2's correction) — but the explicit ash-burning warning sentence and the "one block burns all baked goods regardless of count" fuel-sizing detail are prose, not in `recipes.bin`'s structured fields, same category of gap as Kiln's. |
| [Bar of Steel](https://ringofbrodgar.com/index.php?title=Bar_of_Steel&oldid=124796) | 2026-08-29 | 2026-08-29, 08:02 | **Yes, exact** | The live page states a quality formula (fetched text, partially garbled by markdown conversion: "qCrucible+qCharcoal+qAvgFuel⁄3"); **checked both Bar of Steel's own `recipes.bin` record and the Steel Crucible building record — neither carries a bound formula for this product** (`formulas: []` on both, confirmed by direct query this session, not assumed). Unlike the Oven case, this is **not** a session-3-style search error — both plausible record locations were checked and both are empty. This is either a genuine compiler-parsing gap (the README documents that "ambiguous or unsupported formulas are not guessed" and are silently excluded, consistent with this) or a wiki-page formatting issue that defeated extraction. **Not resolved further** — would need the compiler's own `review.json`/`excluded.json` reports (never generated or read this session) to distinguish. Detailed fuel-timing data (12-hour partial fill vs. 56-hour full cycle, per-fuel-type tick values) is also live-wiki-only, same schema-scope gap as Kiln/Oven. |
| [Crucible](https://ringofbrodgar.com/index.php?title=Crucible) | *(not individually cross-checked — this session fetched the live page to validate `PrepareWorkStation.fillCrucible`'s behavior, not a specific `recipes.bin` record)* | 2025-01-11, 13:04 | N/A | **Validates, rather than gaps, an existing implementation:** the live page states coal is required for all crucible recipes (wood produces too little heat for alloying/glassmaking) and that fuel cannot be removed once added. `PrepareWorkStation.fillCrucible` (session 2) hardcodes exactly this — it fetches and uses Coal specifically, once, before the craft starts. **This retroactively moves that implementation detail from "unexplained hardcoded constant" to EVALUATED-and-confirmed-correct.** The page also states crucible quality affects only a spawned Irrlight's quality, not its crafted products — independently confirming `IrrlightBot`'s use of the plain crucible (session 1-2) as the one place crucible quality actually matters. |

**Overall conclusion on `recipes.bin`'s reliability, now VALIDATED rather than assumed:** for the 4
pages sampled, every cited revision timestamp matched the live page exactly — **the data is current
for these samples**, not stale. Its limitations are structural (burn-time tables, prose warnings, and
at least one unparsed formula are outside or lost from its schema), not staleness. **Do not treat
`recipes.bin` as a complete game-mechanics database** — it is, by its own README's description, a
recipe/quality-formula snapshot specifically, and this session's comparison confirms that scope
boundary empirically, not just by reading the claim.

## 4. Deep comparative audit: Autocrafting vs. dedicated bots

**Read in full this session:** `AutocraftBot.java` (109 lines — previously only referenced, never
read). Combined with `Craft.java`/`UseWorkStation.java`/`PrepareWorkStation.java`/`PrepareCauldron.java`
(session 2, full reads) and the dedicated bots (sessions 1 & 3, full reads), this is now a direct,
evidence-based comparison, not an inference from architecture alone.

| Axis | `AutocraftBot` + `Craft.java` (generic path) | Dedicated fuel-loop bots (`BakerAction` etc.) | Dedicated single-shot bots (`SteelAction`/`TarkilnAction`) |
|---|---|---|---|
| **Reuse vs. duplication** | `AutocraftBot` itself duplicates **nothing** — confirmed by reading it in full: it resolves a preset to a recipe, opens the menu, sets ingredient preferences, then delegates 100% of execution (`new Craft(mwnd, quantity).run(gui)`). All retry/completion/barrel logic lives in `Craft.java` alone. | Each bot re-implements its own wait→unload→refill→refuel→relight loop; 4 of them (`BakerAction`/`BricksAction`/`BlockAshAction`/`BoneAshAction`) are near-duplicates of each other (backlog item 7). | `SteelAction`/`TarkilnAction` share a structurally identical single-shot shape (session 3) but are two separate implementations, not a shared helper. |
| **Navigation/workstation discovery** | `UseWorkStation`/`PrepareWorkStation` use `PathFinder`/`Finder.findGob` with fallback global navigation (session 2) — the same primitives every dedicated bot also uses (`Finder.findGobs`, `PathFinder`) via direct calls rather than through `UseWorkStation`. **No divergence found**: both paths bottom out in the same navigation primitives; the generic path just wraps them in one reusable call. |
| **Distance/positioning/pose-locked state** | Handles it generically via `workstation_map`'s pose table + `FollowAndPose` (session 2) — covers every station in that registry, including the six with no dedicated bot (backlog item 11). | Dedicated bots don't need this axis for their own stations (ovens/kilns/furnaces aren't pose-locked) — not a gap, just not applicable to this family. `FriedFish` (session 3) uses its *own* two-pose mechanism (`roasting`/`carving`), independent of `workstation_map`, because its interaction (spit-turning, carving) isn't a generic recipe at all. |
| **Nearby-inventory handling (barrels)** | `Craft.java` has dedicated, fairly sophisticated barrel logistics (`TransferBarrelToWorkstation`, `findNearestCommonApproachPoint`, per-item placed-barrel hash tracking) — the most developed nearby-inventory handling found in either path across all four sessions. | No equivalent complexity — fuel-loop bots interact with fixed fuel zones/piles (`FuelZones`), not player-placed barrels. Different problem, not a gap. |
| **Material/quality selection** | `AutocraftBot.setPreferredIngredient` and `Craft`'s category-ingredient selection match by **name/area only** — confirmed, again, no quality parameter anywhere in either. | Same absence (backlog item 10). **No divergence**: neither path does quality-aware selection anywhere in this capability area. |
| **Success/completion/failure detection** | `Craft.craftProc` — progress bar + `getLastError()`, with up to 3 retries (session 2/3). Session 2 already found this **more robust** than fire-out detection. | Model-attribute fire-out bit (`WaitForBurnout`/`WaitBurnoutOrBellows`). **This is the clearest, most actionable finding of the whole comparative audit:** the generic path's completion detection is strictly more informative (reads an actual error/progress signal) than the fuel-loop path's (infers completion from an unrelated proxy — the fire, not the product). This is direct evidence for backlog item 7's de-duplication effort to consider borrowing `Craft.craftProc`'s detection *shape* (progress/error-driven) rather than just de-duplicating the existing fire-out loop as-is. |
| **Crafting-action vs. production completion, distinguished** | `Craft.craftProc` detects the **crafting-action's** completion (the server-side "make" action finishing) — this is distinct from, and session 2 already noted, the question of whether the *workstation's production* (e.g. a cauldron's boil finishing) is also complete; `PrepareCauldron`'s re-check-and-redo-if-needed logic (session 1) exists precisely because these two completions aren't always the same event. | The fuel-loop bots conflate the two by design: "fire out" is used as a single proxy for *both* "the station is done producing" and, implicitly, "the product is ready" — which is exactly the gap the Baker bug exploits (§2: the product can finish well before the fire does, or the two can be made to coincide only by luck of fuel sizing). **This is the clearest statement this audit has produced of why the two completion types must not be conflated**, now grounded in a working counter-example (`Craft.craftProc`) that keeps them conceptually separate on purpose. |
| **Interruption/recovery** | Inherits whatever `Craft.java`/`BotExecutor` provide generically (bot-lifecycle area's own findings — `TaskCriticalExitException`, single top-level interrupt catch). `Craft.java` adds its own `craftProc` retry (3 attempts) specifically for the crafting-action step. No workstation-state cleanup logic was found for "bot killed mid-batch." | Same generic inheritance; no workstation-specific resume/cleanup logic found either (session 1 "Not covered," never resolved). **No divergence**: neither path has bespoke mid-batch recovery beyond the generic cancellation mechanism. |
| **Unnecessary polling/scanning** | `AutocraftBot` itself does a bounded, one-time menu/spec-name wait (session-pattern `NTask` checks), not a loop — no overhead found. `Craft.java`'s own `calculateMaxCraftsWithStacking` does a binary search per craft batch (bounded, not unbounded) — not flagged as wasteful. | Not profiled in any session (consistently flagged as "Not covered" since session 1) — **no comparative claim can be made here**; this is a genuine open gap in the comparison, not a finding that one side is better. |
| **Retry/timeout consistency** | `Craft.craftProc`: fixed 3 retries, `MAX_WAIT_FOR_PROG`/`MAX_TICKS_AFTER_PROG` tick-based timeouts, consistently applied to every recipe that goes through it. | No equivalent retry concept — a fuel-loop bot either completes a step or returns an `ERROR`/`FAIL` outright (session 1's trace of e.g. `TarkilnAction`'s `attempts`-bounded coal-collection loop is the closest analog, and it's bot-specific, not shared). **Inconsistent across the capability area**: some dedicated bots have ad hoc bounded retry loops of their own (`TarkilnAction`), most don't, and none share a common retry primitive with each other or with `Craft.java`. |

**Does one approach dominate?** No — consistent with the task's explicit caution not to assume either
side is automatically superior. The generic path has the better-evidenced completion-detection and
barrel-logistics machinery; the dedicated bots exist specifically for stations (ovens, kilns, smelters)
whose production is a server-side timed/fueled process the crafting window doesn't represent at all —
there is no "craft" action to put a progress bar under for "wait for the fire to finish baking everything
inside." **The two paths solve different problems and the evidence supports keeping them separate**,
while two concrete, narrow improvements are newly actionable from this comparison:
1. Session 1-3's proposed shared fuel-loop helper (backlog item 7) should consider the *shape* of
   `Craft.craftProc`'s detection (watch an authoritative signal, retry bounded, distinguish
   crafting-action completion from production completion) as its model, not just deduplicate the
   current fire-out code as-is.
2. No shared retry/timeout primitive exists across the fuel-loop bots' own ad hoc patterns (new
   finding, backlog item 12 below).

## Not covered, after session 4 (additions to the consolidated list)

- **The "server observations supersede wiki amounts" journal/override layer** (§1) — discovered via
  the README, never traced. This could mean some of session 3's "wiki says X" conclusions are
  themselves overridden at runtime by player-observed data the fork separately tracks; not checked.
- **`compile_recipes.py`'s own source** — not read line-by-line; its behavior is inferred from
  `update_recipes.py`'s calls into it and the README's schema section, not from direct code reading.
- **Why Bar of Steel's quality formula isn't bound in `recipes.bin`** — narrowed to "compiler-parsing
  gap vs. wiki-formatting issue" but not resolved between those two; would need `review.json`/
  `excluded.json` from an actual compiler run, not generated this session.
- **Polling/scanning overhead comparison** — still entirely unprofiled, now explicitly flagged as a
  gap in the comparative matrix itself (§4) rather than just a general "not covered" line.
- **Whether any other kiln/oven-family product's formula is similarly mis-filed** under its
  workstation rather than itself in a way that would change a different session-3 conclusion — only
  Bread/Oven was specifically re-checked this session; the other kiln products quoted in session 3's
  table (Brick, Bone Ash, Clay Jar, etc.) already had formulas directly on their own records (verified
  at the time), so this specific error pattern is believed Bread-specific, but that belief itself was
  not exhaustively re-checked against all 1,430 records.

## Suggested next steps (if this capability area is reopened later)

Per the methodology correction in `methodology.md`, this section no longer claims the capability area
is "substantively complete" on the strength of architecture-mapping alone — see that document's
coverage table for the actual per-subsystem levels, which show a mix of DISCOVERED/TRACED/EVALUATED/
VALIDATED, not uniform completion. What sessions 1-4 together have produced is: every subsystem
identified in Phase 1 discovery has been at least DISCOVERED; the fuel-loop bots, the generic-craft
path, `AutocraftBot`, and the four previously-deferred bots are EVALUATED against each other with a
direct comparative matrix (§4 above); `recipes.bin`'s reliability and scope are VALIDATED against its
own generation process and a live-wiki sample, with one correction already applied and documented
rather than silently fixed. Remaining gaps are listed per-session above and consolidated in
"Not covered, after session 4" — most require either runtime observation this audit cannot perform by
reading code/wiki, or reading specific files/reports not yet opened (`Validator.java`,
`compile_recipes.py`, the compiler's own `review.json`). None of these block using the backlog
(`modernization-backlog.md`) for prioritization — they're follow-up investigation items, not
prerequisites to acting on what's already VALIDATED.
