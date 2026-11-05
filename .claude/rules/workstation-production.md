---
description: Workstation/production automation bots (fuel-loop and generic-craft families) — routing rule to the capability audit and knowledge-index records
paths:
  - "src/nurgling/actions/bots/BakerAction.java"
  - "src/nurgling/actions/bots/BricksAction.java"
  - "src/nurgling/actions/bots/BlockAshAction.java"
  - "src/nurgling/actions/bots/BoneAshAction.java"
  - "src/nurgling/actions/bots/SmelterAction.java"
  - "src/nurgling/actions/bots/SteelAction.java"
  - "src/nurgling/actions/bots/TarkilnAction.java"
  - "src/nurgling/actions/bots/LyeBoiler.java"
  - "src/nurgling/actions/bots/GelatinAction.java"
  - "src/nurgling/actions/bots/FriedFish.java"
  - "src/nurgling/actions/bots/SmokeringBot.java"
  - "src/nurgling/actions/bots/AutocraftBot.java"
  - "src/nurgling/actions/Craft.java"
  - "src/nurgling/actions/UseWorkStation.java"
  - "src/nurgling/actions/Validator.java"
---

Before changing a fuel-loop bot (Baker/Bricks/BlockAsh/BoneAsh/Smelter/Steel/Tarkiln/LyeBoiler) or the
generic crafting-window path (`Craft.java`/`UseWorkStation.java`/`AutocraftBot`), read
`docs/audit/capability-workstation-production.md` — full fuel/production-intelligence trace, the
Baker fire-out-vs-item-doneness bug, a sophistication/consistency comparison matrix across the whole
family, and the generic-crafting-path discovery (session 2).

Check `docs/knowledge/capability-index.md` first (`CAP-WSP-001` Baker bug, `CAP-WSP-002` SteelAction
intent-not-confirmed).

If the task depends on a game mechanic, formula, fuel effect, or other server-side behavior (not just
code structure), also check `docs/knowledge/gameplay-index.md` before assuming or re-deriving it —
`GAME-001` (the Oven/Bread quality formula is filed under the *Oven* wiki/`recipes.bin` record, not
Bread's own — don't repeat that keyword-search mistake for any other ingredient-vs-building formula
lookup) and `GAME-004` (Bar of Steel's own quality formula is unresolved/UNKNOWN — don't assume it
follows the same shape as the other kiln formulas). A purely code-structural investigation (e.g. "how
does this bot pick an item" with no quality-formula dependency) does not need the gameplay index.

If the task involves fuel/ingredient quality selection specifically, also check
`docs/knowledge/reuse-candidates.md`'s `REUSE-003` before proposing a new mechanism — it tracks the
already-existing `minQuality`/`FindQualityThreshold` lower-bound pattern as the candidate fix, and
records a past incorrect citation (`targetq`, an upper-bound cap, not a floor) so it isn't repeated.

**This entire area is confirmed byte-identical to Nurgling2 upstream** (Phase 5 diff, all 7 files) —
confirmed by direct diff, not inferred from directory. See `docs/audit/upstream-compatibility.md`
before assuming any specific file's merge risk.

Quality formulas belong in `recipes.bin`/the live wiki, not restated here or in the audit doc —
`docs/knowledge/gameplay-index.md` points at the authoritative source.
