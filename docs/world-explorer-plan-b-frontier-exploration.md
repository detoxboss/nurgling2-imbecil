# WorldExplorer bot — Plan B: frontier/flood-fill exploration redesign

> **Status: historical, snapshot-era planning document — not a description of current or final behavior.**
> This document predates the final implementation and does not describe it. The snapshot-era `WorldExplorer`
> that actually shipped evolved into a separate `CoastFollower`/`TileField` distance-field design, not the
> frontier-directed travel specified below (the `WorldExplorerFrontier`/`FrontierPicker` classes this plan
> produced exist in the final code but are not called for target selection at runtime — see
> [`docs/world-explorer-system.md`](world-explorer-system.md)). **Do not treat this document as a literal
> implementation specification.** For current, code-confirmed behavior, see that document instead.

Standalone implementation plan. Self-contained — no other document needed to act on this.

See also: [world-explorer-plan-a-fix-wall-following.md](world-explorer-plan-a-fix-wall-following.md) — a lower-risk, lower-effort alternative that keeps the existing wall-following algorithm and fixes its bugs instead of replacing it. Not chosen yet; either can be implemented independently. This plan reuses several components from that one (`WaterTiles`, `WorldExplorerMove`, `WorldExplorerStuck`) — read that plan's Design sections 1-3 for their exact specs; they're referenced here, not re-specified.

## Context

`WorldExplorer` (`src/nurgling/actions/bots/WorldExplorer.java`) currently drives a boat along a water-tile-type boundary (wall-following/coastline-hugging). Three concrete problems were reported: jerky stop-start travel, getting stuck on shore obstacles (e.g. clam reefs) with no recovery, and an infinite 180° flip at river mouths. Full root-cause analysis of the current code is in Plan A's Context/Root-causes sections — not repeated here.

This plan replaces the algorithm entirely with **directed travel toward the nearest not-yet-visited map area**, so the bot always makes progress toward genuinely new territory instead of retracing a boundary shape. The user specifically asked whether two existing systems — **ChunkNav** (world-chunk pathfinding/recording) and **ExploredArea** (a session-based "where have I been" minimap overlay) — could be reused as the backbone for this. Both were investigated directly in code; neither provides a ready-made "find the frontier" query, but both offer real, reusable primitives, and the user's own screenshots of both systems (plus the world map's fog-of-war) directly informed the design below.

## What ChunkNav and ExploredArea actually provide (confirmed by reading the code)

**ChunkNav** (`src/nurgling/navigation/`):
- A "chunk" = one game grid, **100×100 tiles** (`ChunkNavConfig.CHUNK_SIZE=100`), walkability sampled at half-tile resolution (`CELLS_PER_EDGE=200`).
- Recording is gated behind the `NConfig.Key.chunkNavOverlay` toggle (`ChunkNavManager.java:184-187, 245-248`) — **off by default**, must be explicitly enabled by the user for a chunk to get recorded while visited.
- Recording happens on a background thread every 2s, sampling only cells within a **50-cell (=25-tile) radius** of the player (`ChunkNavRecorder.java:288, 317-319`) — cells outside that radius stay "unobserved."
- **Critically**: `ChunkNavRecorder.java:29-35` puts deep water tiles (`gfx/tiles/deep`, `gfx/tiles/odeep`) into the **same `BLOCKED_TILES` set as walls and rock**. ChunkNav was built for on-foot/land navigation — it cannot distinguish "blocked by wall" from "blocked by water," so its stored walkability data is **not usable** to tell whether a chunk edge is safe for a boat. Reusing it as-is would be wrong.
- `ChunkNavGraph` (`ChunkNavGraph.java:20`) stores chunks keyed by grid ID with adjacency (`connectedChunks`) and portal edges. There is **no** "list undiscovered neighbor chunks" or "nearest chunk matching predicate" query — only `getChunk(gridId)`, `hasChunk`, `getAllChunks()`, `getChunksForArea(areaId)` (line 347). A frontier query would have to be built by walking neighbor IDs and checking `hasChunk()` for absence — that part is cheap and reusable.
- `ChunkNavExecutor` drives movement by wrapping `nurgling.actions.PathFinder` internally (e.g. `new PathFinder(waypoint).run(gui)` at lines 780-786, 1058-1060) — i.e. **it has the same one-tile-per-click, blocking-to-full-stop movement problem as `GoTo`**, just at a higher level. Not useful for the "smooth continuous travel" requirement without the same movement-primitive fix as Plan A.

