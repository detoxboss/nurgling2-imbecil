# Fork customization ledger

Canonical record of **intentional** behavioral differences between this fork
(`detoxboss/nurgling2-imbecil`) and the upstream Nurgling2 project it tracks
(`aleksandrsvoboda/nurgling2`). This is the fork-vs-upstream-nurgling2 ledger — for the separate
haven-vs-official-Hafen inventory, see `docs/nurgling-modified-files.md`; the two document different
upstream boundaries and are not interchangeable. Read this before resolving any shared-file conflict
or auto-merge during a sync (`docs/fork-sync-guide.md` Phase 1–2) that touches one of these seams.

**Scope discipline:** an entry here records a *deliberate* fork choice with a *reason* someone would
otherwise have to rediscover from scratch. Ordinary upstream bugs, accidental technical debt, or a
quirk that happens to exist in the tree are not customizations and do not belong here — recording them
as if future merges must "preserve" them would be actively wrong. (Example of what does **not** belong
here: a duplicate-key formatting quirk found in `messages.properties`/`messages_ru.properties` during
the 2026-09-04 sync — it predates the fork's own history on both sides and is not a fork invariant.
Track that kind of thing in `docs/fork-maintenance-backlog.md` instead, if it's worth tracking at all.)

Each entry: what the fork does differently, why, the minimum hook a merge must not silently drop, how
to verify it's still intact, and the condition under which upstream doing the same thing natively would
make the fork override obsolete.

---

## Combat Reactor protocol hooks

**Files:** `src/haven/Fightsess.java`, `src/haven/NFightsess.java`

**Fork behavior:** `Fightsess.requestUse`/`releaseHeld` and `NFightsess.requestAction`/`releaseAction`
— protocol-level dispatch hooks the Combat Reactor uses to request and release control of combat
actions without racing the player's own input.

**Why:** The Combat Reactor (`src/nurgling/combat/**`) needs to issue and withdraw combat commands
through the same protocol path the game client itself uses, without permanently taking over input.
These hooks are the seam that makes that possible.

**Minimum hook that must survive:** the four method signatures above must keep existing and keep being
called at the same point in the combat-message dispatch path. The *surrounding* HUD/rendering
architecture in these files is not fork-owned and may be freely replaced by upstream's own evolution of
it (this already happened once — see `docs/upstream-sync-history/2026-08-31.md`, which adopted
upstream's `NCombatData`-based HUD wholesale while re-grafting these same hooks onto it).

**Verify:** `docs/combat/README.md`'s own verification section; grep both files for
`requestUse|releaseHeld|requestAction|releaseAction` and confirm callers still resolve.

**Superseded when:** upstream ships an equivalent request/release protocol hook of its own with the
same non-exclusive-control semantics — not merely when upstream adds its own combat automation, which
may make different tradeoffs entirely.

## MapFile shared `(ResCache, filename)` locking

**File:** `src/haven/MapFile.java`

**Fork behavior:** Map file locks are keyed by `(ResCache, filename)` and shared across sessions
running in the same process, instead of being exclusive per-session.

**Why:** This fork supports multiple concurrent game sessions in one client process
(`src/nurgling/sessions/**`). Per-session-exclusive locking would make two sessions touching the same
map data fight each other or corrupt state; the shared-lock model lets them cooperate.

**Minimum hook that must survive:** lock acquisition must remain keyed by the `(ResCache, filename)`
pair, not by session identity.

**Verify:** run two sessions against overlapping map data; confirm no lock contention crash and no map
corruption.

**Superseded when:** upstream adopts multi-session support natively with its own cross-session map
coordination — at that point this override should be reassessed against whatever upstream's model is,
not assumed to still be the better choice.

## Explicit-session area/scenario/explored-area persistence

**Files:** `src/nurgling/NConfig.java` (`writeAreas`/`writeScenarios` with an explicit session
argument), `src/nurgling/tools/ExploredArea.java` (`saveIfDue()`/`saveNow()`)

**Fork behavior:** Persistence calls take an explicit owning-session argument rather than resolving
"the current session" ambiently at save time.

**Why:** With multiple sessions live at once, an ambient "current session" lookup can resolve to the
wrong session's data at the moment a background save fires — the explicit argument makes ownership
unambiguous regardless of which session happens to be foregrounded when the save runs.

**Minimum hook that must survive:** the explicit session parameter on these methods; don't let a future
merge quietly collapse it back to an ambient lookup for convenience.

**Verify:** two sessions with different areas/scenarios open; confirm each session's save writes its
own data, not the other's.

**Superseded when:** upstream's own persistence layer becomes session-scoped natively.

## Owning-session `NGameUI` teardown

**Files:** `src/nurgling/NGameUI.java`, `src/nurgling/NCore.java`

**Fork behavior:** `dispose()` tears down `ui.core` for the session that owns the widget being
disposed, not whichever session happens to be ambiently "active" at teardown time.

**Why:** Same multi-session root cause as above — disposing the wrong session's core on logout/window
close silently orphans state for a still-live session instead of the one actually closing.

**Minimum hook that must survive:** teardown must resolve its target from the disposing widget's own
owning session, not `NUtils.getGameUI()` or an equivalent ambient accessor.

**Verify:** open two sessions, close one; confirm the other keeps running normally and the closed one's
resources are actually released.

**Superseded when:** upstream ships native multi-session support with its own correctly-scoped
teardown.

## Owning-session `NMiniMap`/`GameUI` resolution

**File:** `src/nurgling/widgets/NMiniMap.java`

**Fork behavior:** Minimap tick/update logic resolves its owning `GameUI`/session explicitly rather
than through an ambient "active session" accessor.

**Why:** Same root cause as the two entries above, applied to the minimap widget specifically — this
was re-verified and re-applied during the 2026-08-31 sync alongside upstream's own new hold-to-move
steering logic in the same file (see that cycle's history entry), confirming the two changes are
independent and both needed.

**Minimum hook that must survive:** explicit session resolution in `NMiniMap`'s per-tick logic must
survive any upstream rewrite of the surrounding rendering/steering code in this file.

**Verify:** two sessions, each viewing a different part of the map; confirm each minimap tracks its own
session's player position, not the other's.

**Superseded when:** same condition as `NGameUI` teardown, above.

## Passive tableware-breakage food-take guard

**File:** `src/nurgling/NGItem.java` (`wdgmsg`, `isFoodTakeBlockedByWornTableware()`)

**Fork behavior:** `NGItem.wdgmsg("take")` checks `NConfig.Key.autoSaveTableware`. When the item being
taken is a **food item** sitting in the food grid of an **open Symbel/feast table**, and some piece of
tableware in that table's 3x3 or 1x2 tableware grid is one hit from breaking, the food `take` is
blocked and the client shows "Tableware is almost broken. Replace it before continuing to eat."
**The tableware item itself is deliberately exempt from this guard** — `isFoodTakeBlockedByWornTableware()`
explicitly returns `false` when the item being taken sits in the 3x3/1x2 tableware slot (not the food
grid), specifically so the player can still pull a worn piece out in order to replace or repair it.
Separately, the background `AutoSaveTableware` worker (`src/nurgling/actions/AutoSaveTableware.java`,
instantiation commented out in `NCore.java`) remains deliberately disabled — this is a second,
independent piece of the same feature area, not the same mechanism as the `wdgmsg` guard above.

**Why:** Stops a food `take` from silently letting worn tableware break mid-feast (losing its bonus)
while still leaving the player free to replace or repair the tableware itself — the guard protects the
*food-eating* action, not the tableware slot.

**Minimum hook that must survive:** the `wdgmsg("take")` check in `NGItem.java` and its
`isFoodTakeBlockedByWornTableware()` predicate (in particular, the early-return that exempts the
tableware grid itself); `AutoSaveTableware`'s instantiation staying commented out/disabled in
`NCore.java`.

**Verify:** with `autoSaveTableware` enabled and a table's tableware near-breaking, confirm taking food
from that table's food grid is blocked with the error message, while taking the worn tableware piece
itself still succeeds. Confirm `NCore.autoSaveTableware` stays null at runtime (worker never started).

**Superseded when:** upstream ships an equivalent food-take guard with the same
"protect eating, never block replacing" semantics — not merely if upstream adds any generic tableware
auto-save behavior of its own.

## Fork configuration keys

**File:** `src/nurgling/NConfig.java` (`Key` enum and its defaults block)

**Fork behavior:** The fork adds its own `Key` entries for fork-only features (Combat Reactor settings,
LP Assistant, World Explorer, table-eat optimizer, and others) alongside whatever upstream adds in the
same enum.

**Why:** `NConfig.Key` is upstream's own extensible settings-storage mechanism; the fork uses it as
designed rather than maintaining a parallel config system.

**Minimum hook that must survive:** every fork-added key stays present with its default; enum identity
(not ordinal position) is what config storage keys off of, so insertion order relative to upstream's
own additions is cosmetic, not load-bearing.

**Verify:** after any sync, diff the `Key` enum's entry count before/after; confirm no fork key was
dropped and no duplicate was introduced.

**Superseded when:** never wholesale — this is an open-ended growing list, not a single override to
retire. Individual keys retire only when the fork feature they back is removed.

## Fork `BotRegistry` registrations

**File:** `src/nurgling/actions/bots/registry/BotRegistry.java`

**Fork behavior:** Fork-owned bots (LP Assistant, Combat Reactor tool, table-eat optimizer, and others)
are registered in the same `bots` list upstream's own bots populate.

**Why:** `BotRegistry` is the single enumeration point the bot menu, scenarios, and (as of the
2026-09-04 sync) upstream's own new `Specialisation` search window all read from — registering fork
bots there instead of a parallel list keeps them visible to all of that machinery for free.

**Minimum hook that must survive:** every fork bot's `BotDescriptor` entry stays present with correct
id/class/`BotType`/metadata. Registration identity is **not** ordinal-keyed the way `NConfig.Key` is
(nothing looks a bot up by list index) — but list position is not entirely inert either:
`NBotsMenu.java` groups `BotRegistry.allowedInBotMenu()`'s entries by `BotType` into per-category
layouts and adds each bot's button to its category **in list iteration order**, so a fork bot's
position within the shared `bots` list determines where its button falls within its category's
button grid. A future merge reordering entries around a fork bot won't break anything functionally,
but could visibly reorder that bot's button within its category — worth a glance after a sync, not a
hard invariant.

**Verify:** diff entry count before/after any sync; confirm every fork bot id is still present with its
registered class, correct `BotType`, and in a reasonable position within its category's button
ordering.

**Superseded when:** never wholesale — same open-ended nature as the config keys above.

## Fork i18n registrations

**Files:** `src/lang/messages.properties`, `src/lang/messages_ru.properties`

**Fork behavior:** Fork features add their own translation keys to the same files upstream's own i18n
keys live in.

**Why:** `L10n.get(...)` reads from these files by key; there's no separate fork-only translation file,
so fork strings live alongside upstream's.

**Minimum hook that must survive:** every fork-added key, in both language files, stays present with
its fork-authored value (not silently replaced by an upstream string that happens to reuse the key).

**Verify:** grep both files for known fork-prefixed keys (e.g. `lpassistantbot.*`, anything
`combatreactor`-prefixed) after a sync; confirm the fork's translated value, not a blank or
upstream-substituted one.

**Note:** these two files also carry a handful of duplicate-key entries with identical values on both
occurrences — this predates the fork's own history and is not a fork customization; see the scope note
at the top of this document.

**Superseded when:** never wholesale.

## Player world-coordinate HUD text

**Files:** `src/nurgling/NMapView.java` (draw override, ~line 234), `src/nurgling/widgets/NMiniMap.java`
(`drawplayercoords()`, ~line 1715)

**Fork behavior:** A QoL toggle (`NConfig.Key.showPlayerCoords`, exposed as Settings → Nurgling
Settings → General → Quality of Life → Debug & Development → "Show player world coordinates") draws
the player's current world position as `"World: x.xx, y.xx"` HUD text — once in `NMapView`'s 3D map
draw path, and once via `NMiniMap.drawplayercoords()` on the corner minimap/full map window. Both call
sites translate the live, session-local `Gob.rc` through `MiniMap.sessloc` before display specifically
so the printed numbers agree with the persisted `MapFile` grid coordinates shown by the separate
"Show Grid" overlay below — comparing raw `Gob.rc` against a grid coordinate would silently show two
different numbering systems as if they were one.

**Why:** Fork-added QoL/debug feature (commit `992ae0787`, 2026-08-01) for correlating a player's live
position with the persisted map-grid addressing external mapping tools use; not present upstream at
the time it was added.

**Minimum hook that must survive:** the `showPlayerCoords` config key; both draw call sites; the
`sessloc` translation step in each (dropping it silently reintroduces the raw-`rc`-vs-grid-coordinate
mismatch this feature exists to avoid).

**Verify:** enable the toggle; confirm matching `World: x, y` text appears on both the 3D view and the
minimap, and that the numbers track player movement.

**Superseded when:** upstream ships an equivalent live-position HUD readout, translated through the
same persisted-grid coordinate space.

## Grid-overlay cell coordinate labels

**File:** `src/nurgling/widgets/NMiniMap.java` (`drawgrid()`, added in the same `992ae0787` commit)

**Fork behavior:** A separate feature from the HUD text above, sharing only its commit of origin. The
existing "Show Grid" red grid overlay (gated by its own pre-existing `NConfig.Key.gridbox`, not
`showPlayerCoords`) additionally labels the top-left corner of every visible grid cell with its
persisted `MapFile` grid coordinate as `"(x,y)"`, computed from the cell's grid index and the current
data/zoom level (`x * levelMul, y * levelMul`) rather than from any live player position.

**Why:** Lets a player read off a cell's grid coordinate directly from the overlay already used to
visualize grid boundaries, using the same addressing external mapping servers expect — without this,
correlating an on-screen grid cell to that addressing required cross-referencing a separate tool.

**Minimum hook that must survive:** the label-drawing block inside `drawgrid()`; its coordinate math
(`x * levelMul`/`y * levelMul` against the cached per-line screen positions) must keep using the same
grid-index space as the line-drawing code immediately above it in the same method, since the labels
are drawn from screen positions the line-drawing pass already computed.

**Verify:** enable "Show Grid"; confirm each visible cell shows a `(x,y)` label at its top-left corner
that changes consistently with zoom/data level.

**Superseded when:** upstream's own grid overlay (if any) natively labels cells in the same persisted
grid-coordinate space.
