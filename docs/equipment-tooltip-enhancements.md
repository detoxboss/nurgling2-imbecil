# Equipment, gilding, food and durability enhancements

**Status: current** — describes the code on this branch. Items marked *not run in-game* have been
compiled, unit-tested and (where noted) rendered headlessly, but nobody has watched them in a live client.

These features adapt the useful parts of the Hurricane client (`Nightdawg/Hurricane`) to Nurgling's own
tooltip and equipment architecture. Nothing from Hurricane was replaced wholesale, no `src/haven/**` file
was changed, and no `@FromResource` annotation was touched.

FEP/food *mechanics* (the prediction formula, satiation, variety) are owned by
[`feps-system-reference.md`](feps-system-reference.md); this page covers the tooltip/equipment features
and where they live.

## Implementation map

| Area | Already in Nurgling | What Hurricane adds | What was extended | Not ported |
|---|---|---|---|---|
| Equipment bonuses | Detection and hard/soft armor on `NEquipory` | `AttrBonusesWdg`: totals of equipped attribute mods | New typed collector + sidebar in `NEquipory` | Hurricane's string-regex summing, its reflection-and-silent-catch paths, its own `Equipory` copy |
| Gilding tooltips | Per-gilding sections in `NTooltip`; compact view only in the base `ISlots` (not used by `NTooltip`) | Compact summary, Shift expands | `NTooltip` compact/expanded views on one typed extraction | `slots_alt` layout code (the alt format is read by reflection instead) |
| Food tooltips | `NFoodInfo` (custom layout, expected FEP, variety, drinks) | Shift = unmodified, weighted totals, FEP/hunger, bar fill, modifier list, Deep Worm warning | Extra rows + Shift in `NFoodInfo`, one calculator (`FoodModifiers`) | Hurricane's `FoodInfo.tipimg()` and its formulas (see below) |
| Durability | `Wear` tooltip overlay (red tint) | Coloured bar in `WItem` | Bar in `NWItem.drawmain` | Nothing else |
| Curiosity | `NCuriosity`: LP/H, LP/H/W, study time, remaining-time overlay | LP/h, LP/h/weight, study overlay | Nothing needed — parity confirmed by reading both | — |
| Subtlety | Detection and armor | Intelligence × Stealth | Third row in the `NEquipory` stat block | — |

## Equipment bonuses sidebar

* Code: `nurgling.iteminfo.bonus` (`AttrBonus`, `AttrBonusCollector`, `EquipmentBonuses`, `AttrOrder`),
  `nurgling.widgets.EquipBonusPanel`, small hooks in `NEquipory`.
* **Typed, not parsed.** An `AttrBonus` is the raw `Mod.mod` value plus the attribute's identity (its
  resource name) and *kind* (`intattr` flat, `pmattr` per-mille, `normattr` fraction, `inormattr`
  inverse). Display text comes from the game's own `Attribute.format`, so `×N`, decimals and the
  inverse-normal sign convention match the game. Entries merge only when identity **and** kind match,
  by adding raw values; flat `+10` and `+10%` never combine. Text-only entries (e.g. a `Transfer`) are
  shown where relevant but never summed.
* **What counts:** each worn item's own top-level `AttrMod` plus the `AttrMod`s of its *installed*
  gildings (`ISlots`, and the alternative `slots_alt.ISlots`). Gilding **recipes** (`Slotted`, the
  "As gilding:" tip an ingredient carries) and `Tool` mining stats are deliberately excluded.
* **Items are de-duplicated by identity** (a two-handed weapon listed under two slots counts once).
* **Incomplete data is never final.** An item whose tooltip data has not arrived, or whose info throws
  `Loading`, is counted as *pending*; the panel says "Loading item data..." and the totals are retried
  every 0.5 s until complete.
* **No per-frame work.** `NEquipory.updateBonuses()` hashes `(item identity, infoseq)` over the worn
  items; it recomputes only when that changes (equip, remove, swap, gilding change, a new `tt`
  message) or while a pass was incomplete. Only runs while the sidebar is open.
* UI: toggle tab on the window's right edge, heading, scrollable list (`haven.Scrollbar`, themed by
  `NStyle`), empty and loading states. New UI and classic look both supported through `UITheme.on()`.
  The window is re-packed when toggled. Setting: `NConfig.Key.equipBonusesExpanded` (default **collapsed**).
* Order: base attributes (str, agi, int, con, prc, csm, dex, wil, psy), then abilities in character-sheet
  order, then anything else by name — keyed on the resource basename, so it is language independent.

## Gilding tooltips (compact / Shift)

* `NTooltip.build` renders installed gildings as **"Gildings (used/total used)" + one combined list +
  "Hold Shift for details"** normally, and the existing per-gilding sections while Shift is held.
* Both views come from `AttrBonusCollector` (`GildingSummary` = sum of the breakdown), so they cannot
  disagree. `extractStatsFromAttrMod` (used by the breakdown, base stats and recipes) now goes through
  the same code, replacing the old reflection plus hand-rolled percent formatting.
* Falls back to the breakdown when there is nothing to summarise (gildings without attribute mods).
  Ungilded items, gilding ingredients (`Slotted`) and `NRecipeTooltip` render as before.
* **Refresh:** `NWItem` asks `TooltipRebuildGate` each frame: a Shift change or a new info list drops the
  cached tooltip and rebuilds at once; item-requested rebuilds are throttled to 2/s (unchanged policy,
  now unit-tested). `ISlots.isShifted` was never maintained, which made `NGItem.needlongtip()` demand a
  rebuild every interval while Shift was held; `NTooltip` now records the view it built.

