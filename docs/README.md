# Documentation index

Routing map for this repo's tracked reference library. Each entry links to the owning document
instead of duplicating it — read the linked doc, not this summary, for actual technical content.
Root `CLAUDE.md` routes here first; load only what your task needs from below.

**Kind** tags: `current` (describes present implementation/behavior), `approved` (intended behavior,
not necessarily fully implemented), `historical` (a record of a past event, not current state),
`proposal` (unimplemented plan/design), `recovery` (backlog/WIP-recovery tracking, not current state).

## Development / upstream maintenance

| Doc | Kind | What it covers |
|---|---|---|
| [`development-workflow.md`](development-workflow.md) | current | Feature-branch discipline, verification, review, multi-worktree practice, low-conflict upstream habits, doc-ownership rule |
| [`hafen-integration-guide.md`](hafen-integration-guide.md) | current | Full procedure for merging official Hafen (`dolda2000/hafen-client`) source into `src/haven/**`, plus a dated log of past integrations |
| [`fork-sync-guide.md`](fork-sync-guide.md) | current | Timeless four-phase procedure (Analyze → Resolve/stage/review → Commit/PR/runtime-verify → Promote) for syncing this fork against the `upstream` remote (`aleksandrsvoboda/nurgling2`); also states the low-conflict development policy for new fork work |
| [`fork-customization-ledger.md`](fork-customization-ledger.md) | current | Canonical record of *intentional* fork-vs-upstream-Nurgling2 differences (Combat Reactor hooks, `MapFile` shared lock, session-ownership invariants, fork config/registry/i18n additions): what exists, why, the minimum hook that must survive a sync, and when upstream would supersede it |
| [`fork-maintenance-backlog.md`](fork-maintenance-backlog.md) | current | Current, non-historical list of known deferred fork problems that are *not* intentional customizations (World Explorer `MCache.grids` sync, multi-session reliability, `FreeContainersInUnboxZone` rewrite, `OpenTargetContainer` null-gob NPE) |
| [`upstream-sync-history/`](upstream-sync-history/) | historical | One dated record per completed (or in-flight) upstream-Nurgling2 sync cycle: refs, merge commit, PR, conflicts reviewed, build/test results, deferred work |
| [`nurgling-modified-files.md`](nurgling-modified-files.md) | current | File-level list of `haven`-package files carrying nurgling changes, and per-file conflict strategy — for **official Hafen** integrations specifically; see `fork-customization-ledger.md` above for the separate fork-vs-Nurgling2 boundary |
| [`hafen-integration-2026-02-27.md`](hafen-integration-2026-02-27.md) | historical | Record of one past hafen integration |
| [`hafen-integration-2026-06-port-design.md`](hafen-integration-2026-06-port-design.md) | historical | Record of another past hafen integration (port-design + execution status) |
| [`resource-upgrade-strategy.md`](resource-upgrade-strategy.md) | current | How server-distributed resource code relates to the git source tree, and the strategy for client-side modifications to it |
| [`release-process.md`](release-process.md) | current | Cutting a portable release: the manual `workflow_dispatch` GitHub Release workflow, archive contents, Java/Steam requirements, and manual verification status. Routed via [`.claude/rules/release-engineering.md`](../.claude/rules/release-engineering.md) |

## Automation systems (bots, actions, navigation)

