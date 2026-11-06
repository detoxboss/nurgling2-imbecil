# Land Survey editor overhaul

**Kind:** current. Covers the Land Survey editor (`haven.res.ui.surv.LandSurvey`/`Display`/`Data`)
as modified by this fork, in two phases: Phase 1 (appearance settings, lock fix, undo/redo,
height-overflow guard — merged as PR #32) and Phase 2 (terrain slope constraint engine, this doc's
main subject). See `docs/fork-customization-ledger.md` for the file-level change list of both
phases; this doc is the investigation/evidence record Phase 2 was built from.

## Phase 1 summary

Survey Settings panel (Nurgling Settings → Game environment → Survey Settings), a lock-button
hit-test fix, per-instance bounded undo/redo (`nurgling.survey.SurveyEditHistory`), and a height
span encoding guard (`Data.encode()` rejects spans over `Data.MAX_SPAN` = 255 instead of silently
wrapping). Appearance/behavior model lives in `nurgling.survey.SurveySettings`, backed by
`NConfig`. See the ledger for the full list.

## Phase 2: terrain constraint engine

### Goal

Prevent the editor from letting a user shape a survey's target heights into something the game's
own terraforming can't actually produce, without being more restrictive than the real rule where
that rule is uncertain.

### What's verified vs. assumed

**Verified by a manual in-game test** (reported by the requester, not independently reproduced by
this change): placing soil on one corner of near-level ground raises that vertex by approximately
0.1 m per unit of soil, and further placement is blocked after roughly 19–20 units — i.e. an
observed limit around 2.0 m between that corner and its (unraised) neighbors.

**From the cited wiki pages** (`ringofbrodgar.com/wiki/Terraforming`, `.../wiki/Survey`) and forum
thread: a maximum slope of approximately 2.0 m is described as applying between "connected"
corners. Neither page was fetched as part of this change (no network access during
implementation) — their content is taken as given by the task description, not re-verified here.

