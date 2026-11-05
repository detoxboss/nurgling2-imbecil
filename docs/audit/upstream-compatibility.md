# Upstream compatibility assessment: bot lifecycle — Phase 4

**Kind:** current (verified against `git diff upstream/master` and `git log upstream/master`,
upstream ref `2ce51342e` per `git status`'s tracked `upstream/master`). Scope: the bot-lifecycle
capability area only (`src/nurgling/actions/**`, `src/nurgling/tasks/**`,
`src/nurgling/scenarios/**`, `src/nurgling/actions/bots/**`), as a Phase 4 companion to
[`capability-bot-lifecycle.md`](capability-bot-lifecycle.md). Every count below is reproducible —
commands are given inline, not just results.

## 1. Fork-specific vs shared: core lifecycle contracts

```
git diff upstream/master -- src/nurgling/actions/Action.java src/nurgling/actions/Results.java \
  src/nurgling/tasks/NTask.java src/nurgling/scenarios/Scenario.java src/nurgling/scenarios/BotStep.java
```

**`Action`, `Results`, `NTask`, `Scenario`, `BotStep` are byte-identical to `upstream/master`.**
The entire bot contract, the `Results` factory methods (including the unconditional
`NUtils.getGameUI().error(msg)` in `ERROR`'s constructor — see §4), and `NTask.baseCheck()`'s
infinite-by-default wait are **not fork-introduced** — they are plain upstream Nurgling2 design, this
fork has never touched them. This matters for scoping any fix: a change to one of these five files is
a change to shared, actively-upstream-maintained code, not fork-only territory.

Only `BotRegistry.java` differs, and the diff is a pure append (new `bots.add(...)` entries for
fork-owned bots, consistent with the "Fork `BotRegistry` registrations" entry in
`docs/fork-customization-ledger.md`) — no edits to existing lines, no restructuring.

## 2. Fork-specific vs shared: the bot/task implementation layer

```powershell
git ls-tree -r --name-only HEAD -- src/nurgling/actions/bots | sort > head.txt
git ls-tree -r --name-only upstream/master -- src/nurgling/actions/bots | sort > up.txt
# then Compare-Object / diff for fork-exclusive vs shared, and git diff per shared file
```

| | `actions/bots/**` (276 files) | `tasks/**` (142 files) |
|---|---|---|
| Fork-exclusive (no upstream counterpart) | 9 | 6 |
| Shared with upstream, present both sides | 267 | 136 |
| …of those, textually modified by the fork | 5 | 2 |
| …of those, byte-identical to upstream | 262 | 134 |

**The overwhelming majority of both layers is plain upstream code the fork has never edited.**
Fork-specific bot/task work is concentrated in a small, named set:

- **Fork-exclusive bots:** `CombatReactorTool`, `CrossingCandidateTracker`, `H4DMarketScanner`,
  `LpAssistantBot`, `OptimizeTableEatingBot`, `VillageWatchBot`, `VillageWatchSettingsAction`,
  `WorldExplorerFrontier`, `WorldExplorerMove`.
- **Fork-exclusive tasks:** `FepStateSettled`, `TaskCriticalExitException`,
  `WaitFirstProgressCycle`, `WaitLpFirstProduct`, `WaitLpProductDiscovered`, `WaitLpSettlement`.
- **Shared files the fork has edited (highest existing divergence, i.e. already-paid merge cost):**

  | File | Diff size vs upstream | Upstream commits on this file | Upstream's last touch |
  |---|---|---|---|
  | `actions/bots/WorldExplorer.java` | +163/-98 (261 lines touched) | 3 | 2026-04-24 |
  | `actions/bots/Fishing.java` | +69/-1 | 9 | 2026-04-28 |
  | `actions/bots/registry/BotRegistry.java` | +38/-1 | 150 | 2026-10-20 |
  | `actions/bots/drinker/DrinkerBot.java` | +30/-5 | 2 | 2026-10-21 |
  | `actions/bots/FFAction.java` | +3/-1 | 10 | 2026-09-15 |
  | `tasks/DropOn.java` | +6/-8 | (not separately counted; low-churn task file) | — |
  | `tasks/WaitNoItems.java` | +37/-3 | (not separately counted; low-churn task file) | — |

  `BotRegistry.java`'s 150-commit churn is expected and low-risk — nearly every upstream commit that
  adds a bot touches it, but as an append, which auto-merges cleanly (confirmed by the ledger's
  2026-10-30 sync note and its registry-count regression test,
  `test/nurgling/actions/bots/registry/SyncRegistryInventoryTest.java`). `Fishing.java` and
  `FFAction.java` are the real risk: upstream edits them often (9–10 commits each) **and** the fork has
  already diverged on both, meaning the next upstream commit to either file has a materially higher
  chance of landing a hunk inside fork-changed lines than on an unmodified file. `WorldExplorer.java`
  is the opposite risk profile — upstream rarely touches it (3 commits, last in April), but the fork's
  divergence there is large (261 lines), so if upstream ever does revisit it, auto-merge is less likely
  to be clean simply from hunk-overlap odds, and semantic review would need to re-derive a large diff
  rather than a small one.

## 3. Where the two "unsafe pattern" findings from session 1 actually sit

Cross-referencing session 1's two structural findings against the fork/upstream split above:

**The 46 `while(true)`-containing bot files:** 41 are byte-identical to upstream, 3 are
shared-but-fork-modified (`Fishing.java`, `WorldExplorer.java`, `DrinkerBot.java` — already in the
table above), and only 2 are fork-exclusive (`LpAssistantBot.java`, `VillageWatchBot.java`).
**Implication for any modernization touching this pattern:** fixing a `while(true)` loop in one of the
41 untouched upstream files creates *new* fork divergence in a file that previously had zero — the
"small shared registry file" cost math from `docs/fork-sync-guide.md` applies directly here. Fixing
one of the 2 fork-exclusive files is free (no upstream file to diverge from). Fixing one of the 3
already-modified files adds to an existing diff rather than opening a new seam, which is the better
place to spend effort per file if a choice must be made, especially for `WorldExplorer.java` (already
large, low upstream churn — a further change there has little marginal merge-risk cost) and worse for
`Fishing.java`/`FFAction.java` (small current diff, high upstream churn — further change there raises
future-conflict odds disproportionately).

**The 137-of-142 `NTask` subclasses with `infinite = true`:** of the 5 that already set
`infinite = false`, `FepStateSettled` is fork-exclusive and `DropOn.java` is fork-modified-shared;
the other 3 (`WaitFreeHand`, `ISRemoved`, `AnimalIsDead`) are upstream's own, byte-identical.
**This means bounded-wait is not a fork invention — it is an existing, if rare, upstream idiom.**
A modernization that adds bounded waiting to more tasks is extending a pattern upstream itself already
uses in places, not introducing a foreign convention upstream would need to reconcile on its next sync.

## 4. Utilities and interfaces that already support improvement without rewriting upstream code

These exist today and are the correct integration points — confirmed by reading
`NCore.addTask`/`NTask.baseCheck` (see the Phase 3 continuation note in
`capability-bot-lifecycle.md` for the full trace):

- **`NTask.infinite`/`maxCounter` + `criticalExit`.** Setting `infinite = false` on a task subclass is
  a one-field, same-class change — no `NCore`/`Action` edit needed — and plugs directly into the
  existing `TaskCriticalExitException` recovery path every bounded task already uses. This is the
  cheapest, lowest-merge-risk lever available: it only touches files the fork chooses to touch, and
  for the 2 fork-exclusive tasks and the already-modified `DropOn.java`/`WaitNoItems.java`, it adds
  zero new divergence surface.
- **`TaskCriticalExitException`** (fork-exclusive, `src/nurgling/tasks/TaskCriticalExitException.java`)
  is already the dedicated subtype callers use to distinguish "task timed out" from "task was really
  cancelled" (its own class doc explains why a plain `isInterrupted()` check can't tell the two apart
  after `Object.wait()` clears the interrupt flag either way). Any new bounded task should throw/catch
  through this existing type rather than inventing a parallel signal.
- **`Results`** has no safe wrapper today — `ERROR(msg)`'s unconditional
  `NUtils.getGameUI().error(msg)` (§identical to upstream, see §1) is the one place in this contract
  that doesn't already follow the codebase's own established null-guard idiom
  (`if (NUtils.getGameUI() != null) ...`, used elsewhere e.g. `NUtils.getTickId()`). A narrow, additive
  fix (guard the dereference, same shape as the existing idiom elsewhere) is a 1–2 line change to a
  file upstream rarely touches (4 commits total, last 2025-03-19) — low upstream-churn, so low future
  re-conflict odds, and it doesn't require rewriting `Results` or touching `Action`.

## 5. Where an adapter/wrapper, rather than an upstream edit, is the appropriate shape

- **Do not edit `NTask`, `Action`, `Scenario`, or `BotStep` directly** to add cross-cutting lifecycle
  behavior (e.g. a default timeout, structured retry, or a cancellation callback) — these are
  byte-identical to upstream today (§1) and every one of upstream's own bots/tasks is built against
  their exact current shape. A behavioral addition belongs in a fork-owned helper (e.g. a static
  factory bots opt into, following the same shape as the existing `TaskCriticalExitException`) that
  individual tasks/bots choose to use, not a change to the base class every upstream file already
  extends unchanged.
- **For `Results`:** the narrowest fix is in `Results` itself (§4) since the defect is in `Results`'s
  own constructor, not something an adapter around it could intercept — `NUtils.getGameUI().error(...)`
  runs inside `private Results(String msg)` before any caller gets a reference back.
- **For the `while(true)` pattern in the 41 untouched upstream bot files:** per §3, prefer not touching
  them at all unless a specific bot is already being modified for another reason — bundle any loop-exit
  hardening into that same touch rather than opening a new diff purely for this.
- **For `ScenarioManager`'s failure-propagation policy** (still unread — see Phase 3 continuation,
  "still not covered" below): if it turns out failure handling is ad hoc per `BotStep`, the
  low-conflict-safe shape is a fork-owned policy object `ScenarioManager` consults, not a rewrite of
  `ScenarioManager` itself — but this is provisional until that file is actually read.

