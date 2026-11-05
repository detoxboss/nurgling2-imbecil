# Modernization backlog: bot lifecycle, workstation/production, and navigation

**Kind:** recovery (prioritization of findings already verified in
[`capability-bot-lifecycle.md`](capability-bot-lifecycle.md),
[`upstream-compatibility.md`](upstream-compatibility.md),
[`capability-workstation-production.md`](capability-workstation-production.md), and
[`capability-navigation.md`](capability-navigation.md)). This is a backlog of
candidate improvements, not an implementation plan — **nothing here has been implemented.** Each item
names its own confidence level; don't treat "high confidence" as "verified bug," since none of these
were confirmed against a live repro (see each linked finding's own caveats).

## Bot lifecycle capability area (sessions 1-3)

Columns: **Severity** (impact if the risk materializes) · **Confidence** (how directly verified,
not just plausible — **CONFIRMED** means read/traced against actual code and reproduced or
unambiguously demonstrated by that trace; **PLAUSIBLE** means the risky elements are confirmed to
coexist but no live repro exists; **THEORETICAL** means not yet investigated beyond a structural
observation) · **Benefit** (what fixing it buys) · **Complexity** (size/risk of the fix itself)
· **Upstream merge risk** (does fixing it create or add to fork/upstream divergence) · **Suggested
test** (how to validate the fix, where one is actually implemented).

## 1. Guard `Results.ERROR`'s `NUtils.getGameUI()` dereference

- **Finding:** `capability-bot-lifecycle.md` §"Session 2: `Results.ERROR`'s null-dereference hypothesis"
- **Severity:** Medium — if it fires, the *original* bot failure is swallowed behind an NPE instead of
  being reported, which is worse than the plain failure (silent instead of visible).
- **Confidence:** PLAUSIBLE. The nullable accessor, the unconditional dereference, and 196 non-null
  call sites including several directly inside `InterruptedException` catch blocks all verifiably
  coexist; no reproduced crash, and the actual teardown-timing window during which `getGameUI()`
  returns null has not been traced against bot-thread cancellation ordering.
- **Benefit:** Closes a real gap against the codebase's own established null-guard idiom
  (`NUtils.getTickId()`'s pattern) with no behavior change in the non-null-UI case.
- **Complexity:** Trivial — 1-2 line guard in `Results`'s private constructor, no API change.
- **Upstream merge risk:** Low. `Results.java` is byte-identical to upstream today and upstream edits it
  rarely (4 commits total, last 2025-03-19) — this creates the fork's first divergence in this file, but
  on a file upstream almost never touches.
- **Recommended shape:** narrow, in `Results` itself (not an adapter — the defect is inside the
  constructor; see `upstream-compatibility.md` §5).
- **Suggested test:** a unit test that nulls/clears `ThreadLocalUI`/`NUtils.getUI()`'s backing state
  (whatever the actual test seam turns out to be — not yet identified) and calls
  `Results.ERROR("msg")`, asserting no `NullPointerException` and that the failure message is still
  retrievable from the returned `Results` even though it couldn't be shown in a UI. A second test:
  with a live `NGameUI`, assert `ERROR(msg)` still calls `gui.error(msg)` as before (no behavior
  regression in the normal case).

## 2. Give unattended-critical bots a self-timeout via existing `NTask.infinite=false` + `criticalExit`

- **Finding:** `capability-bot-lifecycle.md` §"Session 2: tracing the `NTask`/`criticalExit` wait
  mechanism"
- **Severity:** Medium — affects automation reliability (a bot can hang indefinitely with no automatic
  recovery), not correctness or data loss; the mechanism itself is thread-safe.
- **Confidence:** CONFIRMED for the mechanism (the wait/notify/interrupt trace through
  `NCore.addTask`/`baseCheck`/`TaskCriticalExitException` is read in full and verified — see
  "Session 2" and "Session 3" in `capability-bot-lifecycle.md`); THEORETICAL for impact — no specific
  one of the 137 unbounded tasks has been shown to actually hang in practice, and none has been picked
  yet as a concrete candidate.
- **Benefit:** Lets affected bots retry/skip/abort gracefully instead of requiring a human to notice and
  stop them, using a mechanism (`TaskCriticalExitException`) already proven out by the 5 existing
  bounded tasks and already fork-exclusive (no upstream edit needed to use it).
- **Complexity:** Low per task (one field flip + picking a sane `maxCounter`), but there are 137
  candidates — this is a long tail, not a single change. Needs per-task judgment: a task waiting on
  something that can legitimately take a long time (e.g. a long craft) needs a correspondingly large
  `maxCounter`, not a blanket default.
- **Upstream merge risk:** Depends entirely on which tasks are picked. Bounded-wait is an existing
  upstream idiom too (3 of the 5 current bounded tasks are upstream's own, byte-identical) — applying it
  to an **upstream-identical** task file opens a new divergence seam in a file that had none; applying
  it to a fork-exclusive task is free. `upstream-compatibility.md` §3's per-file fork/upstream
  classification is the input for picking low-risk targets first.
- **Recommended shape:** start with fork-exclusive tasks and any task in a file the fork has already
  modified for another reason (bundle the change into an existing diff rather than opening a new one
  purely for this — see `upstream-compatibility.md` §3/§5). **Candidate selection still needed**: no
  specific task(s) have been chosen yet — that's the next concrete step, not "go bound all 137."
- **Suggested test:** for whichever task is converted, a test that drives its `check()` condition to
  never become true and asserts `NCore.addTask` throws `TaskCriticalExitException` within roughly
  `maxCounter` ticks rather than hanging — mirroring however the 5 existing bounded tasks are (or
  should be) tested today. Confirm via `ant test` that no existing bounded-task test regresses.

## 3. Characterize the 46 `while(true)` bot files beyond `BoughBee`

- **Finding:** `capability-bot-lifecycle.md` session 1 (inventory) + session 3 (4-file sample) +
  `upstream-compatibility.md` §3 (fork/upstream classification)
- **Severity:** Unknown per-file — `BoughBee.java` (Qodana triage) is confirmed to have a "wrapper
  never loops" defect; the 4-file sample (`Chopper`, `TickBot`, `Dropper`, `MinesweeperMiner`) found no
  further instances, narrowing but not closing the question for the remaining 37.
- **Confidence:** PLAUSIBLE that the remaining 37 are mostly legitimate — 5 of 46 files are now
  individually read (`BoughBee` defective, 4 others clean), 41 remain grep-only.
- **Benefit:** Would convert a structural suspicion into either a confirmed defect list (actionable) or
  a confirmed "these are legitimate poll loops" finding (closes the question, no fix needed).
- **Complexity:** Investigation only at this stage — reading the remaining 37 files, not a code change.
  Any fix that falls out of it is file-specific and sized separately once found.
- **Upstream merge risk:** N/A for the investigation itself. For any resulting fix: of the 41
  upstream-identical files, 4 are now read and clean, 37 remain unread; 3 of the 46 are already
  fork-modified (`Fishing.java`, `WorldExplorer.java`, `DrinkerBot.java` — lower marginal cost to
  extend); 2 are fork-exclusive (free). Same cost logic as item 2.
- **Recommended shape:** if pursued further, sample a few more files from bot categories not yet
  represented (battle, farming, construction) rather than re-sampling resource/utility shapes already
  shown clean — per the task instruction, broad individual tracing of all 37 is not justified by the
  evidence so far.
- **Suggested test:** none until a concrete defect is found; `BoughBee.java`'s own fix (tracked in the
  Qodana triage, not here) is the template for what a found-defect's test would look like.

## 4. `ScenarioManager`/`ScenarioRunner` chained-bot failure propagation — RESOLVED, no fix needed

- **Finding:** `capability-bot-lifecycle.md` §"Session 3: `ScenarioManager`/`ScenarioRunner`/
  `BotExecutor`"
- **Status:** Closed as a question, not as a defect. `ScenarioRunner.runSteps` aborts the whole
  scenario on the first non-success `Results` and returns it to the caller — a deliberate,
  consistent policy (CONFIRMED by reading `ScenarioRunner.java` in full), not an oversight. No fix is
  proposed here; listed for traceability since it was an open item since session 1.
- **If future behavior change is wanted** (e.g. configurable retry/skip-on-failure per step): that
  would be a new feature request, not a lifecycle bug fix — would need its own severity/benefit/
  complexity assessment against `BotStep`'s JSON schema (adding a per-step policy field) and is out of
  scope for this backlog entry.

## 5. `BotsInterruptWidget.MAX_BOTS` overflow: 10th+ bot runs untracked and uncancellable via the UI

- **Finding:** `capability-bot-lifecycle.md` §"Session 3: `ScenarioManager`/`ScenarioRunner`/
  `BotExecutor`" ("The gap" paragraph)
- **Severity:** Low-Medium — not a crash or data-loss risk (the overflow thread still runs and exits
  normally), but a usability/safety gap: a user who believes "9 bots running, all stoppable" may
  actually have a 10th running with no stop button, discoverable only via thread dumps or unexpected
  game-state changes.
- **Confidence:** CONFIRMED by reading the exact call order (`BotExecutor.runAsync` calls
  `gui.biw.addObserve(t)` unconditionally before `t.start()`; `addObserve` silently skips adding the
  `Gear` when `obs.size() >= MAX_BOTS` but does not prevent or signal back to `runAsync` that the
  thread shouldn't start) — not reproduced live (no test run with 10 concurrent bots).
- **Benefit:** Either the 10th+ bot is refused outright (never started) or it's still tracked/stoppable
  — either closes a real gap between what the UI implies (a hard cap) and what the code does (a soft,
  leaky cap).
- **Complexity:** Low — the fix is local to `BotsInterruptWidget.addObserve`/`BotExecutor.runAsync`'s
  call contract: either make `addObserve` return a boolean the caller checks before `t.start()`, or
  raise/remove the cap's silent-skip behavior. Does not require touching `ScenarioRunner`, `NTask`, or
  any bot implementation.
- **Upstream merge risk:** Unknown — `BotsInterruptWidget.java`'s fork/upstream diff status was not
  checked in this session (out of scope: it's a UI widget, not one of the 5 lifecycle classes or the
  bot/task implementation layer covered by `upstream-compatibility.md`). Check before implementing.
- **Suggested test:** start 10 (or `MAX_BOTS + 1`) no-op/stub `Action`s via `BotExecutor.runAsync` in a
  headless test harness and assert either (a) the 10th never starts, or (b) the 10th gets a `Gear`/stop
  control like the first 9 — whichever behavior is chosen as the fix.

## Explicitly not backlogged (bot lifecycle)

- **`FAIL()`/`ERROR(null)` redundancy** (session 1, confirmed behaviorally identical) — cosmetic, no
  behavior difference, no severity. Worth collapsing only incidentally if `Results.java` is touched for
  item 1 above, not a standalone backlog item.
- **`BotRegistry.java`'s 150-commit upstream churn** — not a risk requiring action; it's an append-only
  file that already auto-merges cleanly and has its own regression test
  (`test/nurgling/actions/bots/registry/SyncRegistryInventoryTest.java`). Listed in
  `upstream-compatibility.md` §2 for context, not as a backlog item.

## Workstation & production automation capability area (session 1)

## 6. Baker bot: finished pies can burn because completion is fire-out, not item-doneness

- **Finding:** `capability-workstation-production.md` §"Phase 3: Baker bot investigation"
- **Severity:** High — direct, named product-loss failure mode (burning an already-finished batch),
  the specific defect the user asked this session to prioritize.
- **Confidence:** Upgraded PLAUSIBLE → **WIKI_DOCUMENTED** in session 4. The mechanism is fully traced
  statically (`WaitForBurnout`-only completion, fixed `maxlvl=4` fuel constant, no per-item doneness
  check) and the Ring of Brodgar Oven page states the failure mode directly, in its own words: "If you
  use more branches, then this will turn your dough into ash unless you manually take it out in time"
  (fetched live this session; page revision matches `recipes.bin`'s cited Oven record exactly, so this
  is current, not stale documentation). **Still not VERIFIED_RUNTIME** — no live repro against an
  actual burned batch in this fork's own game session. Note: `SmelterAction`'s documented sibling bug
  (early burnout pulling half-smelted ore via `FreeContainers`) is a *different* failure mode — physical
  removal of an unfinished item, not quality/ash damage — and should not be conflated with the Baker
  mechanism going forward (session 4 correction).
- **Benefit:** Stops finished pies (and, if the shared loop is later de-duplicated per item 7, every
  other bot sharing this pattern) from being destroyed by continued, unneeded heat.
- **Complexity:** Medium. The fix needs a new oven-specific observed-completion signal — e.g. an
  `NOvenInfo` parallel to the existing `NKilnInfo`, reading the same `GItem.MeterInfo` percent
  `NKilnInfo` already reads for kiln items — plus a check inside `BakerAction`'s loop that doesn't yet
  exist. Not a one-line change, but reuses an established pattern rather than inventing one.
- **Upstream merge risk:** `BakerAction.java` is byte-identical to upstream today, 7 commits of
  upstream history, last touched 2026-09-15 — moderate churn, not the highest-risk file in this area.
  The reused pattern (`NKilnInfo`/`KilnFuelCatalog`) is itself very new upstream (1 commit, introduced
  2026-09-08) — stable enough to build on, but not long battle-tested.
- **Recommended shape:** narrow and additive — a new `NOvenInfo`-equivalent plus a loop-condition
  check in `BakerAction` itself; do **not** touch `WaitForBurnout` (shared by 5+ other bots) or
  `Container.java` (the highest-churn file in this area, 30 commits) to implement this.
- **Suggested test:** a test that drives an oven's `GItem.MeterInfo` to 100% (fully baked) while its
  model-attribute fire flag is still set (fuel not yet exhausted), and asserts `BakerAction`'s loop
  unloads/stops heating that oven rather than continuing to wait on `WaitForBurnout`.

## 7. De-duplicate the wait→unload→refill→refuel→relight loop shared by 4+ bots

- **Finding:** `capability-workstation-production.md` §"Phase 3: comparison matrix" + §"Phase 4"
- **Severity:** Low (maintainability, not correctness) on its own — but see item 6: today's
  duplication means a fix like item 6 only protects whichever bot it's applied to, not its siblings,
  unless this de-duplication happens too.
- **Confidence:** CONFIRMED — `BakerAction`/`BricksAction`/`BlockAshAction`/`BoneAshAction` share a
  near-identical loop body, read directly in each file.
- **Benefit:** A single shared helper fixes the completion-detection gap (item 6's class of issue) for
  every bot that adopts it at once, instead of requiring the same fix four separate times; also
  reduces future maintenance surface.
- **Complexity:** Medium-High. Per `capability-workstation-production.md` §"Phase 4," genuine
  differences between workstation types (fuel potency/`fuelmod`, bellows-aware vs plain completion,
  refuel-trigger threshold, `SteelAction`'s non-looping design) mean the shared helper must be
  parameterized, not a one-size-fits-all controller — explicitly **not** recommended as one universal
  workstation abstraction.
- **Upstream merge risk:** Low for the helper itself (new, fork-owned file). Low-to-moderate for the
  per-bot migration call sites: `BakerAction`/`BricksAction` are moderate churn (7-9 commits, last
  touched 2026-09-15); `BlockAshAction`/`BoneAshAction` churn not individually measured this session.
  None of the four are the highest-risk files in this area (`SmelterAction`/`Container.java`), and this
  item does not propose migrating `SmelterAction` (its bellows-aware, credo-threshold behavior is
  more sophisticated than the shared loop and should not be regressed to it).
- **Recommended shape:** new fork-owned shared loop helper, parameterized by completion-signal
  strategy and refuel threshold; migrate the 4 near-identical bots to call it; leave `SmelterAction`
  and `SteelAction` out of the first pass (the former is more advanced than the shared shape, the
  latter's one-shot design is unconfirmed as intentional — see item 8).
- **Suggested test:** one shared test suite against the new helper (completion-signal swap, refuel
  threshold behavior) plus, per migrated bot, a regression test confirming its existing behavior
  (fuel constants, fuel zone, container type) is unchanged after migration.

## 8. Confirm whether `SteelAction`'s single-shot (no wait/unload/loop) design is intentional

- **Finding:** `capability-workstation-production.md` §"Phase 3: comparison matrix" (session 1),
  updated by §"Session 3 §2: TarkilnAction" (session 3).
- **Severity:** Low-Unknown — not confirmed as a defect; flagged purely because it's structurally
  inconsistent with the wait→unload→refill→refuel→relight bots in the same capability area.
- **Confidence:** Upgraded THEORETICAL → **PLAUSIBLE (intentional)**. Session 3 read `TarkilnAction`
  in full and found it shares `SteelAction`'s exact shape (refuel, light, return `SUCCESS`, no wait/
  unload/loop) — two independent bots converging on the same unusual design is stronger evidence of
  intent than one isolated instance. Still not CONFIRMED — no comment, test, or companion-bot evidence
  directly states the intent either way.
- **Benefit:** Either closes the inconsistency (if unintentional) or documents why it's different (if
  intentional), preventing a future session from re-flagging the same question as new.
- **Complexity:** Investigation only until an answer is known.
- **Upstream merge risk:** N/A for the investigation. `SteelAction.java` and `TarkilnAction.java` are
  both byte-identical to upstream (7 and 11 commits respectively, both last touched 2026-09-15).
- **Recommended shape:** ask the user, or check whether a companion "unload steel"/"unload tarkiln"
  bot/scenario step exists elsewhere that would explain the one-shot design, before assuming either
  needs a loop added. Given two independent occurrences, treat this as a recognized pattern
  ("productions that don't need active babysitting") unless contrary evidence appears, rather than
  re-investigating from scratch.
- **Suggested test:** N/A until the intended design is confirmed.

## 9. Quern has a registered hitbox but zero automation path (feature gap, not a bug)

- **Finding:** `capability-workstation-production.md` §"Session 2 ... Phase 1: comprehensive
  discovery" — `gfx/terobjs/quern` appears in `NHitBox.java`'s interaction-footprint registry but has
  no entry in `NContext.workstation_map`/`workstation_spec_map`, no `FuelZones` entry, and no bot/action
  reference found anywhere in `src/nurgling/**`.
- **Severity:** Low — this is a missing capability, not a defect in an existing one; nothing is
  currently broken because nothing currently exists.
- **Confidence:** CONFIRMED that the gap exists (absence verified across all 4 discovery sources in
  that section). Not confirmed whether quern grinding is in-demand enough to be worth building — that's
  a product-priority question, not a technical one.
- **Benefit:** Would extend generic-craft-style automation (per the `Craft.java`/`workstation_map`
  pattern already used for loom/churn/meatgrinder/etc.) to one more hand-operated station.
- **Complexity:** Unknown until scoped — likely similar to adding any other `workstation_map` entry
  (resource name, pose if position-changing, `Specialisation.SpecName` routing), but whether the quern
  is itself position-changing (pose-locked like the loom) or plain-click (like the anvil) was not
  determined this session.
- **Upstream merge risk:** Low for a new fork-owned `workstation_map` entry by itself (additive to a
  file — `NContext.java` — not yet upstream-diffed this session, see session 2's "Not covered"); real
  risk depends on that still-unrun diff.
- **Recommended shape:** not scoped beyond "this is a real gap, worth a feature request" — do not
  start implementation before the session-3 upstream-diff check on `NContext.java` and before
  confirming (via wiki or live observation) how quern interaction actually works server-side.
- **Suggested test:** N/A until scoped.

## 10. No fuel-loop bot selects fuel (or ingredients) by quality, though kiln/smelter quality formulas say it matters

- **Finding:** `capability-workstation-production.md` §"Session 3 §3: Ring of Brodgar wiki research"
  + §"Session 3 §4: quality-dependent production."
- **Severity:** Medium — not a crash/correctness bug, but a confirmed missed-quality opportunity:
  Ring of Brodgar's own cited pages (Brick, Bone Ash, Clay Jar, Clay Pipe, Earthenware Platter, Bar of
  Tin — 6 independently-cited pages sharing the same formula shape) give fuel quality a direct 1-in-4
  weight in the final product's quality for every kiln-fired and smelted good, and no bot
  (`BricksAction`/`BlockAshAction`/`BoneAshAction`/`SmelterAction`/etc.) filters fuel by quality before
  burning it.
- **Confidence:** WIKI_DOCUMENTED for the formulas (sourced, dated, revision-cited, and in session 4
  cross-checked directly against the live wiki page at the same revision for Kiln/Oven/Bar of Steel —
  see the doc's §"Session 4 §3" comparison table); CONFIRMED for the absence of any quality filter in
  the bots' own code (read directly, not inferred). Not VERIFIED_RUNTIME — no live repro comparing a
  high- vs low-quality-fuel batch. Session 4 additionally confirmed the oven/baked-goods family belongs
  in scope (see "Recommended shape" below, corrected from session 3's wrong exclusion), and left
  `Bar of Steel` specifically UNKNOWN (its own wiki page states a formula exists, but it isn't bound in
  `recipes.bin` under either its own record or the Steel Crucible building record — unresolved whether
  that's a parser gap or a wiki-formatting issue).
- **Benefit:** Lets a player who cares about output quality (e.g. selling high-quality bricks/bars)
  actually benefit from stockpiling better fuel, instead of the bot burning whatever fuel item matches
  by name regardless of its quality value.
- **Complexity:** Low-Medium. A directly reusable precedent already exists in this codebase:
  `TakeItemsFromContainer`'s `targetq` threshold parameter. Adding an optional fuel-quality floor to
  the fuel-fetch step of the shared loop helper (item 7, if/when built) is additive, not a redesign.
- **Upstream merge risk:** Depends entirely on where it lands. If built as a new parameter on the
  item-7 shared helper (new, fork-owned file), risk is low. If added directly inside
  `BricksAction`/`BlockAshAction`/etc. individually, inherits those files' own moderate churn
  (session 1: 7-9 commits each).
- **Recommended shape — corrected in session 4:** session 3 recommended excluding `BakerAction`/oven
  fuel from this, citing "the Bread page records no fuel-quality term." **That citation was wrong**
  (session 3 searched `recipes.bin`'s Bread record and not Oven's; the Oven record carries
  `(qDough*2 + qOven + qAvgFuel)/4`, confirmed against the live wiki page at the same revision — see
  `capability-workstation-production.md` §"Session 4 §2"). **`BakerAction`/oven fuel should be
  *included*, not excluded** — the live wiki page additionally states the exact failure mode in prose
  ("more branches... will turn your dough into ash unless you manually take it out in time"),
  directly matching this backlog's own item 6. Scope as an **optional** parameter (default: no filter,
  today's behavior unchanged) on whichever fuel-fetch step the kiln/smelter/oven-family bots use.
- **Suggested test:** a test asserting the fuel-fetch step, when given a quality floor, skips a
  below-floor fuel item in favor of an above-floor one of the same name; a second test asserting the
  default (no floor set) behavior is byte-for-byte unchanged from today.

## 11. Loom, churn, spinning wheel, ropewalk, potter's wheel, and meat grinder have no dedicated bot — generic-craft only

- **Finding:** `capability-workstation-production.md` §"Session 3 §2: Loom / churn / spinning-wheel /
  meatgrinder / ropewalk / potter's wheel."
- **Severity:** Low — not a defect; a confirmed feature gap distinct from the quern gap (item 9) in
  that these six already have a path (the generic `AutocraftBot` + manual recipe configuration), just
  no purpose-built bot the way ovens/kilns/smelters have.
- **Confidence:** CONFIRMED (absence verified by grepping every `NContext.Workstation` construction
  site and every `Specialisation.SpecName` reference for these six stations outside the registry
  definitions themselves).
- **Benefit:** A dedicated bot for one of these (e.g. a loom-weaving bot) would save the manual
  per-recipe `AutocraftBot` configuration step, the same way `BakerAction` saves it for ovens.
- **Complexity:** Unscoped — would need its own investigation per station (e.g. does weaving need a
  wait-for-completion signal beyond the `Following`-attribute pose lock `Craft.java` already provides
  generically; is there anything left to automate once pose-lock + progress-bar completion already
  works via the generic path).
- **Upstream merge risk:** A new, fork-owned bot file is low-risk by construction. Not scoped further.
- **Recommended shape:** not scoped beyond "confirmed gap, lower priority than items 6/9/10" — no
  specific user-reported pain point surfaced this session for any of these six stations.
- **Suggested test:** N/A until scoped.

## 12. No shared retry/timeout primitive across the fuel-loop bots' own ad hoc patterns

- **Finding:** `capability-workstation-production.md` §"Session 4 §4: Autocrafting vs. dedicated bots"
  comparative matrix, "Retry/timeout consistency" row.
- **Severity:** Low (maintainability/consistency, not correctness) — no bot was found to fail from
  this, it's an inconsistency.
- **Confidence:** CONFIRMED. `Craft.craftProc` (generic-craft path) has a single, consistently-applied
  3-retry/tick-timeout mechanism used by every recipe that goes through it. Dedicated fuel-loop bots
  have no equivalent shared primitive: some (`TarkilnAction`) implement their own bespoke bounded-retry
  loop, most don't retry at all and just return `ERROR`/`FAIL`.
- **Benefit:** A shared retry/timeout helper (reusing `Craft.craftProc`'s shape, not necessarily its
  code) would make failure behavior predictable and consistent across the fuel-loop family, and is a
  natural companion to item 7's de-duplication effort rather than a separate piece of work.
- **Complexity:** Medium — folds naturally into item 7's shared-loop-helper design rather than being a
  standalone change.
- **Upstream merge risk:** Same as item 7 (new, fork-owned helper file; low risk by construction).
- **Recommended shape:** fold into item 7's shared helper design rather than implementing separately;
  model the retry/timeout shape on `Craft.craftProc`'s bounded-tick-count approach.
- **Suggested test:** covered by item 7's own suggested test once combined.

## 13. Fuel-loop bots conflate "station stopped burning" with "production complete" — Baker's root cause, generalized

- **Finding:** `capability-workstation-production.md` §"Session 4 §4," "Crafting-action vs. production
  completion, distinguished" row.
- **Severity:** Medium — this is the general pattern underlying backlog item 6 (Baker), stated at the
  capability-area level rather than just for one bot.
- **Confidence:** CONFIRMED as a design pattern (every fuel-loop bot uses fire-out as its sole
  completion signal, read directly in each); PLAUSIBLE as a cross-bot risk (only Baker has
  WIKI_DOCUMENTED evidence of an actual bad outcome from it; the others may never hit a timing window
  where it matters, per each product's own burn-time-vs-production-time ratio, which was not
  individually checked for Bricks/BlockAsh/BoneAsh/Smelter).
- **Benefit:** Naming this as the general root cause (rather than a Baker-specific quirk) means any
  future fix should address "how do we detect production completion independent of fire state," not
  just "fix Baker's fuel constant" — a narrower fix would leave the same latent pattern in the other
  three looped bots.
- **Complexity:** This item is the conceptual frame for items 6 and 7, not a separate implementation
  task — tracked here so it isn't lost as a standalone insight.
- **Upstream merge risk:** N/A (framing, not a change).
- **Recommended shape:** when scoping item 6's `NOvenInfo`-equivalent fix, design it as "a
  production-completion check independent of fire state," reusable by the same shared helper item 7
  proposes, rather than an oven-only patch — even though only Baker has documented evidence of harm
  today.
- **Suggested test:** N/A (covered by items 6/7's own tests once implemented).

## Explicitly not backlogged (workstation & production)

- **`GelatinAction`/`TarkilnAction`/`FriedFish`/`SmokeringBot`'s exact fuel/completion logic** — not
  individually read this session (see `capability-workstation-production.md`'s "Not covered"); nothing
  to backlog until that reading happens.
- **`SmelterAction`/`Container.java` changes of any kind** — explicitly deferred per
  `capability-workstation-production.md` §"Phase 5" given their high upstream churn (31 and 30 commits
  respectively); not backlogged speculatively.
- **Anything from session 2's generic-craft-path discovery beyond items 9-11** — the generic-craft path
  (`Craft.java`/`UseWorkStation.java`/`AutocraftBot`) was found to already have more robust
  completion-detection (progress bar + error signal) than the fuel-loop path, with no identified defect
  of its own; not backlogged speculatively.
- **The two-cauldron-variant distinction** — session 3 resolved this as a non-gap (both build
  paginae construct the same `gfx/terobjs/cauldron` resource; see the capability doc's "Session 3 §2:
  Cauldron variants"), so it is explicitly removed from the backlog rather than carried forward.
- **`Bar of Steel`'s missing wiki quality formula** — documented as an open contradiction in the
  capability doc (§3), not backlogged, since it's a question about the wiki's own data completeness,
  not something fixable in this codebase.
- **The pose-locked-station barrel-interaction question** — still flagged `UNKNOWN`, needs runtime
  observation, not backlogged as a fix until it's known whether anything is actually broken.
- **`SmokeringBot`'s equipped-item production mechanism** — confirmed tangential to the workstation
  capability area (no station gob involved at all); not evaluated for workstation-style modernization.
- **The "server observations supersede wiki amounts" journal/override layer** (session 4) — discovered
  via `tools/wiki-recipes/README.txt`, never traced; not backlogged until it's known whether it changes
  any conclusion already drawn from `recipes.bin`.
- **`Bar of Steel`'s unbound wiki quality formula** — confirmed as a genuine `recipes.bin` extraction
  gap (checked both plausible record locations, session 4), but fixing the fork's own bundled database
  is a data-pipeline question for `tools/wiki-recipes/compile_recipes.py`'s maintainers, not a
  production-code change; not backlogged as a Java fix.

## Navigation, movement, obstacle-awareness, and recovery capability area (sessions 1-2)

## 14. `ChunkNavExecutor` has two divergent implementations of "where do I stand to use this portal"

- **Finding:** `capability-navigation.md` §4a / §12 finding N3.
- **Severity:** Medium — the hitbox-derived `getPortalAccessPoint(Gob)` and the hardcoded
  per-building-name-table `getPortalAccessPoint(ChunkPortal, long, NGameUI)` are reachable from two
  different code paths in the same class (`followDetailedPath`/`tryTraversePortal` vs.
  `followWaypointPath`/`traversePortal`) and can silently drift apart — e.g. a new building type
  added to one building-name list but not the other would behave inconsistently depending on which
  path a given route happens to take.
- **Confidence:** CONFIRMED — both methods read in full, at `ChunkNavExecutor.java` lines 584 and
  1238 respectively.
- **Benefit:** One access-point calculation, one place to fix a wrong offset, and no risk of the two
  paths disagreeing about where to stand for the same building.
- **Complexity:** Medium. The hitbox-derived version is strictly more general (works for any gob with
  a `hitBox`, no hardcoded building list to maintain) but the hardcoded-table version currently
  handles cases the hitbox version doesn't obviously cover (stairs direction sign, windmill's
  off-center door) — merging them needs care, not a blind delete of one.
- **Severity correction (session 2):** downgrade from Medium to **Low**. `ChunkNavExecutor.java`
  read in full this session (`capability-navigation.md` §13.3, finding N13): `followWaypointPath`
  — the only caller of the hardcoded-table overload — is unreachable from any `ChunkPath` any
  current planner produces (`ChunkPath.waypoints` is never populated). The two implementations
  cannot actually drift apart in a user-visible way today because only one of them ever executes.
  This is now primarily a maintenance-hazard finding (a future bugfix could target the dead one),
  not a live inconsistency risk.
- **Upstream merge risk:** `ChunkNavExecutor.java` is **byte-identical to `upstream/master`**, not
  fork-exclusive (session 1 misclassified it by directory — corrected in `capability-navigation.md`
  §14). `src/nurgling/navigation/**` is under active upstream development (18 commits in the 90
  days before this audit, most recent 2 weeks prior) — **High** churn, meaning **Medium-High**
  conflict risk for any local patch to this file specifically. Removing unreachable dead code
  (see below) is lower-risk than editing the live method, since dead code can't semantically
  conflict with upstream's own edits to the same dead code.
- **Recommended shape:** given the severity correction, prefer deleting the unreachable
  `followWaypointPath` call path (including the hardcoded-table `getPortalAccessPoint` overload,
  `ChunkNavIntraPathfinder`, and `navigateToCoord`'s intra-chunk branch — see backlog item 25) over
  merging it into the live method. Only fold the stairs/windmill special-casing into the
  hitbox-derived method if those cases are still needed once the dead branch is confirmed gone.
- **Suggested test:** a test asserting both call paths resolve to the same access point (within
  tolerance) for each building name currently in `getBuildingAccessOffset`'s table, before and after
  the merge.

## 15. Three independent stuck-detection implementations; `WaypointMovementService` never migrated to the shared `StuckDetector`

- **Finding:** `capability-navigation.md` §7 / §12 finding N4.
- **Severity:** Medium — `WaypointMovementService`'s own inline check defaults to blindly resending
  the identical movement command on stall (`waypointRetryOnStuck` defaults `true`), which is exactly
  the "blind retry where the underlying obstacle hasn't changed" pattern this audit's own brief
  warned against recommending — it already exists as shipped default behavior.
- **Confidence:** CONFIRMED — `StuckDetector.java`'s class doc explicitly claims it was "generalized
  out of `WaypointMovementService`'s existing inline check," but `WaypointMovementService.java` (read
  in full) still has its own separate, un-migrated copy of that exact 2.0-second tile-equality check.
- **Benefit:** One detector, consistently used; `WaypointMovementService` would gain `StuckDetector`'s
  existing detection-only contract instead of a second hand-rolled copy, and any future
  improvement to stuck detection would not need to be made twice.
- **Complexity:** Low-Medium. Migrating `WaypointMovementService.processMovementQueue` to call
  `StuckDetector` instead of its inline tile-equality check is mechanical; deciding what
  `waypointRetryOnStuck` should actually do once migrated (still blind-retry? something smarter?) is
  a separate, judgment-requiring design question this backlog item does not resolve.
- **Upstream merge risk:** Both files are **byte-identical to `upstream/master`**, not fork-exclusive
  (corrected in `capability-navigation.md` §14). Low-Medium conflict risk in practice: neither file
  is in the high-churn set (`ChunkNav`/`PathFinder`'s `src/nurgling/pf/**`) that saw changes in the
  90 days before this audit, but both are still upstream-owned code, so a local-only fix diverges
  from a file upstream could touch at any time.
- **Recommended shape:** migrate `WaypointMovementService` to `StuckDetector` for detection only, as
  a standalone change; leave the `waypointRetryOnStuck` blind-retry-vs-drop-queue behavior itself
  alone unless separately requested — this item is about removing the duplicate detector, not about
  redesigning the retry policy.
- **Suggested test:** a test driving `WaypointMovementService` with a stationary mock player position
  and asserting the same stuck-triggered behavior (retry or clear, per current config) fires at the
  same ~2.0s threshold after migration as before.

## 16. `FollowRoute` returns unconditional success regardless of how many waypoints failed along the way

- **Finding:** `capability-navigation.md` §5 / §12 finding N5.
- **Severity:** Medium — a caller that only checks the returned `Results` cannot distinguish "walked
  the whole route" from "skipped every single waypoint and arrived nowhere useful"; the richer
  per-waypoint outcome (`gui.activeBotFailedWaypoints`) exists but isn't part of the contract a
  caller actually receives.
- **Confidence:** CONFIRMED — `FollowRoute.java` read in full; the final `return Results.SUCCESS()`
  after the section loop is unconditional regardless of `gui.activeBotFailedWaypoints`'s contents.
- **Benefit:** A caller (or a user watching a Scenario chain this bot into) could react to "route
  substantially failed" instead of only ever seeing success.
- **Complexity:** Low — the information already exists (`gui.activeBotFailedWaypoints`); this is
  about surfacing it in the returned `Results`, e.g. returning `Results.ERROR(...)` (or a distinct
  partial-success signal, if one is introduced) when the failed-waypoint count crosses some
  threshold, rather than always `SUCCESS()`.
- **Upstream merge risk:** `FollowRoute.java` is **byte-identical to `upstream/master`**, not
  fork-exclusive (corrected in `capability-navigation.md` §14). Not in the highest-churn file set,
  but it's upstream-owned Forager-support code and upstream is actively developing `ChunkNav`
  specifically in service of Forager (§14) — a plausible future upstream touch, so Low-Medium risk,
  not Low.
- **Recommended shape:** narrow, in `FollowRoute.run`'s own final return — compare
  `gui.activeBotFailedWaypoints.size()` against the route's waypoint count (or simply "any failures
  at all," pending a product decision on the right threshold) before deciding what `Results` to
  return. Do not change the per-waypoint skip-and-continue behavior itself, only what's reported
  afterward.
- **Suggested test:** a route with a deliberately unreachable waypoint in the middle; assert the
  route still completes (current skip-and-continue behavior preserved) but the returned `Results` now
  reflects the partial failure.

## 17. Forager is the only caller of `PathFinder.avoidZones` (dynamic obstacle avoidance) in the whole codebase

- **Finding:** `capability-navigation.md` §5/§9 / §12 finding N6.
- **Severity:** Low-Medium — not a defect (nothing is broken), but a confirmed missed-reuse
  opportunity: `PathFinder.avoidZones` and `ForagerRouteConstraints`'s live-animal-tracking zone
  generation are generic, reusable mechanisms that every other bot walking through territory with
  dangerous animals gets zero benefit from.
- **Confidence:** CONFIRMED — `PathFinder.java`'s own source comment states `avoidZones` is "null for
  every bot but Forager"; independently confirmed by this session's registry-wide read finding no
  other `PathFinder.avoidZones` assignment anywhere.
- **Benefit:** Any bot that walks player-controlled distances through open territory (not just
  Forager's foraging routes) could gain the same live dangerous-animal avoidance for comparatively
  little new code, by constructing its own `ForagerRouteConstraints`-style zone list and setting
  `PathFinder.avoidZones` before calling `run()`.
- **Complexity:** Medium — `ForagerRouteConstraints` itself is Forager-coupled (reads `ForagerPath`-
  specific fields); extracting its animal-zone-generation logic (`dangerZones`, `forEachThreat`) into
  a standalone, Forager-independent helper is a real refactor, not a one-line reuse.
- **Upstream merge risk:** Both files are **byte-identical to `upstream/master`**, not
  fork-exclusive (corrected in `capability-navigation.md` §14). `PathFinder.java` is in the
  highest-churn file set (134 commits total, most recently 2026-10-22, the same day upstream added
  the still-unused `blockCliffBuffer`/cliff-blocking option — `PathFinder.java` is clearly still
  being actively extended upstream) — **Medium-High** risk for any local edit to it specifically.
  `ForagerRouteConstraints.java` is lower-churn but still upstream-owned.
- **Recommended shape:** extract `dangerZones`/`forEachThreat` (the animal-scanning and zone-building
  logic, not the route-specific leash/corridor checks) into a standalone helper taking just an
  `NGameUI` and the Ring Settings animal-radius list, callable by any bot; leave
  `ForagerRouteConstraints` as a thin wrapper over it for Forager's own additional constraints.
- **Suggested test:** a test constructing the extracted helper's zone list from a mock animal `Gob`
  and asserting it matches what `ForagerRouteConstraints.dangerZones` currently produces for the same
  input, before and after the extraction.

## 18. `useGlobalPf` Navigation setting is presented as general but wired into exactly one narrow scenario; `useHFinGlobalPF` has zero consumers anywhere

- **Finding:** `capability-navigation.md` §9/§11 / §12 finding N9.
- **Severity:** Low-Medium — not a crash risk, but a user-facing correctness-of-expectation issue
  (the Navigation settings panel presents "Use Global Pathfinder" as a general pathfinding
  preference under a "Pathfinding" section) and a straightforward dead-code finding
  (`useHFinGlobalPF`).
- **Confidence:** CONFIRMED — both halves independently verified by grepping the entire `src/` tree:
  `useGlobalPf` is read only by `FreeContainersInUnboxZone.java` at two call sites (both gating the
  same "pile gob not currently loaded" scenario); `useHFinGlobalPF` appears only in `NConfig.java`'s
  key-enum declaration and its default-value assignment, with no reader anywhere.
- **Benefit:** Removing `useHFinGlobalPF` removes a config key that misleads a future maintainer into
  thinking it does something. Clarifying or expanding `useGlobalPf`'s scope (pending a product
  decision on which it should be) resolves the gap between what the settings panel promises and what
  the code actually does.
- **Complexity:** Trivial for removing the dead key. Unscoped for `useGlobalPf` itself — expanding it
  to actually gate general `PathFinder`-vs-`ChunkNav` selection across more bots is a real design
  question (which bots, under what conditions) not resolved by this backlog item.
- **Upstream merge risk:** `NConfig.java`, `Navigation.java`, and `FreeContainersInUnboxZone.java`
  are all **byte-identical to `upstream/master`**, not fork-exclusive (corrected in
  `capability-navigation.md` §14). None are in the highest-churn set — Low-Medium risk. Note
  `fork-maintenance-backlog.md` already separately tracks a `FreeContainersInUnboxZone` rewrite
  as a known deferred problem — coordinate with that item if both are picked up, since they touch the
  same file.
- **Resolved (session 2):** read both `FreeContainersInUnboxZone.java` call sites in full
  (`capability-navigation.md` §13.4, finding N15) — the narrow scope reads as an incomplete or
  abandoned migration (both sit directly above a commented-out `RoutePointNavigator` call), not
  deliberate design. No longer an open question blocking a decision.
- **Recommended shape:** remove `useHFinGlobalPF` outright (dead key, no behavior to preserve) in its
  own trivial change. For `useGlobalPf`, given the migration-in-progress evidence, either finish the
  migration (restore/replace the commented-out `RoutePointNavigator` call with a working equivalent,
  so the setting's one gated behavior actually does something by default) or remove the flag and its
  gated branch entirely if the unbox-area walk-back is no longer wanted — don't leave it half-wired.
- **Suggested test:** for the `useHFinGlobalPF` removal, confirm `ant test`/`ant jar` still pass with
  no reference to the removed key anywhere (a grep-based check, not a behavioral test, since nothing
  ever read it). No test proposed for `useGlobalPf` until its intended scope is decided.

## 19. `GoTo`'s vehicle-mode wait dispatch is a hardcoded name-keyed if/else chain

- **Finding:** `capability-navigation.md` §2 / §12 finding N1.
- **Severity:** Low — not a defect (every currently-supported vehicle type works), but a
  maintainability/consistency gap: adding a new mount or vehicle type requires editing `GoTo.run`
  directly rather than registering a new entry somewhere.
- **Confidence:** CONFIRMED — `GoTo.java` read in full; the dispatch is a literal
  `if (NParser.isIt(gob, new NAlias("horse")))`/`else if (... "dugout")`/... chain, six vehicle types
  deep, each pairing its own `IsPoseMov`/`IsNotPose` (or `IsMovingBySpeed`/`MovingCompletedBySpeed`
  for `snekkja`) task pair.
- **Benefit:** A small lookup table (vehicle alias → pose-task-pair constructor) would make adding a
  seventh vehicle type additive instead of requiring an edit to this specific method's control flow.
- **Complexity:** Low-Medium — mechanical to extract into a table, but needs care that the `snekkja`
  case (which uses a distinct `IsMovingBySpeed`/`MovingCompletedBySpeed` pair, not
  `IsPoseMov`/`IsNotPose`) is represented correctly, not flattened incorrectly into the other five's
  shape.
- **Upstream merge risk:** `GoTo.java` is **byte-identical to `upstream/master`**, not fork-exclusive
  (corrected in `capability-navigation.md` §14). Not in the highest-churn file set — Low-Medium risk.
- **Recommended shape:** a small static registry (alias → factory for the appropriate task pair),
  consulted before falling back to the default on-foot `IsMoving`/`MovingCompleted` pair — purely a
  refactor of existing dispatch, not a behavior change for any of the six already-supported types.
- **Suggested test:** one test per currently-supported vehicle type asserting the same task pair is
  selected after refactoring as before (a mapping/equality check, not a live movement test).

## 20. `PatrolArea` has zero obstacle awareness and duplicates its own stuck/arrival poll four times

- **Finding:** `capability-navigation.md` §6 / §12 finding N8.
- **Severity:** Low-Medium — no `PathFinder`/`GoTo` integration at all means this bot will drive its
  vehicle straight into anything in its rectangular patrol pattern's path with no detection or
  recovery; severity depends on how exposed the patrol area typically is (not assessed this session).
- **Confidence:** CONFIRMED — `PatrolArea.java` read in full; every movement is a direct
  `gui.map.wdgmsg("click",...)`, and the same `count++ > 200 && !pose.contains("walking")`-or-
  `dist <= 1` anonymous-`NTask` shape appears four separate times in one method body.
- **Benefit:** Reusing `GoTo`/`PathFinder` would give this bot the same obstacle-awareness and
  stall-recovery behavior every other movement-based bot in the registry already gets for free, and
  collapsing the four duplicated polls into one shared helper removes a four-way copy-paste
  maintenance hazard within the file itself.
- **Complexity:** Medium — this is the oldest-feeling, most structurally different bot in the
  registry; replacing raw clicks with `GoTo`/`PathFinder` calls is more than a mechanical refactor
  since it changes how arrival/stuck conditions are detected, not just how movement is issued.
- **Upstream merge risk:** `PatrolArea.java` is **byte-identical to `upstream/master`**, not
  fork-exclusive (corrected in `capability-navigation.md` §14). Not in the highest-churn file set —
  Low-Medium risk.
- **Recommended shape:** two separable changes, either independently valuable: (a) collapse the four
  duplicated inline polls into one shared private helper within the file (pure deduplication, zero
  behavior change, very low risk); (b) separately, and with more care, consider migrating movement
  itself to `GoTo`/`PathFinder` for real obstacle awareness — don't bundle (a) and (b) into one change.
- **Suggested test:** for (a), none needed beyond `ant test`/`ant jar` passing (behavior-preserving
  refactor). For (b), a test confirming the patrol pattern still visits the same grid of positions
  after migration, plus a manual in-client check that an obstacle placed in the pattern's path is now
  routed around instead of walked into.

## 21. The "bridge to `ChunkNav` when the local grid isn't loaded" pattern is implemented independently twice, and both copies are missing replan resilience the codebase's own canonical pattern already has

- **Finding:** `capability-navigation.md` §5 / §12 finding N7, deepened in session 2 by N12 and N16
  (`capability-navigation.md` §13.2, §13.5).
- **Severity:** **Raised from Low to Medium in session 2.** This is still a duplication finding, but
  session 2 traced what the duplication actually costs: both `FollowRoute.bridgeToRoute` and
  `StorageNav.navigateToContainer` call `ChunkNavManager.navigateWithPath(path, null, gui)` — area
  parameter `null`. `ChunkNavExecutor.replanAndContinue()` unconditionally fails when
  `targetArea == null`, so a single failed segment/portal/cross-layer-walk during either of these
  bridges aborts the whole `ChunkNav` traversal with **zero** of the `MAX_REPLAN_ATTEMPTS = 3`
  budget actually usable, even though the code path that checks that budget runs normally. This
  is a real reliability gap, not just a style duplication (see backlog item 23, which targets the
  underlying null-check gap directly — this item is about the duplication; item 23 is about the
  defect the duplication happens to also hide).
- **Confidence:** CONFIRMED — `FollowRoute.bridgeToRoute`, `StorageNav.navigateToContainer`, and
  `ChunkNavExecutor.replanAndContinue` all read in full; the `area = null` call pattern and the
  immediate-fail branch are a direct source citation, not an inference.
- **Benefit:** A single shared helper removes the duplication *and*, if it routes through (or is
  given) a non-null area the way `NUtils.navigateToArea`'s existing dispatcher already does (N16,
  `capability-navigation.md` §13.5), would also close the replan-resilience gap as a side effect of
  consolidating — one fix instead of two.
- **Complexity:** Low-Medium — the two existing implementations are structurally similar; unifying
  them is mechanical. Fixing the resilience gap at the same time requires deciding what a
  "synthetic area" or an area-free replan path should look like, which is a small design decision,
  not a large one (see item 23's recommended shape for the more isolated fix of just that gap).
- **Upstream merge risk:** `FollowRoute.java` and `StorageNav.java` are **byte-identical to
  `upstream/master`**, not fork-exclusive (corrected in `capability-navigation.md` §14). Neither is
  in the highest-churn set, but `StorageNav`/`FollowRoute` sit adjacent to the actively-developed
  `ChunkNav`/Forager surface — Low-Medium risk.
- **Recommended shape:** extract a shared helper (e.g. on `ChunkNavManager` itself, or a small
  standalone utility) taking a gridId + local coordinate, modeled on `NUtils.navigateToArea`'s
  existing "local-PF-first, `ChunkNav`-fallback" pattern (N16) rather than inventing a new one;
  migrate both call sites to it. Coordinate with item 23 so the null-area replan gap is fixed once,
  in the new shared helper, rather than once per migrated caller.
- **Suggested test:** a test confirming both `FollowRoute` and `StorageNav` still produce the same
  navigation outcome (reach the target, or fail identically) for both the "grid loaded" and "grid not
  loaded" cases after migrating to the shared helper, plus a test that a simulated mid-journey
  segment failure now triggers a replan instead of an immediate abort (the behavior item 23 also
  tests for, in isolation).

## 22. `ChunkNavManager`'s background recording runs unconditionally for every session, not gated by its own "opt-in" overlay flag

- **Finding:** `capability-navigation.md` §10/§11 / §12 finding N10.
- **Severity:** Low-Medium — not a correctness bug, but a confirmed steady-state background cost
  (throttled grid recording every 2s, portal-display refresh every 2s, disk saves every 60s) paid by
  every player every session, including players who never open a `ChunkNav`-aware window or bot;
  magnitude not measured.
- **Confidence:** CONFIRMED — `NMapView.java` read at the relevant lines: `chunkNavManager = new
  ChunkNavManager()` and `chunkNavManager.initialize(genus)` are unconditional; `chunkNavManager.tick()`
  is called every map-view tick with no `chunkNavOverlay` (or any other config) guard around it.
- **Benefit:** If the background cost turns out to be measurable (not yet profiled), gating recording
  itself behind a real opt-in (distinct from the purely-visual `chunkNavOverlay` flag) would let
  players who never use `ChunkNav`-dependent bots avoid paying for it. Independent of that, correcting
  `land-navigation-research.md`'s "default off" characterization (that document describes the
  recording layer as opt-in, which this session's direct source reading shows is only true of the
  overlay) prevents a future session from re-trusting the stale characterization.
- **Complexity:** Unscoped for an actual recording gate — would need to decide what breaks if
  recording is sometimes off (does `StorageNav`/`FollowRoute.bridgeToRoute`/the Navigator window
  degrade gracefully with an empty graph, or do they currently assume recording has always been
  running). Trivial for the documentation correction alone.
- **Documentation fix: done (session 2).** `land-navigation-research.md` §2's characterization has
  been corrected in place to describe the recorder as always-on and the overlay as the only opt-in
  piece, with a pointer to `capability-navigation.md` §11 for the source citation.
- **Upstream merge risk:** `ChunkNavManager.java`/`NMapView.java` are **byte-identical to
  `upstream/master`**, not fork-exclusive (corrected in `capability-navigation.md` §14).
  `ChunkNavManager.java` is in the actively-developed `src/nurgling/navigation/**` set (18 commits
  in the 90 days before this audit) — **Medium-High** risk for any local edit to its tick/recording
  logic specifically. `NMapView.java` was not separately churn-checked.
- **Recommended shape:** the documentation fix above is complete. For an actual recording gate:
  only pursue it if profiling (not yet done, see `capability-navigation.md` §13's "Remaining
  runtime work" item 4) shows a real cost — and confirm first whether any current feature (`StorageNav`,
  `FollowRoute.bridgeToRoute`, the Navigator window) silently depends on recording always being on,
  since none of those were checked for that assumption this session.
- **Suggested test:** N/A for the doc fix. For a future recording gate: a test asserting
  `StorageNav`/`FollowRoute.bridgeToRoute`/etc. fail gracefully (not with an NPE or silent wrong
  answer) when queried against an intentionally-empty `ChunkNavGraph`, before gating recording behind
  an opt-in.

## 23. `ChunkNavExecutor.replanAndContinue()` fails immediately instead of replanning when the executor has no target area

- **Finding:** `capability-navigation.md` §13.2 / §12 finding N12.
- **Severity:** **High** — this is the most concrete reliability defect found across either
  navigation session. `replanAndContinue()` returns `Results.FAIL()` immediately when
  `targetArea == null`, before calling `forceRecordVisibleGrids()` or attempting a replan. Both
  callers that plan via grid-coordinate rather than area — `FollowRoute.bridgeToRoute` and
  `StorageNav.navigateToContainer` — pass `area = null`, so for those two callers the entire
  `MAX_REPLAN_ATTEMPTS = 3` budget is unusable: a single failed segment, portal, or cross-layer walk
  ends the traversal, even though the surrounding code explicitly checks the attempt counter first
  and looks like it's about to retry.
- **Confidence:** CONFIRMED — `ChunkNavExecutor.replanAndContinue`, `FollowRoute.bridgeToRoute`, and
  `StorageNav.navigateToContainer` all read in full; the `area == null` guard and both callers'
  `null` argument are direct source citations.
- **Benefit:** Restores the replan resilience the code already budgets for but silently can't use,
  for exactly the two callers (Forager route-bridging, storage-container walks) most likely to hit a
  transient mid-journey failure (a closed gate, a moved obstacle) given they're walking to
  not-currently-loaded, possibly-stale-recorded destinations.
- **Complexity:** Low-Medium. The cleanest fix is for `replanAndContinue` to re-plan via
  `ChunkNavPlanner.planToGridCoord`/`planToCoord` (the same entry points the `null`-area callers
  used originally) when `targetArea == null`, instead of unconditionally failing — this requires
  threading the original grid-coordinate target through to the executor (it currently isn't stored
  anywhere once `targetArea` is null), which is a small constructor/field addition, not a redesign.
- **Upstream merge risk:** `ChunkNavExecutor.java` is **byte-identical to `upstream/master`**, not
  fork-exclusive (`capability-navigation.md` §14) and sits in the actively-developed
  `src/nurgling/navigation/**` set (18 commits in the 90 days before this audit) — **Medium-High**
  conflict risk. Given the severity, consider contributing this fix upstream rather than patching
  the fork alone, since upstream almost certainly has the same gap (the files are identical) and a
  fork-only fix will need re-applying at every future sync.
- **Recommended shape:** add a nullable grid-coordinate target field to `ChunkNavExecutor`
  (populated by the two `null`-area callers instead of passing `null`), and have
  `replanAndContinue` branch on "replan via area" vs. "replan via grid-coordinate" rather than
  failing outright in the second case. Do not change the area-based path's existing behavior.
- **Suggested test:** a test that constructs a `ChunkNavExecutor` with a grid-coordinate target
  (mirroring `FollowRoute`/`StorageNav`'s call pattern), forces a segment/portal failure, and asserts
  a replan attempt actually occurs (not an immediate `FAIL`) up to `MAX_REPLAN_ATTEMPTS` times.

## 24. `ChunkNavData`'s confidence decay is computed but never consumed by the live path planner

- **Finding:** `capability-navigation.md` §13.1 / §12 finding N11.
- **Severity:** Medium — not a crash or an incorrect-route risk by itself, but it means the system
  cannot currently distinguish a chunk recorded a minute ago from one recorded weeks ago (past the
  48-hour confidence half-life) when choosing a route. `ChunkNavConfig.UNCERTAINTY_PENALTY` exists
  specifically to let low-confidence chunks cost more in path selection, so the gap is between
  designed-for and actual behavior, not a missing feature nobody anticipated.
- **Confidence:** CONFIRMED — `ChunkNavData.getCurrentConfidence()`, `ChunkNavConfig.UNCERTAINTY_PENALTY`,
  and `ChunkNavGraph.calculateEdgeCost()` (the only place that reads both together) all read in
  full; `calculateEdgeCost()`'s only caller, `ChunkNavGraph.getEdges()`, confirmed via exhaustive
  grep to have zero callers anywhere in `src/`. The live planning path
  (`ChunkNavPlanner` → `UnifiedTilePathfinder.findPath()`) was independently confirmed to never
  read `.confidence`/`getCurrentConfidence()` at all.
- **Benefit:** Wiring confidence into `UnifiedTilePathfinder`'s per-tile or per-chunk-crossing cost
  would let the planner prefer a route through recently-verified chunks over a route through
  stale-but-technically-recorded ones when both exist — directly relevant to "how reliable is
  navigation when cached info is outdated," one of the user's named priority questions for this
  capability area.
- **Complexity:** Medium. `UnifiedTilePathfinder.moveCost()`/`heuristic()` would need a confidence
  term added per the existing `UNCERTAINTY_PENALTY` constant's intent; care is needed that this
  doesn't make the heuristic inadmissible in a new way (the cross-layer heuristic already has an
  unverified admissibility question, `capability-navigation.md` §13.1) or regress the common case
  where confidence is uniformly high (most-recently-recorded chunks, which is the typical case for
  an actively-played area).
- **Upstream merge risk:** `ChunkNavGraph.java`/`ChunkNavData.java`/`UnifiedTilePathfinder.java` are
  all **byte-identical to `upstream/master`**, not fork-exclusive, and in the actively-developed
  `src/nurgling/navigation/**` set — **Medium-High** risk. As with item 23, this looks like a gap
  upstream also has (identical code), so upstreaming a fix is the lower-conflict path if the
  benefit justifies the effort.
- **Recommended shape:** either (a) wire confidence into `UnifiedTilePathfinder` directly (duplicate
  the intent of `calculateEdgeCost()` at tile granularity, since the chunk-level version is dead
  code — see item 25), or (b) if (a) proves too invasive, as a smaller first step have
  `ChunkNavPlanner` simply refuse (or heavily deprioritize) routing through chunks whose confidence
  has decayed below some threshold, forcing a `forceRecordVisibleGrids()`-driven re-verification
  instead of blindly trusting old data. Needs the runtime experiment in
  `capability-navigation.md` §13 ("Remaining runtime work" item 1) before committing to either
  shape, to confirm the live-local-`PathFinder`-fallback-during-execution doesn't already mask the
  problem often enough that the fix's benefit is marginal.
- **Suggested test:** a test with two chunks of identical walkability but artificially different
  `lastUpdated` timestamps, asserting the planner prefers the fresher one once confidence is wired
  in (currently would show no preference either way).

## 25. Dead code: `followWaypointPath`, `ChunkNavIntraPathfinder`, and `ChunkNavGraph`'s legacy edge-cost methods are unreachable under current planner wiring

- **Finding:** `capability-navigation.md` §13.3 / §12 findings N13, N14.
- **Severity:** Low (no runtime behavior is affected — this is dead code, not a live defect) but
  worth prioritizing for cleanup specifically *because* of its interaction with item 14: a future
  contributor fixing a reported portal-access bug has roughly even odds of editing the
  implementation that never executes, since nothing in the source currently flags one path as live
  and the other as dead.
- **Confidence:** CONFIRMED via direct data-flow trace, not inference — `ChunkPath.waypoints` is
  never written anywhere in `src/nurgling/navigation/**` (grep for `.waypoints.add`/`.waypoints =`
  returns zero matches), so `ChunkPath.isEmpty()` (which checks `waypoints.isEmpty()`) is always
  `true`, so `ChunkNavExecutor.run()`'s `!path.isEmpty()` branch (the only entry to
  `followWaypointPath()`) can never be taken. `ChunkNavGraph.getEdges()`/`ChunkEdge`/
  `calculateEdgeCost()` confirmed to have zero callers anywhere in `src/` by exhaustive grep.
- **Benefit:** Removing confirmed-dead code removes the maintenance hazard described in item 14 and
  reduces the surface a future reader (fork-side or upstream) has to understand when reasoning about
  how portal access points or chunk-edge costs are actually computed.
- **Complexity:** Low for the removal itself (nothing currently calls any of it, so deletion cannot
  regress a live caller) — but see the verification step below before actually deleting, since this
  session's reachability trace covers `src/nurgling/navigation/**` and its direct callers, not every
  file in the wider tree that could in principle construct a `ChunkPath` by hand.
- **Upstream merge risk:** `ChunkNavExecutor.java`/`ChunkNavGraph.java`/`ChunkNavIntraPathfinder.java`
  are all **byte-identical to `upstream/master`**, not fork-exclusive, and in the actively-developed
  `src/nurgling/navigation/**` set — **Medium-High** risk for a fork-only deletion, since upstream
  could still be touching these exact dead methods for its own reasons not visible from this fork's
  history alone. **This is a strong candidate to propose upstream rather than delete fork-side**,
  precisely because the dead-code status is a property of the shared code, not of fork-specific
  usage.
- **Recommended shape:** do not delete speculatively. First run the verification step in
  `capability-navigation.md` §13 ("Remaining runtime work" item 3) — confirm nothing outside this
  audit's grep scope ever constructs a `ChunkPath` with `waypoints` populated — then remove
  `followWaypointPath`, `ChunkNavIntraPathfinder`, `navigateToCoord`'s intra-chunk branch, the
  hardcoded-table `getPortalAccessPoint(ChunkPortal, long, NGameUI)` overload (superseded by item
  14's live hitbox-derived method), and `ChunkNavGraph.getEdges()`/`ChunkEdge`/`calculateEdgeCost()`
  together, in one change, with a clear commit message explaining the reachability proof (so a
  future upstream sync's three-way merge has context if it conflicts).
- **Suggested test:** `ant test`/`ant jar` passing with the removed code gone is the primary signal
  (nothing currently exercises it, so no behavioral test can regress); additionally, a static check
  (even a simple grep run in CI) asserting `ChunkPath.waypoints` is never written would catch a
  future regression back into this same dead-code shape.

## Explicitly not backlogged (navigation)

- **The cliff-specific group (`CliffScan`/`CliffAwareMove`/`CliffCalibrate`/`DynamicPf.cliffAware`)**
  — confirmed still absent from current `master` (independently re-checked via grep in session 1,
  `capability-navigation.md` §1); already tracked as snapshot-only recovery material in
  `land-navigation-research.md` and `mixed-wip-recovery.md`, not duplicated here.
- **`Graph.java`'s `LinkedList`-resorted-every-pop open set** — already tracked as
  `perf/pathfinder-priority-queue` in `mixed-wip-recovery.md` and referenced by
  `land-navigation-research.md` §10; `capability-navigation.md` §2/§10 only cross-references it
  (confirming it backs every system in the navigation registry, not just the land-explorer
  proposal), not a new backlog item. Session 2 confirmed `UnifiedTilePathfinder` (the actual live
  `ChunkNav` planner) does **not** share this issue — it already uses a correct `PriorityQueue`.
- **`ChunkNavBinaryFormat`/`ChunkNavFileStore`** — still DISCOVERED only after session 2
  (persistence serialization, lowest-priority remaining gap in the registry); see
  `capability-navigation.md` §16.
- **`UnifiedTilePathfinder`'s cross-layer heuristic admissibility** — session 2 noted (§13.1) that
  the portal-crossing heuristic (`100.0 + (depth-1)*400.0`) is not verified to be admissible, which
  could make A* return a non-optimal (but still valid and terminating) path across layers. Not
  backlogged because it's a possible optimality gap, not a correctness or reliability one — no
  evidence it causes a wrong or failed route, only possibly a longer one.
- **The upstream-Nurgling2 divergence check for the whole navigation registry — done in session 2**,
  resolving the previous "not performed" status: every file in the registry is byte-identical to
  `upstream/master`; see `capability-navigation.md` §14. Every "upstream merge risk" line above now
  reflects that finding plus each file's actual upstream churn, not a directory-based guess.
- **Live/runtime verification of any navigation finding above** — none of items 14-25 were
  reproduced in-client in either session; all are static-code findings. See
  `capability-navigation.md` §13's "Remaining runtime work" for the specific experiments that would
  need to happen before implementing items 23/24/25 — this is intentionally not a blocker on
  closing the source-level discovery phase itself (see closing assessment below).

## Navigation capability area: closing assessment (end of session 2) — CLOSED

**The source-level discovery/modernization-identification phase for this capability area is
closed**, per explicit user confirmation at the end of session 2. Closing the discovery phase does
not mean every finding below has been implemented, every performance hypothesis measured, or every
runtime-only verification performed — those stay open as separately-tracked future work (items
14-25 below, and `capability-navigation.md` §13's "Remaining runtime work"). Closing means: no
further general discovery/coverage-improvement work on this capability area is planned or needed
until a specific implementation or bug surfaces a reason to revisit it. Session 1 did
breadth-first discovery across the full registry; session 2 traced every `ChunkNav` internal file
the user named (`ChunkNavPlanner`, `ChunkNavGraph`, `ChunkNavData`, `ChunkPath`, `ChunkNavRecorder`,
`UnifiedTilePathfinder`) in full, corrected the session-1 upstream-compatibility misclassification,
and answered every one of the user's specific §2 questions (route planning across loaded/unloaded
areas, recorded-terrain representation, reliability under stale/incomplete data, portal/building/
door/stairs/cave/cellar handling, route-invalidation detection, stuck/recovery, replan intelligence
vs. blind retry, arrival/portal-traversal verification, route-invalidation sources, failure
propagation to callers, and background-recording overhead) with direct source citations rather than
inferred from names or comments.

What remains is not "unanswered architecture questions" but **runtime verification of already-
identified findings** (the four items in `capability-navigation.md` §13's "Remaining runtime work"),
a handful of low-value DISCOVERED-only files (persistence serialization, UI/diagnostic windows —
`capability-navigation.md` §16), and the judgment calls this backlog deliberately leaves open
(whether/how to fix items 23-25, which require product decisions this audit doesn't make). None of
those block calling the discovery phase itself done. The highest-priority item for an actual
implementation session, if/when one is scheduled, is **item 23** (the `replanAndContinue` null-area
immediate-fail defect) — it's the one finding in this entire capability area with Severity: High,
a small fix shape, and a concrete reproduction path.
