---
description: ChunkNav persisted multi-layer navigation subsystem — routing rule to the capability audit and knowledge-index records
paths:
  - "src/nurgling/navigation/**/*.java"
  - "src/nurgling/actions/FollowRoute.java"
  - "src/nurgling/actions/StorageNav.java"
---

Before changing `ChunkNav` (planner, graph, recording, persistence, route execution) or its
grid-coordinate callers (including `FollowRoute`, `StorageNav`, `Forager`, `GhostConstruction`, and
`NUtils.navigateTo(NGlobalCoord)`), read `docs/audit/capability-navigation.md`
— full trace of `ChunkNavPlanner`/`ChunkNavGraph`/`ChunkNavData`/`ChunkPath`/`ChunkNavRecorder`/
`UnifiedTilePathfinder`, a four-way comparison against local pathfinding/global pathfinding/Forager's
route-walking layer, and a confirmed High-severity defect (`ChunkNavExecutor.replanAndContinue()`
fails instead of replanning whenever `targetArea == null` — `CAP-NAV-001` in
`docs/knowledge/capability-index.md`).

Check `docs/knowledge/capability-index.md` first for a one-line pointer (`CAP-NAV-001`, `CAP-NAV-002`)
before opening the full audit doc — it tells you whether the thing you're touching is already a known
finding and what reachability/provenance tag it carries. Also check
`docs/knowledge/reuse-candidates.md` (`REUSE-001`, `REUSE-002`) for cross-system reuse opportunities
already identified for this area before proposing a new mechanism.

**This entire registry is byte-identical to Nurgling2 `upstream` and under active upstream
development** (`ChunkNav` had 18 commits in the 90 days before the audit) — confirmed by direct diff
against the `upstream` remote, not inferred from the `src/nurgling/**` directory. Treat any fix here
as opening new fork/upstream divergence in a file upstream actively edits; check
`docs/audit/upstream-compatibility.md` before assuming low merge risk.

Do not apply anything here to the water-based World Explorer bot (`.claude/rules/world-explorer.md`)
or the proposed land-navigation track (`.claude/rules/land-navigation.md`) without separate approval
— see those rules' own scope-boundary notes.
