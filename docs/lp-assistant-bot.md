# LP Assistant Bot & LP Discovery System Reference

Reference for anyone doing LP-Assistant-related work in this repo: the pre-existing
"LP Assistant" marker feature (`nurgling.tools.LpExplorer` + its overlay/minimap
renderers) and the bot built on top of it (`nurgling.actions.bots.LpAssistantBot`,
added 2026-08). Documents the *actual client implementation*, several non-obvious
bugs found and fixed while building the bot, and one bug found but deliberately not
fixed (out of scope at the time). Written so a future session doesn't have to
re-derive any of this from scratch.

## What the feature is

H&H grants a one-time Learning Points bonus the first time a character picks up a
given product from a given resource (e.g. the first "Red Apple" ever picked, the
first "Board" ever cut). The **LP Assistant** feature (`NConfig.Key.lpassistent`
toggle) tints a small icon on any gob that still has at least one such
not-yet-earned product, both as a 3D-world label (`NLPassistant`) and a minimap dot
(`MinimapDiscoveryRenderer`) — purely a display feature; it does not act on its own.

**`LpAssistantBot`** automates *acting* on those markers: walks to every currently-
loaded qualifying gob, performs the right flower-menu action for each undiscovered
product (equipping a configured tool first if needed), confirms the pickup actually
registered, then moves to the next — until nothing is left in view.

## Core objects

| Concept | Where it lives | Notes |
|---|---|---|
| Which products a resource tracks | `nurgling.tools.VSpec.object` (`Map<String,ArrayList<String>>`, resource name → product names) | Static data table. No category metadata — category is inferred from naming convention, see below. |
| Per-character discovery record | `nurgling.widgets.NCharacterInfo.lpExplorer` (`Map<String,ArrayList<String>>`, resource name → discovered product names) | **Not SQLite** — a plain per-character JSON file, see "Storage" below. The single source of truth every marker/overlay ultimately reads from. |
| "Is this undiscovered" query layer | `nurgling.tools.LpExplorer` | `allUndiscoveredProducts(Gob)`, `hasUndiscoveredProduct(Gob)`, `isFullyDiscovered(String)` (cached per-character per-season), `isProductUndiscovered(gobResName, product)`. All resource-scoped (not gob-instance-scoped) — discovering "Red Apple" on one apple tree clears the marker on every apple tree of that species at once, by design. |
| Writing a discovery | `LpExplorer.checkLpExplorer(String itemName)` | Called from `NGItem.tick()` the *first time* a picked-up item widget's name resolves. Gated by `recentHarvestClick()` — see "The clickedGob trap" below, the single most important gotcha in this file. |
| 3D-world marker (fallback) | `nurgling.overlays.NLPassistant` | Only active when the gob's own always-on harvest overlay (`NObjHarvestOl`, gated by `HarvestSpecs`) is off. Re-evaluates every tick, no staleness. |
| Minimap marker | `nurgling.overlays.map.MinimapDiscoveryRenderer` | Recomputes every frame, non-blocking. |
| Always-on harvest overlay (tints discovery in place) | `nurgling.overlays.NObjHarvestOl` + `nurgling.tools.{TreeHarvestSpec,BushHarvestSpec,ProductListHarvestSpec}` | Logs/stones/old-trunks (no live per-instance state) use `ProductListHarvestSpec`; trees/bushes decode a live bitmask for seed/leaf presence. |
| Bot main loop | `nurgling.actions.bots.LpAssistantBot` | See "Bot design" below. |
| Product → petal-text matching | `nurgling.tools.LpActionMatcher` | See "Petal matching" below. |
| Bot settings | `nurgling.conf.NLpAssistantProp` + `nurgling.widgets.bots.LpAssistant` | Per-category tool `NAlias` strings, `autoEatNew`/`autoDropNew` (independent toggles), `debug`. |
| Post-harvest inventory triage | `nurgling.actions.StudyEatOrDrop` | See "Drop mechanics" below. |
| Discovery-confirmation task | `nurgling.tasks.WaitLpProductDiscovered` | Polls two independent signals, see "Confirming a harvest actually counted" below. |
| Interrupt-timing task | `nurgling.tasks.WaitFirstProgressCycle` | Polls `haven.GameUI.prog`, see "Stopping a repeating harvest" below. Superseded as the bot's actual interrupt trigger by `WaitLpFirstProduct` (Round 5) - kept for reference/rollback, not deleted. |
| Shared inventory/cursor topology model (Round 6) | `nurgling.tools.InventorySnapshot` | Baseline capture, ownership delta, cursor classification - see `docs/inventory-grid-system.md` §7. |
| Post-interrupt settlement task (Round 6) | `nurgling.tasks.WaitLpSettlement` | State-driven quiet-period wait, replaces a fixed sleep - see Round 6 section below. |

## Storage: NOT SQLite, and NOT server-authoritative for display

Discovery records live in a plain JSON file per character, **not** the project's
SQLite integration (`nurgling.db`) — that's used for scenarios/planning, unrelated.

File: `NCharacterInfo.java:91-118` builds the path as
`<dataDir>/[profiles/<genus>/]<sanitizedUsername>_<sanitizedChrid>.dat` (JSON despite
the `.dat` extension) and reads it synchronously in the constructor. `chrid` comes
directly from the server's `"gameui"` widget-creation message (`GameUI.java:265,281`,
`args[0]`) — nurgling does not compute or normalize it beyond stripping `*`.

**This is purely a client-side mirror of "what has this character ever picked up
before", used only to decide whether to draw a marker/icon.** It is not what
actually grants or withholds the LP bonus server-side — that's authoritative on the
server regardless of what this file says. A stale/wrong local record can cause a
*wrong marker* (showing one that shouldn't be there, or hiding one that should), but
never a duplicate LP grant: if the bot fires a harvest action on something the
server already knows is discovered, the server simply grants nothing, and the exp/
record confirmation below correctly reports "not confirmed".

### Confirmed real bug: an empty-chrid file can silently absorb discoveries

Found 2026-08-02 while investigating a report of a brand-new character missing
markers it should have had. `chrid` is whatever the server sent for that `"gameui"`
widget instantiation; on at least one live session it arrived as an **empty
string**, producing a file like `<username>_.dat` that then accumulated real
discovery records (confirmed by reading it directly: ~40 distinct species) across
what was almost certainly multiple different character sessions bleeding into the
same shared bucket. Any session whose `chrid` resolves empty (most likely during a
reconnect/relogin transition, before the real character name comes back from the
server — not yet pinned to an exact trigger) reads/writes against that same
contaminated file, making it look like dozens of species were already discovered by
a character that never touched them.

**Not root-caused precisely** (never traced the exact code path that produces an
empty `chrid` — candidates are nurgling's own multi-session/reconnect handling, since
the widget factory itself just passes through whatever the server sent). **Workaround
applied, not a fix**: the user located and deleted the stray `<username>_.dat` file(s)
from their profile folder; a subsequent login to the affected character showed
correct markers. If this recurs, the next step is watching (with the character's own
data file open) whether a fresh empty-chrid file reappears on a specific action
(normal login vs. bot-triggered reconnect vs. multi-session executor start) to
isolate the trigger — that reproduction is the missing piece, not further guessing.

## The clickedGob trap (why bot-driven harvests weren't clearing markers)

This was the root cause of "stone gobs never clear" and "duplicate-instance markers
don't all clear together" reports during live testing, and is the most important
thing to understand before writing *any* new bot that wants `LpExplorer` discovery
to track correctly.

`LpExplorer.checkLpExplorer()` only records a discovery if
`recentHarvestClick(MapView map)` (`LpExplorer.java:456-468`) returns true, which
requires `haven.MapView.clickedGob` — a public field — to currently point at a
harvestable gob within the last 10 seconds (matched by resource *type*, not exact
gob identity, so a slightly-stale-but-same-species value is accepted by design).

**`clickedGob` is only ever assigned inside `MapView.Click.hit()`
(`MapView.java:2162-2193`), and that callback only runs off a real local mouse-click
raycast (`Hittest`).** Every bot-synthesized click —
`NUtils.rclickGob()`/`NUtils.lclick()` (`NUtils.java:265-271`), `GoTo`'s movement
click (`GoTo.java:26,30`), any other `widget.wdgmsg("click", ...)` call — sends the
message straight to the server and **never touches `clickedGob` at all**. So for any
bot driving harvests autonomously, `clickedGob` is simply whatever a real mouse click
last set it to (often stale from before the bot even started, or `null` on a fresh
session) — confirmed live: one whole test run logged the *identical* stale gob name
for `clickedGob` across a dozen entirely different bot targets. Discovery only
recorded when that stale value's resource type happened to coincidentally match the
current target — explaining both why some products recorded seemingly at random and
why others (stone, specifically, in the reported case) essentially never did.

**Fix used in `LpAssistantBot`** (`LpAssistantBot.java`, right after
`NUtils.rclickGob(target)`): stamp it explicitly —

```java
gui.map.clickedGob = new MapView.ClickedGob(target, 3);
```

`MapView.ClickedGob`'s constructor and the `clickedGob` field are both public, so
this needed zero `haven` file changes. Re-stamped fresh before every single product
attempt, each giving its own 10s window.

**This is a systemic gap, not something specific to this one bot.** Any other bot or
feature that wants `LpExplorer`'s discovery tracking to work reliably while running
autonomously will need the same stamp (or an equivalent fix upstream in
`LpExplorer`/`MapView` itself, which was not attempted — the point fix on the one
call site was judged lower-risk and sufficient for the immediate need).

## Confirming a harvest actually counted

`WaitLpProductDiscovered` (`nurgling/tasks/WaitLpProductDiscovered.java`) polls two
independent real signals, not a guessed delay:

1. **`haven.CharWnd.exp`** (`CharWnd.java:64`, public `int`, server-pushed via the
   `"exp"` `uimsg`) — total unspent Learning Points. A snapshot taken right before
   the harvest action, checked for any increase afterward. Fast, server-authoritative,
   but can't say *which* product caused the rise (acceptable since only one harvest
   is ever in flight at a time) — and, importantly, **does nothing for marker
   display**, which depends solely on signal 2.
