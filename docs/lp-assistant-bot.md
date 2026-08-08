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
| Interrupt-timing task | `nurgling.tasks.WaitFirstProgressCycle` | Polls `haven.GameUI.prog`, see "Stopping a repeating harvest" below. |

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
  throttled to the same 150ms interval via a class-static timestamp.
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
  match in `VSpec.object`. Crabapple's in-season entry is plural (`"Crabapples"`) but its
  Yesteryear entry singular (`"Yesteryear's Crabapple"`), so the stripped base (`"Crabapple"`)
  never matched, and the method's "no sibling found" fallback returns `true` unconditionally -
  showing both as undiscovered simultaneously, year-round, on every crabapple tree. This was the
  root cause of the "duplicate icon that never fully clears" report. Fixed with an explicit
  `YESTERYEAR_BASE_OVERRIDE` map in `LpExplorer.java`; confirmed via full grep of `VSpec.object`
  that Crabapple is the only such mismatch.
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
  Switched to a total-free-square-count threshold (`getFreeSpace() >= WORKING_SQUARES`, i.e. 8),
  matching how Haven's own item placement actually works (fills whatever free squares fit, no
  contiguity requirement).
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

### Still open

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
