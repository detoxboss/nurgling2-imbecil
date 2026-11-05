# Audit documentation index

Routing index for the Qodana / modernization audit tree, following the same `docs/README.md`
router convention: read the linked doc, not this summary.

**Kind** tags per `docs/README.md`: `current`, `approved`, `historical`, `proposal`, `recovery`.

| Doc | Kind | What it covers |
|---|---|---|
| [`qodana-audit.md`](qodana-audit.md) | current | Why the original Qodana scan found nothing, how the project model was fixed, reproducible config, coverage limitations, baseline usage |
| [`qodana-findings-triage-3a35b8b8a.md`](qodana-findings-triage-3a35b8b8a.md) | current | Verified triage of the 5 named high-severity findings plus a grouped (not individually verified) breakdown of `DataFlowIssue`/`StringEquality` |
| [`qodana/baseline-3a35b8b8a.sarif.json`](qodana/baseline-3a35b8b8a.sarif.json) | historical | Full SARIF from the first complete scan (commit `3a35b8b8a`, 11,738 results / 1,465 files). The *real* baseline — the original `baseline.sarif.json` from the broken scan was empty and must never be used as one |
| [`capability-bot-lifecycle.md`](capability-bot-lifecycle.md) | recovery/current | Capability registry, bot lifecycle & state machines, sessions 1-3: `Action`/`Results`/`NTask`/`Scenario`/`BotRegistry` contracts, `ScenarioManager`/`ScenarioRunner`/`BotExecutor` execution and concurrency model (all verified), quantified structural facts (NTask's infinite-by-default wait, the 46-of-276 `while(true)` bot pattern, a 4-file sample of it), the `NCore.addTask`/`criticalExit` wait-mechanism trace, the `Results.ERROR` null-dereference call-pattern evidence, and the `BotsInterruptWidget.MAX_BOTS` overflow gap |
| [`upstream-compatibility.md`](upstream-compatibility.md) | current | Phase 4: fork-vs-upstream classification of every bot-lifecycle file (core contracts identical to upstream; bot/task implementation layer 95%+ shared-and-unmodified), upstream churn/conflict-risk ranking of the handful of already-diverged files, which existing utilities support safe improvement, where adapters belong, and regression risks |
| [`capability-workstation-production.md`](capability-workstation-production.md) | recovery/current | Capability registry, workstation & production automation, session 1: full bot/primitive inventory (ovens, kilns, smelters, steel, cauldrons); verified fuel/production-state capabilities (per-item `GItem.MeterInfo`/model-attribute bitmasks exist and are used only for UI tooltips, never by bot completion logic); the Baker bot's fire-out-vs-item-doneness bug mechanism; a sophistication/consistency comparison matrix; and a Phase 5 upstream assessment (100% of the area's files are byte-identical to upstream, ranked by churn) |
| [`capability-navigation.md`](capability-navigation.md) | recovery/current | Capability registry, navigation/movement/pathfinding/obstacle-awareness/recovery, sessions 1-2: full breadth-first inventory of every navigation implementation found (core local `PathFinder`/`NPFMap`/`Graph`/`DynamicPf`, the `ChunkNav` persisted multi-layer chunk-navigation subsystem, Forager's independent route-recording/walking layer, legacy raw-click `PatrolArea`); a four-way stuck-detection comparison; a correction to `land-navigation-research.md`'s "default off" characterization of `ChunkNav`; a corrected upstream-compatibility assessment (session 1 wrongly assumed fork-exclusivity by directory — session 2 diffed every file against the `upstream` remote directly and found the entire registry byte-identical to Nurgling2 upstream, under active upstream development); a full trace of `ChunkNavPlanner`/`ChunkNavGraph`/`ChunkNavData`/`ChunkPath`/`ChunkNavRecorder`/`UnifiedTilePathfinder`'s actual bodies; and 16 confirmed modernization findings including a High-severity reliability defect (`ChunkNavExecutor.replanAndContinue` fails immediately instead of replanning for two real callers), a confirmed-dead confidence/staleness-penalty mechanism, and a confirmed-unreachable legacy code path |
| [`modernization-backlog.md`](modernization-backlog.md) | recovery | Prioritized backlog (severity/confidence/benefit/complexity/upstream-merge-risk/suggested test) across all capability areas covered so far; nothing in it has been implemented |
| [`methodology.md`](methodology.md) | current | The four-level coverage methodology (DISCOVERED/TRACED/EVALUATED/VALIDATED) this audit now tracks per subsystem, not per broad capability area; includes the retrofit coverage table for workstation & production and the worked "Oven/Bread" example of why mapping architecture alone isn't auditing. Applies to every future capability area. |

## Status

**Done:** Qodana project-model fix, reproducible `qodana.yaml` bootstrap, this baseline, triage of
the explicitly-named high-severity findings (Phase 1–2), sessions 1-3 of the capability registry
for bot lifecycle & state machines (Phase 3, user-selected starting area — framework layer now fully
read, including `ScenarioManager`'s chained-failure propagation which was open since session 1), and
the upstream compatibility assessment for that same area (Phase 4).

**Bot lifecycle capability area: framework layer is now fully traced** — see
`capability-bot-lifecycle.md`'s "Suggested session 4 scope" for what remains (the `MAX_BOTS` overflow
gap not yet reproduced live, further `while(true)` sampling if wanted, per-bot-pairing resource
contention — none of these block moving to the next capability area, they're incremental
hardening/confidence items, not open structural unknowns).

**Workstation & production automation capability area: sessions 1-4.** Per `methodology.md` (new this
session — read it before starting any future capability area), this area is no longer described as
"substantively complete" from architecture-mapping alone; its actual coverage is tracked per subsystem
in that document's retrofit table (a mix of DISCOVERED/TRACED/EVALUATED/VALIDATED, not uniform).
Session 1: fuel-loop bot inventory, fuel/production intelligence, Baker investigation, comparison
matrix, upstream Phase 5. Session 2: widened scope to the full workstation-interaction surface —
discovered a second, structurally distinct **generic crafting-window path** (`Craft.java`/
`UseWorkStation.java`/`AutocraftBot`), confirmed the quern gob-hitbox-but-zero-automation gap, traced
position-changing-station interaction range (gated on the server-reported `Following` attribute).
Session 3: ran the upstream diff/churn check deferred from session 2 (all 7 files byte-identical to
upstream); read `GelatinAction`/`TarkilnAction`/`FriedFish`/`SmokeringBot` in full, finding a **third**
observable-completion mechanism (`Roastspit` sprite-overlay content); confirmed loom/churn/
spinning-wheel/ropewalk/potter's-wheel/meat-grinder have no dedicated bot at all; performed initial
Ring of Brodgar wiki research via local extraction of `src/nurgling/craft/recipes.bin`. **Session 4
caught and corrected a session-3 research error**: a keyword search had missed that Bread's quality
formula is filed under the *Oven* record in `recipes.bin`, not Bread's own — session 4 re-checked
against the live wiki (exact revision match) and reversed the prior conclusion, which strengthens
rather than weakens the Baker-bug finding (the live page states the ash-burning failure mode directly).
Session 4 also traced `recipes.bin`'s generation pipeline (`tools/wiki-recipes/`, maintainer-run,
offline, never network-accessed by the client), validated 4 representative pages against the live
wiki (all current, with structural — not staleness — gaps: burn-time tables and one unparsed formula
are outside its schema), and built a full Autocrafting-vs-dedicated-bots comparative matrix (`AutocraftBot`
read in full for the first time: confirmed to duplicate nothing, delegating 100% of execution to
`Craft.java`). See `capability-workstation-production.md`'s "Not covered, after session 4" for
remaining gaps, and `methodology.md`'s coverage table for per-subsystem status.

