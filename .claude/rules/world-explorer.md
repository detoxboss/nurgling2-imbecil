---
description: Water-based shore-tracing World Explorer bot — routing rule to the canonical reference
paths:
  - "src/nurgling/actions/bots/WorldExplorer.java"
  - "src/nurgling/conf/NWorldExplorerProp.java"
  - "src/nurgling/widgets/bots/WorldExplorerWnd.java"
  - "src/nurgling/actions/bots/WorldExplorerMove.java"
  - "src/nurgling/actions/bots/WorldExplorerFrontier.java"
  - "src/nurgling/actions/bots/CrossingCandidateTracker.java"
  - "src/nurgling/actions/StuckDetector.java"
  - "src/nurgling/pf/CoastFollower.java"
  - "src/nurgling/pf/FrontierPicker.java"
  - "src/nurgling/pf/TileField.java"
  - "src/nurgling/pf/WaterTiles.java"
  - "src/nurgling/tools/NDebugLog.java"
  - "docs/world-explorer-system.md"
  - "docs/world-explorer-plan-a-fix-wall-following.md"
  - "docs/world-explorer-plan-b-frontier-exploration.md"
---

Before changing or reviewing the water World Explorer bot, read `docs/world-explorer-system.md` first.

- The current bot is the **water-based shore tracer** driven by `TileField` (distance-to-land field)
  and `CoastFollower` (iso-contour steering) — not the pre-rewrite tile-boundary chaser and not a
  frontier-directed traveler.
- `world-explorer-plan-a-fix-wall-following.md` and `world-explorer-plan-b-frontier-exploration.md` are
  **historical planning documents, not current implementation specifications** — neither was built as
  literally specified; see their status banners and `world-explorer-system.md` for what actually exists.
- Consult `world-explorer-system.md`'s active/dormant component table (§7) and recorded latent findings
  (§8) before proposing a fix — several components are intentionally dormant (not bugs to "reconnect"
  without a separate decision) and several findings are documented, unfixed defects, not undiscovered
  ones.
- Preserve the existing menu route (`BotRegistry.java`'s `"worldexplorer"` descriptor, unchanged) unless
  separately instructed — don't add a second entry or change the registered class/icon without explicit
  direction.
- **Do not conflate this bot with the future land-based explorer.** Land navigation research belongs to
  [`docs/land-navigation-research.md`](../../docs/land-navigation-research.md) and
  [`.claude/rules/land-navigation.md`](land-navigation.md) — it is a separate, future, proposed bot, not
  a mode or dependency of this one. Do not apply land-navigation research to this bot, or vice versa,
  without separate, explicit approval.
- The 2026-08-08 runtime smoke test (`world-explorer-system.md` §10) verifies restored baseline
  behavior — launch, both directions, band-width effect, debug logging, first-level stuck recovery. It
  does **not** validate every dormant or unfinished subsystem (river mouths, dead-end inlets, soak
  testing, relog persistence, crossing-candidate consumption, three-strike escalation, every
  coastline/obstacle/boat configuration) — see §10's explicit unverified list before assuming broader
  coverage.
- Any fix to a recorded latent finding (e.g. the three-strikes escalation defect, §8 finding 2) must
  preserve the tested baseline behavior and be scoped and reviewed as its own separate change, with its
  own new runtime verification — not bundled into an unrelated change.