| Doc | Kind | What it covers |
|---|---|---|
| [`inventory-grid-system.md`](inventory-grid-system.md) | current | `NInventory`/`WItem` grid mechanics: footprint, stacking, whole-stack vs. per-unit operations, `InventorySnapshot`'s ownership model. Routed via [`.claude/rules/inventory-management.md`](../.claude/rules/inventory-management.md) |
| [`gob-context-menu.md`](gob-context-menu.md) | current | The Ctrl+Right-Click flower-menu system for launching gob-scoped bots |
| [`feps-system-reference.md`](feps-system-reference.md) | current | FEP/food/attribute-gain mechanics, for anything touching `OptimizeTableEating.java` or similar |
| [`mixed-wip-recovery.md`](mixed-wip-recovery.md) | recovery | Reusable navigation/automation primitives found in unreconstructed rescue-snapshot code (cliff-aware movement, `DynamicPf`'s opt-in cliff handling), plus reconstruction status for the World Explorer group — see its "Reusable systems awaiting reconstruction" table before treating any of the still-unreconstructed ones as available |
| [`world-explorer-system.md`](world-explorer-system.md) | current | World Explorer bot: runtime path (`WorldExplorer` → `TileField` → `CoastFollower`), movement/backoff/stuck-detection, water-mode classification, character-scoped config/persistence, active vs. dormant components, recorded-but-unfixed latent findings, and 2026-08-08 runtime-smoke-test results. Water/shore-tracing only — see its own scope-boundary note. Routed via [`.claude/rules/world-explorer.md`](../.claude/rules/world-explorer.md) |
| [`land-navigation-research.md`](land-navigation-research.md) | recovery/proposal | Research for a **separate, future, proposed** land-based long-distance exploration bot: rolling-horizon navigation over live `MCache` terrain (not `ChunkNav`-dependent), player-observed and snapshot-calibrated cliff-traversal evidence, reusable-candidate inventory (`PathFinder`/`NPFMap`, `FrontierPicker`, `StuckDetector`, `animalrad`), and open decisions. Not a mode or dependency of the water World Explorer. Routed via [`.claude/rules/land-navigation.md`](../.claude/rules/land-navigation.md) |

No general-purpose automation/navigation system document exists yet for primitives that remain
unreconstructed (cliff-aware movement/`CliffScan`/`CliffCalibrate`, the `DynamicPf.cliffAware` opt-in) —
none of those are implemented on current `master`. Do not create one for them until they're
reconstructed and verifiable; see `mixed-wip-recovery.md` for the promotion criteria. World Explorer
itself now has its own canonical doc and routing rule (above), and the land-navigation research above is
a separate, not-yet-reconstructed track with its own routing rule — do not conflate the two.

## Inventory / LP Assistant

| Doc | Kind | What it covers |
|---|---|---|
| [`lp-assistant-bot.md`](lp-assistant-bot.md) | current | LP Assistant bot + LP discovery-marker system: storage, the `clickedGob` trap, confirmation tasks, round-by-round bug history. Routed via [`.claude/rules/lp-assistant.md`](../.claude/rules/lp-assistant.md) |
| [`inventory-grid-system.md`](inventory-grid-system.md) §7 | current | `InventorySnapshot` baseline/ownership/cursor model that LP Assistant is built on |
| [`live-harvest-availability.md`](live-harvest-availability.md) | current | Live harvest-availability tracking feature merged alongside LP Assistant |

## Combat

Start at [`combat/README.md`](combat/README.md) — it is itself the routing index for the combat
documentation tree (mechanics, behavior contract, code map, evidence, verification status, ADRs,
changelog) and states its own authority/conflict rules. Routed via
[`.claude/rules/combat-automation.md`](../.claude/rules/combat-automation.md). Don't link directly to
the sub-documents from here; follow `combat/README.md`'s own "Read this for…" table.

| Doc | Kind | What it covers |
|---|---|---|
| [`haven-combat-reactor-nurgling-port-brief.md`](haven-combat-reactor-nurgling-port-brief.md) | historical | Original functional-spec brief for porting the AutoHotkey combat script into Nurgling2 — background/requirements input, superseded as current truth by `combat/behavior-contract.md` |
| [`automation-requirements-r3.md`](automation-requirements-r3.md), [`combat-interface-reference-r3.md`](combat-interface-reference-r3.md), [`combat-system-spec-r3.md`](combat-system-spec-r3.md), [`implementation-decisions-r3.md`](implementation-decisions-r3.md) | historical | The pre-port AutoHotkey script's own requirements/interface/spec/decisions documentation. Per `combat/README.md`'s authority rules: not automatically a Nurgling2 requirement — only what the port brief/behavior-contract explicitly carried forward applies |
| [`claude-code-combat-documentation-prompt.md`](claude-code-combat-documentation-prompt.md) | historical | The original prompt used to generate the `combat/` documentation tree |
| `combat/hurricane-port-brief.md`, `combat/hurricane-ready-files.md` | proposal | Not tracked in this repo — file names only, no link. See `mixed-wip-recovery.md` for where they're recorded. A handoff package for porting the combat reactor into a *different, external* H&H client project ("Hurricane"), not work on this repo |

## Recovery / backlog

| Doc | Kind | What it covers |
|---|---|---|
| [`mixed-wip-recovery.md`](mixed-wip-recovery.md) | recovery | Ledger of feature groups preserved in rescue snapshot `802a8855` (branch `backup/mixed-wip-before-upstream-2026-08-07`): what's already reconstructed (LP Assistant, World Explorer — see `world-explorer-system.md`), what's separable (the `Graph.java` priority-queue change), and reusable navigation/automation systems still awaiting a reconstruction decision (cliff-aware movement) |

## Reusable-system questions

If you're asking "does a reusable X already exist" for navigation, stuck-detection, terrain-scanning,
or bounded-wait patterns: check `mixed-wip-recovery.md`'s "Reusable systems awaiting reconstruction"
table first. If it's not there and not in this index's Automation systems section, **do not assume it
exists** — search current source and verify directly before recreating or reusing it. Absence from
these docs is not proof of absence in the code, only that no one has documented it here yet.