**Navigation/movement/pathfinding/obstacle-awareness/recovery capability area: sessions 1-2,
CLOSED (architecture/discovery phase).** Closed by explicit user confirmation at the end of
session 2 — no further discovery/coverage-improvement work on this area is planned. This does not
mean every finding is implemented or every performance hypothesis measured; see
`capability-navigation.md`'s "Remaining runtime work" and `modernization-backlog.md`'s navigation
items 14-25 for what's still open as separately-tracked future work. Session 1 was a breadth-first
discovery pass across the whole navigation surface, plus full
TRACE/EVALUATE of the highest-value previously-undocumented systems: core local pathfinding, the
`ChunkNav` persisted multi-layer chunk-navigation subsystem (no prior audit doc at all), Forager's
independent route-recording/walking layer, and the legacy raw-click `PatrolArea` bot. Session 2
**corrected a wrong assumption from session 1**: everything under `src/nurgling/**` was assumed
fork-exclusive by directory, but a direct diff against the Nurgling2 `upstream` remote shows the
entire navigation registry is byte-identical to upstream and under active upstream development
(`ChunkNav` itself: 18 commits in the 90 days before the audit). Session 2 then read
`ChunkNavPlanner`/`ChunkNavGraph`/`ChunkNavData`/`ChunkPath`/`ChunkNavRecorder`/
`UnifiedTilePathfinder`'s actual bodies (session 1 had stopped at their public API surface),
finding: a confirmed-dead confidence/staleness-penalty mechanism (computed, never consumed by the
live planner); a confirmed-unreachable legacy waypoint-following code path (`followWaypointPath`/
`ChunkNavIntraPathfinder`, which downgrades session 1's N3 finding's practical severity); and a
High-severity reliability defect — `ChunkNavExecutor.replanAndContinue()` fails immediately instead
of replanning for the two real callers (`FollowRoute`, `StorageNav`) that plan via grid-coordinate
rather than area targets. Per `methodology.md`'s per-subsystem tracking, see
`capability-navigation.md`'s §16 table for the current per-file coverage and its closing assessment
(in `modernization-backlog.md`) for why the source-level discovery phase for this capability area
is now considered ready to close, pending only runtime verification of specific findings (listed in
§13's "Remaining runtime work") before any of them are implemented.

**Not started:** the other two capability areas (inventory/item movement, client/server
assumptions/performance/UI), the bot/legacy-implementation inventory, per-bot behavior review
beyond the handful of sampled/triaged files, and the `/audit-*` command set beyond `/audit-qodana`
(`.claude/skills/audit-qodana/`). These are large, separate undertakings — attempting all of them
at once risks shallow, unverified output. Pick up one capability area at a time and add a doc here
per area, following this file's table format, rather than one all-at-once document.

## Next planned milestone (not started — read this before starting new audit work)

With the navigation capability area closed, the next major project task is **establishing a
permanent, lightweight, Git-versioned Nurgling2 Knowledge Base** — organizing code capabilities,
gameplay mechanics, wiki references, cross-system relationships, modernization opportunities, and
documentation-drift detection, designed to progressively improve Claude Code's codebase
understanding without loading large documents unnecessarily. This is a separate, already-scoped
task with a detailed prompt prepared outside this document — it has **not** been designed or
implemented yet. A fresh Claude Code conversation picking up this project should treat this as the
next thing to ask the user about, rather than starting another capability-area audit (inventory/
item movement, client/server assumptions, etc.) on its own initiative.
