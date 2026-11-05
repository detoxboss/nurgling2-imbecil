# Modernization backlog: bot lifecycle — Phase 3/4 output (sessions 1-3)

**Kind:** recovery (prioritization of findings already verified in
[`capability-bot-lifecycle.md`](capability-bot-lifecycle.md) and
[`upstream-compatibility.md`](upstream-compatibility.md)). This is a backlog of candidate
improvements, not an implementation plan — **nothing here has been implemented.** Each item names its
own confidence level; don't treat "high confidence" as "verified bug," since none of these were
confirmed against a live repro (see each linked finding's own caveats).

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

## Explicitly not backlogged

- **`FAIL()`/`ERROR(null)` redundancy** (session 1, confirmed behaviorally identical) — cosmetic, no
  behavior difference, no severity. Worth collapsing only incidentally if `Results.java` is touched for
  item 1 above, not a standalone backlog item.
- **`BotRegistry.java`'s 150-commit upstream churn** — not a risk requiring action; it's an append-only
  file that already auto-merges cleanly and has its own regression test
  (`test/nurgling/actions/bots/registry/SyncRegistryInventoryTest.java`). Listed in
  `upstream-compatibility.md` §2 for context, not as a backlog item.
