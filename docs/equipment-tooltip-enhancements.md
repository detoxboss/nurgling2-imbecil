# Equipment, gilding, food, durability and seasoning enhancements

**Status: current** — describes the code on this branch. Items marked *not run in-game* have been
compiled, unit-tested and (where noted) rendered headlessly, but nobody has watched them in a live client.

These features adapt the useful parts of the Hurricane client (`Nightdawg/Hurricane`) to Nurgling's own
tooltip and equipment architecture. Nothing from Hurricane was replaced wholesale and no `@FromResource`
annotation was touched. Two small hooks live outside `src/nurgling/**`: one line-group in
`haven/ItemInfo.buildinfo` (attaches the seasoning marks, next to the existing `WellMined` hook) and the
quality label in the server-distributed `res/ui/barterbox/Shopbox` (see "Server-distributed classes").

The source-level comparison with Hurricane — what Nurgling already had, what differs, what was ported or
skipped and why — is in [`hurricane-tooltip-audit.md`](hurricane-tooltip-audit.md). FEP/food *mechanics*
(the prediction formula, satiation, variety) are owned by [`feps-system-reference.md`](feps-system-reference.md);
this page covers the tooltip/equipment features and where they live.

## Implementation map

| Area | Already in Nurgling | What Hurricane adds | What was extended | Not ported |
|---|---|---|---|---|
| Equipment bonuses | Detection and hard/soft armor on `NEquipory` | `AttrBonusesWdg`: totals of equipped attribute mods | Typed collector + sidebar in `NEquipory` | Hurricane's string-regex summing, its reflection-and-silent-catch paths, its own `Equipory` copy |
| Gilding tooltips | Per-gilding sections in `NTooltip` | Compact summary, Shift expands | `NTooltip` compact/expanded views on one typed extraction | `slots_alt` layout code (the alt format is read by reflection instead) |
| Food tooltips | `NFoodInfo` (custom layout, expected FEP, variety, drinks) | Shift = unmodified, weighted totals, FEP/hunger, bar fill, modifier list, Deep Worm warning | Extra rows + Shift in `NFoodInfo`, one calculator (`FoodModifiers`) | `FoodInfo.tipimg()` and its formulas |
| Durability | `Wear` tooltip overlay (red tint) | Coloured bar in `WItem` | Bar in `NWItem.drawmain` | — |
| Seasoning marks | `WellMined` overlay only | Salt/pepper/truffle icons | `NSpiceMarks` overlay | Per-frame resource loading |
| Barter | Price quality | Quality shown beside the offered item | `Shopbox` label | — |
| Curiosity | `NCuriosity` | LP/h, LP/h/weight, study overlay | Nothing needed | — |
| Subtlety | Detection and armor | Intelligence × Stealth | Third row in the `NEquipory` stat block, with the character sheet's Stealth icon | — |

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
* **Three outcomes per item, never silently "zero".**
  * *Read* — contributes its bonuses.
  * *Pending* — its tooltip data has not arrived or throws `Loading`. The panel says "Loading item data..."
    and the totals are retried every 0.5 s until complete.
  * *Unsupported* — the class is recognised as an attribute modifier or a gilding list but a field the
    reader needs is missing or has another shape (the server changed the structure), or an unknown class
    from a "slots" package is present. The panel says "Some items use unrecognised data, totals incomplete";
    what could be read is still listed, and there is **no** retry (waiting does not fix a changed structure).
    The reason is logged once to stderr as `[AttrBonus] ...`.
  `EquipmentBonuses.Result.complete()` is true only when nothing is pending or unsupported.
* **No per-frame work.** `NEquipory.updateBonuses()` hashes `(item identity, infoseq)` over the worn
  items; it recomputes only when that changes (equip, remove, swap, gilding change, a new `tt`
  message) or while a pass was pending. Only runs while the sidebar is open.
* UI: toggle tab on the window's right edge, heading, scrollable list (`haven.Scrollbar`, themed by
  `NStyle`), empty/loading/unsupported states. New UI and classic look both supported through `UITheme.on()`.
  The window is re-packed when toggled. The scroll position survives a refresh (`bar.ch(0)` only clamps).
  Setting: `NConfig.Key.equipBonusesExpanded` (default **collapsed**).
