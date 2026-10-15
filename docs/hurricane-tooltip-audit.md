# Hurricane tooltip and item-information audit

**Status: current** — a source-level comparison of the Hurricane client (`Nightdawg/Hurricane`, read at its
`v1.67` working tree) against this fork, done for PR #20. It is a record of what was compared and decided,
not a roadmap. Everything here is from reading both code bases; nothing was run in a live client.

Classes used below:

1. **Implemented** — Nurgling already has an adequate equivalent.
2. **Partial / different** — Nurgling has the capability but it behaves differently; not ported on purpose.
3. **Missing** — worth having. Each one is marked *ported* (done in PR #20) or *deferred* with the reason.
4. **Excluded** — not relevant here, or intentionally left out.

Feature descriptions of what was built live in [`equipment-tooltip-enhancements.md`](equipment-tooltip-enhancements.md);
FEP/food mechanics in [`feps-system-reference.md`](feps-system-reference.md).

## Items, overlays and item info

| Hurricane feature (source) | Class | Nurgling | Notes |
|---|---|---|---|
| Durability bar, `WItem.drawDurabilityBars` (`WItem.java:442-454`, wear cache `:68-77`) | 1 | `NWItem.drawmain`, `tools/ItemDurability` | Ported earlier in the PR; cached per `infoseq`, drawn under the quality/count overlays. |
| Salted / Peppered / Black-truffled / White-truffled icons (`WItem.java:239-257`, `drawadhocicon :484-488`) | 3, **ported** | `iteminfo/NSpiceMarks`, `iteminfo/marks/SpiceMarks`, hook in `ItemInfo.buildinfo` | Same overlay mechanism as `WellMined`; the game's own `gfx/invobjs/{salt,pepper,herbs/truffle-*}` icons, cached, top-right of the item. Hurricane loads and draws them per frame; this caches. Setting `showSpiceMarks` (QoL). |
| "Well mined" marker (`WItem.java:243-244`, `drawwellmined :478-482`) | 1 | `res/ui/tt/wellmined/WellMined`, `ItemInfo.java:446-449` | Already present before the PR (a different glyph, same information). |
| Item count text from contents/ad-hoc lines (`WItem.heurnum :62-66`, `ItemInfo.getCount :674-706`) | 2 | `GItem.Amount`, `Stack` overlay, `NGItem.content()`, `ItemOverlaySettings` | Nurgling shows amounts through its configurable overlay system. Not ported: a second counting path would show two numbers. |
| Quality overlay incl. an "Empty" label for emptied containers (`res/ui/tt/q/quality/Quality.java:28-65`) | 2 | `Quality` + `ItemQualityOverlaySettings` (corner, font, colour), content quality via `withContent` (`Quality.java:166-171`) | Nurgling's version is user-configurable and already shows what a container holds. The "Empty" word is a preference, not information; not ported. |
| Average quality of a stack (`GItem.stackQualityTex`, `WItem.java:263`) | 1 | `NInventory.getEffectiveQuality` (`:2157-2163`), `res/ui/tt/stackn/Stack` | |
| Pie/percent text for progress and remaining study time (`WItem.java:361-416`) | 2 | `NCuriosity` remaining-time overlay, `ItemOverlaySettings` "progress" tab | Configurable in Nurgling. |
| Search highlight pulse (`WItem.java:202-224`) | 2 | `NSearchingHighlight`, `NWItem.search()` | Different visual, same function. |
| Tooltip cache dropped on Shift (`WItem.java:142-149`) | 1 | `TooltipRebuildGate` + `NWItem.tooltip` | Plus `DisposableSlot` so replaced textures are freed. |
| Hover contents only while Shift (`WItem.mousehover :338-343`), transfer-ordered, auto flower menu (`mousedown :272-326`) | 4 | — | Inventory controls, not information. Explicitly out of scope. |
| Reflection helpers `ItemInfo.getBonuses/getArmor/getDamage/getWear` (`ItemInfo.java:577-672`) | 1 | typed `AttrBonusCollector`, `ItemDurability`, armor parse in `NGItem` (`:339-340`) | Hurricane swallows every exception; Nurgling reports pending/unsupported (see the main doc). |
| `longtip` override that avoids a double text stroke (`ItemInfo.java:300`) | 4 | — | Specific to Hurricane's stroked tooltip rendering. |
| Compact vs extended gilding list (`res/ui/tt/slots/ISlots.java:45`, `slots_alt :49`) | 1 | `NTooltip.renderGildingSummary` | Per-item Shift via the item's own UI. |

## Food

| Hurricane feature (source) | Class | Nurgling | Notes |
|---|---|---|---|
| Food tooltip with efficiency, modified/unmodified view, weighted totals, FEP bar fill (`resutil/FoodInfo.java:85-221`) | 1 | `NFoodInfo` + `FoodDetailsLayout` + `iteminfo/food/*` | Formulas differ deliberately; see the FEP reference for each rule and its evidence. |
| Satiation of foods with several types | fixed | `FoodModifierReader.satiation` | Was "last type wins" (order dependent). Hurricane multiplies; so does Nurgling now. |
| Satiation (and salt) scale displayed hunger (`FoodInfo.java:101,107,159`) | 1, **supported, not measured** | `FoodModifiers.hungerMultiplier()` = satiation x salt x table hunger modifier | Hurricane's code, its character-sheet tooltip (`BAttrWnd.java:506`) and the Ring of Brodgar "Food Satiations" page agree; nothing contradicts them. Adopted as the default. Hurricane's `gmod`/account factors apply to FEP only there too, so they stay off hunger. Uses native `el.a`, not Hurricane's inverted value. Procedure to measure it: `feps-system-reference.md`. |
| Salt satiation as a separate factor (`FoodInfo.java:105-108`) | 1, **unverified** | `FoodModifierReader.saltSatiation` | Applied only when salted, a salt entry exists, and the food's own types do not already include it. |
| Subscribed-only account factor 1.3 (`FoodInfo.java:111-113`) | 2 | `FoodModifiers.Account` (1.5 / 1.2 / 1) | Not adopted: Nurgling detects "Verified" and "Subscribed" from two independent icons; whether a subscribed account also shows the verified icon is not known, and the fork's 1.5 has been the working value. |
| Deep Worm warning (`FoodInfo.java:139-155`) | 1 | `FoodModifiers.deepWorm`, `FoodDetailsLayout` | Wording is deliberately vague. |
| Table "Food event bonus" / "Hunger modifier" labels (`FoodInfo.java:114-138`) | 1 | `FoodModifierReader.table`, `TableLabels` | Now strict: an unreadable label means "no prediction", not "neutral". |

## Character sheet, study, crafting, barter, buffs

| Hurricane feature (source) | Class | Nurgling | Notes |
|---|---|---|---|
| Base and total attribute columns (`BAttrWnd.java:65-106`) | 1 | `NBAttrWnd.NAttr` via `NAttrUtil.drawValue` (`NBAttrWnd.java:60`) | |
| Satiation list showing efficiency, inverted `El.a` (`BAttrWnd.java:139-146,213`) | 1 | `NBAttrWnd.NConstipations` | Hurricane stores `1 - a`; that is why its formulas read `1 - el.a`. Not a different mechanic. |
| Custom Food Efficiency and Hunger header tooltips (`BAttrWnd.java:506-514`) | 4 | the game's own `settip` tooltips | Editorial text. Noted only because it states that satiation, table, verification and subscription affect "FEP and Hunger gain". |
| Glut meter tooltip wording (`BAttrWnd.java:480`) | 4 | stock text | Wording only. |
| Equipment bonuses panel (`Equipory.java`, `AttrBonusesWdg`) | 1 | `EquipBonusPanel`, `iteminfo/bonus/*` | Typed instead of regex-summed. |
| Detection / armor text under the equipment (`BAttrWnd.java:109-123`) | 1 | `NEquipory.updatePercExpText`, `updateTotalArmor` | Armor now counts an item once. |
| Curiosity LP/H, LP/H/W, study time, remaining time (`resutil/Curiosity.java:46,83-100`) | 1 | `NCuriosity` (`:54,110-116`) | Plus a real-time toggle. |
| Mini-Study window (`MiniStudy.java`) | 2 | `NStudyReport` floating widget (`NSAttrWnd.java:236-244`), `StudyDeskInventoryExtension` (total LP, per-curio detail) | |
| Curio time target slider (`StudydeskInfo.java:30-61`) | 3, **deferred** | — | A study-desk planning widget, not item information. Nurgling's study desk extension already totals LP and shows per-curio detail. Worth a separate change if wanted. |
| Crafting recipe/ingredient tooltips (`Makewindow.java:99-125,309-324,450-471`) | 1 | `NMakewindow` (`:729-787`), `NRecipeTooltip` | Hurricane's file carries no Hurricane-specific tooltip code (no `ND:` tags); nothing to port. |
| Quality shown beside the offered item at a barter stand (`res/ui/barterbox/Shopbox.java:37,107-108,138-141,326-341`) | 3, **ported** | `Shopbox.quality()` calling `nurgling.iteminfo.OfferQuality` | The quality was reachable only through the tooltip. Same place and text as Hurricane. Hurricane builds the label inside `info()` and never clears it when the tooltip changes; here it is cached per built info list, hidden while the info is loading, and invalidated on `tt`/`res`. |
| Buff tooltips (`Buff.java:132-164`) | 1 | stock | Unchanged from upstream in Hurricane too. |
| Wound "Treated with" cure list (`WoundWnd.java:121-132`, data `Config.java:962-`) | 3, **deferred** | — | A hand-maintained table (31 wounds, about 74 treatment references) that cannot be verified offline and would rot with game updates. Better as its own change with a data source. |

## Modifier keys that change item information

| Key | Hurricane | Nurgling |
|---|---|---|
| Shift on a food | hides modifiers (`FoodInfo.java:91,206`) | shows unmodified values (`NFoodInfo.layout`, `FoodDetailsLayout.shiftHeld`) |
| Shift on a gilded item | per-gilding detail (`ISlots.java:45`) | per-gilding detail (`NTooltip.isExpandedView`) |
| Shift while hovering | rebuilds the cached tooltip (`WItem.java:142-149`) | `TooltipRebuildGate.observe` |
| Shift over a container item | opens the hover inventory | not touched (inventory control) |

Both clients key the view on the **item's own UI**. Hurricane notes a barter-stand quirk where `ui.modshift` is not
seen (`ISlots.java:45`, `ui == null` falls back to extended); Nurgling's `isExpandedView` makes any non-`NGItem`
owner (shop, recipe) extended.

## Not compared

The full `OptWnd`/settings surface, map and combat overlays, and chat features are out of scope for "tooltip and
item information" and were not read.
