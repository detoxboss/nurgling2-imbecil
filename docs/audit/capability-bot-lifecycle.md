# Capability registry: bot lifecycle & state machines — sessions 1-3

**Kind:** current (framework contracts) / recovery-in-progress (coverage). Phase 3 of the
modernization audit (see [`README.md`](README.md)'s status). Session 1 covers the **framework
contracts** every bot is built on, read in full and verified, plus a handful of **quantified,
grep-level structural facts** about how consistently bots actually use those contracts. Sessions 2-3
close out the framework-level open questions (the `NTask`/`criticalExit` wait mechanism, the
`Results.ERROR` null-dereference call pattern, and `ScenarioManager`/`ScenarioRunner`/`BotExecutor`'s
failure propagation and concurrency model) and sample the `while(true)` pattern. None of this covers
per-bot behavior for the ~208 registered bots beyond the handful named in each section — see
"Not covered" below for what's still out of scope.

## Framework contracts (read in full, verified)

- **[`Action`](../../src/nurgling/actions/Action.java)** — the entire bot contract:
  `Results run(NGameUI gui) throws InterruptedException`, plus a default `getSupp()` returning an
  empty list (sub-action composition hook). **Nothing else.** No retry, backoff, timeout, or
  state-machine support at this level — every bot hand-rolls its own loop/retry/failure logic
  inside `run()`. This is a design fact, not a bug, but it's the reason behavior is inconsistent
  bot-to-bot (see below).
- **[`Results`](../../src/nurgling/actions/Results.java)** — three static factories:
  - `SUCCESS()` → `isSuccess = true`, no message.
  - `ERROR(msg)` → `isSuccess = false`, and **unconditionally calls
    `NUtils.getGameUI().error(msg)`** when `msg != null` (line 23) to surface the error in the UI.
  - `FAIL()` → `isSuccess = false`, no message. **Verified: behaviorally identical to
    `ERROR(null)`** (same constructor path, same resulting state) — a redundant pair, not a bug,
    but worth collapsing if this file is next touched.
  - **Plausible, not proven:** `ERROR(msg)`'s unconditional `NUtils.getGameUI().error(msg)` call is
    exactly the dereference pattern Qodana's `DataFlowIssue` check flagged **344 times** across the
    bot codebase (`docs/audit/qodana-findings-triage-3a35b8b8a.md` §2) as the single most common
    nullable dereference. If a bot calls `Results.ERROR(...)` at a moment `getGameUI()` can return
    null (e.g. mid-teardown), reporting the failure would itself NPE and swallow the original error
    behind a crash instead of showing it. **Not verified against a concrete repro** — flagging the
    hypothesis, not claiming it fires in practice.
- **[`NTask`](../../src/nurgling/tasks/NTask.java)** — the bounded-wait predicate base class every
  "wait for X" task extends. `baseCheck()` enforces a `maxCounter` (default 200) poll limit —
  **unless `infinite` is true, which it is by default** (`protected boolean infinite = true;`,
  line 22). A subclass must explicitly set `infinite = false` to get a bounded wait at all.
  **Quantified:** of 142 files under `src/nurgling/tasks/`, only **5** (`WaitFreeHand`,
  `ISRemoved`, `FepStateSettled`, `DropOn`, `AnimalIsDead`) set `infinite = false`. The other 137
  (~96%) wait unboundedly by default unless they override `maxCounter`/`infinite` some other way
  not checked here. **Not verified per-task** whether each of those 137 actually relies on some
  other exit path (e.g. `InterruptedException` from an outer timeout) — this is a structural fact
  about the default, not a claim that 137 tasks hang in practice.
- **[`Scenario`](../../src/nurgling/scenarios/Scenario.java) /
  [`BotStep`](../../src/nurgling/scenarios/BotStep.java)** — chained-bot sequences are pure data:
  an ordered list of `BotStep` (just a bot id string + a settings map), JSON-serializable. **No
  retry/failure-propagation policy exists at this data-model level.** What happens when one step's
  `Action.run()` returns `Results.ERROR(...)` or `FAIL()` — does the scenario stop, skip, or retry
  the step? — is decided by whatever executes a `Scenario` (presumably `ScenarioManager`).
  **Not read in this session — open question for session 2, not answered here.**
- **[`BotRegistry`](../../src/nurgling/actions/bots/registry/BotRegistry.java)** — a static list of
  **208** `BotDescriptor` entries (`bots.add(...)` call count, verified), each mapping a string id
  to a `BotDescriptor.BotType`, display name/description, flags, the `Action` class, and a command
  alias. New bots register here by convention (per `CLAUDE.md`) — not classpath-scanned.

## Quantified structural pattern: ad-hoc `while(true)` retry loops

Grep across `src/nurgling/actions/bots/**/*.java` (276 files) for `while\s*\(\s*true\s*\)`:
**46 files (~17%) contain at least one.** None of `src/nurgling/tasks/*.java`'s 142 files do (tasks
are single `check()` predicates, not loops — consistent with the `NTask` contract above).