* Order: base attributes (str, agi, int, con, prc, csm, dex, wil, psy), then abilities in character-sheet
  order, then anything else by name — keyed on the resource basename, so it is language independent.
* **Armor total.** The "hard/soft" figure beside the sidebar used to add an item's armor once per slot it
  occupies; `EquipStats.armor` now counts each worn item once (broken items still give none), matching the
  sidebar.

## Gilding tooltips (compact / Shift)

* `NTooltip.build` renders installed gildings as **"Gildings (used/total used)" + one combined list +
  "Hold Shift for details"** normally, and the existing per-gilding sections while Shift is held.
* Both views come from `AttrBonusCollector` (`GildingSummary` = sum of the breakdown), so they cannot
  disagree. `extractStatsFromAttrMod` (used by the breakdown, base stats and recipes) now goes through
  the same code, replacing the old reflection plus hand-rolled percent formatting. This is an intended
  behaviour change: values are formatted by the attribute's own `format`.
* Falls back to the breakdown when there is nothing to summarise (gildings without attribute mods).
  Ungilded items, gilding ingredients (`Slotted`) and `NRecipeTooltip` render as before.
* **Refresh:** `NWItem` asks `TooltipRebuildGate` each frame: a Shift change or a new info list drops the
  cached tooltip and rebuilds at once; item-requested rebuilds are throttled to 2/s (unchanged policy,
  now unit-tested). `ISlots.isShifted` was never maintained, which made `NGItem.needlongtip()` demand a
  rebuild every interval while Shift was held; `NTooltip` now records the view it built.
* **Texture lifecycle.** The engine only frees the tooltip textures it created itself (`UILoop.drawtooltip`
  frees `String` tooltips only), so every Shift toggle or info change used to leave a `PaddedTip`'s `TexI`
  to the garbage collector's finalizer. `DisposableSlot` now disposes the previous tooltip when it is
  replaced or dropped, and `NWItem.destroy` clears it. Disposing is safe against a frame still in flight
  (GL objects are reference counted), and a disposed `TexI` that is drawn again is simply re-uploaded.

## Food tooltips

* Code: `NFoodInfo` (hooks), `FoodDetailsLayout` (rows), `nurgling.iteminfo.food.*` (`FoodModifiers`
  maths, `FoodModifierReader` live inputs, `TableLabels` parsing).
* Normal hover: the existing layout, then **(+2 weighted) total and per-hunger**, **Predicted FEP/Hunger**,
  **FEP bar after eating**, a **Modifiers ×M** breakdown, and the Deep Worm warning when it applies.
* **Hold Shift:** the Expected FEP / Expected total rows and the modified rows are replaced by
  "Showing unmodified values" and the listed weighted total; the hunger shown returns to the listed value.
  Release restores the normal view.
* **Incomplete data is stated, not hidden.** `FoodModifiers` has a status: *available*, *pending*,
  *unsupported*, *unavailable*. Only *available* produces numbers, "Expected FEP" included.
  * *Pending*: a satiation entry's resource (needed to find the salt entry) or a realm buff's tooltip has
    not loaded yet, or a wound resource is still loading → "Modifiers still loading, no prediction yet".
  * *Unsupported*: the table's "Food event bonus" label or a realm buff line is present but has no readable
    percentage → "Unrecognised modifier data, no prediction". A table with no such label is simply a table
    without that modifier.
  * *Hunger side only*: an unreadable "Hunger modifier" label (including 0% or less, which would mean a meal
    that fills no hunger) makes only the hunger figures unknown — hunger is shown as listed with a note, and
    per-hunger values show "-". The FEP prediction does not use that label, so it stays available, which is
    what keeps the food optimizer working at such a table.
  * *Unavailable*: no session or no character sheet yet → nothing is shown, as before.