## 6. Regression risks future upstream changes could introduce

- **`Fishing.java`/`FFAction.java`:** highest-probability future conflict surface in this capability
  area — small existing fork diff, high upstream edit frequency (9–10 commits historically). A future
  upstream commit touching either has a real chance of landing a hunk adjacent to or inside the fork's
  current changes; `docs/fork-sync-guide.md`'s disposable-trial-merge step (Phase 1, step 8) is the
  right place to catch this, not a static read now.
- **`WorldExplorer.java`:** low-probability but high-blast-radius if it happens — 261 lines already
  diverged; if upstream ever restructures this file the way it restructured `MapFile.java` (see the
  ledger's "SUPERSEDED by upstream" entry), the fork's divergence would need a full semantic re-review,
  not just a textual merge.
- **`NTask`/`Action`/`Results`/`Scenario`/`BotStep` being edited by upstream:** since the fork carries
  zero divergence here today, an upstream change merges clean by construction — the risk is purely
  semantic: if upstream changes `NTask.baseCheck()`'s default-infinite behavior, or `Results.ERROR`'s
  side effect, every "safe/unsafe" characterization in `capability-bot-lifecycle.md` and this document
  needs re-verification against the new text, not an assumption that the old trace still holds.
- **Any future fork-owned bounded-wait fix layered on `NTask.infinite`/`maxCounter`** (§4) regresses
  silently if upstream ever renames or re-scopes those fields — low likelihood (4 commits total on
  `NTask.java` across its whole history) but worth a grep-confirm (`infinite`, `maxCounter`,
  `criticalExit`) at the start of any future sync's Analyze phase per `fork-sync-guide.md`.

## Not covered in this session

- `ScenarioManager` itself — still unread (carried over from session 1's "not covered" list; this
  session did not pick it up, per the task-sequencing instruction to prioritize Phase 4 first and only
  partially return to Phase 3).
- Per-bot review of the 46 `while(true)` files beyond the classification in §3 (fork/upstream status
  only — no file beyond `BoughBee.java`, already covered in the Qodana triage, has been individually
  read for the "wrapper never loops" defect).
- Headless (`-bots` CLI) lifecycle differences against the GUI path — not looked at.
- Whether the 41 upstream-identical `while(true)` files loop correctly, share `BoughBee`'s defect, or
  are legitimate poll loops — still grep-level only.