This confirms the pattern behind the `BoughBee.java` finding in
`qodana-findings-triage-3a35b8b8a.md` (§1.2 — a `while(true)` wrapper whose every internal path
`return`s, so it never actually loops) **is not a one-off** — it's a common idiom across roughly a
sixth of bot implementations. **Only `BoughBee.java` was individually read and confirmed to have
the "wrapper never loops" defect.** The other 45 files were located by grep only, not opened — they
may loop correctly, may share the same defect, or may be legitimate infinite-poll loops exited via
`InterruptedException`/an internal `return`/`break` on a real condition. Characterizing all 46 is
session 2+ work, not done here. Listing them for whoever picks this up next:

```
# re-run to get the current list (grep, not re-embedded here to avoid staleness):
rg -l 'while\s*\(\s*true\s*\)' src/nurgling/actions/bots
```

## Not covered (status after session 3)

- Per-bot behavior review for any of the 208 registered bots beyond `BoughBee` (Qodana triage),
  `Chopper`/`TickBot`/`Dropper`/`MinesweeperMiner` (session 3's `while(true)` sample), and
  `ScenarioRunner` itself.
- ~~`ScenarioManager` — how chained-bot failure actually propagates between steps.~~ **Resolved in
  session 3** — see that section above.
- `BotDescriptor`'s field meanings (type/flags) beyond what's visible in the registry listing.
- ~~Whether the 137 `NTask` subclasses with `infinite = true` actually rely on it safely...~~
  **Resolved in session 2** — the mechanism is safe to cancel, unsafe to leave unattended; see that
  section above. Per-task tracing of which specific tasks would most benefit from bounding remains
  future work (tracked in `docs/audit/modernization-backlog.md` item 2).
- Headless (`-bots` CLI) lifecycle differences, if any — not looked at.
- The `MAX_BOTS = 9` overflow gap (session 3) — not reproduced live, only confirmed by reading code.
- 37 of the 41 upstream-identical `while(true)` files remain grep-only (4 sampled in session 3, no
  defect found in the sample).
- Per-bot-pairing resource contention when multiple bots/scenarios run concurrently — framework
  provides no mutual exclusion; whether any specific pairing is unsafe in practice is unverified.

## Session 2: tracing the `NTask`/`criticalExit` wait mechanism (resolves part of "not covered")

Read in full: `NCore.addTask(NTask)` and the task-polling half of `NCore.tick()`
(`src/nurgling/NCore.java`, ~lines 578–667).

**How a task actually blocks and unblocks, verified:** `addTask` synchronizes on the task object,
checks `task.check()` once immediately (a task already satisfied never calls `wait()` — the comment at
line 626 explains why `Thread.interrupted()` is also checked up front: `wait()` is "the only place that
notices an interrupt"). If not yet satisfied, it registers the task in a shared `tasks` list and calls
**`task.wait()` with no timeout argument** — genuinely unbounded at the `Object.wait()` level regardless
of `infinite`. Two things can wake it:

1. **`NCore.tick()`'s poll loop** calls `task.baseCheck()` once per tick for every registered task.
   For a bounded task (`infinite = false`), once `counter >= maxCounter`, `baseCheck()` sets
   `criticalExit = true` and returns `true` without ever calling the subclass's `check()` again; for an
   unbounded task, `baseCheck()` only returns `true` when the subclass's own `check()` becomes true. Either
   way, a `true` return moves the task to `pending_notify`, which a later tick phase `notify()`s,
   waking the blocked `wait()`.
2. **An external `Thread.interrupt()` on the waiting thread** — e.g. a user stopping a running bot —
   throws a real `InterruptedException` straight out of `wait()`, independent of `infinite`/`criticalExit`
   entirely; `addTask`'s catch block removes the task from both `tasks` and `pending_notify` and
   rethrows.

After `wait()` returns normally (path 1), `addTask` checks `task.criticalExit` and, if set, throws
`TaskCriticalExitException` (a dedicated `InterruptedException` subtype — its own class doc explains why
a plain `isInterrupted()` check can't distinguish "timed out" from "really cancelled" after `wait()`
clears the interrupt flag for both). A caller that only catches `InterruptedException` broadly is
unaffected either way; a caller that needs to tell the two apart catches
`TaskCriticalExitException` first (e.g. `LpAssistantBot.java:272`).

**Verdict on the 137-of-142 unbounded tasks — not a bug, but not free either:**
Thread-safety-wise this is **safe**: the exact same `wait()`/`notify()`/interrupt plumbing backs both
bounded and unbounded tasks, so there's no deadlock or resource leak specific to `infinite = true` —
an outer bot-cancel interrupt always gets through. The real cost is **operational, not structural**: an
unbounded task has no self-timeout. If the real-world condition its `check()` waits for never becomes
true (an unexpected game state, a dialog that doesn't appear, an item that got moved), that bot hangs
indefinitely with no automatic recovery signal — the only way out is a human noticing and manually
stopping it. A bounded task, by contrast, self-reports via `criticalExit`/`TaskCriticalExitException`,
letting the bot's own catch block retry, skip, or abort gracefully (see `LpAssistantBot.java`'s handling
around line 272 for a concrete example of code already built on this). For bots meant to run unattended,
the lack of a self-timeout is the operational risk this finding should be read as — not "a crash
waiting to happen," but "a hang nothing but a person can end."

**Confirms, rather than contradicts, session 1's framing:** this doesn't change the
137/5 count or claim the pattern is a bug; it traces the actual mechanism so "unbounded" has a precise,
verified meaning (safe to cancel, unsafe to leave unattended) instead of being a guess about what
`infinite = true` implies.

## Session 2: `Results.ERROR`'s null-dereference hypothesis — upgraded from "plausible" to "confirmed call-pattern, not confirmed failure"

Session 1 flagged `Results.ERROR(msg)`'s unconditional `NUtils.getGameUI().error(msg)` (no null guard)
as a plausible NPE risk but explicitly did not verify it against a concrete repro. This session adds two
pieces of hard evidence without closing the gap all the way to a reproduced crash:

- **`NUtils.getGameUI()` is confirmed nullable by the codebase's own code**: `getGameUI()` returns
  `(ui != null) ? ui.gui : null` (`NUtils.java:65-68`), and at least one other call site
  (`NUtils.getTickId()`, line 60) already guards it with `if (NUtils.getGameUI() != null)` before use —
  so null-on-teardown is a real, already-anticipated state in this codebase, not a hypothetical.
  `Results.ERROR`'s constructor (`private Results(String msg)`, line 20-24) is the one place in the
  five core lifecycle files that dereferences it unconditionally instead of following that established
  guard idiom.
- **The risky call shape — catching `InterruptedException` and immediately calling `Results.ERROR(msg)`
  with a non-null message — is real and common**, not a one-off: `git grep -c "Results.ERROR("` across
  `src/nurgling/actions/**` returns **196** non-null call sites, and several sit directly inside
  `catch (InterruptedException e)` blocks (e.g. `BlueprintTreePlanter.java:190`, `:443`, `:1117`;
  `PlantTrees.java:151`, `:196`, `:322`, `:385`; `WorldBlueprintEditor.java:398`). An `InterruptedException`
  is exactly the signal a bot sees during cancellation/teardown — the same window where `getGameUI()`
  returning null is plausible. **Still not verified:** whether `getGameUI()` can actually be null at the
  specific moment one of these catch blocks runs (that needs either a reproduced crash log or tracing
  `NUI`/session teardown ordering against bot-thread cancellation, neither done here). What's now
  established is that the dangerous combination — nullable accessor, unconditional dereference, and a
  widespread call shape in exactly the handler that fires during cancellation — all genuinely coexist in
  this codebase; it is not merely a theoretical pairing.

## Session 3: `ScenarioManager`/`ScenarioRunner`/`BotExecutor` — closes the last open framework question

Read in full: `src/nurgling/scenarios/ScenarioManager.java`, `src/nurgling/actions/bots/ScenarioRunner.java`,
`src/nurgling/sessions/BotExecutor.java`, and the relevant sections of
`src/nurgling/widgets/BotsInterruptWidget.java` (`addObserve`/`removeObserve`/`tick`, ~lines 400-555).

**`ScenarioManager` itself is pure data CRUD, not an executor.** It loads/saves a
`Map<Integer, Scenario>` to/from JSON (`NConfig.current.getScenariosPath()`) and has exactly one
behavioral method, `executeScenarioByName`, which looks a scenario up by name and hands it off —
it does not run anything itself and holds no execution state.

**The actual chained-step execution is `ScenarioRunner` (`implements Action`), launched via
`BotExecutor.runAsync`.** `ScenarioRunner.runSteps` (static, also reusable outside `ScenarioManager`)
is a plain sequential loop over `BotStep`s: for each step it resolves the bot via
`BotRegistry.byId(step.getId())`, instantiates it with the step's settings, and runs it. **Verified
failure propagation policy — session 1's open question, now answered:** the loop **aborts on the
first non-success `Results`** (`FAIL()` or `ERROR(...)`) and returns that same `Results` to the
caller; it does not retry, does not skip, and does not run any cleanup step. An unknown bot id in a
step produces `Results.FAIL()` the same way a failed bot would, with a `gui.msg(...)` naming the bad
id. There is no partial-rollback of already-completed steps — by design, since `Action`/`BotStep`
carry no undo contract either (consistent with session 1's finding that `Action` has nothing beyond
`run()`).

**Cancellation is handled one layer up, in `BotExecutor`, not in `ScenarioRunner` itself.**
`runAsync` wraps `action.run(gui)` in `try { ... } catch (InterruptedException e) { gui.msg(name +
": STOPPED"); } finally { ThreadLocalUI.clear(); }`. Since `ScenarioRunner.run()` declares `throws
InterruptedException` and never catches it internally, an interrupt firing inside any step's
`bot.run(gui)` (including `TaskCriticalExitException`, a subtype) propagates straight out of
`ScenarioRunner` and is caught exactly once, at the executor boundary, where it's reported and the
thread-local session binding is torn down. **This is a clean, verified propagation path** — no step
can catch-and-swallow an interrupt without that being a bug in the step's own bot code, since
`ScenarioRunner` itself does nothing that would mask one.

**Session binding, and why concurrent bots/scenarios don't trample each other's "current session":**
`BotExecutor` is documented (`BotExecutor.java:10-18`) and verified by reading as "the ONLY place that
calls `ThreadLocalUI.set/clear`" — every bot/scenario thread it creates captures `NUtils.getUI()` once,
at launch time (`boundUI`), and binds it to that thread via `ThreadLocalUI.set(boundUI)` before
running and `clear()` in a `finally`. This is the mechanism that makes the "owning-session" pattern
documented repeatedly in `fork-customization-ledger.md` actually work for bot threads specifically:
a bot started while session A is foregrounded keeps running against session A even if the user
switches focus to session B mid-run, because the thread's own `ThreadLocalUI` binding, not an ambient
"current session" lookup, is what `NUtils.getUI()` resolves to from inside that thread.

**Multiple concurrent bots/scenarios — verified mechanism, one real gap found:**
Every thread `BotExecutor` starts is registered with `BotsInterruptWidget.addObserve(t)` *before*
`t.start()`, which assigns it a `Gear` UI row with its own stop control; `Gear.stop()` →
`removeObserve(t)` → `t.interrupt()` is the only way a user manually cancels one bot/scenario without
affecting any other — each tracked thread is interrupted individually, not as a group. `tick()` also
polls every tracked thread's `isAlive()` once per frame and auto-removes/destroys the `Gear` for any
thread that finished on its own, resetting the shared `waitBot` flag to `false` once no bots are
tracked — this is the existing "recovery mechanism" for normal completion cleanup, and it is already
in place, not overlooked.

**The gap:** `addObserve` caps tracked bots at `MAX_BOTS = 9` (`BotsInterruptWidget.java:50`). When
the cap is already reached, `addObserve` calls `NUtils.getGameUI().error("Too many running bots!")`
and **does not add a `Gear` for the new thread** — but `BotExecutor.runAsync` calls `addObserve`
*before* `t.start()` unconditionally, so the 10th-and-beyond thread **still starts and runs to
completion regardless of the cap.** It simply runs with no stop control in the UI and is never
tracked by `tick()`'s cleanup pass (though it still exits cleanly on its own — this is a visibility
and cancellability gap, not a leak or a hang). **Not reproduced live** — confirmed by reading the
exact call order in both files, not by running 10 bots at once.

**Resource contention between concurrently-running bots** (e.g. two bots both trying to use the
player's hand/cursor, or both targeting the same container) has **no framework-level mutual
exclusion** — `BotExecutor`/`ScenarioRunner` provide session binding and independent cancellation,
nothing else. Whether that's safe in practice is a per-bot-pairing question the framework doesn't
answer either way; **not investigated further here** (would require picking specific bot pairs and
tracing their actual game-state writes, out of scope for a framework-level pass).

## Session 3: sampling the remaining 45 `while(true)` bot files

Per the sync-history taxonomy in `upstream-compatibility.md` §3, the 41 upstream-identical files are
the ones where a future fix opens *new* fork divergence — so confirming whether `BoughBee`'s
"wrapper never loops" defect generalizes there (as opposed to being a one-off) is the useful question
before spending any fix effort. Sampled 4 of the 41, chosen to span different bot shapes
(a resource-gathering loop, a utility run-forever loop, a drop-and-wait loop, and a tile-solver mining
loop) rather than 4 similar ones:

| File | Loop shape (read in full) | Exit mechanism | Matches `BoughBee`'s defect? |
|---|---|---|---|
| `Chopper.java` (~line 174) | Work-exhaustion: re-scans the source area for logs each pass | `break` when no logs remain or none are reachable (with a `gui.msg` on the latter) | No — every path that doesn't `break` genuinely loops to do more work |
| `TickBot.java` (~line 50) | Run-forever utility: wait for a trigger condition (low HP or full inventory) via an embedded unbounded `NTask`, then drop off | No `break` found — by design, this bot is meant to run until manually stopped | No — this is the "legitimate infinite-poll loop, exited via interrupt" category the session 1 doc already allowed for |
| `Dropper.java` (~line 27) | Run-forever utility: wait for more items via `WaitMoreItems`, then drop unwanted ones | No `break` found — same run-until-stopped shape as `TickBot` | No |
| `MinesweeperMiner.java` (~line 88) | Work-exhaustion: re-solves the minefield each pass for the next safe tile | `break` when no safe tile remains (with a summary `gui.msg`); also an early `Results.ERROR` return if the player reference is lost | No |

**Zero of the 4 sampled files reproduce `BoughBee`'s defect.** All four are one of two legitimate
shapes: a work-exhaustion loop with a real `break` condition, or an intentional run-until-stopped
utility loop that relies on the same interrupt-based cancellation traced above in §"Session 3:
`ScenarioManager`/...". **This is a 4-of-41 sample, not a census** — it narrows the prior "could be
anywhere from 0 to 45 more defects" uncertainty down to "the two obviously legitimate loop shapes
account for at least these 4, and by extension plausibly most of the remaining 37," but does not
rule out a `BoughBee`-style defect existing in one of the other 37. Per the task instruction to use
representative examples rather than trace all 45 absent evidence of a broader problem: **no evidence
from this sample justifies individually tracing the remaining 37** — if a future session wants higher
confidence, sampling a few more from bot categories not yet represented here (battle, farming,
construction) would be the next increment, not a full census.

## Suggested session 4 scope

Both items from the prior "suggested next scope" are now done (`ScenarioManager`/`ScenarioRunner`/
`BotExecutor` traced above; 4 of the 41 upstream-identical `while(true)` files sampled with no
defect found). Remaining open items, in priority order: (a) the `MAX_BOTS` overflow gap found in
§"Session 3: `ScenarioManager`/..." — confirm live whether a 10th bot really starts untracked, or
decide it's low-severity enough to leave as a backlog item without a repro; (b) if higher confidence
on the `while(true)` sweep is wanted, sample a few more files from bot categories not yet represented
(battle, farming, construction) rather than re-sampling resource/utility shapes; (c) per-bot-pairing
resource-contention investigation for specific bots known to run concurrently in practice, if any
user report ever surfaces one. See `docs/audit/modernization-backlog.md` for how these are currently
tracked.