**Not verified, and explicitly left uncertain by design:**
- Whether the 2.0 m rule applies only to the four edge-adjacent corner pairs of a tile, or also
  to its two diagonal pairs. The manual test (raising one corner on otherwise-flat ground) cannot
  distinguish these: both interpretations predict a block somewhere in the 19–20 unit range for
  the edges, and the diagonal distance is 1.41x longer, so a diagonal-inclusive rule would block
  at a different count there, not the one actually observed. **In-game test that would resolve
  it:** on a flat 1x1 tile survey, raise one corner until the dig/fill display or an attempted
  push is rejected on the two edge-adjacent corners; then check whether the untouched diagonal
  corner shows any limit indicator (or whether the push keeps succeeding past the edge-adjacent
  corners' limit). The validator defaults to edge-only (`SurveyConstraintValidator.CHECK_DIAGONALS
  = false`) and exposes the alternative as an internal, non-persisted switch rather than a user
  setting, specifically so flipping it doesn't need a client update once someone runs that test.
- Whether an existing cliff/ridge tile, paved tile, a building, a tree, or water-adjacent terrain
  changes the applicable limit, is itself exempt, or interacts with terraforming at all beyond
  what `haven.resutil.Ridges.brokenp()` already models (a tile-type classification used elsewhere
  in this fork by `CliffCorridorChecker` for pathfinding, not a height-difference rule). This PR
  does not attempt to incorporate tile classification into the slope check at all — see "Known
  limitations" below.
- Whether the server's real constraint is evaluated on the *committed* target shape only, or also
  restricts what intermediate states are reachable via the client's own drag UI (irrelevant to a
  server-side check, since the server only ever sees the final submitted `data` message — but
  relevant to whether Constrain mode's live clamping during a drag could ever reject something the
  server would have accepted via some other path, e.g. multiple smaller edits). Not testable
  without server access; assumed the server only cares about the final committed shape.

### Where terrain height data comes from

`MCache.getfz(Coord tc)` returns a tile corner's elevation in metres (`Grid.getz`, ultimately from
the server's terrain data); `MCache.tilesz` is 11x11 (world units per tile, matching the game's
known 11 m tile size). `haven.res.ui.surv.Data` stores the survey's own target heights as an
integer array `dz` in units of `1/gran` metres, where `gran` is set from the server-supplied
resource argument at widget construction (`args[2] / 11`); `wz` is the float working copy
`Mover`/`CMover` mutate during a drag, rounded into `dz` by `Data.dupdate()`. The manual
soil-placement test's "0.1 m per unit, blocked near 20" is consistent with `gran = 10` in whatever
units the live server actually sends — the engine never hardcodes that assumption, converting via
the survey's own `gran` at every boundary instead (`SurveyConstraintValidator`/
`SurveyDragConstraintSolver` both take `gran` as an explicit parameter).

`Display.vfill()` already reads `map.getfz(vc)` per vertex every frame (for the fill/dig status
dot color) and tolerates `haven.Loading` by treating that vertex as matching target height for
*rendering* purposes only — explicitly not a precedent followed here, since the constraint engine
must never substitute a default height for unknown terrain (`SurveyTerrainSnapshot` represents
"unknown" as an absent map entry, not zero or any other sentinel value).

### Identifying shared vertices and outside-boundary terrain

`Data.varea` is the survey's vertex area (one larger in each dimension than its tile area
`Area`). `Area.ridx(Coord)` returns the row-major index of a vertex within some area, or -1 if the
coordinate isn't in it — callers must check `contains()` first. A vertex's four
grid-adjacent neighbors outside the survey (needed to check the boundary-crossing edges) are
reached by probing `Area.margin(1)` (the vertex area expanded by one in every direction) via plain
`Coord` arithmetic; no existing utility already does this for the survey system specifically.

### Existing cliff/obstruction detection

`haven.resutil.Ridges.brokenp(MapSource, Coord)` (used today by
`nurgling.actions.bots.forager.CliffCorridorChecker` to keep a Forager bot from pathing across a
cliff edge) reports whether a tile is a "broken" ridge tile — a tile-type classification, not a
height-difference measurement. `SurveyTerrainSnapshot` captures this per probed tile alongside
elevations, but **this PR does not yet use it** in the slope validator itself (see "Known
limitations"); it's captured now so a later PR can add tile-type-aware rules without touching the
snapshot's capture logic again.

No other existing client code detects "steep terrain" or terraforming limits — Ridges' cliff
tiles and the slope rule here are two independent things that happen to both relate to cliffs.

### How the client learns about terrain changes

`MCache.chseq` increments on any terrain change the client receives; `Display` already compares
against a cached `mapseq` every `autogtick`. The constraint engine's own terrain snapshot
(`LandSurvey.terrainSnapshot(MCache)`) follows the same pattern: cached, rebuilt only when
`chseq` no longer matches the value recorded at capture time.

### How dragging changes target heights

`LandSurvey.Mover` (whole-selection vertical move) and `LandSurvey.CMover` (four-corner
tilt/interpolation) both reduce to one scalar `diff` (rounded to an integer every mouse-move)
applied to each affected vertex through a fixed per-vertex coefficient — 1 for every vertex in
`Mover`; a bilinear corner weight in `CMover`, computed once at gesture start from which corner
(`ul`/`ur`/`bl`/`br`) is being dragged. `Ground Level`/`Ground Plane` instead set every target
height directly from (respectively) each vertex's own current terrain height, or the
whole-selection average — a single-shot whole-array write, not a `diff`-parameterized drag.

### How Undo/Redo and Ground Level/Ground Plane modify the target array

`SurveyEditHistory` (Phase 1) records one `Edit` (an index list plus before/after float arrays)
per completed gesture or Ground Level/Ground Plane click, applying the recorded array slice
directly back onto `Data.wz` on undo/redo. None of this is touched by Phase 2: the constraint
engine only ever decides what gets *proposed* to history (by clamping a drag's `diff` before it's
applied, in Constrain mode), never how history itself replays. See "User interface" below for why
Ground Level/Ground Plane and Undo/Redo are warned-about rather than blocked or auto-corrected.

## Architecture

```
SurveyTerrainSnapshot   -- immutable capture: vertex heights (metres, nullable-absent = unknown)
                           + per-tile cliff classification, around a survey's vArea + 1-vertex ring
SurveyConstraintResult  -- VALID / VIOLATION / UNKNOWN per edge, plus an overall status
SurveyConstraintValidator
  .validate(...)          -- worsening-aware: does a candidate shape make any edge *worse* than
                              the survey's last committed shape already was
  .validateAbsolute(...)  -- informational: is any edge over the limit at all (used for idle/live
                              status display, never to decide whether an edit is allowed)
SurveyDragConstraintSolver
  .clamp(...)              -- exact per-edge analytic solve for the largest same-direction drag
                              amount that doesn't newly violate anything, given a gesture's
                              per-vertex coefficients; used only in Constrain mode
```

All four types are plain data/logic classes with no dependency on `MCache`, `NConfig`, or the
renderer — `SurveyTerrainSnapshot.capture()` is the one method that touches `MCache`, and tests
build snapshots directly via `SurveyTerrainSnapshot.forTesting()` instead.

**Units:** everything works in the survey's own integer `dz` units plus its `gran`, converting to
metres only where a limit (metres) and a difference (dz units) need to be compared. The 2.0 m
limit itself lives in `SurveySettings.TERRAIN_MAX_SLOPE_METERS`.

**Worsening rule:** an edge is a blocking `VIOLATION` iff its candidate difference exceeds
`max(limit, priorDifference)` — equivalent to "exceeds the limit AND is worse than it already
was," collapsed into one comparison. This is what lets an existing over-limit cliff be edited
towards legality (allowed) without permitting it to be made worse (blocked), per spec, without
needing a separate "is this edge already broken" branch.

**Drag solver math:** both `Mover` and `CMover` apply a single scalar `diff` through a
per-vertex-fixed coefficient, so any one edge's height difference is an affine function of `diff`
alone (`d(diff) = C + diff*D`). Since the gesture's starting state is itself never a (newly)
blocking violation by construction, solving `|C + diff*D| = max(limit, priorDiff)` directly gives
the exact boundary where that edge would start blocking — no iterative search, and no assumption
that the clamp is monotonic *across* edges (the final answer is simply the tightest of each
edge's independently exact threshold). The result is rounded conservatively (towards zero) before
being applied, so the integer `dz` that `Data.dupdate()` eventually rounds to never overshoots due
to rounding the wrong way.

## Testing

`test/nurgling/survey/SurveyConstraintValidatorTest.java` and
`SurveyDragConstraintSolverTest.java` cover (synthetic 1x1-tile grids, no game resources needed):
flat terrain is valid; exactly-at-limit is valid and one unit past it is a violation; diagonal
corners are excluded by default and included when the internal flag is flipped; downward moves are
symmetric; a uniform-translation gesture leaves interior edges unaffected; a boundary-crossing
restriction just outside the survey is caught; a pre-existing over-limit edge doesn't block
unrelated edits; editing an over-limit edge towards (but not past) legality is allowed while
editing it further isn't; missing outside terrain is reported as unknown, never as zero; a
whole-survey translation and a corner tilt both clamp against the correct limiting edge; unknown
outside terrain never clamps a drag (only surfaces as a warning flag); a zero-diff gesture is
never clamped; and a fractional analytic boundary rounds conservatively rather than outward.
`NConfigSyncKeysTest` was updated for the one new config key.

**Not covered by automated tests** (needs the full widget/game harness, or live verification):
Undo/Redo interaction with a clamped drag end-to-end through `LandSurvey`/`SurveyEditHistory`;
mode changes while a survey window is already open; multiple simultaneous survey sessions;
`ant jar`/`ant test` were both run clean (see the PR description for the run).

## Known limitations and proposed follow-ups

- **Diagonal rule unverified** — see "What's verified vs. assumed" above for the exact test.
- **No edge-highlighting**, only vertex markers: `Display` gained a third halo-style render pass
  (two new flag bits, 16 = violation / 32 = unverifiable) that rings a vertex without touching its
  existing status-dot color, rather than drawing a colored line along the specific violating edge.
  A later pass could add a `LINES`-mode overlay keyed off `SurveyConstraintResult`'s edge list, the
  same way the existing selection outline/wireframe passes work — deferred here to keep this PR's
  render-pipeline surface small.
- **Paved tiles, buildings, water-adjacency are not checked at all.** `Ridges.brokenp()` is
  captured in every `SurveyTerrainSnapshot` for a future rule to use, but no such rule exists yet.
- **No opt-in developer diagnostic overlay** (per-vertex current/requested/applied/limiting-edge
  readout) was added; `LandSurvey`'s new `terrlbl` status line (edge-over-limit count, worst
  exceedance, or an "unverifiable nearby" message) is the only runtime visibility beyond the
  vertex markers themselves.
- Constrain mode's clamp only ever restricts a drag to a smaller amount in the same direction as
  requested — it never offers a "snap to the nearest legal shape in some other direction"
  suggestion.

## In-game verification checklist

1. Open a survey, set Terrain Constraints to Warn (the default), and raise one corner of an
   otherwise-flat 1x1 selection well past where the manual soil test blocked placement. Confirm
   the corner and the status line light up (over-limit count, worst exceedance) without the drag
   being stopped.
2. Repeat with Constrain selected: confirm the drag visibly stops a few units before the point
   Warn flagged, and that releasing the mouse there commits cleanly (Undo still works afterwards).
3. With a pre-existing cliff (or a manually-forced over-limit edge from a prior test run) inside
   a survey, confirm Constrain mode still allows edits elsewhere in the same survey, and allows
   dragging that specific edge towards (but not past) legality.
4. Toggle Off/Warn/Constrain while a survey window is already open and confirm the change applies
   on the next tick without reopening the window.
5. Walk a survey's edge right up against unloaded terrain (e.g. a chunk boundary that hasn't
   rendered in yet) and confirm the status line reports "unverifiable," not a false "OK."
6. Compare this validator's block point against the exact in-game soil-placement limit on fresh,
   perfectly flat ground, to confirm the assumed `gran`-to-metres conversion matches the live
   server (the engine itself never hardcodes `gran`, but the 2.0 m constant and this comparison
   are the thing that would reveal a mismatch).