**ExploredArea** (`src/nurgling/tools/ExploredArea.java`) — a **separate system**, unrelated to ChunkNav, built on 100×100 boolean tile masks per grid (`GRID_SIZE=100`, lines 36-37). This is the system the user described as the "session mode": `startSession()`/`endSession()` (lines 221-243), toggled via the `ExploredAreaMenu` widget's "Create/Delete Session Layer" petal, gated by master toggle `NConfig.Key.exploredAreaEnable` (default `false`, `NConfig.java:315`).
- **It is queryable in code, not just visual**: `getExploredMaskForGrid(Coord gridCoord, long segmentId, int dataLevel)` (line 190) and `getSessionMaskForGrid(...)` (line 253) return the raw `boolean[]` mask.
- **Reveal size confirmed**: every tick, `NMiniMap.tick()` marks a **9×9 block of 100-tile subgrids (900×900 tiles!)** around the player's current subgrid as explored in one call (`NMiniMap.java:611-618`) — far larger than actual sight radius, and keyed off which subgrid the player occupies rather than true line-of-sight. This confirms the user's own observation that "revealing new terrain gives a fair bit of leeway/radius" — but it means this mask is too coarse/generous to reliably mean "I have actually seen this exact tile," only "I was somewhere in this 900×900-tile neighborhood."

**Bottom line**: neither system exposes a ready "find the frontier" API. Both require the user to have a toggle enabled ahead of time (`chunkNavOverlay` or `exploredAreaEnable`) for their data to exist at all, which is a real adoption risk if left as a hard dependency for a bot meant to "just work."

## User-provided screenshots — what they confirmed

The user shared screenshots of three distinct "revealed area" overlays: (1) the world Map window (`M` key, part of `src/mapv4/**`'s minimap/map-image subsystem) with a debug red grid toggled on, showing incremental fog-of-war reveal as the character moves — each red-grid cell (a **mapv4 map-image segment boundary**, unrelated to `MCache`'s tile `Grid` or ChunkNav's 100×100 chunk) fills in gradually, with a visible buffer of already-revealed space between the character and the edge of the still-black cell; (2) the minimap's "Explored area (RMB for session)" toggle — a coarse green block around the player, consistent with `NMiniMap.java`'s confirmed 900×900-tile block-per-tick reveal; (3) the "ChunkNav Exploration" minimap overlay — green = chunk-recorded, reddish = seen-but-not-yet-chunknav-recorded, visibly trailing/leading the player by a consistent radius as they sail down a coastline, with clam/oyster reef Gobs plainly visible sitting right on the shoreline the character is tracing (good direct visual confirmation of the exact obstacle type causing the reported stuck-bug).

**Important**: these are three *separate* grid/tiling concepts — the mapv4 world-map segment grid, `MCache`'s own tile `Grid` (loaded as the client receives terrain data from the server), and ChunkNav's 100×100-tile chunk convention. Don't conflate them. The one that matters for live bot navigation is `MCache`'s own tile-loading boundary, addressed below — it requires no toggle and is already exactly what `WorldExplorer.java`'s existing code queries every tick (`tilesetr`/`tilesetname` return `null` for a tile the client hasn't received terrain data for yet, confirmed by the existing `if (res_beg != null)` guard at `WorldExplorer.java` line 63/104).

**Refinement this motivates**: rather than building frontier-detection purely on a from-scratch 100×100 visited-chunk grid, use `MCache`'s live null-vs-resolved tile state as the **primary, toggle-independent, always-available frontier signal** — the edge of currently-resolvable terrain *is* the frontier, and it naturally advances outward exactly like the buffer the screenshots show, without depending on ChunkNav or ExploredArea being enabled at all. Confirmed via code read: `src/haven/MCache.java:70` — `public Map<Coord, Grid> grids = new HashMap<>()` — is a plain map that only grows (no eviction/remove path found in the file); so within one login session, once a `Grid` loads, its tiles stay resolvable for the rest of that session — a `null` tile reliably means "not yet loaded this session," not "loaded then dropped." That durability does **not** cross a relogin, though: a fresh session starts with an empty `grids` map, so previously-explored water would again read as unresolved/null until re-approached. This is exactly why the persisted long-term visited-chunk set (below) remains necessary — it's the cross-session memory that the live null-tile signal cannot provide by itself.

## Recommended design

Two-tier frontier signal:
- **Short-term/tactical** (always on, no dependency): scan outward from the player and treat the boundary where `tilesetr`/`tilesetname` starts returning `null` as the immediate exploration target — steer toward the nearest point on that boundary that's still water-safe (`WaterTiles.isSafe`). This is cheap, exactly matches the natural reveal-buffer the screenshots demonstrate, and needs no ChunkNav/ExploredArea toggle.
- **Long-term/strategic** (persisted): a lightweight visited-chunk tracker (below) so the bot doesn't sail back into fully-explored water after a relogin, when the short-term signal alone can no longer tell old water from new.

