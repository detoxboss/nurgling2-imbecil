---
description: Land-based long-distance navigation, cliff handling, and rolling-horizon overland exploration research — routing rule to the canonical reference
paths:
  - "src/nurgling/pf/CliffScan.java"
  - "src/nurgling/actions/CliffAwareMove.java"
  - "src/nurgling/actions/bots/CliffCalibrate.java"
  - "src/nurgling/actions/DynamicPf.java"
  - "src/nurgling/actions/PathFinder.java"
  - "src/nurgling/pf/NPFMap.java"
  - "src/nurgling/pf/Graph.java"
  - "src/nurgling/navigation/**/*.java"
  - "src/nurgling/actions/bots/LandExplorer*.java"
  - "src/nurgling/actions/bots/*LandNav*.java"
  - "docs/land-navigation-research.md"
---

Before land-exploration work, cliff-traversal handling, or rolling long-distance overland navigation —
including anything touching `DynamicPf`, `PathFinder`/`NPFMap`, or `src/nurgling/navigation/**`
(`ChunkNav`) in service of that goal — read `docs/land-navigation-research.md` first. It's recovery/
proposal material tagged by evidence tier (user-reported, snapshot-era tested, current
source-confirmed, proposal/open-decision); do not treat any of it as current implementation until it
says otherwise.

`LandExplorer*`/`*LandNav*` glob patterns above are anticipatory — no such class exists yet anywhere in
this repo (not on `master`, not in the rescue snapshot). They're listed so this rule activates
automatically the moment land-explorer work actually begins.

**Do not apply anything from `docs/land-navigation-research.md` to the water-based World Explorer bot
(`src/nurgling/actions/bots/WorldExplorer.java` and its companion files — see
`docs/world-explorer-system.md`) without separate, explicit approval.** The land bot is a distinct,
future, proposed bot with its own future menu entry — not a mode, extension, or dependency of the water
bot. The two share only the fact that some infrastructure (`FrontierPicker`, `StuckDetector`) was
originally written broadly enough that both *could* eventually use it; shared-authorship intent is not
the same as an approved shared implementation. Confirm which bot you are actually working on before
reusing code or conclusions across this boundary.

`src/nurgling/pf/Graph.java`'s `LinkedList`→`PriorityQueue` change is tracked as the separate
`perf/pathfinder-priority-queue` proposal (general `DynamicPf` performance work) — it is not a
land-navigation dependency; see `docs/land-navigation-research.md` §10 before assuming otherwise.