* **Hunger.** The hunger shown (Energy line) and every per-hunger row use one figure,
  `FoodModifiers.predictedHunger`: listed hunger x combined satiation x salt x the table's hunger modifier. The
  hunger-bar efficacy, account bonus, realm bonus and the table's FEP bonus are FEP-only. This is a well-supported
  client-side prediction (Hurricane, its tooltip text, the Ring of Brodgar "Food Satiations" page; no contradicting
  source), not a proven reading of private server code, and it has not been measured in this client; the procedure
  to do so is in [`feps-system-reference.md`](feps-system-reference.md). The first two lines of the base layout
  ("FEP Sum", "FEP/Hunger") stay the *listed* figures, as before. With Shift everything is the listed value.
* **Satiation of foods with several types.** Every type's entry applies and they multiply
  (`FoodModifiers.combineSatiation`). Before this, `efficiency` kept only the *last* matching type, so the
  result depended on type order; it also made `check()` ask for a tooltip rebuild every tick for such
  foods (and for any buffed entry, because layout and `check()` clamped differently). The rule is
  supported by Hurricane and by the client's own satiation list highlighting *all* of a food's types, but it
  is not proven against the server's numbers; see [`feps-system-reference.md`](feps-system-reference.md).
* **Realm bonus.** `NGameUI.getRealmMod()` read `Buff.info`, but `RealmBuff` keeps its tooltip in its own
  shadowing fields (`info()`, `rawinfo`), so the base field stayed empty and the bonus was always 0. It is
  now read through `RealmBuff.info()`, and a buff whose tooltip has not arrived is *pending*. **Caveat:**
  `find-updates` reports `ui/rbuff` as "no longer found" on the server, so a current world may no longer
  send realm buffs at all; the fix then changes nothing.
* **Weighted total.** `FoodInfo.sev` is read by constructor position (the `Fac` and `NFoodInfo` constructors
  name the two optional numbers `sev, cons` while `FoodInfo` names them `cons, sev`). Whether the server's
  second number really is the weighted total is not confirmed. `NFoodInfo.weightedTotal()` therefore only
  returns it when it lies between the plain FEP sum and twice that (a "+2" weighting cannot do otherwise);
  anything else (for example a satiation-sized fraction, if the order were the other way round) hides the
  rows instead of showing a wrong number.
* **FEP bar after eating** is measured against where the bar really completes: the cap less the variety
  bonus the food would add (`NFoodInfo.barTarget`, the same figure as `calcNeededFep`), so 100% coincides with
  a non-negative "Expected FEP" balance. When the target is below the cap the row says so. Hurricane
  divides by the cap, which understates the fill.
* **Invalidation.** `NFoodInfo.check()` compares `FoodModifiers.signature()` (status, every factor, Deep
  Worm) with what the last build showed, besides the expected/needed FEP. Two factors that move in opposite
  directions and cancel in the predicted FEP still refresh the breakdown.
* **Multi-session.** Food type/drink rows read the `NUI` of the owning item (`NFoodInfo.gui().ui`), never
  `NUtils.getUI()`. Recipes (no item) still use the active UI, deliberately: a recipe tooltip is only ever
  drawn in the UI the player is looking at.
* Row "Actual total" is *not* repeated: the existing "Expected FEP" row is that number.

## Durability bars

* `NWItem.drawmain`: a narrow bar on the left edge from `Wear` (`d` damage of `m`), red → orange → yellow →
  green, with a track; broken items show an empty bar with a red stub. `ItemDurability` holds the maths and
  reads `Wear` by name. Cached per `infoseq`; no textures are allocated.
* Drawn right after the sprite so quality, count, study and progress overlays are drawn over it, and after
  the item render state is reset so search/colour effects do not tint it.
* Setting: `showDurabilityBars` (default on), Options → QoL → Inventory.
* The existing `Wear` red tint overlay is unchanged.

## Seasoning marks

* `NSpiceMarks` (an `ItemInfo.Tip` + `GItem.OverlayInfo`, the same mechanism as `WellMined`) draws the game's
  own ingredient icons — `gfx/invobjs/salt`, `pepper`, `herbs/truffle-white`, `herbs/truffle-black` — scaled
  to 12 px in one row at the item's top-right, for the plain tooltip lines "Salted", "Peppered",
  "White-truffled", "Black-truffled" (`SpiceMarks`, exact text match as Hurricane does).