2. The `NCharacterInfo` discovery record itself (`IsLpExplorerContains`/
   `IsLpExplorerContainsAnywhere`) — the same thing the marker reads.

Whichever fires first wins; `confirmedVia()` reports which one did, for debugging.

## Stopping a repeating harvest without over-harvesting

Board/Block/Leaf/Stone actions repeat server-side once started (unlike a one-shot
"Take bough"/"Pick berries"), and Board/Block additionally share one depleting log
"HP" pool between two separately-discoverable products — so these four categories
are interrupted after one unit instead of left to run to a natural stop.

The interrupt waits on **`haven.GameUI.prog`** (`GameUI.java:85,992-1028`, a
`GameUI.Progress` widget with public `double prog` field, server-pushed via the
`"prog"` `uimsg`) via `WaitFirstProgressCycle` — the same signal
`Forging`/`LightFire`/`Craft`/`LightObject`/`TunnelingBot` already poll for a timed
action's completion — for the first cycle to finish, then steps the player away
(`GoTo`) to cancel the repeat. A real event, not a fixed delay; degrades safely to a
bounded tick timeout if a given action never shows progress.

Every other category (seed/leaf-fruit/bough/bark) gives a small fixed quantity
(observed 1-6) before its flower option disappears on its own, and is left to run to
that natural stop via `WaitCollectState` — same signal `CollectFromGob` already uses.

## Petal matching (`LpActionMatcher`)

