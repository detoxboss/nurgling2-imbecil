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

## Auto-drink: configurable threshold, DrinkMeter-based water check, and non-blocking bot concurrency

**Files:** `src/nurgling/actions/AutoDrink.java`, `src/nurgling/NConfig.java`,
`src/nurgling/widgets/options/QoL.java`, `src/lang/messages.properties`, `src/lang/messages_ru.properties`

**Fork behavior:** Upstream's Auto-drink (`AutoDrink.java`) hardcodes its trigger at a fixed 51%
stamina fraction, gates itself off entirely whenever any bot is registered on
`BotsInterruptWidget.waitBot`, and detects water availability by manually re-walking the Belt
equipment slot and a hand-held bucket — missing the hotbelt, pouches, and general inventory, which
made the feature silently never trigger for a normal playstyle where water lives on the hotbelt. The
fork instead: (1) reads a new `NConfig.Key.autoDrinkThreshold` (percent, 1-100, default 75) each cycle
instead of a hardcoded fraction; (2) latches an "active" cycle at threshold-crossing that only clears
once stamina reaches `FULL_STAMINA` (0.99), so a brief rise above threshold mid-drink doesn't abort the
cycle; (3) removed the blanket bot-running exclusion so Auto-drink can inject a Drink command behind
whatever primary action (manual or bot) is already in progress, per Haven's action-ordering rule that a
command issued after an in-progress action doesn't cancel it; (4) replaced the manual container walk
with `gui.drinkMeter.getWater() > 0` (deliberately `getWater()`, not `getTotalDrinkable()`, so Auto-drink
never consumes tea); (5) inspects `WaitPoseOrMsg.isError()` after issuing Drink and backs off via a
60-tick cooldown NTask instead of retrying immediately, with a one-shot `gui.error(...)` notification
per failure episode instead of silence or per-tick spam.

**Why:** See the 2026-09-07 Auto-drink diagnostic session — upstream's implementation had a real,
reproducible bug (confirmed byte-identical against `upstream/master` at the time), not a fork-introduced
regression. Fixing it as a fork override rather than waiting on upstream because the water-detection gap
and lack of a configurable threshold were blocking normal play.

**Minimum hook that must survive:** the `Drink` `MenuGrid.Pagina` button-click dispatch path
(`pag.button().use(new MenuGrid.Interaction(1, 0))`) must remain a supplemental menu action, never a
cancel/Escape/click-to-move — that non-interrupting property is the entire point of the feature. The
`active` latch and `getThresholdFraction()`/`getThresholdPercent()` clamp-to-[1,100] must stay together;
don't let a merge quietly restore the hardcoded `0.51`. `gui.drinkMeter` (fork-owned widget, not
upstream) is a hard dependency of the water check.

**Verify:** enable Auto-drink, set a threshold, drink stamina down below it while manually running or
chopping — confirm the primary action is never interrupted, Drink fires once per depletion (no spam),
and toggling threshold takes effect without a client restart.

**Superseded when:** upstream ships an equivalent configurable-threshold, DrinkMeter-based, bot-agnostic
Auto-drink of its own — at which point this override should be diffed against upstream's approach rather
than assumed to still be correct.

## Kin/Village/Realm/claim permission groups: companion-selector overlay on a lifecycle hook

**Files:** `src/haven/BuddyWnd.java` (`ncolors`, `nquick`, `named`/`gc`, `GroupSelector.basesz` +
`attached()`/`dispose()` hooks; `BuddyInfo`, `BuddyList.ItemWidget`, the `@RName("grp")` factory - all
of which are now **unchanged from plain upstream** except the `ncolors`/`nquick` split), `src/haven/Polity.java`
(`Member.group`, `MemberList.makeitem`, `uimsg("add")`), `src/nurgling/widgets/NGroupSelectorAugmenter.java`,
`src/nurgling/widgets/NGroupSelectorCompanion.java`, `src/nurgling/widgets/NGroupLabelPopup.java`,
`src/nurgling/conf/NGroupLabels.java`, `src/nurgling/widgets/NKinSettings.java`,
`src/nurgling/NConfig.java` (`Key.kinGroupLabels`, `Key.villageGroupLabels`), `src/lang/messages.properties`,
`src/lang/messages_ru.properties`.