* Attached from `ItemInfo.buildinfo` (3 lines, only for `NGItem` owners). Icons load lazily and are cached by
  resource name: *loading* is retried next frame, a resource that cannot load is skipped without hiding the
  others. No asset was added.
* Setting: `showSpiceMarks` (default on), Options → QoL → Inventory. "Well mined" already had its overlay.
* Placement is top-right because the defaults leave it free (quality bottom-right, study bottom-left, variety
  and well-mined top-left); the quality and study corners are user-configurable, so an overlap is possible
  if someone moves them there. *Not run in-game.*

## Barter stands

`Shopbox` shows "Quality: N" beside the offered item (its own quality, or its contents' for a filled
container), where Hurricane puts it. The tooltip was the only place it appeared before.

The rule lives in `nurgling.iteminfo.OfferQuality` (unit-tested); `Shopbox` keeps a private `Text quality`, a
`qinfo` cache key and a small `quality()` that draw calls. The label is cached against the identity of the built
info list, so it is rebuilt only when the server sends new item information, is hidden while that information
is `Loading` (never the previous item's), and is cleared on both `tt` and `res`. It does not need a hover:
`draw` already builds the info for the item-count overlay. `Shopbox` is also read directly by seven other
Nurgling classes (`TransferToBarter`, `FindBarterStand`, `TakeItems2`, ...), so the local class is already a hard
dependency; the label adds no new edge. **If the server ships a newer `ui/barterbox`** the `@FromResource` version
(76) stops matching, the server's class is loaded instead, and the label disappears; nothing crashes, but the
bots then also see the server's class (a pre-existing property). Do not edit the version annotation without
re-fetching the resource (`resource-upgrade-strategy.md`).

The seasoning marks needed a hook in `ItemInfo.buildinfo`; `GItem.info()` offers no Nurgling-owned seam
(`NGItem` would have to re-implement it), and `buildinfo` already contains the four other Nurgling additions in the
same `owner instanceof NGItem` block, so the three lines add no new conflict surface.

## Subtlety

`EquipStats.subtlety` = Intelligence × Stealth using the same *computed* values as Detection; shown only when
both attributes exist. The row uses the character sheet's own Stealth skill icon (`SAttrWnd.SAttr.img`, a game
resource, no new asset) in both the classic and the New UI block, falling back to the text label "Sub" while the
skill row does not exist. Hovering the stat block explains each figure.

## Server-distributed resource classes

`find-updates` was run against `https://game.havenandhearth.com/res/` on 2026-10-14 and again on 2026-10-15
(after the follow-up changes): no "needs update", "conflicting versions" or "newer locally"; only the
pre-existing "no longer found" for `ui/rbuff`, `lib/tspec`, `lib/mapres`. So the local `ui/tt/attrmod` (v12),
`slots` (v34), `slot` (v20), `wear` (v4), `food` (v15) and `ui/barterbox` (v76) classes **are the active ones**
in the standard world. The alternative `slots_alt.ISlots` is not in this tree; it is read by reflection (and
`NTooltip` already did). All new tooltip code recognises these classes **by simple name and field**, so it also
works if a server version ever overrides the local one. `ResourceClassStructureTest` pins the field names the
reader depends on in the *real* local classes. No `get-code` was run and no annotation changed; `Shopbox` keeps
`@FromResource(name = "ui/barterbox", version = 76)`, and if the server ever ships a newer one the label
simply disappears with the local class.

## Multi-session

Equipment totals come from the `NEquipory`'s own items. Food reads go through `NFoodInfo.gui()`: the
owning `NGItem`'s own `NUI.gui`, never `NUtils.getGameUI()` (recipes, which have no item, keep the active
UI). The Subtlety/Detection numbers read the character sheet of the equipory's own `GameUI`. Nothing
character-specific is stored statically. The remaining `NUtils.getGameUI()` uses in `NWItem` (search, auto-drop)
and `NEquipory` (quick-slot toggle) predate this work and were not touched.

## Tests