Board/Block/Bark/Bough/Stone have fixed, confirmed literal petal text (`"Make
boards"`, `"Chip into blocks"`, `"Take bark"`, `"Take bough"` — **not** `"Take
branch"`, a confirmed decoy present on many species that never produces a discovery
— `"Chip stone"`). Seed/fruit petals are per-species and effectively unbounded
("Pick berries"/"pomes"/"cone"/"catkin"/"crabapple"/"seeds"/"fruits"/"mulberry"/
"apple"/"nuts"/"samara", all observed live) — matched by shape instead of vocabulary
(`findSeedPetal()`: starts with `"Pick "`, isn't the leaf petal, resolves only if
it's the single such match — never guesses).

## Drop mechanics: server flood protection, not a stale-reference bug

`StudyEatOrDrop` (post-harvest inventory triage) went through several wrong
diagnoses before landing on the real cause, worth recording so it isn't
re-investigated from scratch:

- `NUtils.drop(WItem item)` (`NUtils.java:492-494`) sends one `"drop"` wdgmsg. On a
  **stack container it drops the whole stack in that one message** (confirmed via
  this codebase's own pre-existing `NConfig.Key.autoDropper` feature,
  `NWItem.java:155-175`, which relies on exactly this behavior) — so clearing N
  distinct stacks/loose-items of a name needs exactly N drop calls, never more.
- Firing several `NUtils.drop()` calls back to back **trips the server's own flood/
  spam protection**, which silently drops part of the burst instead of erroring —
  `NWItem.java:132-138`'s own comment already documented this, and its
  `autoDrop()` already self-throttles to one drop per 150ms (`AUTODROP_INTERVAL_MS`)
  for exactly this reason. `StudyEatOrDrop`'s drop loop had no such throttle and was
  the actual cause of "drop-all" reliably leaving 1-2 units behind — not a stale
  `WItem` reference from a stack collapsing to its last unit, as an earlier fix
  attempt theorized (that "fix" made the burst worse by adding more drop calls, not
  fewer).
- Current implementation: query matching items once, drop each exactly once,
  throttled to the same 150ms interval via a class-static timestamp, then **waits (bounded, see
  Round 3 below) for the server to actually remove them** before reporting success - added after
  further live testing showed the throttled-but-unconfirmed version still let a caller (the
  inventory-space check) run before the drop's delta had arrived.
- **Not fixed, flagged only**: `nurgling.actions.bots.Dropper` (`Dropper.java:34`)
  and `DropTargets` (`DropTargets.java:42`) have the identical untimed-burst pattern
  in their own drop loops. Out of scope for this feature; worth the same fix if
  either is ever reported to lose items.

See `docs/inventory-grid-system.md` for the general footprint/stacking background
(item footprint = sprite size swapped to (height, width), a stack is one top-level
`WItem`, Eat/Study consume one unit per click with no whole-stack equivalent) this
was built on.

## Round 2 fixes (2026-08-07, live-testing feedback)

- **VSpec Yesteryear season-pair mismatch (Crabapple).** `LpExplorer.isCurrentSeasonProduct()`
  derives a product's normal/Yesteryear sibling name by prefix-stripping and assumes an exact
  match in `VSpec.object`. Crabapple's in-season entry was plural (`"Crabapples"`) while its
  Yesteryear entry was singular (`"Yesteryear's Crabapple"`), so the stripped base (`"Crabapple"`)
  never matched, and the method's "no sibling found" fallback returned `true` unconditionally -
  showing both as undiscovered simultaneously, year-round, on every crabapple tree. This was the
  root cause of the "duplicate icon that never fully clears" report. Originally patched with an
  explicit `YESTERYEAR_BASE_OVERRIDE` map in `LpExplorer.java`; superseded 2026-08-13 by upstream's
  root fix (`3f85d829f`, merged via the 2026-08-13 upstream sync) renaming `VSpec.object`'s
  crabapple normal entry itself to singular `"Crabapple"` - matching what `VSpec`'s own
  `seedsAndBerries` category already used for this item - so the generic exact prefix-strip
  pairing works unmodified and the override was removed as obsolete.
- **Olive Branch misclassified as SEED.** Every tree's bough-equivalent product is named
  `"<Species> Bough"` except olive, whose product is `"Olive Branch"`. Both `LpExplorer.
  isBoughProduct()` and `LpActionMatcher.classify()` matched only on `"Bough"`, so Olive Branch
  fell into the seed bucket - wrongly gated behind the seed-presence bit for display, and
  unreachable for the bot (`findSeedPetal()` only matches `"Pick "`-prefixed petals, but olive's
  real petal is `"Take branch"`). Fixed both classifiers to special-case `"Olive Branch"`, and
  added `"Take branch"` to `LpActionMatcher.ACTIONS_BOUGH` as a second candidate (only ever
  reached when `"Take bough"` isn't present at all, which in practice is olive-only).
- **Bot thread could die silently on any uncaught exception.** `BotExecutor.runWithSupports()`
  (the thing that actually runs `Action.run()` on a background thread) only catches
  `InterruptedException` around the call. Any other exception - confirmed reachable via a gob
  whose live attrs disagree with themselves, e.g. the Crabapple bug above manifesting as a
  `NullPointerException`-shaped edge case in some sessions - killed the thread outright with zero
  message shown to the player, which read exactly like "the bot silently finished" rather than
  "the bot crashed". `LpAssistantBot` now isolates each gob's processing (`processGob()`) and each
  scan-time candidate scored (`pickNearestCandidate()`'s inner loop) behind its own
  `catch (RuntimeException e)` that logs and skips just that one gob, instead of letting one bad
  gob take the whole run down. This is a general robustness fix, not tied to one specific
  exception cause - any future bug in this area now degrades to "one gob skipped, run continues"
  instead of "run silently dies".
- **`hasWorkingSpace()` false-positived "inventory full".** The original check required
  `getNumberFreeCoord(WORKING_SPACE) > 0` - a literal contiguous 4x2 rectangle free somewhere in
  the grid. Once the bot's own gathered items (or anything else) fragment the free area into a
  non-rectangular shape, no single 4x2 block exists even with plenty of total free squares -
  confirmed as the cause of the bot aborting mid-run on a player-reported empty 2x4/5x4 block.
  Switched to a total-free-square-count threshold (`getFreeSpace() >= WORKING_SQUARES`, i.e. 8).
  **Superseded in Round 3 below**: a total-square count doesn't prove any *specific* multi-cell
  item (a 1x4 board, a 2x1 block) actually fits - it was replaced with a per-product, shape-aware
  check once that gap surfaced in further live testing.
- **`Equip` only ever searched the belt.** `Equip.run()` looked up the target tool exclusively in
  `wbelt.item.contents` and returned `Results.ERROR("No target item")` (or, if no belt was even
  equipped, a silent `Results.SUCCESS()` that equipped nothing) if it wasn't there - never checking
  the main inventory at all. Root cause of LP Assistant never swapping to the axe/saw for
  board/block/stone actions when the tool happened to be in plain inventory rather than belted.
  Fixed to fall back to `gui.getInventory()` when the belt search comes up empty, generalizing the
  belt-vs-inventory container reference through the rest of the swap logic.
- **Log/old-trunk hitbox: single failed attempt treated as "nothing left".** Logs and old trunks
  have a wide, non-circular hitbox; approaching from certain sides leaves the player somewhere
  `PathFinder` considers "reached" but too far for the harvest click to actually land, opening no
  flower menu (or one missing the expected petal) even though the resource is genuinely still
  there - previously recorded as a permanent skip on the first miss. `processGob()` now retries
  BOARD/BLOCK/OLDTRUNK products up to 3 times, backing off and re-approaching from a ~70-degree
  rotated angle each retry (`repositionAround()`) before accepting the skip. Trees/bushes/stone use
  round hitboxes and weren't reported to have this failure mode, so they stay single-attempt.
- **Hotbar drag-drop routed to the wrong bot.** `BotDescriptor.iconPath` was being reused as both
  "which icon image to draw" AND "unique identity for hotbar persistence/lookup"
  (`NBotsMenu.NButton.path`, stored via `prop.custom.put(slot, pag.path)` and resolved via
  `NBotsMenu.find(path)`). Before this fix, `LpAssistantBot` and `Forager` both used
  `iconPath = "forager"` in `BotRegistry.java` (LpAssistantBot has since been given its own
  `"lpassistant"` iconPath, see below), so dragging either one to a hotbar slot persisted the same
  string, and
  `find()` always returned whichever bot was registered first under that path (Forager) -
  clicking either hotbar slot ran Forager regardless of which was actually dragged there. Fixed by
  adding a separate `NButton.id` (sourced from the unique `BotDescriptor.id`) used for persistence
  and lookup everywhere identity matters; `path` is now purely which icon image to load. New saves
  (`NGameUI.java`'s `dropthing()`) always persist the id.
  - **Backward compatibility with pre-existing saved hotbar slots**: `NBotsMenu.find()` is two-pass
    - it checks every button's `id` first across the whole collection, and only falls back to a
    legacy `path` match if nothing matched by id anywhere. This matters beyond just this one bot:
    a grep of `BotRegistry.java` turned up 24+ other bots whose `id` differs from their `iconPath`
    (e.g. `blueprint_tree_planter`/`treegardener`, `tunneling`/`tunelling`,
    `table_eat_optimizer`/`eater`) - an id-only lookup would have silently broken every one of
    their pre-existing saved hotbar slots (which were saved under the old path-based scheme), not
    just LpAssistantBot's. Checking id first, across everything, before any path fallback also
    means a value that happens to equal one bot's path can't shadow a different bot's real id -
    e.g. a saved `"forager"` value resolves to Forager via its own id (Forager's `id` and
    `iconPath` are both `"forager"`), not to whichever other bot merely shares that icon.
  - **LpAssistantBot's own icon+tooltip**: it now has its own resource identity,
    `nurgling/bots/icons/lpassistant/` (`BotRegistry.java`'s `iconPath` changed from `"forager"` to
    `"lpassistant"`), with its tooltip layer referencing `@bot.lpassistantbot.title` /
    `@bot.lpassistantbot.desc` (the same `messages.properties` keys `BotDescriptor` already used) -
    so the sidebar now shows the correct name and description on hover, not Forager's. The
    displayed *artwork* still reuses Forager's `image_0.png` verbatim (no art tool was available to
    author something distinct), so the two icons still look alike at a glance despite being
    genuinely separate resources now. Dropping a different `image_0.png` into
    `resources/src/nurgling/bots/icons/lpassistant/{u,d,h}.res/image/` and rebuilding is enough to
    change the artwork - no further identity or tooltip change needed, since those are already
    wired to their own resource and their own keys.

## Round 3 fixes (2026-08-07, continued live-testing feedback)

- **Drop requests weren't confirmed before the next space check ran.** `StudyEatOrDrop.run()`
  fired its (throttled) `"drop"` wdgmsgs and returned immediately - a "drop" wdgmsg is
  fire-and-forget at the protocol level, the server removes the item asynchronously on its own
  schedule. Confirmed live: the next loop iteration's space check could run before that delta
  arrived, and see the just-triaged item still sitting in the grid, which is what the repeated
  `triage: <name>` log lines across otherwise-unrelated products were actually showing (not a
  broken drop, an unconfirmed one). `StudyEatOrDrop.run()` now waits (bounded, 3s -
  `WaitNoItems`'s new timeout constructor) for every dropped name to actually disappear before
  returning, and reports whether it did via its `Results` (`StudyEatOrDrop.dropConfirmed()`) -
  `LpAssistantBot.triageNewItems()` logs when it didn't, instead of silently repeating.
  - **Known, deliberately unfixed limitation**: if a freshly-gathered item merges into a stack
    sharing a name the player already had before the run (`preexistingItemNames`), that whole
    stack - correctly - stays completely untouched, so the newly-gathered units are never
    triaged either. There is no reliable client-visible signal (stack-child identity, widget
    identity, or a pre-run quantity baseline) that safely tells which units in the merged stack
    are the new ones - Haven doesn't expose per-unit origin, and a partial drop would need to
    split the stack first with nothing marking which split portion is "new". Investigated and
    deliberately left as name-only-protected rather than guessed, per the explicit standing
    requirement to never risk the player's own pre-existing items - see
    `LpAssistantBot.triageNewItems()`'s own doc comment for the full reasoning.
- **Space check used a total-free-square count, not the actual footprint about to be placed.**
  `hasWorkingSpace()` (`getFreeSpace() >= WORKING_SQUARES`, from Round 2) proves there are enough
  free squares in total, never that any single spot is wide/tall enough for the specific shape
  about to land - a 1x4 board or 2x1 block can fail to fit in a inventory that's fragmented into
  8+ free squares none of which are adjacent the right way. Replaced with
  `LpAssistantBot.footprintFor(Category)` (Board 1x4, Block/Old Trunk's block 2x1, Bough 1x2,
  everything else 1x1 - all confirmed live) checked via `NInventory.findFreeCoord()` - the same
  live, shape-aware placement scan the game's own item-drop code uses - immediately before each
  product's harvest, inside `processGob()`. If it doesn't fit right away, `ensureSpaceFor()` gives
  the bot's own cleanup one settle-and-recheck pass (now meaningful, since drops are confirmed -
  see above) before giving up. The old total-square check remains, unchanged in spirit, only as
  the cheap up-front "is there basically any room at all" sanity gate before the run starts and at
  the top of each loop iteration - it now checks the Board footprint specifically rather than an
  arbitrary square count, so its own messaging is accurate again.
- **`clearHand()` could wait forever.** A harvested item that couldn't auto-stack sat in
  `gui.vhand`; the old recovery path used `NInventory.getFreeCoord()` (`GetFreePlace`, `infinite =
  true`, no timeout) once the item's sprite had loaded. Confirmed live: with a board in hand and
  genuinely no fitting spot, this waits forever, since nothing frees space on its own once the bot
  is itself stuck waiting on this same call. Replaced with `LpAssistantBot.nonBlockingFreeCoord()`
  (a direct, immediate `findFreeCoord()` call, not wrapped in a blocking task) plus a bounded
  (5s) confirmation wait (`waitHandClear()`) for whichever action - stash or drop - was taken.
  Split into two call sites with different policies, since they answer different questions:
  - `clearHandAtStartup()` - the cursor item here is whatever the *player* was already holding
    when they started the bot. Only ever stashes; if that doesn't fit, refuses to start with an
    explicit message instead of guessing whether it's safe to drop the player's own item.
  - `clearHandAfterHarvest()` - the cursor item here is always this bot's own just-gathered
    pickup, so dropping it to the ground when it doesn't fit is the correct, intended outcome.
    Also calls `interruptActivity()` first (same step already used between products for the
    high-volume categories) so a still-repeating harvest action can't refill the cursor the
    instant it's cleared.
- **A non-empty bucket could get dropped-on-the-spot mid-tool-swap.** `Equip`'s both-hands-occupied
  swap path takes whichever hand it picks into the cursor, then drops that cursor item into the
  belt/inventory slot the target tool occupies. A bucket carrying liquid can't go into a container
  slot at all - the server rejects it ("The bucket must be carried when not empty.") and silently
  ignores the request rather than erroring, so the `DropOn` task backing that drop just sits there
  until it times out. Confirmed live: `Board of Spruce` needing Bonesaw with a full bucket in the
  other hand reproduced exactly this, ending in `Incorrect final of task class
  nurgling.tasks.DropOn` (see below for why that used to kill the whole bot). Fixed in `Equip.run()`
  by detecting a non-empty bucket (`NGItem.content()` non-empty) in either hand and excluding that
  hand from the swap candidates the same way the existing `exception` alias parameter already is -
  a two-handed tool request fails cleanly with a descriptive error if either hand is bucket-
  protected, and a one-handed request swaps the other hand if it's free, or fails cleanly if both
  are protected. Never touches unrelated Equip behavior (other bots' calls with no bucket in
  either hand take the exact same code path as before).
- **`DropOn`'s own timeout fired at roughly half its configured bound.** `NTask.baseCheck()`
  already increments the shared `counter` field and checks it against `maxCounter` once per poll
  for any bounded (`infinite = false`) task, before ever calling that task's `check()` - `DropOn.
  check()` re-implemented that exact same counter/maxCounter comparison a second time inside
  itself, against the same field, so `counter` advanced twice per poll and the 200-poll bound
  actually tripped after ~100. Removed the duplicate logic from `DropOn.check()`; it now only
  contains its actual completion predicate, relying solely on `baseCheck()`'s handling - a real,
  if minor, latent bug independent of the bucket case above, but one that made every `DropOn`
  timeout (bucket-caused or otherwise) fire twice as fast as intended.
- **A `DropOn` (or any other bounded-task) timeout could still kill the whole bot.** `NCore.
  addTask()` turns a timed-out bounded task's `criticalExit` into a thrown `InterruptedException`
  - deliberately, without setting the calling thread's own interrupt flag (a real stop-button
  cancellation does set that flag). `processGob()`'s per-gob containment (Round 2) only caught
  `RuntimeException`, so this specific, checked `InterruptedException` sailed straight past it and
  out of the whole run, which is exactly what "the bot stops after `Incorrect final of task class
  nurgling.tasks.DropOn`" meant live. `run()`'s main loop now also catches `InterruptedException`
  around `processGob()` and checks `Thread.currentThread().isInterrupted()` to tell a genuine
  cancellation (rethrown, honored) from a synthetic bounded-task timeout (logged, gob skipped,
  run continues) - the identical technique `SortContainersInArea.tryTransfer()` already uses
  elsewhere in this codebase for the same distinction.
- **OLDTRUNK never matched a real petal.** `LpActionMatcher.candidateActions()` fell back to
  matching the flower-menu petal against the product's own name (e.g. `"Block of Mirkwood"`) for
  OLDTRUNK, since no reference bot existed to confirm the real literal at the time Round 1 shipped.
  Confirmed live petals for an old trunk are `"Open, Chop into blocks"` - `"Open"` unlocks it as a
  container and is never the harvest action, and the product name is never itself a petal label,
  so this always failed with "no matching petal". Fixed: OLDTRUNK now shares `ACTIONS_BLOCK`
  (`"Chop into blocks"`) with the ordinary log BLOCK category, since it produces the same kind of
  item via the same literal action.
- **Old Trunk's configured tool never matched.** Separately from the petal bug, the player's
  saved Old Trunk tool setting was the literal text `"stoneaxe"` (no space), which `NAlias.
  matches()` - a case-insensitive `String.contains()` check - never matches against the real item
  name `"Stone Axe"` (confirmed: regular Block/Stone harvesting, using a `"Stone Axe"`-valued
  setting, worked correctly the entire time). `LpActionMatcher.requiredTool()` now normalizes a
  small table of known-bad free-text spellings (currently just this one) to the real item name,
  scoped to LP Assistant's own settings rather than changing `NAlias` itself, which is shared by
  every other bot/tool lookup in the codebase.

## Round 4 fixes (2026-08-07, code review findings on Round 3)

A review of the Round 3 patch (before it was committed) found six problems with the fixes
themselves - not new live-testing reports, but real gaps in how Round 3 solved the six original
issues. Fixed together, same branch:

- **`isInterrupted()` cannot distinguish a task timeout from a real cancellation.** Round 3's
  `Thread.currentThread().isInterrupted()` check (in `run()`'s per-gob catch, copying the pattern
  already present in `SortContainersInArea.tryTransfer()`) is unsound: `Object.wait()` - which is
  what `NCore.addTask()` blocks on - clears the calling thread's interrupt flag when it throws
  `InterruptedException` for a *real* interruption, same as it does for the synthetic
  `criticalExit` case. Both paths therefore read as "not interrupted" once caught, meaning a
  genuine stop-button cancellation arriving while a task was in flight could have been silently
  swallowed and treated as a recoverable per-gob timeout instead of propagating. Fixed with a
  dedicated type instead of a flag: `nurgling.tasks.TaskCriticalExitException` (a subtype of
  `InterruptedException`, so every existing `catch (InterruptedException e)` call site elsewhere in
  the codebase - including `SortContainersInArea`, not touched here, still has the identical latent
  bug - keeps working unchanged). `NCore.addTask()` now throws this specific type for a
  `criticalExit` timeout; `LpAssistantBot.run()` catches it first (recoverable, per-gob skip) and
  lets a plain `InterruptedException` propagate unconditionally in a separate, later catch clause.
- **Cursor stash wasn't actually wall-clock bounded.** Round 3's `clearHandAtStartup()`/
  `clearHandAfterHarvest()` called `NInventory.dropOn(pos)` before their own 5s bounded wait -
  but `dropOn()` sends the "drop" wdgmsg and then *itself* blocks inside
  `NCore.addTask(new DropOn(...))`, whose 200-poll bound is checked once per `NCore.tick()` call,
  not once per fixed wall-clock interval (see `docs/inventory-grid-system.md`'s new `dropOn()`
  gotcha entry). Stacking an unknown-duration wait in front of a 5s wall-clock wait meant the real
  worst case was unbounded in wall-clock terms. Fixed: both methods now send `inv.wdgmsg("drop",
  pos)` directly (the same message `dropOn()` sends) and rely solely on a new wall-clock-deadline
  task, `waitCursorSettled()`, which confirms both the cursor clearing *and* (when the item's name
  is known) that the target slot actually now holds it - not just that the cursor emptied, since the
  server can silently reject a placement the same way it silently rejects a non-empty bucket (see
  Round 3's `Equip` fix). `clearHandAfterHarvest()` now returns `ClearHandResult.CLEAR`/`STUCK`
  instead of `void`; on `STUCK`, `processGob()` throws a new `CursorStuckException` instead of
  continuing to triage/equip/right-click with the cursor in an unknown state - caught in `run()`'s
  main loop, which stops the whole run (not just one gob, since cursor state isn't gob-scoped) with
  an explicit message. Startup handling still only ever stashes the player's own pre-held item,
  never drops it, per the original Round 3 rule.
- **A non-high-volume collection's `WaitCollectState` result was discarded.** Round 3 only
  interrupted a still-repeating collection when `gui.vhand` was already observed non-null at the
  point `clearHandAfterHarvest()` ran - but a collection that stopped because
  `WaitCollectState.State.NOFREESPACE` fired can still have the server's own cursor-occupied update
  in flight, reading `vhand == null` for a moment even though an item is about to land there. Fixed:
  `processGob()` now keeps the `WaitCollectState` instance for the `!highVolume` branch and passes
  `collectState.getState() == NOFREESPACE` into `clearHandAfterHarvest()` as a `forceStopExtraction`
  flag, which interrupts the repeating action unconditionally in that case instead of only when
  `vhand` already reads occupied.
- **`findFreeCoord(Coord)`'s outer scan bounds were swapped**, silently under-scanning valid column
  start positions for any non-square footprint (see `docs/inventory-grid-system.md`'s full writeup
  for the exact bug and worked example). This is the shared method every space check in this bot
  (and several other callers - `Equip`, `SortInventory`, `TransferToTrough`/`TransferToBarrel`,
  `FeedClover`, `TaimingAnimal`, `EquipFromInventory`, more) relies on, so Round 3's whole
  shape-aware space-check design was silently unreliable even though it looked correct. Fixed at
  the source (`NInventory.findFreeCoord`) - a strict correctness fix, finds a superset of what it
  found before, so no caller's existing behavior regresses. Also added `NInventory.isGridReady()`
  so callers can tell "grid state not ready yet, some sprite still loading" apart from "no
  placement exists" instead of both reading as a `null`/false "no fit" - `LpAssistantBot`'s
  `ensureSpaceFor()`/`checkSpace()`/`waitForSpaceState()` now retry briefly (bounded, 20 x 50ms) on
  a not-ready read and log it distinctly from a real no-fit before falling back to "no" either way.
- **The startup space gate reused Board's footprint, implying it was "the hardest" shape.** A 1x4
  board fitting says nothing about whether a 2x1 block fits (a spot wide enough for one can be too
  short for the other) - Round 3's startup/loop-top check called `footprintFor(BOARD)` for this,
  which both overclaimed board's status and produced a technically-inaccurate "couldn't fit a 1x4"
  message for what was really meant to be a general "is there room to work with at all" gate.
  Restored a separate, explicit `WORKING_REGION = Coord(4, 2)` constant (the original pre-Round-2
  design's `WORKING_SPACE` value - a 2-wide x 4-tall clear area) for exactly that startup-only role,
  now backed by the corrected `findFreeCoord` instead of Round 2's total-square-count proxy. The
  **loop-top** check (re-run at the top of every `while(true)` iteration, before even knowing the
  next candidate/product) was removed entirely per the same reasoning - `processGob()`'s existing
  per-product `footprintFor(category)`/`ensureSpaceFor()` check, immediately before that specific
  product's harvest, is the only space gate that actually needs to run once the run is under way;
  checking a fixed, unrelated shape speculatively at the top of the loop proved nothing about
  what was about to be harvested.
- **`StudyEatOrDrop` only ever made one drop attempt**, despite `triageNewItems()`'s log line
  claiming a retry ("will retry next pass") - true only in the loose sense that the *caller* might
  call `triageNewItems()` again on a later product, which doesn't happen if the unconfirmed drop
  was on the last product of the last gob in the run. Fixed: `StudyEatOrDrop.run()` now retries the
  drop-and-confirm cycle itself, up to `MAX_DROP_ATTEMPTS = 3` times, bounded (3s each, 9s worst
  case) - each attempt re-queries `getItems(alias)` fresh rather than reusing a `WItem` from an
  earlier attempt, so a retry only ever re-sends drops for items genuinely still present, never a
  stale reference. Separately, `triageNewItems()` now also uses the pre-run `wdgid()` snapshot
  (`preexistingWidgetIds`, new alongside `preexistingItemNames`) to distinguish, for a name that
  existed at startup, "the player's original stack/item (same wdgid, possibly merged-into since -
  still untouched, still no way to separate merged child units)" from "a newly created, genuinely
  separate item/stack that happens to reuse that name (different wdgid - distinguishable, but still
  not auto-triaged here, since `StudyEatOrDrop`'s drop step queries by name across *all* top-level
  matches by design, which would also catch the protected one - safely acting on just the new
  widget would need changing that shared behavior, out of scope and risky for its other callers)".
  The second case is now logged accurately instead of being silently indistinguishable from
  "nothing new" - see `LpAssistantBot.triageNewItems()`'s own doc for the full reasoning. The
  original merged-child limitation itself remains genuinely unfixable client-side (see Round 3 above
  and "Still open" below) and is unchanged.

## Round 5 fixes (2026-08-08, correct-build live-testing feedback)

Round 4 was verified working (confirmed via a process/jar audit that the earlier "no effect"
report was actually testing a stale build from an unrelated worktree/branch, not this feature -
see git history around this date for the audit). Testing the *actual* Round 4 build surfaced three
further problems, all fixed together here:

- **The bot dropped the player's equipped Stone Axe.** Root cause, verified in code before any
  fix: the startup protection snapshot (`preexistingItemNames`/`preexistingWidgetIds`) only ever
  scanned `gui.getInventory().getItems()` - main inventory. A tool equipped in-hand at startup was
  never in that list. Sequence, confirmed against the log: `Equip()` swapped the Stone Axe out of
  hand to free it for the Bonesaw (Board needs a saw), placing the axe into inventory for the first
  time this run - `triageNewItems()`'s whole-inventory "any name not in the startup snapshot is new,
  triage it" scan then saw "Stone Axe" as bot output and dropped it, exactly matching the log
  (`triage: Stone Axe` immediately followed by the next product reporting the tool missing).
  Fixed with two independent layers (see `preexistingItemNames`'s own class doc):
  1. The startup snapshot now also covers both equipped hands, the belt itself, and everything
     inside the belt (`protectExisting()`), not just main inventory.
  2. Far more importantly, **triage is no longer a whole-inventory scan at all.** It's scoped to
     the exact product name just harvested (`triageHarvestOutput()`), so a tool's name (which is
     never a harvest product name) is structurally never even considered, regardless of snapshot
     timing. `protectedToolNames` (every currently-configured Board/Block/Stone/Old Trunk tool,
     normalized the same way `Equip()` resolves them) is checked as a third, explicit, independent
     safety layer on top of that.
  Within a single product name, widget identity (`wdgid()`) still separates a genuinely new,
  distinguishable item from ambiguous growth on a pre-existing same-named stack - see
  `newWidgetIds()`'s doc; the latter is still left untouched and logged, same conservative rule as
  before, just applied per-product instead of per-whole-inventory-name.
- **Board/Block ran for several units (4-5 boards observed) before stopping**, risking depleting a
  log that also had an undiscovered second product (Board and Block share one depleting "log HP"
  pool). The old design waited for either a fixed natural-stop signal (`WaitCollectState`, for
  small-fixed-quantity categories) or one server progress-cycle plus an unconditional interrupt
  (`WaitFirstProgressCycle`, for Board/Block/Leaf/Stone) - neither actually stopped at "the first
  unit produced." Replaced for every category with a new bounded task, `nurgling.tasks.
  WaitLpFirstProduct`: snapshots the target product's current widget identities and stack sizes
  right before the harvest click, then fires on the *earliest* of four signals - a brand-new
  top-level widget, an existing widget's stack growing, the product appearing in the cursor
  (`gui.vhand`), or the discovery/exp confirmation itself (reusing `WaitLpProductDiscovered`'s exact
  logic via composition, not duplicated). `processGob()` then unconditionally interrupts extraction
  the instant this fires, before ever touching the cursor (Round 5's ordering requirement - see
  below), with a short (400ms) bounded settle for one already-in-flight unit rather than depending
  on the interrupt racing a trailing server delta. A log with both an undiscovered board and block
  now yields one board, leaves the log otherwise intact, then switches tools and takes one block -
  matches the feature's actual purpose ("confirm this resource CAN produce X"), not exhaustive
  harvesting.
- **Stack-collapse cleanup was correct but slow** - Round 4's `StudyEatOrDrop`-based retry waited up
  to `DROP_CONFIRM_TIMEOUT_MS` (3s) per attempt even when the actual remaining work (e.g. a 4-stack
  gone, one leftover loose singleton still to drop once the stack hit `StackSupporter`'s max size -
  see `docs/inventory-grid-system.md` §3) settled in well under a second. LP Assistant's own harvest
  triage no longer goes through `StudyEatOrDrop`'s by-name drop step at all (see the tool-loss fix
  above - a by-name drop is exactly the operation that's unsafe to run unscoped); its replacement,
  `dropHarvestWidgets()`, re-queries live widget presence every poll and sends a drop for whichever
  targeted widget is still present, throttled to no faster than `HARVEST_DROP_INTERVAL_MS` (150ms,
  same flood-safe interval as everywhere else in this codebase - never weakened), bounded by one
  overall `HARVEST_DROP_DEADLINE_MS` (4s) deadline rather than a fresh wait per retry. `StudyEatOrDrop`
  itself is unchanged and still used, but only for its Study/Eat step (`autoDrop=false`) on the
  exact widgets `newWidgetIds()` already proved safe - never its own drop step.
- **Cursor ordering** (Round 5 explicit requirement, already mostly true by construction in Round 4
  but now made unconditional): extraction is always interrupted and that interruption's completion
  (`GoTo`'s own bounded run) is treated as confirmation *before* `clearHandAfterHarvest()` runs, for
  every category - no more per-category branch deciding whether to interrupt first. A cursor-clear
  failure (`ClearHandResult.STUCK`) still throws `CursorStuckException`, still stops the whole run
  before any further equip/triage/right-click/pathing (unchanged from Round 4).
- **`ensureSpaceFor()`'s cleanup fallback** no longer calls a whole-inventory triage sweep (that
  method no longer exists). It retries only the most recent harvest's own already-proven-safe
  pending widget set (`lastTriageProduct`/`lastTriagePendingWidgetIds`) via the same
  `dropHarvestWidgets()` used right after each harvest - the only thing left that's still safe to
  retry under the new scoped model.

New debug lines added this round: which first-product signal fired, extraction-interrupt
confirmation, the exact product name being triaged, why a candidate widget was left untouched
(protected name / ambiguous stack growth), drop-confirmed/not-confirmed per triage pass, and the
cursor-clear result.

Not implemented this round (explicitly deferred): build-identification metadata in the debug log
(see the process/jar audit note above for why that was investigated).

## Round 6 fixes (2026-08-08, read-only audit follow-up)

A read-only audit of the Round 5 build (see git history around this date) confirmed four remaining
problems in the stack-topology/ownership model Round 3-5 had been incrementally patching around
rather than replacing: no single consistent model of "top-level item vs. stack child" was shared
across the pre-harvest baseline, first-product detection, post-harvest settlement, and cleanup
stages, which is what left the merged-stack-child gap (see "Still open" below, pre-Round-6) open
despite several rounds of otherwise-correct triage-scoping work. Fixed together here:

- **One consistent inventory/snapshot model.** New `nurgling.tools.InventorySnapshot` (see
  `docs/inventory-grid-system.md` §7 for the full model) replaces every ad-hoc widget-tree walk
  previously duplicated across `WaitLpFirstProduct`, `LpAssistantBot.snapshotStackSizes()`/
  `newWidgetIds()`, and `dropHarvestWidgets()`. It captures top-level items, stack children, and the
  cursor by `wdgid()` identity (never a long-lived `WItem` reference - a `WItem` can be destroyed/
  recreated by the client for the same server-side item), and provides one shared `diff()` (ownership
  delta against a baseline) and `classifyCursor()` (four-state cursor classification) used by every
  stage. **Evidence: code-confirmed + automated-test-confirmed** (`test/nurgling/tools/
  InventorySnapshotTest.java`, 7 cases covering the delta/cursor/collapse logic below) +
  **build-confirmed** (`ant test`, `ant jar` both pass). **Runtime pending** - not yet exercised
  against a live server.
- **Merged-stack-child ownership is no longer an unfixable limitation.** The Round 3/5 "Still open"
  item claimed no client-visible signal could tell which units in a pre-existing same-name stack were
  newly merged - true for a bare unit count, but `InventorySnapshot.Delta.newChildrenInExistingStacks`
  gets this from the same identity Round 5 already used for top-level widgets: a stack child's own
  `wdgid()` absent from the baseline's child set for that same parent stack is unambiguously new, same
  as a top-level widget absent from the baseline is. This was available all along once the topology
  was captured consistently - the previous rounds' baseline (`snapshotStackSizes`) only tracked a
  *count* per top-level wdgid, which genuinely couldn't distinguish "which" child grew a stack; Round 6's
  baseline tracks the *child wdgid set* itself. A merged child is now dropped via that exact child
  `GItem`'s own `wdgmsg("drop", Coord.z, 1)` (the same per-child protocol `NWItem.autoDrop()`'s
  quality-threshold branch already uses), never by touching the pre-existing container.
- **First-product detection (`WaitLpFirstProduct`) now runs on the shared delta/cursor-classification
  model** instead of its own parallel (though already close to correct) widget walk - `NEW_ITEM`/
  `STACK_GROWTH` are exactly `InventorySnapshot.Delta.newLooseItems`/`newStacks`/
  `newChildrenInExistingStacks` against the pre-harvest baseline, and `CURSOR_ITEM` is exactly
  `classifyCursor() == NEW_EXPECTED`. A pre-existing matching stack (present in the baseline, no new
  children) structurally cannot fire `NEW_ITEM`/`STACK_GROWTH` - it's absent from every "new" bucket
  `diff()` produces by construction, not by a separate case-by-case check that could drift out of
  sync with cleanup's own notion of "new."
- **Fixed 400ms post-interrupt sleep replaced with a bounded, state-driven settlement wait.** New
  `nurgling.tasks.WaitLpSettlement` polls the same `InventorySnapshot` every tick, resets a short
  (300ms) quiet-period deadline whenever the matching product/cursor state actually changes, and
  finishes once state has held steady for a full quiet period or a hard 1500ms wall-clock deadline is
  hit, whichever comes first - logged either way (`settlement ended (QUIET|DEADLINE)`). Absorbs one
  already-in-flight unit committed server-side just before the interrupt landed (the fixed sleep's
  original purpose) without a fixed guess at how long that takes, and without waiting long enough to
  risk folding in a second production cycle (the quiet period is a fifth of `FIRST_PRODUCT_TIMEOUT_MS`).
  `check()` only ever calls `InventorySnapshot.capture()` (instant, non-blocking) - never a nested
  blocking `NCore.addTask()` from inside its own predicate.
- **Cursor handling no longer assumes any held item is this harvest's own output.** Previously,
  `clearHandAfterHarvest()` treated *any* non-null `gui.vhand` after a harvest as this bot's own
  fresh pickup and unconditionally stashed/dropped it. It now takes the cursor's
  `InventorySnapshot.CursorClassification` (computed from the same settled/baseline snapshot pair
  used for ownership) as an explicit parameter: `PRE_EXISTING` (same wdgid the baseline already had)
  is left completely untouched; `AMBIGUOUS` (present, but neither pre-existing nor a confirmed match
  for this harvest's product) throws `CursorStuckException` and stops the whole run rather than
  guessing; only `NEW_EXPECTED` goes through the existing bounded stash/drop path.
- **`dropHarvestWidgets()` and its pending-retry state now resolve both top-level and nested child
  wdgids.** The Round 5 version walked only `inv.child` (top-level widgets), so a pending target that
  was actually a merged stack child could never be found there, meaning its cleanup-confirmation poll
  would have looped until its deadline even after the child was already genuinely gone (misreporting
  "not confirmed"), or worse, never located it to drop in the first place. Now resolves via
  `InventorySnapshot.findTopLevel()`/`findChild()`, covering both levels, and `lastTriagePendingWidgetIds`
  tracks each target's parent wdgid (or "not a child") so a retry from `ensureSpaceFor()`'s cleanup
  fallback resolves the same way.
- **`StudyEatOrDrop`'s class doc corrected.** It previously claimed `NInventory.getItems(alias)`
  returns "each already either a loose item or a whole stack container." It does not: `GetItems.
  checkContainer()` recurses into a stack's contents and returns the matching *leaf children* inside
  it, never the container's own top-level `WItem` (see `docs/inventory-grid-system.md` §2's new
  correction). `NUtils.drop()` on one of those child `WItem`s therefore already drops only that one
  unit, not the whole stack - the doc now states this precisely instead of the whole-container claim,
  which was wrong but happened to not cause an active bug in that file's own logic (drop-by-name still
  works correctly per-match either way, since the same message goes to whatever `WItem` is passed).
- **Drop throttling: no cross-bot shared limiter added this round.** LP Assistant's own harvest drops
  (`dropHarvestWidgets()`) and `StudyEatOrDrop` (invoked from LP with `autoDrop=false`, so it never
  itself sends a drop when called from LP) do not run concurrently within a single bot's execution -
  `StudyEatOrDrop` runs synchronously to completion before `dropHarvestWidgets()` is ever invoked in
  `triageHarvestOutput()`, both on the same bot thread. A genuinely concurrent sender (a *second* bot
  or drop path running in parallel with LP Assistant, each with its own 150ms throttle, could in
  principle exceed the server's combined flood threshold together) was not found in LP Assistant's own
  execution path, so a broader shared limiter was not built speculatively. **Backlog item** (not
  implemented): a small non-blocking `nurgling.tools` cross-bot drop-rate limiter, if a future report
  shows two bots' drops actually interleaving in wall-clock time and tripping flood protection
  together - `NWItem.AUTODROP_INTERVAL_MS`'s own static-field throttle is the closest existing
  precedent for the shape such a limiter would take.

## Round 7 fixes (2026-08-08, patch review of Round 6)

A patch review of the (still uncommitted) Round 6 work found six concrete ownership/integration
gaps in the InventorySnapshot design itself and its LpAssistantBot integration - not a new audit,
a focused correction pass on the approved Round 6 direction. Fixed together here:

- **Identity-universe re-parenting safety.** Round 6's `diff()` compared primarily by top-level
  container wdgid, so a pre-existing unit that changed container shape (a stack child becoming
  loose, a loose item merged into a brand-new stack, or a recreated container holding only old
  children) could be misclassified as new. `InventorySnapshot` now captures an alias-independent
  `allUnitIds` universe (every loose item's own wdgid, every stack child's wdgid, the cursor's wdgid
  - never a container's own wdgid) at baseline time, and `diff()`/`classifyCursor()` check candidate
  units against that universe instead of against container presence. Container novelty alone no
  longer proves every contained unit is new - see `docs/inventory-grid-system.md` §7's rewritten
  Round 7 section for the full model and `test/nurgling/tools/InventorySnapshotTest.java`'s new
  re-parenting tests (below) for the exact scenarios this fixes.
- **Cursor integration: triage now uses a POST-cursor-handling snapshot.** Round 6's
  `triageHarvestOutput()` was called with `settled` (captured before `clearHandAfterHarvest()` ran),
  so a newly-harvested cursor item that `clearHandAfterHarvest()` just stashed into a free inventory
  slot was invisible to triage - it would never be studied/eaten/dropped, silently. `processGob()`
  now captures a fresh `finalState` snapshot immediately after cursor handling succeeds and passes
  *that* to `triageHarvestOutput()` - a stashed item is now visible in its new inventory slot; a
  dropped-to-ground item is correctly still absent.
- **Cursor safety tightened.** `clearHandAfterHarvest()` now: (1) revalidates the LIVE cursor's own
  wdgid against the classified `cursorWdgid` immediately before stashing/dropping - refuses to act
  (throws `CursorStuckException`) if a different, unclassified item arrived in the gap between the
  settlement snapshot and this call; (2) throws the same exception if the settlement-time
  classification was `EMPTY` but the live cursor now reads occupied (something arrived that was
  never classified at all); (3) `PRE_EXISTING` no longer quietly returns `CLEAR` - it now also stops
  the whole run, since a non-empty cursor hijacks the next right-click regardless of whose item it
  is, so "leave it untouched" and "safe to continue" are different questions; (4) `AMBIGUOUS`
  continues to stop the run, unchanged from Round 6.
- **Cleanup is now identity-based, not parent-scoped.** Round 6's `dropHarvestWidgets()` looked each
  target up via `findTopLevel()`/`findChild(originalParent, id)` and treated a lookup miss as
  confirmation the target was gone - wrong when a unit simply moved to a different parent (or became
  loose) since ownership was computed. It now resolves every target via `InventorySnapshot.findAny()`
  (searches the whole inventory, any parent) and mutates the caller's own `OwnedTargets` structure in
  place, so whatever remains un-confirmed when it returns is exactly what's genuinely still present -
  Round 6 silently discarded that distinction by operating on an internal copy and never writing
  back to the retained pending state. A wholly-new stack's child ids are preserved specifically so
  cleanup can keep confirming/recovering them individually if the container's own identity stops
  resolving before its one drop message is confirmed (e.g. it collapses after a partial pickup).
- **Official drop protocol for a whole owned stack.** `haven.WItem.mousedown()`'s real CTRL-click
  handler sends `item.wdgmsg("drop", ev.c, n)` (two arguments plus count) for ANY top-level item,
  including a stack container, dropping the whole stack in that one message. `NUtils.drop()` sends
  a different, four-argument ground-drop message. A `newWholeStacks` target is now dropped via the
  container's own `wdgmsg("drop", Coord.z, 1)` - matching the real protocol exactly, one message for
  the whole stack, never iterated per contained item (the rate limit still counts it as exactly one
  message). A `newChildrenInStacks` target (a specific child in a mixed/pre-existing stack) uses the
  same two-argument-plus-count message sent to that child's own GItem, unchanged from Round 6's
  per-child approach - not touching the container. See `docs/inventory-grid-system.md` §7 for the
  side-by-side comparison. **Not modified**: `haven/WItem.java` itself, no synthesized mouse input,
  no Hafen override - only comparing and matching the existing, already-public protocol from
  `nurgling`-side code.
- **Auto Study/Eat fixed for a wholly new stack, and now runs once per product.** Round 6 iterated
  every owned target and called `StudyEatOrDrop` on each - for a `newWholeStacks` target that meant
  passing the stack's top-level container `WItem`, but `StudyEatOrDrop.run()` checks
  `getItems(alias).contains(item)`, and `getItems()` returns a stack's LEAF CHILDREN, never the
  container itself (see `docs/inventory-grid-system.md` §2) - so that check always failed and
  Study/Eat silently did nothing for a wholly-new-stack harvest. `triageHarvestOutput()` now selects
  ONE live, owned LEAF unit (never a container) as the Study/Eat candidate and runs it exactly once
  per product, matching the feature's intent ("confirm this resource once"), not once per contained
  unit.
- **Session-correct, atomic capture.** `InventorySnapshot.capture()` previously read the cursor via
  the globally-selected `NUtils.getGameUI()`, which can pair one session's inventory with a
  different session's cursor in a multi-session process. It now reads `inv.ui.gui` - the inventory
  widget's own owning session - and the whole widget-tree-plus-cursor read runs inside
  `synchronized (inv.ui)`, the same critical section `NInventory`'s own `GetItems` task uses around
  its traversal, so a poll can't observe a partially-mutating widget tree. Still instant and
  non-blocking - no nested tasks, no polling loop, no background thread added.

**Evidence labels**: all of the above are **code-confirmed** + **automated-test-confirmed**
(`InventorySnapshotTest`, 14 cases, 5 of them new this round - see below) + **build-confirmed**
(`ant test`, `ant jar` both pass). **Runtime pending** for all of it - not yet exercised against a
live server, same as Round 6.

**Tests added this round** (in `test/nurgling/tools/InventorySnapshotTest.java`, alongside the 8
carried from Round 6):
- `pre_existing_stack_child_becoming_loose_top_level_remains_pre_existing`
- `pre_existing_loose_becoming_child_of_new_stack_container_remains_pre_existing`
- `new_stack_with_old_child_plus_new_child_owns_only_the_new_child`
- `recreated_stack_container_with_only_pre_existing_children_owns_nothing`
- `wholly_new_stack_with_only_new_children_is_still_safely_recognized`
- `cursor_item_already_present_in_baseline_inventory_is_pre_existing_even_with_new_wdgid_slot`
  (bonus coverage for the same identity-universe rule applied to the cursor)

Total: 14 `InventorySnapshotTest` cases + 4 pre-existing `PlanningMergerTest` cases = **18 tests,
18 passing**.

## Round 7b fixes (2026-08-08, patch review of Round 7)

A focused correction pass on four concrete gaps found in patch review of Round 7 - not a new audit:

- **Whole-stack ownership now requires every PHYSICAL child, not just matching ones, to be proven
  new.** `InventorySnapshot.TopLevelEntry` splits `physicalChildWdgids` (every child actually in the
  container, including one whose name is still null/unresolved or genuinely a different product)
  from `matchingChildWdgids` (the confirmed-matching subset). `diff()` only reports a `newWholeStacks`
  entry when the physical set exactly equals the new-matching set - any unresolved/unmatched/
  pre-existing physical child now correctly blocks the whole-stack drop optimization and downgrades
  the container to individual (`newChildrenInStacks`) ownership of just its proven-new matching
  children. See `docs/inventory-grid-system.md` §7's updated Round 7b note.
- **Whole-stack membership is revalidated live, in the same cleanup poll, immediately before the
  drop is sent.** `dropHarvestWidgets()` now re-reads the container's live physical children
  (`InventorySnapshot.physicalChildrenOf()`) right before sending `wdgmsg("drop", Coord.z, 1)` on it,
  and only sends that message when the live set is non-empty and fully contained in the recorded
  owned set. If an extra/unowned child has merged in since ownership was computed, the container is
  never dropped - the still-resolvable recorded children are downgraded to individual per-child
  cleanup instead, leaving the unowned child untouched.
- **Individual drop protocol is chosen from CURRENT live topology, not from origin bucket.** Whether
  a unit currently sits top-level (loose ground-drop via `NUtils.drop()`) or nested in a stack
  (per-child `wdgmsg("drop", Coord.z, 1)`) is now checked fresh (`InventorySnapshot.findTopLevel()`)
  at send time, not inferred from whether the id was originally recorded in `looseIds`,
  `wholeStacks`, or `partialChildParents` - covers a unit re-parenting between ownership calculation
  and the actual drop.
- **Cursor stash confirmation is identity-based**, fixing a false ~5s timeout: `waitCursorSettled()`
  previously required the exact requested slot to become occupied, but a stash that MERGES into a
  pre-existing stack elsewhere leaves that slot empty even though the stash fully succeeded. New
  `waitCursorStashSettledByIdentity()` confirms via the stashed item's own wdgid resolving anywhere
  in the inventory (`InventorySnapshot.findAny()`) plus the cursor clearing - used by both
  `clearHandAfterHarvest()` and startup's `clearHandAtStartup()`. `waitCursorSettled()` itself was
  trimmed to its remaining real use (ground-drop cursor-empty confirmation only), since its
  slot/name-based branch had no callers left.

**Tests added this round**: `stack_with_one_old_physical_child_and_one_new_matching_child_owns_only_the_new_one`,
`wholly_new_container_with_one_unresolved_physical_child_is_never_whole_owned`,
`container_with_fully_matching_and_new_physical_children_remains_whole_stack_eligible`.

**Total: 17 `InventorySnapshotTest` cases + 4 pre-existing `PlanningMergerTest` cases = 21 tests,
21 passing.** Evidence: code-confirmed + automated-test-confirmed + build-confirmed (`ant test`,
`ant jar`). Runtime pending, same as Rounds 6-7. No `src/haven/**` or other shared/core file touched.

## Round 7b runtime test results (2026-08, live testing)

Live-tested against a Sorbtreewood log (Board + Block, both loose-item outputs, no stacking
involved). **Confirmed working**: LP-first-product detection, state-driven settlement, ownership
delta for new loose items, drop-throttled cleanup, and discovery confirmation all completed
correctly and produced exactly one board and one block as expected. This exercises the Round 6/7/7b
core machinery's common path (`InventorySnapshot` baseline/settlement/diff for loose items,
`WaitLpSettlement`, `dropHarvestWidgets()`'s loose-item branch).

**One failure, unrelated to the Round 6/7/7b ownership/snapshot work itself**: `Equip()` timed out
(`TaskCriticalExitException` from `nurgling.tasks.DropOn`) while trying to free a hand for the
Bonesaw needed to cut the board, because that hand held an equipped EMPTY bucket. See "Round 7c
fixes" below for the root cause and the fix.

**Still runtime-unexercised** (this test run didn't touch these paths - they remain code+test-
confirmed only, same as before):
- A whole-new-stack drop (`InventorySnapshot.Delta.newWholeStacks` / the container's own
  `wdgmsg("drop", Coord.z, 1)` CTRL-click-protocol message) - Board/Block are never stackable, so no
  harvest in this test ever produced a stack at all.
- Merged-child cleanup (`newChildrenInStacks` / per-child drop into a pre-existing stack) - same
  reason; would need a stackable product (e.g. a seed/fruit category) with a pre-existing stack of
  that same name already in inventory.
- Harvested-item cursor handling (`NEW_EXPECTED` cursor classification, `waitCursorStashSettledByIdentity()`'s
  stash-merge-into-existing-stack path) - the test inventory had free space, so no harvested unit
  ever needed the cursor at all.

## Round 7c fixes (2026-08, runtime finding: equipped empty bucket)

**Root cause, confirmed against current source** (`src/nurgling/actions/Equip.java`,
`src/nurgling/actions/bots/LpAssistantBot.java`): when `Equip(Bonesaw)` needs a free hand and the
occupying hand isn't excluded (`isNonEmptyBucket()` only excludes a bucket with *non-empty* content
- `Equip.java:138-145`), it takes that hand's item to the cursor and swap-drops it into the belt/
inventory slot the target tool occupies (`Equip.java:94-96, 106-112`, `NInventory.dropOn()` ->
blocking `NCore.addTask(new DropOn(...))`). Runtime evidence (04:31:49 and 04:32:10, Sorbtreewood
log) showed this swap-drop hang and time out (`TaskCriticalExitException`) specifically for an
equipped bucket the player later confirmed was empty, leaving it stuck in the cursor. Two compounding
factors made this worse than a normal per-gob failure:
1. `TaskCriticalExitException` from inside `Equip()` is caught by `processGob()`'s own generic
   `TaskCriticalExitException` handler (`LpAssistantBot.java`, `run()`'s per-gob catch) as a
   recoverable "skip this gob" case - but that handler has no cursor-recovery step, because a stuck
   cursor from *inside* `Equip()` is a completely different code path from the harvest-triage cursor
   handling (`clearHandAfterHarvest()`/`CursorStuckException`) the rest of this bot already guards.
   The cursor was left occupied with no automatic fix, requiring the player to manually correct it.
2. A NON-empty bucket is already safe - `isNonEmptyBucket()` correctly excludes it from ever being
   moved, and this is separately runtime-confirmed (04:32-ish, same session): a water-filled bucket
   stayed equipped correctly while the *other* hand swapped between Bonesaw and Stone Axe. Only the
   EMPTY-bucket case was unprotected, since an empty bucket is normally a perfectly ordinary,
   movable inventory item (§ - see docs/inventory-grid-system.md; nothing about *emptiness* itself is
   unsafe in general) and excluding it from `Equip()`'s swap logic globally would be wrong.

**Fix**: a new LP-Assistant-only startup gate, `LpAssistantBot.checkEquippedBucketsAtStartup()`,
runs once per bot run - after config/debug-log setup, before `clearHandAtStartup()` (cursor
handling), before any `Equip()` call, before walking, before any gob is processed. If either
equipped hand definitely holds an empty bucket, the bot never starts: it shows and debug-logs
*"LP Assistant bot: an equipped water bucket is empty. Fill it with water or unequip it, keep a
clear 2x4 inventory area, and restart."* and returns `Results.ERROR` immediately - never moving the
bucket, never calling `Equip()`, never touching a gob. This sidesteps the `Equip()`/`DropOn` hang
entirely for the one case that was unsafe, without changing `Equip()` itself (so every other caller
of `Equip()`, and every other bot, is completely unaffected) and without a global "never move an
empty bucket" rule (which would be wrong - an empty bucket is normally fine to move; see scope note
in the class doc).

**Detection method - why it can't misclassify a still-loading filled bucket**:
`LpAssistantBot.classifyBucketFill(name, hasContent, qualityResolved)` (pure, unit-testable) and its
live caller `isDefinitelyEmptyBucket()` use two independently-loaded NGItem signals, not
`content().isEmpty()` alone:
- `NGItem.name()` resolves from the item's own RESOURCE-bundled default tooltip text
  (`haven.ItemInfo.Name.Default.get()` reads `Resource.tooltip` directly - confirmed by reading
  `ItemInfo.java:223-246`) - fast, and independent of the server's per-instance tooltip round-trip.
  Confirms "this is a Bucket," nothing about fill state.
- `NGItem.content()` is populated only from the server's own per-item tooltip payload
  (`NGItem.updateraw()`, driven by the `"tt"` wire message - `NGItem.java:280-343`,
  `haven.GItem.java:414-418`) - genuinely asynchronous, and defaults to an empty list until that
  payload has arrived even once. An empty read here is ambiguous by itself: "genuinely empty" and
  "not loaded yet" are indistinguishable from `content()` alone - exactly the risk flagged before
  implementing this.
- `NGItem.quality` is populated by that *same* server tooltip payload/`updateraw()` call as
  `content()` (same `"tt"` message, same switch statement) - `NWItem.autoDrop()`'s quality-threshold
  branch already treats a null `quality` as "tooltip not loaded yet, re-check later" for the
  identical reason (see `docs/inventory-grid-system.md` §4), so reusing it here as the readiness
  gate is an existing, established idiom in this codebase, not a new assumption.

A search across the repo (including bundled resource sources) confirmed **no separate empty-vs-
filled resource identity exists** for buckets (no `"bucket"`/`"bucket-water"` resource pair, or
equivalent) - every existing bucket fill-state check in this codebase (`FillWaterskins.java`,
`FillEmptyContainersAction.java`, `NEquipory.findBucket()`) already uses `content()` the same way,
each paired with its own explicit wait task after an action that could change fill state
(`WaitItemContent`, `WaitBucketInHandContentQuantityChange`). This gate follows the same idiom for a
bucket that's simply being *inspected* (not just-filled) at startup: `isDefinitelyEmptyBucket()`
polls up to 10 times (100ms apart, ~1s worst case) via `classifyBucketFill()`; the moment `content()`
ever reads non-empty, it returns `NOT_EMPTY` immediately (a non-empty read can never be a false
positive, whether or not quality has resolved). Only once `quality` has also resolved while
`content()` is still empty does it return `DEFINITELY_EMPTY`. If the bound is exhausted with quality
never resolving, it returns `INDETERMINATE` - `isDefinitelyEmptyBucket()` treats that as "not proven
empty" and lets the bot proceed, logging the ambiguity, rather than ever concluding "definitely
empty" from unproven state (matching this bot's standing conservative bias elsewhere - see e.g.
`InventorySnapshot`'s `AMBIGUOUS` cursor classification).

**Known limitation, noted rather than guessed around**: this relies on buckets normally carrying a
resolvable quality value in their tooltip (true for ordinary crafted/found buckets). If a specific
bucket instance's tooltip genuinely never includes a quality sub-field, the readiness gate never
fires and the check falls back to `INDETERMINATE` → proceeds without stopping - i.e., the worst case
is "this guard doesn't fire," never "this guard fires incorrectly."

**Deferred enhancement (explicitly not implemented this round)**: automatic bucket refilling. Rather
than stopping the bot, a future round could route an equipped empty bucket to
`nurgling.actions.FillWaterskinsGlobal` - the existing global-zone workflow (a thin
`useGlobalZone=true` subclass of `FillWaterskins`, see `FillWaterskinsGlobal.java`) that already
calls `context.goToArea(Specialisation.SpecName.water)` (`FillWaterskins.java:36-40`) to look up the
`nurgling.areas.NArea` `"water"` specialization (see `NArea.java` `Specialisation` list,
`messages.properties` key `...'water' specialization for water source...`) and find a nearby water
source automatically, no manual zone selection required - unlike plain `FillWaterskins()`'s default
(`useGlobalZone=false`) constructor, which prompts the user to select a water zone interactively and
would not be usable headlessly by a bot. `FillWaterskinsGlobal`/`FillWaterskins` share the same
`content()`-based empty/fill detection this round's bucket check is itself modeled on (see
`FillWaterskins.checkIfNeed()`), and the same barrel/cistern/well refill actions
(`nurgling.contextmenu.FillEmptyContainersAction`, `FillFromWaterTileAction`) underneath. A future
round could call `FillWaterskinsGlobal` when this startup check finds an empty bucket, before
falling back to stopping only if no water source is configured/reachable. Not built this round - out
of scope for a narrow correction pass, and refilling mid-run introduces its own space/pathing/
tool-swap interactions that deserve their own dedicated design pass rather than being bolted on here.

**Tests added**: `test/nurgling/actions/bots/LpAssistantBotBucketCheckTest.java` - 4 pure cases for
`classifyBucketFill()` (non-bucket item, content-present overrides unresolved quality, empty-before-
tooltip-loads is indeterminate not empty, empty-after-tooltip-loads is definitely empty).
`isDefinitelyEmptyBucket()`'s live polling loop itself is not separately tested - it needs a real
`NGItem`/`WItem`, which (per this repo's established testing approach - see Round 6/7's own test
notes) is not fabricated for one guard; the pure decision function it wraps is what's tested.

**Total: 4 new tests. Combined with Round 7b: 21 `InventorySnapshotTest`-family cases +
4 `LpAssistantBotBucketCheckTest` cases + 4 pre-existing `PlanningMergerTest` cases = 25 tests, all
passing.** No `src/haven/**` or other shared/core file touched - `classifyBucketFill()`/
`isDefinitelyEmptyBucket()`/`checkEquippedBucketsAtStartup()` are all new private/package-private
members of `LpAssistantBot` itself; `Equip.java` was read for root-cause tracing but not modified.

### Still open (post-Round-7c)

- The Bonesaw/Sorbtreewood scenario that originally hit this bug has NOT yet been re-run live with
  the fix in place - the fix itself (the startup gate refusing to start with an empty bucket
  equipped) is code+test-confirmed only. The next live run should confirm the message displays
  correctly and the bot genuinely does not start when reproducing the same equipped-empty-bucket
  setup.
- The three still-unexercised live paths listed under "Round 7b runtime test results" above
  (whole-new-stack drop, merged-child cleanup, harvested-cursor handling) remain open.
- Everything under "Still open (post-Round-7)" and "Still open (post-Round-6)" below remains open in
  the same sense - fixed in code and test, runtime-unconfirmed.

### Still open (post-Round-7)

- Everything under "Still open (post-Round-6)" below remains open in the same sense - fixed in code
  and test, runtime-unconfirmed.
- The revalidate-before-acting cursor check and the `PRE_EXISTING`-stops-the-run change are stricter
  than Round 6's behavior; if live testing shows this stops the run in a case that's actually safe
  (e.g. a false-positive `PRE_EXISTING` classification), the classification logic - not the "stop
  when unsafe" policy - would be the thing to revisit first.

### Still open (post-Round-6)

- The merged-stack-child limitation from Round 3/5 (see above) is now fixed in code and covered by
  `InventorySnapshotTest`, but **not yet runtime-confirmed** - the next live LP Assistant run against
  a resource that merges its output into a pre-existing stack (e.g. a seed/fruit category where the
  player already had some of that same seed in inventory) is the first real-world exercise of this
  path.
- Cross-bot drop-flood coordination (see "Drop throttling" above) - identified as a plausible backlog
  item, not confirmed as an actual live problem, not built this round.
- `EquipFromInventory.java`'s bucket-protection gap (see `Equip.java`'s own Round 3 fix) is unsafe by
  the same reasoning that motivated protecting `Equip.java`, but has no current callers and was left
  unmodified this round - out of scope, tracked here as a backlog item rather than fixed speculatively.
- A dedicated `findFreeCoord()` non-square-footprint regression test was not added this round:
  `NInventory` is a live haven `Widget` whose `isz`/`sqmask` fields are only meaningfully populated
  through real widget/UI lifecycle, which this repo's JUnit harness has no scaffolding for building
  in isolation. The Round 4 fix itself remains **code-verified** (see `docs/inventory-grid-system.md`'s
  worked example) rather than test-covered; automating it would need either a lightweight fake
  `NInventory` subclass exposing `isz`/`sqmask` directly, or a live-UI test harness, neither of which
  exists yet - noted here as a backlog item rather than built speculatively for this pass.

### Still open (pre-existing, unrelated to Round 6)

- **VSpec.object completeness.** Confirmed incomplete generally (per live-testing report) beyond
  the two specific bugs above; `VSpec.object` is a hand-maintained static Java table, not
  server/JSON-sourced (see class doc). No mechanism currently exists for adding entries without
  editing `VSpec.java` and rebuilding.
- **Log/stone/old-trunk per-instance depletion.** `ProductListHarvestSpec` (the always-on overlay
  class covering these three) has no live per-instance state at all - upstream's own marker
  feature can't tell a depleted log from a fresh one either, it's not a nurgling-specific gap. The
  bot's actual live signal remains the flower menu at interaction time (now retried per the
  hitbox-retry fix above before being treated as truly empty); there's no cheaper live-availability
  check available upstream to mirror. See `docs/live-harvest-availability.md` for the full
  writeup - written specifically to prevent a future session from assuming the hitbox-retry fix
  added real depletion tracking, which it didn't.
- **Gob scan range.** Candidate gobs are read from `NUtils.getGameUI().ui.sess.glob.oc`, the raw
  client object cache mirroring exactly what the server has sent - i.e. already bounded by the
  server's fixed load radius, not camera zoom/facing. No bug found here.
- The empty-`chrid` trigger (see "Storage" above) — worked around, not root-caused.
- Whether other bots/features reading `LpExplorer` (none currently do besides this
  one and the passive marker/overlay code) would need the same `clickedGob` stamp -
  presumed yes, not verified against a second caller.
- `Dropper`/`DropTargets`'s own flood-protection gap (see "Drop mechanics") -
  identified, not fixed or confirmed as user-visible there.
- ~~**Triage can't safely separate newly-gathered units that merge into an existing same-name
  widget** (Round 3, refined Round 5)~~ - **superseded in Round 6** (see above): child-wdgid-set
  tracking in `InventorySnapshot` now identifies exactly which merged child is new, code- and
  test-confirmed, runtime pending. Left struck through rather than deleted so the history of why this
  was believed unfixable for two rounds isn't lost.
- **`SortContainersInArea.tryTransfer()` has the same `isInterrupted()`-after-catch bug Round 4
  fixed in `LpAssistantBot`** (see Round 4 above) - not fixed there, out of scope for this feature.
  Worth applying `TaskCriticalExitException` there too if a stop-button interruption is ever
  reported to get swallowed mid-transfer.