**History — read before touching this again.** Three earlier versions of this work were tried and
rejected, each by a real, specific failure:

1. PR #7 (merged) grew `BuddyWnd.GroupSelector` itself from a one-row, 8-square grid into a multi-row
   grid (28, then 40, colour squares). Live testing proved this broke the Village permission window:
   it positions Banish/Forget assuming the selector stays roughly one row tall, so with 3-4 rows they
   drew on top of groups ~20-29, making those unclickable and turning a click there into an accidental
   Banish/Forget.
2. The first replacement kept `GroupSelector` compact but added a visible `nquick`-square row **plus**
   a dropdown **plus** an edit button side by side in one `NExtendedGroupSelector`. That combined width
   (~350px) was never checked against the ~160px a one-row `GroupSelector` actually occupies
   (`BuddyWnd.width` is `UI.scale(263)`, and the Village window is no wider) - live testing showed the
   dropdown and edit button clipped almost entirely off the Kin panel, and invisible on both Village
   selectors.
3. The second replacement fixed the width (dropdown + edit button only, capped at the one-row
   footprint) and wired it in by having the `@RName("grp")` factory and `BuddyWnd.BuddyInfo` construct
   the new control directly, on the theory that `grp` was the Village permission UI's creation path.
   **Live testing proved this theory wrong**: Kin's dropdown worked (because `BuddyInfo` constructs its
   own selector directly, in this tree), but both Village selectors still showed only the original
   8 squares - `grp` was never their creation path. Cross-checked against a real reference client that
   had already traced this exact architecture (irongete's `brodgar-io-client`, commits `87230ee3`,
   `a580b7fc`, `715251e0` - see "Verification against a reference client" below): the Village, Realm,
   and personal-claim ("Stake") permission windows are server-distributed resource code (`ui/vlg`,
   `ui/realm`, `ui/land`) that **construct `BuddyWnd.GroupSelector` directly in their own Java
   constructors**, never through any `@RName` factory. `@RName("grp")` is the wire protocol's generic
   widget-creation path; these windows are bespoke compiled classes with no reason to use it once they
   already have compile-time access to `haven.BuddyWnd.GroupSelector`.

**Do not re-attempt any of the three rejected approaches** (growing `GroupSelector`'s row count;
laying a visible quick-square row out beside the dropdown; wiring the compact control in via `$grp` or
by having first-party call sites construct it directly). The design below is the replacement for all
three, not an addition to any of them.

**Verification against a reference client.** `irongete/brodgar-io-client` (a separate, unrelated Haven
& Hearth client fork) had already solved the identical problem and documented it in commit messages
readable via `gh api repos/irongete/brodgar-io-client/commits/<sha>`. Three commits were fetched and
read in full before any of this was implemented:

- `87230ee38c12e14679f8bc056de9885e528ee2b0` ("a group is a number, not a colour") - the same
  out-of-bounds palette crash this fork already fixed in PR #7, and confirms `ui/vlg`'s `Village` /
  `ui/realm`'s `Realm` construct `BuddyWnd.GroupSelector` directly (quoting an actual crash stack
  frame, `haven.res.ui.vlg.Village$VMember.draw`).
- `a580b7fce8f656d51572a6278e1fd50298b81c36` ("a row says which group it is in...") - the exact
  `Polity.Member.group`/`uimsg("add")` change this fork's `Polity.java` diff now carries (verified
  byte-for-byte against that commit's own diff before writing it here), and the finding that driving
  the claim window's selector above group 7 is a **silent failure that measures as a client-side
  problem**, not a crash.
- `715251e03ad1d40975bad4bb90785fa24ee6b107` ("the claim's permission table answers for the whole
  group space") - a full vendored copy of `ui/land`'s `Landwindow.java` (fetched and read in full),
  which **conclusively identifies the claim-window limitation as an 8-long `int bflags[]` array inside
  that resource's own, otherwise-unmodified Java code** (`bflags[group.group]`, read/written by
  `updflags()` and the `"shared"` wdgmsg/uimsg), not a server data-model limit - Brodgar fixed it there
  by vendoring exactly that one array's size to 255. **This fork deliberately does not do that** (see
  "Personal claim ('Stake') window" below) - the 0..7 restriction here is this fork choosing not to
  carry that vendored patch, correctly attributed to its real cause rather than described as a server
  limitation.