`ant test` (175 tests, see the verification table): `EquipmentBonusesTest` (including the unsupported/pending
cases), `ResourceClassStructureTest` (real classes), `NTooltipGildingTest` (real headless `NTooltip.build`),
`TooltipRebuildGateTest`, `DisposableSlotTest`, `FoodModifiersTest`, `FoodDetailsLayoutTest`,
`ItemDurabilityTest`, `EquipStatsTest` (including armor de-duplication), `SpiceMarksTest`. The rendering
tests need the built-in resource jars; `build.xml`'s `test` classpath lists them (missing jars are ignored and
those tests skip themselves).

## Verification status

| Item | Status |
|---|---|
| Compiles (`ant jar`), all tests pass (`ant test`), `ant bin` builds | verified |
| Typed aggregation, de-duplication, pending/unsupported handling, ordering | unit-tested |
| Real `AttrMod`/`Mod`/`Entry`/`Attribute`/`ISlots` structure and real attribute `format()` | unit-tested (structure of `ISlots` by reflection; it cannot be instantiated without a game) |
| Compact vs Shift gilding tooltip, food rows, Deep Worm row | rendered headlessly, images inspected |
| Food multiplier equals the previous `calcExpectedFep` formula for single-type, non-salted food | unit-tested against a copy of it |
| Multi-type satiation = product; unreadable table/realm data → no prediction | unit-tested; the *rule* is unverified against the server |
| Tooltip texture disposal | unit-tested on `DisposableSlot`; no GL leak measurement was taken |
| Sidebar layout in the live window, nested window sizing, themes, UI scales | **not run in-game** |
| Shift refresh while hovering, durability/seasoning mark placement vs overlays | **not run in-game** |
| Stealth icon in the stat block, both themes | **not run in-game** |
| Barter quality label position | **not run in-game** |
| Multi-session behaviour | **not run in-game** (code path reviewed only) |
| Satiation (and salt) scale hunger | **community-documented + Hurricane**, adopted, **not measured in game** |
| `FoodInfo.sev` is the weighted total; salt rule; index alignment | **unverified** (need live data) |

## Known limitations and open questions

* **Satiation scaling hunger** is adopted (Hurricane, its character-sheet tooltip saying satiation affects "FEPs and
  Hunger", and the Ring of Brodgar page `HungerGained = FoodHungerValue × SatiationModifier`; nothing contradicts
  it) but **not measured** here, and the server is private. If a measurement shows the listed hunger is what
  fills, make `FoodModifiers.hungerMultiplier()` return only the table modifier. The empty-list case is
  unverifiable: before the first satiation message arrives the list is empty and is read as "no reduction",
  so a prediction made that early is briefly too high; the next satiation update changes the signature and
  refreshes the tooltip.
* **Hurricane's satiation convention is the same mechanic.** Hurricane's `El.a` stores `1 - a`
  (`BAttrWnd.java:144`), which is why its code reads `1 - el.a`. This fork's `el.a` is the game's own value
  (the list drops `a == 1.0` and paints `a > 1.0` as buffed), so `a` is already the multiplier.
* **Salt satiation** is part of the shared calculator, so `calcExpectedFep()` for *salted* food includes it
  (only when the character has a salt entry and salt is not already one of the food's own types). Follows
  Hurricane's observation; unverified.
* **Food-type indices.** Satiation is looked up by food-type *index* into the satiation list, exactly as the
  game's own highlight does (`BAttrWnd.Constipations.draw`) while it also removes entries that return to
  1.0. That only works if the server's list mirrors the client's, which cannot be checked offline. Indices
  past the end are skipped (such a type has no reducing entry). The food also carries the type *resources*
  (`FoodTypes`), which could be matched by identity instead; it was not switched because the two naming
  schemes could not be confirmed to coincide.
* Account bonus keeps this fork's `subscribed ? 1.5 : verified ? 1.2 : 1`; Hurricane has a 1.3 subscribed-only
  case. The flags come from two independent icons (`Img.checkVerificationStatus`), so the question is whether
  a subscribed account without the verified icon exists; unknown.
* The Deep Worm warning is based on Hurricane's report that the wound reduces food efficiency; the exact
  effect is unverified, so the warning only says predictions may be inaccurate.
* No gilding-recipe or tool-stat bonuses in the sidebar, by design.
* Not done in this pass: wound cure lists and the curio time-target slider (see the audit).
