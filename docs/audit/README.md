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
| [`modernization-backlog.md`](modernization-backlog.md) | recovery | Prioritized backlog (severity/confidence/benefit/complexity/upstream-merge-risk/suggested test) for the bot lifecycle capability area; nothing in it has been implemented |

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

**Not started:** the other four capability areas (inventory/item movement, pathfinding/navigation,
workstation fuel/production, client/server assumptions/performance/UI), the bot/legacy-implementation
inventory, per-bot behavior review beyond the handful of sampled/triaged files, and the `/audit-*`
command set beyond `/audit-qodana` (`.claude/skills/audit-qodana/`). These are large, separate
undertakings — attempting all of them at once risks shallow, unverified output. Pick up one
capability area at a time and add a doc here per area, following this file's table format, rather
than one all-at-once document.
