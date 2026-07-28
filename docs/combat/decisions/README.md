---
doc_id: combat-decisions-index
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Index of combat-reactor architecture decision records"
---

# Decisions index

ADRs preserve **why**, not current truth — current truth stays in the topic documents ([mechanics.md](../mechanics.md), [behavior-contract.md](../behavior-contract.md), [code-map.md](../code-map.md)). Only lasting decisions with actual evidence get an ADR; a decision without user approval is filed as `Proposed`, never `Accepted`, regardless of how reasonable it seems.

| ADR | Title | Status |
|---|---|---|
| [ADR-0001](ADR-0001-direct-client-state-over-pixel-recognition.md) | Direct client-state reads instead of screen/pixel recognition | Accepted |
| [ADR-0002](ADR-0002-tick-diff-observation-model.md) | Tick-based observation model instead of haven message-level push hooks | Accepted |
| [ADR-0003](ADR-0003-relation-scoped-fail-closed-state.md) | Relation-scoped and session-scoped state resolution with fail-closed unknown handling | Accepted |
| [ADR-0004](ADR-0004-dispatch-through-fightsess-protocol-path.md) | Dispatch through the existing `Fightsess`/`NFightsess` protocol path | Accepted |
| [ADR-0005](ADR-0005-synchronous-single-shot-action-dispatch.md) | Synchronous single-shot send instead of a persistent action queue | Accepted |
| [ADR-0006](ADR-0006-flat-rule-group-schema.md) | Flat `ALL`/`ANY` schema for a future configurable rule system | **Proposed** (not approved) |

## See also

- [../code-map.md](../code-map.md) — where each decision is actually implemented.
- [../behavior-contract.md](../behavior-contract.md) — which requirements each decision serves.
- [../changes/CHANGELOG.md](../changes/CHANGELOG.md) — when each decision was made.
