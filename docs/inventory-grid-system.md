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

**`getItems(NAlias)` returns LEAF units, not the top-level stack container (Round 6 correction).**
`GetItems.checkContainer()` (`nurgling/tasks/GetItems.java`) only ever adds a `WItem` to its result
when `item.item.contents == null` (i.e. it isn't a container); when `contents` **is** a stack, it
recurses into `contents.child` instead of adding the container itself, and returns whatever matching
children it finds inside. So for a matching stack, `inv.getItems(alias)` gives you the individual
child `WItem`s (each wrapping one `GItem` from the `ItemStack`'s `wmap`/`order`), never the
container's own top-level `WItem` — a caller that assumed `getItems(alias)` yields "one WItem per
occupied slot, whole stacks included" (as an earlier version of `StudyEatOrDrop`'s class doc did) is
wrong: it already gets a flattened, per-unit view for anything stacked. This matters for *dropping*:
calling `NUtils.drop()` on one of these child `WItem`s sends `"drop"` to that child's own `wdgid`,
which removes only that one unit (see §4's per-unit drop message) — it does **not** drop the whole
stack, even though `NUtils.drop()` on the *container's own* top-level `WItem` would. Get the
container's `WItem` itself only via a top-level walk (`inv.child`, or `InventorySnapshot.
findTopLevel()` below), never via `getItems(alias)`.

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
  — `isStackable(inv, name)` / `getFullStackSize(name)`. The base layer is still a **hand-maintained
  client-side heuristic table** (per-name overrides, a `catExceptions` never-stacks set, then
  category lookup in `categorySize`, e.g. `"Berry"`/`"Fruit or Berry"`/`"Seed of Tree or Bush"`/
  `"Mushroom"` → 4), not anything read from the server/protocol — but as of the shared `stack_sizes`
  DB table (migration 13, `nurgling/db/migration/MigrationManager.java`;
  `nurgling/db/service/StackSizeService.java`), both methods consult that table *first* whenever a
  database is configured, and only fall back to the static table when the DB has no opinion on a
  given name. The DB-backed table starts seeded from the static one, then self-corrects: it's
  passively updated whenever a client observes a real stack bigger than what's on record
  (`NInventory.observeStackSizesForLearning()`, gated by `NConfig.Key.stackSizeLearning`), and can be
  edited directly by a player via the "Stack Size Calibration" window
  (`nurgling/widgets/db/StackSizeCalibrationWindow.java`, reachable from the Database settings
  panel). Corrections sync to every client sharing the same database the way `NArea`s do. Treat the
  *static table alone* as "best known, may need updating if game balance changes, not ground truth"
  — but with the DB layer configured, staleness for any item someone has actually played with (or
  manually calibrated) self-heals without a client update.
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

**A `"drop"` wdgmsg is fire-and-forget, not a confirmation.** `NUtils.drop()` (and `NInventory.
dropOn()`) send the message and return immediately; the item actually leaves the grid only once the
server's own delta arrives, on its own schedule — and it can be silently ignored altogether (e.g.
the server rejecting a non-empty bucket being put into a container slot, confirmed live 2026-08 —
see `docs/lp-assistant-bot.md`'s Round 3 notes). Code that needs to know a drop *actually happened*
(not just that the message was sent) before doing something else — like checking free space right
afterward — must wait for it: `nurgling/tasks/WaitNoItems.java` polls live grid state for a name to
disappear, and now has a bounded-timeout constructor (`WaitNoItems(NInventory, NAlias, long)`) for
exactly this, instead of assuming the fire-and-forget send already succeeded.

**`NInventory.dropOn(Coord)` is bounded by poll *count*, not wall-clock time (Round 4 gotcha).** It
sends the "drop" wdgmsg itself, then blocks inside `NCore.addTask(new DropOn(...))` — an `NTask`
whose default `maxCounter = 200` is checked once per `NCore.tick()` call, not once per fixed time
interval (see `NTask.baseCheck()`). Code that needs an actual wall-clock bound ("give up after 5
real seconds, no matter what") must not call `dropOn()` and then also wrap that in its own
wall-clock wait — the two bounds compose unpredictably, since the tick-count bound's real-world
duration isn't knowable. Send the "drop" wdgmsg directly instead (`inv.wdgmsg("drop", pos)`, the
same message `dropOn()` sends) and use one wall-clock-deadline task of your own (see
`LpAssistantBot.waitCursorSettled()` for a worked example — confirmed live 2026-08 as the fix for a
cursor-clear step whose real worst-case latency had been unknowable this way).

**`findFreeCoord(Coord)`'s outer scan bounds were swapped (fixed 2026-08, Round 4).** The convention
above (`target_size.x` = rows/height, `target_size.y` = columns/width) held for the inner loop and
for `calcNumberFreeCoord()`, but `findFreeCoord()`'s *outer* loop bounds had the two swapped
(`i <= isz.y - target_size.y`, `j <= isz.x - target_size.x`), which under-scanned valid column start
positions for any non-square footprint. Concretely: a 1-wide x 4-tall board (`Coord(4,1)`) in a
6-column inventory only ever tried column starts 0..(6-4)=2, silently never considering columns 3-5
even though a 1-wide item obviously fits in any of them - `findFreeCoord()` could report "no fit"
even with a wide-open column near the right edge. Fixed to match `calcNumberFreeCoord()`'s (correct)
bound orientation. Shared by every caller of `findFreeCoord`/`getFreeCoord` (`Equip`, `SortInventory`,
`TransferToTrough`/`TransferToBarrel`, `FeedClover`, `TaimingAnimal`, `EquipFromInventory`, and
others) - this was a strict correctness fix (finds a superset of what it found before, never fewer
placements), not a behavior change any caller was relying on.

**`NInventory.isGridReady()`**: `containerMatrix()` (and therefore every placement/free-space query
built on it) returns `null` while any top-level item's sprite hasn't loaded yet - see §1. That "not
ready to answer" state must never be read as "no placement exists": they're different facts a caller
may need to react to differently (retry shortly vs. a real full inventory). `isGridReady()` is a
convenience wrapper (`containerMatrix() != null`) for code that wants to tell the two apart before
trusting a `null` return from `findFreeCoord`/`calcNumberFreeCoord`/`calcFreeSpace`.

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

## 7. Consistent snapshot/ownership rules (`nurgling/tools/InventorySnapshot.java`, Round 6-7)

LP Assistant needs to answer "what did *this specific harvest* actually produce, and is it safe to
touch" across several separate stages (pre-harvest baseline, first-product detection, post-interrupt
settlement, ownership calculation, drop lookup, cleanup confirmation) without each stage quietly
implementing its own notion of "new." `InventorySnapshot` is the one shared model all of them use:

- **Capture** (`InventorySnapshot.capture(inv, alias)`): an instant, non-blocking read of every
  top-level item and stack child whose name matches `alias` into `topLevel` (or everything, if
  `alias == null`), PLUS an alias-**independent** `allUnitIds` set of every unit wdgid in the
  inventory regardless of name (Round 7 - see next bullet), plus the cursor. Never queues an `NCore`
  task — safe from inside another task's `check()`. Runs inside `synchronized (inv.ui)` (the same
  critical section `GetItems`'s own traversal uses) so the walk can't observe a partially-mutating
  widget tree, and reads the cursor via `inv.ui.gui` — the inventory widget's own owning session —
  never the globally-selected `NUtils.getGameUI()`, which could pair one session's inventory with a
  different session's cursor in a multi-session process. Identities are `GItem.wdgid()`, never
  long-lived `WItem` references — a `WItem` is a UI widget the client can destroy/recreate for the
  same server-side item (e.g. a stack's contents widget re-attaching), so holding one across a wait
  risks acting on a stale/destroyed reference. Anything that needs to actually touch an item
  re-resolves it live by wdgid via `findAny()` (searches the WHOLE inventory, any parent) or the
  narrower `findTopLevel()`/`findChild()`.
- **Identity universe, not container shape (Round 7 correction).** `allUnitIds` is captured
  unconditionally — a loose item's own wdgid is a unit; a stack **container's** wdgid is never
  itself a unit, only its children's wdgids are. `diff()` decides ownership by checking each
  candidate unit's wdgid against `baseline.allUnitIds`, not by asking whether its *container* existed
  before. This is what makes ownership survive re-parenting: a pre-existing stack child that becomes
  loose, a pre-existing loose item that gets merged into a brand-new stack container, or a recreated
  container that happens to hold only old children, are all correctly recognized as still
  pre-existing — a Round 6 gap, since that version compared primarily by *container* wdgid and could
  misclassify a re-parented pre-existing unit as this harvest's own output.
- **Physical vs. matching children (Round 7b).** `TopLevelEntry` tracks a stack's
  `physicalChildWdgids` (EVERY child actually inside the container, including one whose name is
  still null/unresolved, or a genuinely different product sharing the same widget) separately from
  `matchingChildWdgids` (the subset confirmed by name to belong to this snapshot's alias). This
  matters because a container's child count/matching-name-count alone was never sufficient proof
  that every physical unit inside it is safe to sweep up in one whole-stack drop message — an
  unresolved or unrelated physical child would otherwise be silently dropped along with it.
- **Delta** (`settled.diff(baseline)`): a loose item's wdgid absent from `baseline.allUnitIds` is
  unambiguously new. A stack's MATCHING children are checked individually against that same
  universe to find which are new; the container only qualifies for `newWholeStacks` (safe to drop as
  one message, see below) when its complete PHYSICAL child set exactly equals that new-matching set
  — i.e. every physical child, no exceptions, is both confirmed-matching and new. If only **some**
  matching children are new (or any physical child is unmatched/unresolved/pre-existing), just the
  new matching ones go into `newChildrenInStacks` and the container itself is never treated as
  ownable (never whole-dropped); if **none** are new (e.g. a recreated container holding only
  pre-existing children), nothing is reported for it. A pre-existing stack that only *loses*
  children (collapse/split) never causes any pre-existing unit to look owned, since only
  genuinely-absent-from-baseline ids are ever reported. A top-level wdgid whose OWN loose/stack shape
  flipped between snapshots (the identity itself was reused for a different kind of thing) is
  reported separately as `ambiguousTopLevel`, never folded into either "new" bucket. The whole-stack
  membership is also **revalidated live** (`InventorySnapshot.physicalChildrenOf()`), in the same
  cleanup poll that sends the drop, immediately before it's sent — see
  `docs/lp-assistant-bot.md`'s Round 7b section.
- **Cursor classification** (`settled.classifyCursor(baseline)`): `EMPTY` (nothing held);
  `PRE_EXISTING` (the exact same wdgid `baseline`'s cursor already had, OR a wdgid already present
  anywhere in `baseline.allUnitIds` — e.g. a pre-existing inventory item the player moved into the
  cursor — never touched); `NEW_EXPECTED` (matches the harvest's product and its wdgid was never
  observed anywhere in the baseline — safe to stash/drop, but LP Assistant still re-verifies the
  LIVE cursor against this classification immediately before acting, since time passes between the
  snapshot and the action — see `docs/lp-assistant-bot.md`'s Round 7 section); `AMBIGUOUS` (present,
  but neither of the above — unknown origin, must not be assumed safe to clear, and LP Assistant
  stops the whole run rather than guessing for both `PRE_EXISTING`-while-still-occupied and
  `AMBIGUOUS`).
- **Whole-stack vs. specific-child dropping, and the official drop protocol (Round 7).**
  `haven.WItem.mousedown()`'s real CTRL-click handler sends `item.wdgmsg("drop", ev.c, n)` — a
  two-argument-plus-count message — regardless of whether `item` is a loose item or a stack
  container; for a stack container this drops the *whole* stack in that one message, matching
  `NWItem.autoDrop()`'s own documented "drops the whole stack" behavior for the `ALWAYS`-threshold
  case. `NUtils.drop()` (§4 above) sends a *different*, four-argument message (an outside-the-window
  ground-drop, not a CTRL-click). For a `newWholeStacks` target specifically, LP Assistant now sends
  the container's own `wdgmsg("drop", Coord.z, 1)` — matching the real CTRL-click protocol exactly —
  as ONE message for the whole stack, never iterating its children; a `newChildrenInStacks` target
  (a specific child in a mixed/pre-existing stack) uses the identical two-argument-plus-count
  message but sent to that **child's own** GItem instead (the same per-child protocol
  `NWItem.autoDrop()`'s quality-threshold branch already uses) — the container itself is never
  touched. A plain loose item still uses `NUtils.drop()`, unchanged. Every target is resolved live
  via `InventorySnapshot.findAny()` (identity-based, any parent), not a lookup scoped to whichever
  container it was originally recorded under — a unit that gets re-parented between ownership
  calculation and drop confirmation is still found this way, and a whole-stack container that stops
  resolving before its drop is confirmed falls back to per-child drops for whichever of its recorded
  children can still be found.

See `nurgling/actions/bots/LpAssistantBot.java` (baseline/settlement/triage/cleanup) and
`nurgling/tasks/WaitLpFirstProduct.java`/`WaitLpSettlement.java` for the actual call sites, and
`docs/lp-assistant-bot.md` for the bot-level behavior this enables.

## 8. Inventory-window UI: sort buttons and the item-list side panel

Two client-only UI features live on `nurgling/NInventory.java`'s window chrome, on top of the grid
mechanics above. Both are per-`NInventory`-instance (main player inventory and every container window
each get their own), not global singletons.

### 8a. Title-bar sort buttons — Sort, Sort Within Stacks, Stack to Max & Sort

Three buttons sit in the title bar, right-anchored before the window's close button (main inventory:
`NInventory.installMainInv()`, `NInventory.java:834-848` builds `stackMaxBtnRef`/`stackSortBtnRef`/
`sortBtnRef` in that left-to-right visual order and `positionTitleBarButtons()`,
`NInventory.java:1069-1080`, lays them out with priority-based dropping when the window is too narrow
to fit them all; container windows: `NInventory.addSortButtonToTitleBar()`, `NInventory.java:154-243`,
positions the same three via each button's own `tick()` override chained off `deco.cbtn`, independent
of `positionTitleBarButtons()`). All three delegate to static entry points on
`nurgling/actions/SortInventory.java`:

- **Sort** (`SortInventory.sort()`) — positional-only: alphabetizes 1x1 items into the grid, highest
  quality first within a name (`ITEM_COMPARATOR`, `SortInventory.java:43-95`). Pre-existing, undocumented
  here previously.
- **Sort Within Stacks** (`SortInventory.sortDeep()`) — positional sort, then `sortWithinStacks()`
  (`SortInventory.java:495-` on, cycle-chase permutation sort) redistributes individual units *across*
  same-name stacks so the highest-quality units end up concentrated in the first (top-left-most) stack,
  without changing how many stacks exist or their sizes. Pre-existing, undocumented here previously.
  This algorithm is real-time-costly by construction — every unit move is a live take→drop/itemact
  round trip through the server (`NUtils.takeItemToHand`, `NUtils.itemact`), each followed by a real
  wait task (`WaitFreeHand`, `StackSizeChanged`, `ISRemovedLoftar`/`ISRemoved`) for the server's delta to
  land, and a full fresh grid rescan (`freshScan()`) starts every cycle. There is no way to batch these
  into fewer server round trips without a different, unverified client-side protocol shortcut (see the
  note at the end of 8a on the native drag-merge gesture) — treat its slowness as inherent to "many
  individually-confirmed unit moves," not as an unexamined inefficiency.
- **Stack to Max & Sort by Quality** (`SortInventory.sortAndStack()`, `SortInventory.java:389-407`,
  new) — runs a consolidation pass (`consolidateStacks()`/`consolidateOne()`,
  `SortInventory.java:444-529`) *before* the positional sort, then always runs `sortWithinStacks()`
  afterward regardless of the `deepSort` flag. Consolidation greedily drains the globally
  smallest same-name slot into the fullest same-name slot with room (one unit per take/itemact round
  trip, via the same primitives `sortWithinStacks()` already uses — `takeItemFromSlot()` was widened to
  accept a nullable quality, `SortInventory.java:849-870`, `null` meaning "take whichever unit is
  first/only," since consolidation doesn't care which physical unit moves) until the item occupies
  `ceil(totalUnits / StackSupporter.getFullStackSize(name))` slots — the minimum possible — for every
  stackable name present (`StackSupporter.isStackable()` gates which names are attempted). *Which*
  physical units end up in which resulting stack is left up to the following positional + within-stack
  sort passes to fix, exactly like the plain "Sort Within Stacks" flow's `deepSort` case does; this
  avoids duplicating the quality-concentration logic.
  - **Known unexplored optimization**: the native client exposes a drag-and-drop gesture (Shift/Ctrl+Shift
    held while right-click-dragging one item onto another — see the in-game tooltip on any stack: "Hold
    Ctrl+Shift to make stacks of all such available items") that reportedly merges *all* available units
    of a kind in one gesture, server-side, without per-unit round trips. This repo's code never invokes
    it — `WItem`'s drag/drop handling for that gesture was not traced as part of this work, and no
    `wdgmsg` call reproducing it exists anywhere in `src/`. If a future pass confirms the exact
    message shape, `consolidateOne()` could send one message per (source, destination) pair instead of
    one per unit, which would be the actual fix for "Sort Within Stacks is slow" rather than a
    micro-optimization of the current per-unit loop.
  - New button icons (`NStyle.stackmaxi`, `NStyle.java`, and `nurgling/hud/buttons/inv/stackmax/{u,d,h}`
    for the main-inventory `NHeaderButton` string-base constructor) are placeholder duplicates of the
    existing "Sort Within Stacks" artwork (`resources/src/nurgling/hud/buttons/sort/stackmaxbtn{u,d,h}.res`
    and `resources/src/nurgling/hud/buttons/inv/stackmax/{u,d,h}.res`, copied from `stacksbtn*`/
    `stacksort/*` respectively) — visually identical to the stack-sort button until real art exists.

### 8b. Item-list side panel — per-unit quality, and container support

The panel toggled by the eye-icon title-bar button (`NHeaderCycler`, cycles closed → simplified →
expanded → closed, state persisted in `NConfig.Key.inventoryRightPanelShow` and shared across every
inventory window) lists every item name in the inventory, optionally grouped/binned by quality
(`NInventory.Grouping`), with quantity and (in expanded mode) average quality shown per row.

**Per-unit quality (fixed; previously wrong).** Both list-building passes —
`rebuildItemList()` (expanded panel, `NInventory.java:1498-` on) and `rebuildCompactList()` (simplified
panel, `NInventory.java:1589-` on) — used to iterate only *top-level* `WItem`s and, for a stack
container, read its quality via `item.getInfo(Stack.class)` (`haven/res/ui/tt/stackn/Stack.java`),
which is the on-screen badge's **average across the stack's children** (`Stack.overlay()`/`tick()`,
lines 56-72/200-224), computed live from `item.contents.children()`. That collapsed an entire stack
into one (name, averaged-quality) bucket with the stack's full unit count, discarding every child's
real individual quality — a 4-unit stack with qualities 10/50/100/300 showed as one `x4 q115.0` row
instead of four separate rows. Each unit inside a stack has its own independent quality (§3 above); the
averaging was only ever meant for the small on-screen number overlay, not for accounting.

Fixed by flattening: both loops now check `nitem.contents instanceof ItemStack` and, when true, iterate
`stack.order`/`stack.wmap` (the same fields §3's stacking section documents) to feed each **child**
`NGItem`/`WItem` pair in individually — never the container — via new small helpers
`addUnitToItemGroups()` (`NInventory.java:1495-1521`, quality-aware, expanded panel) and
`addUnitToCompactGroup()` (`NInventory.java:1586-1596`, name-only, simplified panel). A non-stack slot
(loose item, or a numeric-badge "amount" item per §3(a)) is fed in unchanged, since it was already a
single accounting unit. `getItemQuality()` (`NInventory.java:1470-1486`) and `ItemGroup.recalculate()`
(`NInventory.java:1417-1453`) were simplified to drop their `Stack`-info branch entirely, since a
container's own averaged quality can no longer reach them — every `NGItem` that reaches an `ItemGroup`
now is always a real single physical unit (or a genuine Amount-based bulk blob, which still carries one
quality for its whole counted amount, per §3(a)).

One consequence worth relying on deliberately: because each row's `wItems` list now holds the actual
per-child `WItem` (from `stack.wmap`) instead of the stack container's own `WItem`, a plain click on a
row (`createItemWidget()`'s `mousedown()`, and the simplified-panel row's own `mousedown()`) sends
`"take"` to that **specific child's** own `GItem` wdgid — which, per the native per-unit `"take"`
protocol (§4 above, `haven/WItem.java:180-200`), lifts just that one unit out of its stack into the
cursor, leaving the rest of the stack alone. Same for Shift/Ctrl-click group actions
(`processGroupItems()`, `NInventory.java`) — they now send `"transfer"`/`"drop"` to the matching
individual child(ren) instead of the whole container. This was a direct requirement, not a side effect
kept out of caution: pulling one exact-quality unit out of a mixed stack via the list panel now works
because the row *is* that unit, not an average of several.

**Container support (new).** The panel was previously installed only for the main player inventory,
via `NInventory.installMainInv()` (called once from `NGameUI.addchild()`,
`NGameUI.java:577-580`, guarded by the per-instance `mainInvInstalled` flag). Container windows now get
the same panel through a new `installListPanelIfContainer()` (`NInventory.java:261-320`, called from
`added()` alongside the existing `addSortButtonIfContainer()`), guarded by its own `listPanelInstalled`
flag and skipped for the main inventory itself and for any window in
`SortInventory.EXCLUDE_WINDOWS` (crafting stations, Character Sheet, Study, Belt/Pouch/Purse, etc. —
the same exclusion list the sort buttons already used, since a non-grid or special-purpose "inventory"
doesn't have a meaningful item list either). It reuses `setupRightPanel()`/`applyPanelState()`/
`updateItemListSize()` unchanged — those were already written generically against `NInventory.this`
with no main-inventory-specific assumptions, which is what made this an additive change rather than a
refactor: `rightPanel` visibility/refresh in `tick()` (`NInventory.java:604-613`) and repositioning in
`resize()` (`NInventory.java:509-525`) already ran unconditionally for every `NInventory` instance, they
simply had nothing to act on for containers until `rightPanel`/`eyeBtn` existed. The container install
path deliberately does **not** set `sortBtnRef`/`stackSortBtnRef`/`stackMaxBtnRef`/`searchBtn`/
`dropperBtn` — those stay null for containers, so `positionTitleBarButtons()`'s left-anchored,
priority-drop layout (built for the main inventory) only ever manages the eye button there, leaving the
three sort buttons under their separate self-positioning `tick()` overrides from `addSortButtonToTitleBar()`
undisturbed. Search and the auto-dropper toggle were intentionally not added to containers — out of this
feature's scope (auto-drop-on-pickup and the main-inventory search widget don't have an obvious
container analog) — only the list panel itself.

## Related code (for future changes, not yet covered above)

- `nurgling/tools/StackSupporter.java` — the stack-size/stackability table; update here if a new
  item's stacking behavior needs teaching to bots.
- `nurgling/actions/StudyEatOrDrop.java` — the per-slot Study→Eat→Drop triage this doc's Eat/Study
  guidance was written to fix; read its class comment for the concrete bug this document prevents
  re-introducing.