Confidence levels differ across what was verified: `Landwindow`'s exact class
(`haven.res.ui.land.Landwindow`), field (`BuddyWnd.GroupSelector group`), and construction pattern
(`group = add(new BuddyWnd.GroupSelector(0) {...}, coord)`) come from a directly-read vendored copy -
high confidence. `Village`'s exact class name (`haven.res.ui.vlg.Village`) is inferred from crash-report
prose (a real stack trace element quoted twice across two commits, not from an independently vendored
copy) - see the constant's own doc comment in `NGroupSelectorAugmenter.java` for the fallback behavior
if it's ever wrong (Village simply gets no companion, not a misattributed one).

**Fork behavior (current):**

- Three previously-conflated concepts stay three distinct, separately-named constants, none used as a
  stand-in for another: `BuddyWnd.ncolors = 40` (total assignable Kin/Village groups), `BuddyWnd.nquick
  = 8` (how many groups get a colour square on the *plain* `GroupSelector`), `gc.length = 255` (safe
  server-range backing table). `GroupSelector` itself is **back to being exactly what plain upstream
  Nurgling2/Hafen ships** - one row, `nquick` squares, the same bounds-checked `update()`/`select()` it
  always had - plus two narrow lifecycle hooks (below). `$grp` and `BuddyWnd.BuddyInfo` are likewise
  back to constructing a plain `GroupSelector` directly, byte-for-byte identical to before this whole
  feature existed except for the `ncolors`/`nquick` split.
