# Haven & Hearth inventory grid system — reference for bot/inventory code

Read this before writing or reviewing any code that places, counts, drops, transfers, eats, or
studies items in an `NInventory`/`WItem` grid. Every claim below is verified against this repo's
actual code (file:line cited); nothing here is guessed. Where the client genuinely can't confirm a
server-side rule, that's called out explicitly rather than asserted.

## 1. Items are not 1x1 — footprint is read off the rendered sprite

There is no static per-resource size table. An item's grid footprint (in cells) is computed at
runtime from its loaded sprite's pixel size:

```java
// nurgling/NInventory.java:1813, GetFreePlace.java:38, GetNumberFreeCoord.java:43 — all three
// independently recompute this the same way rather than sharing one helper
Coord sz = wItem.item.spr.sz().div(UI.scale(32));
```

`haven/Inventory.java:37` — one grid cell is `32x32` px (scaled by UI scale), so dividing sprite
pixel size by `UI.scale(32)` gives cell width/height.

**This requires `item.spr` to already be loaded.** `GetFreePlace.check()` / `GetNumberFreeCoord.check()`
return `false` (not-ready, keep waiting) while `spr == null` — never treat "no size yet" as "0x0" or
"1x1".

**The critical footprint gotcha**: every grid-placement call site (`findFreeCoord`, `GetFreePlace`,
`GetNumberFreeCoord`) swaps the computed size to `(height, width)` before using it:

```java
// NInventory.java:1813-1814
Coord sz = wItem.item.spr.sz().div(UI.scale(32));
return findFreeCoord(new Coord(sz.y, sz.x));   // note the swap
```

Boards are documented (by the user, confirmed by this mechanism) as 1 cell wide x 4 cells tall.
If you ever compute a footprint yourself instead of going through `getFreeCoord`/`getNumberFreeCoord`,
remember the swap or you will search for free space using the transposed shape.

`nurgling.NGItem.sprsz()` (`NGItem.java:131-138`) computes the same ratio as a convenience method,
but note the grid-placement code paths above do **not** call it — they inline the same formula
separately. Don't assume changing `sprsz()` affects placement logic.

## 2. Grid state / free-space queries (`NInventory`)

- `containerMatrix()` (`NInventory.java:1734-1780`) — builds a `short[isz.y][isz.x]` occupancy
  matrix (`0`=empty, `1`=occupied, `2`=blocked by `sqmask`). **Returns `null` if any item's sprite
  hasn't loaded yet** — treat `null` as "not ready to answer," not "full."
- `findFreeCoord(Coord size)` (`NInventory.java:1818-1836`) — first free top-left cell for a
  `size.x` x `size.y` rectangle, or `null` if none. Wrapped for bots as `getFreeCoord(WItem)` →
  `nurgling/tasks/GetFreePlace.java`.
- `calcNumberFreeCoord(Coord size)` (`NInventory.java:1782-1809`) — how many non-overlapping
  placements of that footprint still fit (greedy, so it can undercount for shapes that don't tile
  perfectly — good enough for "is there room," not an exact packing solver).
- `getNumberFreeCoord(GItem/WItem)` (`nurgling/tasks/GetNumberFreeCoord.java`) — like the above, but
  **multiplies by the item's max stack size** if it's stackable (see §3), so it answers "how many
  *units* fit," not just "how many empty footprint-shaped holes exist."
- `calcFreeSpace()` / `calcTotalSpace()` — raw empty/total **cell** counts, footprint-unaware.

## 3. Stacking — two unrelated mechanisms, don't conflate them

**(a) Numeric-badge "amount" items** (coins, kg/L bulk goods) — `GItem.Amount`
(`haven/GItem.java:150-337`) renders a `×64`-style count on a *single* `GItem`. Not a container.
Read via `nurgling/tasks/GetItemCount.java` (uses `CustomName.count`) or
`GetTotalAmountItems.java` (sums `Amount.itemnum()` — **fails permanently** the moment it hits a
matching item with no `Amount` info, so only safe when every match is guaranteed to carry one).

**(b) True stack containers** — this is "4 blueberries as one grid slot with a count," and boards
"never stacking" is the negative case of this same mechanism:

- A stack occupies **exactly one `WItem`/one footprint slot** in the top-level inventory grid — it
  is a container `GItem` whose `contents` field points at a `haven/res/ui/stackinv/ItemStack.java`
  widget holding N independent child `GItem`/`WItem` pairs (`wmap`, `order`).
- Detect it via `contents instanceof ItemStack`, or the cached flag `NGItem.isStackContainer`
  (`NGItem.java:35,170-172`).
- **Current unit count**: `((ItemStack) witem.item.contents).wmap.size()`.
- **Is this item type stackable, and what's its max stack size**: `nurgling/tools/StackSupporter.java`
  — `isStackable(inv, name)` / `getFullStackSize(name)`. This is a **hand-maintained client-side
  heuristic table** (per-name overrides, a `catExceptions` never-stacks set, then category lookup
  in `categorySize`, e.g. `"Berry"`/`"Fruit or Berry"`/`"Seed of Tree or Bush"`/`"Mushroom"` → 4),
  **not** anything read from the server/protocol. Treat it as "best known, may need updating if
  game balance changes," not ground truth.
