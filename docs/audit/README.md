# Audit documentation index

Routing index for the Qodana / modernization audit tree, following the same `docs/README.md`
router convention: read the linked doc, not this summary.

**Kind** tags per `docs/README.md`: `current`, `approved`, `historical`, `proposal`, `recovery`.

| Doc | Kind | What it covers |
|---|---|---|
| [`qodana-audit.md`](qodana-audit.md) | current | Why the original Qodana scan found nothing, how the project model was fixed, reproducible config, coverage limitations, baseline usage |
| [`qodana-findings-triage-3a35b8b8a.md`](qodana-findings-triage-3a35b8b8a.md) | current | Verified triage of the 5 named high-severity findings plus a grouped (not individually verified) breakdown of `DataFlowIssue`/`StringEquality` |
| [`qodana/baseline-3a35b8b8a.sarif.json`](qodana/baseline-3a35b8b8a.sarif.json) | historical | Full SARIF from the first complete scan (commit `3a35b8b8a`, 11,738 results / 1,465 files). The *real* baseline — the original `baseline.sarif.json` from the broken scan was empty and must never be used as one |
| [`capability-bot-lifecycle.md`](capability-bot-lifecycle.md) | recovery/current | Capability registry, bot lifecycle & state machines, session 1: `Action`/`Results`/`NTask`/`Scenario`/`BotRegistry` contracts (verified), plus quantified structural facts (NTask's infinite-by-default wait, the 46-of-276 `while(true)` bot pattern) |

## Status

**Done:** Qodana project-model fix, reproducible `qodana.yaml` bootstrap, this baseline, triage of
the explicitly-named high-severity findings (Phase 1–2), and session 1 of the capability registry
for bot lifecycle & state machines (Phase 3, user-selected starting area — see
`capability-bot-lifecycle.md`'s own "not covered"/"suggested session 2" sections for what's left in
just that one area).

**Not started:** the other four capability areas (inventory/item movement, pathfinding/navigation,
workstation fuel/production, client/server assumptions/performance/UI), the bot/legacy-implementation
inventory, the modernization backlog, the upstream-compatibility assessment (Phase 4), and the
`/audit-*` command set beyond `/audit-qodana` (`.claude/skills/audit-qodana/`). These are large,
separate undertakings — attempting all of them at once risks shallow, unverified output. Pick up one
capability area at a time and add a doc here per area, following this file's table format, rather
than one all-at-once document.