## Food tooltips

* Code: `NFoodInfo` (hooks), `FoodDetailsLayout` (rows), `nurgling.iteminfo.food.*` (`FoodModifiers`
  maths, `FoodModifierReader` live inputs, `TableLabels` parsing).
* Normal hover: the existing layout, then **(+2 weighted) total and per-hunger**, **Predicted FEP/Hunger**,
  **FEP bar after eating**, a **Modifiers ×M** breakdown, and the Deep Worm warning when it applies.
* **Hold Shift:** the Expected FEP / Expected total rows and the modified rows are replaced by
  "Showing unmodified values" and the listed weighted total; the hunger shown returns to the listed value.
  Release restores the normal view.
* The weighted total is the game's own `FoodInfo.sev`. By parameter order (the Fac and `NFoodInfo`
  constructors name the two optional numbers `sev, cons` while `FoodInfo` names them `cons, sev`; the two
  swaps cancel) `FoodInfo.sev` is the second number the server sends, which Hurricane also uses as the
  "(+2 weighted) total". This was read from source, not confirmed against live data. The rows are hidden
  when the value is 0 (the server did not send it).
* Predictions are hidden when the character's data is unavailable; nothing is inferred.
* Row "Actual total" is *not* repeated: the existing "Expected FEP" row is that number.

## Durability bars

* `NWItem.drawmain`: a narrow bar on the left edge from `Wear` (`d` damage of `m`), red → orange → yellow →
  green, with a track; broken items show an empty bar with a red stub. `ItemDurability` holds the maths and
  reads `Wear` by name. Cached per `infoseq`; no textures are allocated.
* Drawn right after the sprite so quality, count, study and progress overlays are drawn over it, and after
  the item render state is reset so search/colour effects do not tint it.
* Setting: `showDurabilityBars` (default on), Options → QoL → Inventory.
* The existing `Wear` red tint overlay is unchanged.

## Subtlety

`EquipStats.subtlety` = Intelligence × Stealth using the same *computed* values as Detection; shown only when
both attributes exist. Label is "Sub" (there is no icon resource); hovering the stat block explains each
figure.

## Server-distributed resource classes

`find-updates` was run against `https://game.havenandhearth.com/res/` on 2026-10-14: no "needs update",
"conflicting versions" or "newer locally". So the local `ui/tt/attrmod` (v12), `slots` (v34), `slot`
(v20), `wear` (v4) and `food` (v15) classes **are the active ones** in the standard world. The alternative
`slots_alt.ISlots` is not in this tree; it is read by reflection (and `NTooltip` already did). All new
code recognises these classes **by simple name and field**, so it also works if a server version ever
overrides the local one. No `get-code` was run and no annotation changed.

## Multi-session

Equipment totals come from the `NEquipory`'s own items. Food reads go through `NFoodInfo.gui()`: the
owning `NGItem`'s own `NUI.gui`, never `NUtils.getGameUI()` (recipes, which have no item, keep the active
UI). Nothing character-specific is stored statically.

## Tests

`ant test`: `EquipmentBonusesTest`, `NTooltipGildingTest` (real headless `NTooltip.build`),
`TooltipRebuildGateTest`, `FoodModifiersTest`, `FoodDetailsLayoutTest`, `ItemDurabilityTest`,
`EquipStatsTest`. The rendering tests need the built-in resource jars; `build.xml`'s `test` classpath
lists them (missing jars are ignored and those tests skip themselves).

## Verification status

| Item | Status |
|---|---|
| Compiles (`ant jar`), all tests pass (`ant test`) | verified |
| Typed aggregation, de-duplication, pending handling, ordering | unit-tested |
| Compact vs Shift gilding tooltip, food rows, Deep Worm row | rendered headlessly, images inspected |
| Food multiplier equals the previous `calcExpectedFep` formula (no salt) | unit-tested against a copy of it |
| Sidebar layout in the live window, nested window sizing, themes, UI scales | **not run in-game** |
| Shift refresh while hovering, durability bar placement vs overlays | **not run in-game** |
| Multi-session behaviour | **not run in-game** (code path reviewed only) |

## Known limitations and open questions

* **Satiation scaling hunger.** Hurricane multiplies shown hunger by satiation. Nothing in the available
  source supports that, so only the table's "Hunger modifier" scales hunger
  (`FoodModifiers.SATIATION_SCALES_HUNGER = false`). Needs an in-game check.
* **Hurricane's satiation convention differs.** Hurricane uses `1 - el.a`; this fork uses `el.a` (as
  `NFoodInfo` always did). The satiation list treats `a == 1.0` as "no entry" and `a > 1.0` as buffed, i.e.
  `a` is already the multiplier. Hurricane's convention was **not** adopted.
* **Salt satiation is new in the shared calculator**, so `calcExpectedFep()` for *salted* food now includes
  it (only when a salt satiation entry exists and salt is not already part of the food's own satiation).
  Non-salted foods are unchanged. This follows Hurricane's observation and is unverified.
* Satiation is looked up by food-type *index* into the satiation list, as before; entries the server omits
  could shift positions (pre-existing behaviour, not changed here).
* Account bonus keeps this fork's `subscribed ? 1.5 : verified ? 1.2 : 1`; Hurricane distinguishes
  subscribed-only (1.3).
* The Deep Worm warning is based on Hurricane's report that the wound reduces food efficiency; the exact
  effect is unverified, so the warning only says predictions may be inaccurate.
* The existing armor total counts each equipped item once per slot it occupies (unchanged).
* No gilding-recipe or tool-stat bonuses in the sidebar, by design.
* Subtlety has a text label instead of an icon.