Build this as a **new, lightweight, boat/water-specific visited-chunk tracker**, decoupled from ChunkNav's persisted land-navigation database (to avoid contaminating it with water-as-blocked data, and to avoid a hard dependency on the user having `chunkNavOverlay` enabled beforehand) — but reuse ChunkNav's **chunk-size convention (100×100 tiles)** and **PathFinder's existing `waterMode`** machinery where it already does the right thing.

### 1. New file `src/nurgling/pf/WaterTiles.java`
Same utility as specified in Plan A's Design section 1 (`isShallow`/`isDeep`/`isDeeper`/`isSafe` by tile resource-name suffix). Reuse verbatim if Plan A is implemented first; otherwise implement it fresh per that spec.

### 2. New file `src/nurgling/actions/bots/WorldExplorerFrontier.java` — visited-chunk bookkeeping + frontier selection
- In-memory `HashSet<Long> visitedGridIds` (grid ID via the same grid-coordinate convention ChunkNav uses — confirm exact accessor via `NUtils.getGameUI().ui.sess.glob.map.getgrid(...)`/existing grid-id helpers used in `ChunkNavManager`/`ChunkNavRecorder`; do not touch ChunkNav's own stored data, just borrow its ID scheme for consistency). Persist this set per-character across sessions via a new `NConfig.Key` (e.g. `worldExplorerVisitedGrids`), following the same JSON persistence pattern as `NWorldExplorerProp`, so re-running the bot doesn't immediately revisit the same chunks after a relogin — recommended default: **persist**, since the whole point is "always go somewhere new," and the existing `ExploredArea`/`ChunkNav` systems already establish precedent for persisting this kind of data per character.
- `markVisited(Coord pltc)` — call once per movement tick, computing the current grid ID and adding it to the set (and to `NConfig` periodically, not necessarily every tick — mirror `ChunkNavManager`'s 2s throttle at `ChunkNavManager.java:41-42` to avoid excessive disk writes).
- `Coord pickFrontierTarget(Coord pltc, Coord heading, boolean deeperMode)` — two-step selection, per the refinement above:
  1. **Primary**: ray-scan outward from `pltc` along `heading` (and a few neighboring headings, e.g. ±30°/±60°) using `MCache.gettile`/`tilesetname`, looking for where the result turns `null` (unresolved this session) — that's the live frontier edge. Walk back inward from that edge along the same ray until `WaterTiles.isSafe` holds, and use that as the target. This is the common case and needs no persisted state at all.
  2. **Fallback**: if every nearby ray hits a `visitedGridIds`-covered chunk before it hits any unresolved tile (i.e. the immediate area was already fully explored this session or in a prior one, per the persisted set), fall back to: check the 4 (or 8) neighboring 100×100 grid chunks for one not in `visitedGridIds`, preferring the candidate closest to `heading`, then search outward from that chunk's center (small spiral/ring search, capped at e.g. 20 tiles) using `WaterTiles.isSafe` for an actual safe-water point — chunk centers can land on shore/land, so this step is required, not optional.

### 3. Movement: reuse `PathFinder` for the long leg, `WorldExplorerMove` for smooth execution
`WorldExplorerMove` is specified in Plan A's Design section 2 (`scanAhead`, `clickAndChase` — a non-blocking, chained-click mover built on the existing `IsMoving`/`IsMovingBySpeed` tasks, deliberately not modifying `GoTo.java`). Reuse it verbatim.

- For **Deep&Shallow mode**: `PathFinder`'s existing `waterMode` (public boolean field, `PathFinder.java:24`, consumed by `NPFMap.java:308-316`) already treats `gfx/tiles/water`/`owater`/`deep`/`odeep` as passable and everything else (including land) as blocked — this is an exact match for what "safe shallow/deep water travel" needs. Use `PathFinder pf = new PathFinder(frontierTarget); pf.waterMode = true;` then call `pf.construct()` (returns `LinkedList<Graph.Vertex>`, confirmed at `PathFinder.java:122-250`) to get a full route, rather than calling `pf.run()` (which internally just does one blocking `GoTo` per vertex — same stop-start problem as `WorldExplorer` today).
- Drive the returned vertex list through `WorldExplorerMove.clickAndChase` instead of `GoTo`, chaining waypoints with the non-blocking `IsMoving`/`IsMovingBySpeed` wait so momentum carries across the whole multi-chunk route, not just single tiles.
- **For Deep&Deeper mode**: `NPFMap`'s water-passable set (`NPFMap.java:308-316`) explicitly does **not** include `gfx/tiles/odeeper` — it's treated as blocked, same as land. So `PathFinder.waterMode` is **not usable as-is** for a mode that deliberately wants to travel through/along `odeeper`. Two options, to be decided at implementation time (do not guess further without re-reading `NPFMap` in full):
  - (a) Add a second water-mode variant to `NPFMap` (e.g. an `oceanMode` boolean alongside `waterMode` that also permits `odeeper`) — touches shared pathfinding code, needs care not to affect on-foot/coracle pathfinding elsewhere that also uses `NPFMap`.
  - (b) Skip `PathFinder` entirely for Deep&Deeper mode and drive the frontier leg with `WorldExplorerMove.scanAhead` repeated at chunk scale — a straight-line greedy walk toward the frontier target, gated by `WaterTiles.isSafe(name, true)` at each step, falling back to the stuck-recovery maneuver (section 4) if blocked. Simpler, no shared-code risk, but loses A*'s obstacle-routing intelligence over long distances (mitigated by frontier targets being relatively close — one chunk away — so a long detour is unlikely).
  - **Recommendation: option (b)** for a first implementation — avoids touching shared pathfinding code (`NPFMap.java`) that many other bots depend on, and the risk/reward doesn't favor a shared-code change for one mode of one bot.

### 4. Stuck detection/recovery
Reuse `WorldExplorerStuck` verbatim, as specified in Plan A's Design section 3 (>2s no-progress detection; back-up + swing-wide recovery; abort after repeated failure). Also applicable here: if a `PathFinder`-computed route becomes blocked mid-execution (e.g. a Gob spawned on the path after the route was computed), the same stuck detector triggers, and recovery falls back to local `scanAhead`-based maneuvering, rather than always immediately recomputing a full `PathFinder` route (cheaper, and `PathFinder` route construction itself is not free — it expands a search grid up to ~200 tiles).

### 5. On arrival / re-loop
Once within one tile of the frontier target (or once `WorldExplorerFrontier.markVisited` has been called for that chunk during transit), call `pickFrontierTarget` again from the new position and repeat. If no unvisited neighbor exists in any of the 4/8 directions (fully surrounded by already-visited chunks — unlikely early on, more likely after long runs), widen the search to 2 chunks out, and if still nothing, `Results.ERROR("No unexplored frontier found nearby")`.

### 6. UI
Add to `WorldExplorerWnd`/`NWorldExplorerProp`: no new checkboxes needed beyond the existing clockwise/counterclockwise (repurposed as a tie-breaker preference when multiple frontier directions are equally close, or dropped if frontier selection makes them meaningless — **flag this as a decision to confirm with the user before implementing**, since "clockwise around the coast" doesn't map cleanly onto "pick nearest unvisited chunk"). Same tunables as Plan A (stuck timeout, backup/swing tiles) apply here too since the same `WorldExplorerStuck` is reused.

## Open items to confirm before implementing
1. Exact grid-ID accessor to reuse (needs a quick re-read of `ChunkNavManager.java`/`ChunkNavRecorder.java` grid-ID computation at implementation time to get the precise API call).
2. Whether visited-chunk data should persist across sessions (recommended: yes) or reset each run.
3. Deep&Deeper mode's pathfinding approach — option (a) vs (b) above (recommended: (b)).
4. Whether `clockwise`/`counterclockwise` config still makes sense as a frontier tie-breaker, or should be replaced/removed for this mode.

## Verification
Same approach as Plan A (`ant jar` + manual testing), plus specifically:
- Confirm the bot visits genuinely new chunks over an extended run rather than looping in a small area.
- Confirm persisted visited-chunk data survives a relogin and the bot doesn't immediately re-walk the same water.
- Confirm Deep&Deeper mode never enters land (since it bypasses `PathFinder.waterMode`, this is enforced purely by `WaterTiles.isSafe` gating in the fallback `scanAhead` walk — test this specifically).

## Files touched
- `src/nurgling/pf/WaterTiles.java` (new, shared with Plan A)
- `src/nurgling/actions/bots/WorldExplorerFrontier.java` (new)
- `src/nurgling/actions/bots/WorldExplorerMove.java` (new, shared with Plan A)
- `src/nurgling/actions/bots/WorldExplorerStuck.java` (new, shared with Plan A)
- `src/nurgling/actions/bots/WorldExplorer.java` (rewrite — frontier-driven main loop instead of boundary-hugging)
- `src/nurgling/conf/NWorldExplorerProp.java` (add visited-chunk persistence key reference + shared tunables)
- `src/nurgling/widgets/bots/WorldExplorerWnd.java` (shared tunables; reconsider clockwise/counterclockwise UI per open item 4)
- `src/nurgling/actions/PathFinder.java` — reused as-is (Deep&Shallow mode only), not modified
- `src/nurgling/pf/NPFMap.java` — reference only; touched only if open item 3 is resolved as option (a), not recommended