- `nurgling/tasks/GetNotFullStack.java` / `GetNotStack.java` find an existing mergeable stack/lone
  item of a given name by comparing `wmap.size()` against `getFullStackSize(name)`.
- **Quality does not gate stacking** — `haven/res/ui/tt/stackn/Stack.java:56-72` *averages*
  `quality` across a stack's children purely for the on-screen badge; `TransferToContainer`'s merge
  logic matches by name only, no quality check anywhere in the merge path. (What the *server* would
  do is outside what client code can prove — see §6.)

## 4. Whole-stack operations vs. per-unit operations — pick the right one

Because a stack is one `WItem` at the top level, many "act on this WItem" calls are *already*
whole-stack operations. Getting this wrong is what made an earlier version of this bot's auto-drop
feel like it was clearing a 4-berry stack one slow round-trip at a time instead of instantly.

**Whole stack, one message:**
```java
// nurgling/NUtils.java:492-494 — drops the entire container (and everything inside it) at once
public static void drop(WItem item) {
    item.item.wdgmsg("drop", item.sz, getGameUI().map.player().rc, 0);
}
```
Confirmed explicitly by `NWItem.autoDrop()`'s own comment (`NWItem.java:165-169`): dropping the
container "drops the whole stack, which is exactly what 'drop all of these' means."

`nurgling/actions/TransferToContainer.java:442` — moving a whole stack into a container is one
`wdgmsg("transfer", ...)` sent to the **container** `GItem`, not iterated per child.

`Dropper.java` / `FreeInventory2.java` both already call `NUtils.drop(item)` once per top-level
`WItem` (no per-unit loop) — this is the established, correct pattern for "get rid of this slot."

**Inherently per-unit, no whole-stack shortcut exists:**
- **Eat** and **Study** only ever consume one unit per click — eating one apple from a 4-stack
  produces a 3-apple stack *plus a separate 1-eaten-apple slot* (a new resource = a new `ItemStack`
  identity, not a mutation of the same one). There is no "eat/study the whole stack" message.
  If you need a stack fully consumed, loop the interaction on the *same slot* until it stops
  offering the action or the slot is empty — don't rely on an outer per-gob/per-scan loop to
  slowly rediscover the same partially-eaten stack across many expensive passes (see
  `nurgling/actions/StudyEatOrDrop.java` for the corrected pattern: check the slot still exists,
  interact, repeat, capped at a small iteration limit).
- **Partial-stack drop** (drop only some units, keep the rest) — `NWItem.autoDrop()`'s
  quality-threshold branch (`NWItem.java:192-211`) iterates `stack.order` and sends
  `childGItem.wdgmsg("drop", Coord.z, 1)` **per child**, deliberately not touching the container so
  the rest of the stack survives.

**Native client protocol** (for reference, `haven/WItem.java:180-200`, unmodified engine class):
Shift-click → `"transfer"`; Ctrl-click → `"drop"`; a count argument of `1` = one unit, `-1` = "all"
(sent for Shift+Ctrl / Ctrl+Alt respectively). This is the origin of the `n` count parameter seen on
`"transfer"`/`"drop"` wdgmsgs elsewhere in the codebase.

**Flood protection**: `NWItem.java:132-153` — a static 150ms throttle shared across *all* auto-drop
calls, with an explicit comment that dropping many items in the same instant trips the server's
spam protection and disconnects the client. Another reason to prefer one whole-stack message over N
per-unit messages whenever the goal really is "get rid of the whole slot."

**Bulk same-name operations across the grid** (not just one stack): Alt+Shift/Alt+Ctrl-click on an
item fires `NInventory`'s `"transfer-same"`/`"drop-same"` handling (`NInventory.java:1884-1933`),
which collects every top-level same-named slot (explicitly *not* expanding stacks — "would break
them apart during transfer") and sends one wdgmsg per slot. Same principle: one message per
stack-slot, not per unit.

## 5. Quality

- Set on `NGItem.quality : Float` (`NGItem.java:26`), populated from the `ui/tt/q/quality` tooltip
  info (`haven/res/ui/tt/q/quality/Quality.java:31-37`) or a faster raw-wire-data read in
  `NGItem.updateraw()` (`NGItem.java:272-333`).
- `NInventory.QualityType` (`NInventory.java:236-238`) is **not** a quality value — it's a 2-value
  `enum { High, Low }` used only as a sort-order selector for `getItems(NAlias, QualityType)`
  (returns all matches, sorted; not filtered by a quality threshold).
- Each unit inside a stack has its own independent `quality` — a stack is not one shared value.

## 6. What this doc can't tell you

Everything above is read from the client codebase, which only proves what the *client* does/assumes
— it can't prove server-side rules the client never has to enforce itself (e.g. whether the server
would ever refuse to merge two differently-qualified items into one stack). Where the client's own
behavior already implies an answer (e.g. `Stack.java` averaging quality across a stack's children
only makes sense if mixed-quality stacks are normal), that's noted as inference, not fact.

## Related code (for future changes, not yet covered above)

- `nurgling/tools/StackSupporter.java` — the stack-size/stackability table; update here if a new
  item's stacking behavior needs teaching to bots.
- `nurgling/actions/StudyEatOrDrop.java` — the per-slot Study→Eat→Drop triage this doc's Eat/Study
  guidance was written to fix; read its class comment for the concrete bug this document prevents
  re-introducing.