- The seam is now the two lifecycle hooks added to `GroupSelector` itself:
  ```java
  protected void attached() {
      super.attached();
      NGroupSelectorAugmenter.attached(this);
  }
  public void dispose() {
      NGroupSelectorAugmenter.detached(this);
      super.dispose();
  }
  ```
  `attached()` (not `added()`) is used deliberately: a resource window commonly finishes building its
  entire child tree, synchronously, inside its own constructor - which runs *before* the wire protocol
  attaches that constructor's own widget instance to anything. Classifying a `GroupSelector` by walking
  its ancestor chain at `added()` time would be unreliable (the chain isn't fully linked to the live
  root yet for a selector nested inside a not-yet-attached sub-panel, e.g. Village's per-member panel).
  `attached()` fires only once every ancestor up to the live root is genuinely connected (Hafen's own
  `Widget.attached()` recursion guarantees this), making ancestor-based classification reliable
  regardless of construction order. `dispose()` (not `destroy()`) is used for teardown for the mirror
  reason: `Widget.destroy()` only reliably fires on the widget an `ui.destroy()` call directly targets;
  a cascading teardown from an ancestor calls `rdispose()` → `dispose()` on descendants, never their
  own `destroy()` override. `dispose()` is the one hook Hafen guarantees on every widget in a torn-down
  subtree regardless of where the teardown was triggered.
- `nurgling.widgets.NGroupSelectorAugmenter` is the classifier: on `attached()`, it walks the new
  `GroupSelector`'s ancestor chain and decides whether to attach a companion, by structural/resource
  identity, never a localized caption:
  - `getparent(MapWnd.class) != null` → **leave alone** (map-marker picker; simple by design).
  - `getparent(BuddyWnd.BuddyInfo.class) != null` → Kin scope, groups 0..`ncolors`-1, no cross-client
    warning (Kin group assignments are purely local, never sent to other players).
  - `getparent(NKinSettings.class) != null` → Kin scope, groups 0..`ncolors`-1 (see below), same as above.
  - `getparent(Polity.class)` resolves and that instance's runtime class is `haven.res.ui.vlg.Village`
    → Village scope, groups 0..`ncolors`-1 (covers **both** the top-level and per-member selectors -
    both are `Polity`-descended `GroupSelector`s constructed the same way), **with** the cross-client
    warning (below) above `nquick`.
  - any other `Polity` subtype (Realm, or anything future) → **leave alone**. Not sharing the Village
    label namespace with Realm was an explicit requirement; without an independently-verified
    `haven.res.ui.realm.Realm` class-name match this fork does not guess one is safe to add.
  - immediate parent's runtime class is `haven.res.ui.land.Landwindow` → Kin scope, groups
    0..`nquick`-1 **only**, no warning needed (the range offered never reaches `nquick`). See "Personal
    claim ('Stake') window" below.
  - **everything else** (not `MapWnd`, not Kin, not `Polity`-descended, not `Landwindow`) → Kin scope,
    groups 0..`ncolors`-1, **with** the cross-client warning above `nquick`. This fallback exists because
    Field Cairn's permission window still showed the plain 8-square picker after the first live-testing
    round, and **Field Cairn's Java class name has no evidence behind it at all** - no crash report, no
    reference-client commit, no vendored copy - unlike `Landwindow` (vendored copy read in full) or
    `Village` (named in a crash-report stack frame) - so it cannot be matched by name the way those two
    are. A repo-wide search for `new GroupSelector(` confirms the only first-party construction sites
    (`BuddyInfo`, `$grp`, `MapWnd`'s marker picker) are excluded by the branches above it, so anything
    reaching this fallback is, by construction, resource code this fork has no source for.
    **The 0..`ncolors`-1 range here (rather than 0..`nquick`-1) is per the user's report that the game's
    own developers - who can see server-side storage, unlike this fork - confirmed Field Cairn's
    permission storage is not capped at 8 the way the personal claim's is.** This is materially
    different evidence from everything else in this section: it is neither independently vendor-verified
    nor inferred from a crash report, only asserted by the user citing an unnamed developer source, and
    is being shipped specifically to be live-tested and confirmed or refuted - see "Verify" below. If
    live testing shows Field Cairn is in fact capped at `nquick` like the claim window, revert this
    fallback's `hi` back to `nquick - 1` (or, better, get Field Cairn's actual class name from a live
    crash/log and give it its own named branch like `Landwindow`'s, once evidence for one exists). If a
    resource window is ever found that embeds a `GroupSelector` for a non-permission purpose (the way
    `MapWnd`'s marker-colour picker does), add an explicit exclusion for it above, the same shape as the
    `MapWnd` one.
- For a classified selector, `NGroupSelectorAugmenter` builds a `nurgling.widgets.NGroupSelectorCompanion`
  (a dropdown + a small `...` button, sized to `GroupSelector.basesz` so it never exceeds the footprint
  a plain selector already occupies), calls `sel.hide()` (which Hafen's own `Widget.draw`/pointer-event
  dispatch both already skip for invisible widgets - confirmed by reading `Widget.draw(GOut,boolean)`
  and `PointerEvent.propagation` - so this removes both the visible squares and their click handling
  with zero risk to `sel`'s own `update()`/`select()` continuing to work programmatically), and adds
  the companion as a sibling at `sel.c` inside `sel.parent`. **The real `GroupSelector` is never
  replaced, copied, or reconstructed** - resource code that holds a reference to it keeps working
  unmodified, including calling `select()`/`update()` on it or reading/writing its public `group` field
  directly.
- **The critical dispatch path**, unchanged in principle since the second rejected attempt: choosing a
  group in the companion's dropdown calls `real.select(item)` - the exact public method a colour-square
  click on the real, resource-or-first-party-owned `GroupSelector` already calls - which runs *that
  selector's own* `changed(int)` override (Village's/Realm's/Landwindow's own compiled code, or
  `BuddyInfo`'s/`$grp`'s anonymous override) and sends *its* protocol message. Neither
  `NGroupSelectorCompanion` nor `NGroupSelectorAugmenter` ever call `wdgmsg` themselves, and neither
  needs to know what message a given selector's owner sends.
- Because resource code (or the server re-driving a selector) can change `real.group` without going
  through `select()` - e.g. `BuddyInfo.update()` calling the selector's own `update(int)` directly, a
  raw field write, or logic entirely inside a resource class this fork cannot see - the companion
  mirrors `real.group` into its own displayed value on every `tick()`, a Java-native per-instance
  pattern rather than a global polling timer, exactly as this feature's design review specified.
- `Polity.Member` (the base class both `Village.VMember` and `Realm.RMember` - published resource
  code with no source here - subclass) gains a `public int group = -1` field, populated from
  `uimsg("add")`'s optional second wire argument when present, and copied forward on re-`add` (a
  member's own row is rebuilt whenever its polity re-sends it). `Polity.MemberList.makeitem()` (this
  fork's own code, not resource-provided, and reused unmodified by whatever `MemberList` subclass a
  Polity uses, since only `Member`/`VMember`/`RMember` are subclassed per the reference commits) draws
  the `"[N]"` tag *after* calling `item.draw(g)`, deliberately outside `Member.draw()` itself - since
  it cannot be verified whether `VMember`/`RMember` call `super.draw()` (no vendored copy of either to
  check), drawing from the wrapper runs regardless of what the subclass's own override does or doesn't
  call. Guarded to `group >= 0` so polities that send no group argument (a plain Kin-less generic
  Polity, if one ever exists) draw nothing extra.
- **Personal claim ("Stake") window.** `haven.res.ui.land.Landwindow` is server-distributed resource
  code that persists only 8 permission rows (`int bflags[] = new int[8]` in the unmodified, currently
  served version) - a client-side array bound inside that resource's own code, not a server data-model
  limit (see "Verification against a reference client" above). This fork's companion offers **only**
  0..`nquick`-1 there, under the Kin label namespace (these windows grant permissions to the owning
  character's own Kin groups, not a separate namespace) - never the full 0..`ncolors`-1 range - and
  this fork does **not** vendor `ui/land` to widen that array, even though doing so is now known to be
  a one-line fix. Vendoring a version-pinned (`@FromResource`) copy of a frequently-served resource is
  exactly the long-lived, version-bumping maintenance burden this fork's low-divergence policy exists
  to avoid, for a capability (claim permissions above group 7) nobody has asked for.
- **Field Cairn.** Field Cairn's own permission window (placed via the `gfx/terobjs/fieldcairn` gob,
  live-tested to show a "Groups:" row of 8 squares plus Trespassing/Rummaging/Theft/Vandalism privilege
  checkboxes, structurally like the claim window) reaches the companion mechanism through the
  generalized fallback above, not a name match - its Java class has never been read. Unlike the claim
  window, it is offered the **full** 0..`ncolors`-1 range, not capped at `nquick`-1: the user reported
  the game's own developers - who can see server-side storage - confirmed Field Cairn's permission
  storage supports the full range, unlike the claim's 8-row `bflags[]`. **This is asserted, not
  independently verified by this fork** (no vendored copy, no crash report - see the fallback's own
  comment in `NGroupSelectorAugmenter.classify()`), and is shipped specifically to be live-tested; see
  "Verify" below. The same cross-client crash risk Village carries above `nquick` (below) is assumed to
  apply here too until live testing says otherwise, since Field Cairn's ground permission-color overlay
  is shared world state rendered by every nearby client the same way Village's member list/overlay is.
- **Client-side custom labels.** `nurgling.conf.NGroupLabels` persists labels through two separate
  `NConfig.Key` slots (`kinGroupLabels`, `villageGroupLabels`) so the same numeric group id can carry
  an independent meaning in Kin vs. Village. Each scope is additionally keyed by an `owner` string
  nested one level inside that per-world map:
  - **Kin** - the owning character's `haven.GameUI#chrid`, so two characters played on the same world
    can give the same Kin group number different meanings (resolved via
    `selector.getparent(GameUI.class).chrid` - never an ambient "current session" accessor).
  - **Village** - the polity's own `Polity.name`. This is a deliberate tradeoff, not an oversight: the
    wire protocol exposes no durable numeric village id to the client at this seam, so a village's
    display name is the most stable identity available. Renaming a village orphans its old labels, and
    two differently-charter'd villages sharing an identical name on one world would share a namespace.
    Both are judged rare and low-stakes (client-side presentation, no data loss) enough to accept
    rather than block the feature on an id the protocol doesn't give us - documented here rather than
    silently made world-global.
  - Both `owner` keys are computed once, in `NGroupSelectorAugmenter.classify()`, where the selector's
    ancestor context is already being resolved anyway - not re-derived per keystroke.
  - The top-level Village permission selector and the per-member Village selector resolve to the
    **same** owner key (both being descendants of the same `Polity` instance), so they always show the
    same labels for the same village, as required.
- `nurgling.widgets.NGroupLabelPopup` (unchanged in approach from the previous attempt - review found
  no bug in its save/persistence logic, only in its session resolution) is a small free-floating
  `Window`, never a child of the companion or anything resource-owned, opened by the companion's `...`
  button. It saves on Enter, the Save button, and every close path (titlebar cross, Escape) through one
  idempotent `destroy()`-time save, and now resolves its attach point via
  `owner.getparent(GameUI.class)` - the companion's own owning session - instead of the ambient
  `NUtils.getGameUI()` accessor a previous draft used; this fork runs multiple sessions in one process,
  and a popup opened from one session's panel must land in that session's own widget tree, not
  whichever session happens to be foregrounded elsewhere.
- **Kin list display**: `BuddyWnd.BuddyList.ItemWidget` renders `Name [N]` (bracketed group id, neutral
  colour, after the still group-coloured name) via a cached `Buddy.grouptag()`, draw-layer only -
  `Buddy.name`, sorting, and searching are untouched. The Village/Realm member-list equivalent is the
  `Polity.Member.group`/`makeitem()` mechanism described above, not this one - the two are separate
  numbering systems (`BuddyWnd.Buddy.group` is a personal Kin classification; `Polity.Member.group` is
  a polity's own per-member group) and must never be conflated.
- `nurgling.widgets.NKinSettings` no longer duplicates `GroupSelector`/`GroupRect` in its own inner
  classes; it constructs a real `BuddyWnd.GroupSelector` directly (`getparent(NKinSettings.class)`
  classifies it into the Kin scope like any other), so it participates in the same companion mechanism
  and label namespace as the Kin panel, while `NKinProp.get(group)`/`set(...)` still key off the exact
  numeric group selected, unchanged.

**Minimum hook that must survive:** `BuddyWnd.GroupSelector` must stay exactly the plain, one-row,
`nquick`-square widget it always was, with **no** dependency on any Nurgling class in its own body
beyond the two lifecycle-hook calls - the moment resource windows' own assumption of a roughly-one-row
selector breaks again, the Banish/Forget overlap bug this whole rewrite exists to fix comes back.
`attached()`/`dispose()` (not `added()`/`destroy()`) must remain the hook pair, for the ordering and
cascading-teardown reasons above. `NGroupSelectorCompanion` must never be added as a replacement for a
real selector, only as a sibling alongside a hidden one, and must keep driving it via `real.select(int)`
- never a direct `wdgmsg` call. `Polity.MemberList.makeitem()`'s tag-drawing must stay in the wrapper,
not moved into `Member.draw()`, unless a vendored `VMember`/`RMember` copy someday proves both call
`super.draw()`. The `CLASS_VILLAGE`/`CLASS_LANDWINDOW` string constants in `NGroupSelectorAugmenter`
are resource-version-sensitive by nature (a class rename in a future `ui/vlg`/`ui/land` publish would
silently stop matching, degrading to "leave alone"/the generalized fallback rather than breaking) -
re-verify them with a live crash/log check or a fresh vendored copy if Village or claim companions stop
appearing (or the claim window's companion suddenly offers 0..39 because it fell into the fallback)
after a game update. The generalized fallback (everything not `MapWnd`/Kin/`Polity`/`Landwindow`) has
no class name to go stale, but relies on the invariant that no first-party code other than
`BuddyInfo`/`$grp`/`MapWnd` ever constructs a `GroupSelector` directly - re-check that invariant (a
repo-wide search for `new GroupSelector(`) before adding any new first-party construction site, and add
it as an explicit early branch the same shape as `MapWnd`'s if it isn't meant to fall into the
0..`ncolors`-1 Kin bucket. That bucket's range (0..`ncolors`-1, not 0..`nquick`-1) rests on the user's
report of unverified developer testimony about Field Cairn specifically - see the "Field Cairn" bullet
above and the fallback's own comment in `classify()` before trusting it further or extending it to any
other window that lands in this same bucket.

**Known live-tested bugs and fixes (second round of live testing, after the merge to `master`):**

- **Crash closing a window with an open dropdown.** `haven.Listbox.mousedown` calls `change(null)`
  when a click lands inside the open list but not on an actual item - correct base-widget behavior, not
  a bug there. `NGroupSelectorCompanion.GroupDropbox.change(Integer item)` unconditionally forwarded
  `item` into `real.select(item)`, unboxing a null `Integer` into `int` and throwing
  `NullPointerException` (reported stack trace: `GroupDropbox.change` → `Dropbox$Droplist.change` →
  `Listbox.mousedown`). Fixed by skipping the `real.select(item)` forward (not the `super.change(item)`
  call) when `item` is null; the companion's own `tick()` already resyncs its displayed selection back
  to `real.group` every frame regardless.
- **Label popup opening behind the main window.** `NGroupLabelPopup.open()` added the popup via
  `host.add(...)` but never raised or focused it, so it could land behind whatever window already had
  focus in that session. Fixed by calling `popup.raise(); host.setfocus(popup);` immediately after
  adding it, mirroring the existing raise+focus pattern used elsewhere in this codebase (e.g.
  `Window.java`'s own child-window handling, `GameUI.java`'s window-reopen logic).
- **Save button disappearing without closing the window.** Both the Save `Button.click()` override and
  the `TextEntry.activate()` (Enter-to-save) override in `NGroupLabelPopup` called a bare, unqualified
  `destroy()` from inside an anonymous inner-class body. Neither `Button` nor `TextEntry` (nor their
  shared ancestor `SIWidget`) overrides `destroy()`, so Java resolved that unqualified call to
  `Widget.destroy()` invoked **on the anonymous widget itself** (the button, or the text field) rather
  than on the outer `NGroupLabelPopup` - which is why Save visibly vanished (its own `remove()`/
  `rdispose()` ran) while the window and its actual close/save logic never triggered. Only the titlebar-
  cross/Escape path (`NGroupLabelPopup.wdgmsg`, a direct override on the outer class, where an
  unqualified `destroy()` correctly resolves to `this`) worked correctly before this fix. Fixed by
  qualifying both call sites as `NGroupLabelPopup.this.destroy()`.
- **Cross-client crash from high Village (and, provisionally, Field Cairn) groups (reported as a
  question, not a bug - answered, not fixed).** Assigning a Village member (or, per the Field Cairn
  change above, a Field Cairn) a group above 7 is genuinely unfixable from this fork alone: the
  member-list `[N]` tag and the ground permission-color overlay are shared world state rendered by
  *every* client viewing them, including bystanders on an unmodified client who never opened any
  permission window - and an unmodified client's own compiled rendering code has its own array bound at
  8, the same class of bug this fork's `gc[255]`/`ncolors`/`nquick` split fixed locally. There is no
  protocol-level or server-side lever reachable from this fork that could patch another player's
  binary. As a client-side mitigation (not a fix), `NGroupSelectorCompanion` now calls
  `GameUI.error(...)` with a translated warning (`group.high_group_warn`) whenever a group `>=
  BuddyWnd.nquick` is picked in a context `NGroupSelectorAugmenter` flagged `warnAboveNquick` -
  currently Village and the Field-Cairn/unknown fallback. The claim window can never trigger it, since
  it is capped to 0..7 before a companion is even built; Kin never triggers it either, since Kin group
  assignments are purely local and never rendered to other players.

**Verify:** `ant test && ant jar`; in-game, confirm the Kin panel shows a compact dropdown + `...`
button with no visible quick squares and no clipping; that both Village selectors (top-level and
per-member) show the same control, fitting exactly where the original 8-square selector fit, with
Banish/Forget undisturbed; that the claim ("Stake") window's selector offers only groups 0..7; that
**Field Cairn's selector offers the full 0..39 and that assigning a group above 7 there actually works
server-side rather than silently failing or corrupting state** (this is the one asserted-not-verified
claim in this section - if it's wrong, revert the fallback's range per the note in `classify()`); that
groups 0/7/8/20/39 are selectable everywhere they should be and assigning 39
sends `39` (not a remapped index); that Kin and Village labels persist independently, are scoped
per-character (Kin) and per-village-name (Village) as described, and the dropdown reflects a saved
label immediately; that the Edit popup opens in the correct session, on top of and focused ahead of
the main window, with two sessions open; that clicking Save (or pressing Enter) closes the popup
immediately and persists the edit; that opening a dropdown and closing its window without picking an
item does not crash; that picking a Village group 8..39 shows the new crash-risk warning and picking
0..7 does not; that `NKinSettings`/`MapWnd` behave exactly as before this feature existed structurally,
just reachable through the shared companion mechanism (`NKinSettings`) or left alone (`MapWnd`); and
that switching Village members repeatedly leaves no duplicated or leaked companions.

**Superseded when:** upstream ships an equivalent expanded permission-group system, or the Village/
Realm/claim resource windows' own implementations change such that a different seam becomes available
or this one stops matching (see the class-name re-verification note above) - at which point this
override, and the three "do not re-attempt" notes in the History section, should be re-evaluated
against whatever the new situation is, not assumed to still be necessary.

## Per-instance gob Configure overrides

**Files:** `src/nurgling/tools/GobCustomize.java`, `src/nurgling/widgets/GobConfigWindow.java`,
`src/nurgling/contextmenu/ConfigureGobAction.java`, `src/nurgling/overlays/NGobConfigMarker.java`,
`src/nurgling/overlays/NGobConfigLabel.java`, `src/nurgling/NConfig.java` (`Key.gobInstanceConf`),
`src/lang/messages.properties`, `src/lang/messages_ru.properties`

**Fork behavior:** Upstream's Ctrl+RMB "Configure" window (scale/tint/marker/caption) resolves and
persists settings by resource path alone (`GobCustomize.conf: res -> Settings`), so any edit applies
to every gob of that resource type at once — configuring one cupboard silently reskins every cupboard.
The fork adds a second settings layer, `GobCustomize.instConf: hash -> Settings`, keyed by
`NGob#hash` (the SHA-256 of resource name + map grid id + in-grid position — the same identity
`Container`/`ContainerDao` already use to persist individual storage containers). The Configure window
gained a "This object" / "All objects of this type" scope choice (`RadioGroup`, defaulting to "this
object" whenever the clicked gob has a resolvable hash), and is now retargeted by hash rather than by
resource path so that opening it on two different physical gobs of the same resource never collides.
`GobCustomize.effectiveSettings(gob)` resolves instance override, else type-wide setting, else
defaults, and is what `apply(Gob)`/`scaleOf(Gob)` actually use — so both the attribute-based scaling
path (`NGobCustomScale`/`NGobCustomTint`) and `CSprite`'s (`src/haven/resutil/CSprite.java`, untouched)
scale-on-tick path pick up instance overrides for free.

**Why:** Requested because the type-wide-only behavior made the feature unusable for anything the
player wanted to mark individually (e.g. one labeled "seed cupboard" among many identical cupboards).
`NGob#hash` was deliberately reused rather than `Gob.id` (a session-local network object id that does
not survive relog) specifically because it is the identity this fork already trusts for
cross-session-persisted per-object state.

**Minimum hook that must survive:** `GobCustomize.instConf`/`effectiveSettings`/`updateInstance`/
`commitInstance`/`setInstance`/`applyOne` and the `gobInstanceConf` config key must keep existing
alongside upstream's own `conf`/`gobConf`, as an additive second layer — never collapsed back into a
single resource-keyed map. `apply(Gob)` and `scaleOf(Gob)` must keep resolving through
`effectiveSettings`, not `settings(res)` directly, or instance overrides silently stop rendering.
`GobConfigWindow.open` must keep retargeting by hash (falling back to `res` only when hash is
unavailable) rather than by resource path alone, or the two-cupboards-cross-target bug this exists to
fix comes back. `applyOne` must keep scoping its reapply to the caller-supplied `Glob` rather than
scanning every open session by a bare hash match, per this fork's existing explicit-session-ownership
convention (see the `NGameUI`/`NMiniMap` entries above).

**Verify:** two gobs of the same resource (e.g. two cupboards); configure one under "this object" with
a distinct scale/tint/marker/caption — confirm only that one changes. Then configure "all objects of
this type" — confirm the other, unconfigured gobs pick up the type setting while the instance-overridden
one keeps its own. Relog and confirm the instance override survives. Reset each scope independently and
confirm resetting one never clears the other.

**Superseded when:** upstream ships an equivalent per-instance override for this window, keyed by a
persistent per-object identity of its own — at which point this override should be diffed against
upstream's approach rather than assumed to still be correct.
