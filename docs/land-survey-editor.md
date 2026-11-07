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

`MCache.getfz(Coord tc)` returns a tile corner's elevation in **world-Z units**, *not* metres,
despite this doc's first revision assuming otherwise (see "Regression: unit-conversion bug" below
for the fix). `MCache.Grid`'s z-buffer decode (`MCache.java`, the z-format-0..3 branches)
multiplies every server-sent value by `MCache.tilesz.x` (11) when storing it, to align the
elevation scale with the x/y grid; `getfz()`/`getz()` return that pre-multiplied value as-is. The
real-world elevation the server actually sent is recovered by dividing back out by
`MCache.tilesz.x`.

`haven.res.ui.surv.Data` stores the survey's own target heights as an integer array `dz`, where
`dz = round(getfz(vc) * gran)` (`LandSurvey.initsurf()`) — i.e. `dz` is in "world-Z units scaled by
`gran`", not metres directly either. `gran` is set from the server-supplied resource argument at
widget construction (`args[2] / 11`); `wz` is the float working copy `Mover`/`CMover` mutate during
a drag, rounded into `dz` by `Data.dupdate()`.

Converting a `dz` difference to metres therefore needs **two** divisions, not one:
`metres = dzDiff / gran / MCache.tilesz.x` (`SurveyConstraintValidator.metersPerDzUnit(gran)`
computes exactly this, as a single combined factor). The manual soil-placement test's "0.1 m per
unit, blocked near 20" is consistent with the live server's `gran = 10f/11f`: `1 / ((10/11) * 11)
== 0.1`. This also matches the pre-existing (upstream-derived, unmodified) "Peak to trough"
readout in `LandSurvey.updmap()`, which has always divided by a hardcoded `10.0` — that line is
only correct because live `gran` happens to always be `10/11`, making `gran * tilesz.x == 10`; the
constraint engine computes the same factor from `gran` and `tilesz.x` explicitly instead of
hardcoding it, so it stays correct even if a server instance ever sent a different `gran`.

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

**Units:** everything works in the survey's own integer `dz` units, converting to metres only via
the single centralized factor `SurveyConstraintValidator.metersPerDzUnit(gran)` (`1 / (gran *
MCache.tilesz.x)`) wherever a limit (metres) and a difference (dz units) need to be compared.
`SurveyTerrainSnapshot.capture()` also divides `MCache.getfz()` by `MCache.tilesz.x` at capture
time, so `terrainHeight()` always returns real metres too — every value `SurveyConstraintValidator`
and `SurveyDragConstraintSolver` compare is in metres by the time it's compared, with no scattered
hardcoded `10`/`11` conversions anywhere else. The 2.0 m limit itself lives in
`SurveySettings.TERRAIN_MAX_SLOPE_METERS`.

### Regression: unit-conversion bug (fixed)

The first version of this engine (opened as PR #33) computed `dzDiff / gran` and treated that
result as metres directly, and `SurveyTerrainSnapshot.capture()` stored `MCache.getfz()` unchanged
and labeled it "metres" in its own doc comment. Both were wrong in the same way: `getfz()` returns
world-Z units, which are `MCache.tilesz.x` (11) times larger than a real metre (see above), so
every comparison was effectively checking a limit about 11x too strict. In-game testing found
ordinary edges rejected after roughly one 0.1 m soil increment instead of the expected ~20 — the
"effective limit" came out to `2.0 / 11 ≈ 0.18 m`, exactly matching the reported symptom, and
confirming the missing `/ MCache.tilesz.x` factor as the root cause rather than a logic error in
the worsening-aware rule itself (which was, and remains, correct).

**Why the original automated tests didn't catch it:** both test files used an unrealistic
`gran = 10f` constant instead of the real client's `gran = 10f/11f`. With the (buggy) single
division `dzDiff / gran`, `gran = 10f` happens to produce `dzDiff / 10`, which is numerically the
*correct* metres value — by coincidence, not because the code was right. The tests were exercising
a value that only looked right because the chosen test input silently cancelled out the bug. Fixed
by changing both test files' `GRAN` constant to the real `10f / 11f`, which makes the (now
corrected) `metersPerDzUnit()` factor still equal `1/10`, but would have made the original buggy
formula produce `dzDiff * 1.1` — about 11x too large — so the existing assertions now also serve as
regression tests for this exact bug.

**The fix:** added `SurveyConstraintValidator.metersPerDzUnit(float gran)` as the single
authoritative dz-to-metres conversion (`1.0 / (gran * MCache.tilesz.x)`), used by both the
validator and the solver in place of the bare `/ gran` division, and corrected
`SurveyTerrainSnapshot.capture()` to divide `MCache.getfz()` by `MCache.tilesz.x` before storing
it. No change was needed to the worsening-aware comparison logic, the drag-solver's affine-edge
math, or any rendering/UI code — this was purely a unit-conversion defect in how two already-right
formulas were combined.

### Follow-up: actual terrain slopes vs. target slopes, and AT_LIMIT severity

A second in-game report (after the unit-conversion fix landed) raised a distinct concern: the
~2.0 m limit applies to the *real terrain itself*, however it got that way (natural, dug/filled,
or from an earlier survey) — opening a new survey doesn't reset that or grant a fresh 2.0 m of
headroom. Example: if two real, connected vertices already differ by 0.3 m, raising the higher
one alone by the "usual" 2.0 m produces 2.3 m against the untouched one, which must be rejected.

**Investigation finding: this was already handled correctly for the geometry that matters, with
one real gap.** The validator's interior-edge check has always compared *target* height against
*target* height (never target against actual terrain) — which is the right comparison, because
the final real terrain *becomes* the committed target once executed; an interior edge's legality
depends only on the final target shape, not on how far that shape is from today's terrain. Given
that, the 0.3 m example above was already caught correctly, *provided* the compared vertex's
`priorDz` reflects its real height — true whenever a survey is initialized via **Ground Level**
(`LandSurvey.initsurf()`, target := actual terrain) or reopened via `Data.decode()` from a
previously accurate target. Verified with a new test
(`SurveyConstraintValidatorTest#groundLevelInit_raisingAloneRespectsExistingActualSlope` /
`SurveyDragConstraintSolverTest#singleVertexRaiseRespectsExistingActualSlopeWithinSurvey`): the
solver correctly clamps the raise to +17 units (1.7 m), landing at exactly 2.0 m against the
untouched neighbour, not the "fresh" 20.

**Ground Level vs. Ground Plane are intentionally different, and both keep working.** Ground
Plane (`LandSurvey.initplane()`, the default for a brand-new survey) sets every target vertex to
the same flat average, deliberately discarding today's real per-vertex offsets — because the
*final* shape it produces (flat) is self-consistent and legal regardless of what the real terrain
looked like beforehand. A new test
(`groundPlaneInit_flatTargetIgnoresActualSlopeForFinalShapeLegality`) confirms raising one corner
of a flat Ground-Plane target by the full 20 units is correctly valid: the real terrain's 0.3 m
offset is irrelevant to whether the *resulting* shape is legal, only to how much digging/filling
each vertex individually needs (already shown by the existing "soil required/left over" readout).

**The one real gap: no AT_LIMIT distinction.** `validateAbsolute()` only flagged a strictly-greater
comparison, so an edge already at *exactly* 2.0 m (reachable, and not uncommon after a prior
Constrain-mode edit landed exactly on the boundary) got no marker at all, indistinguishable from
one at 0.3 m. Fixed by adding `SurveyConstraintResult.Severity` (`BELOW_LIMIT` / `AT_LIMIT` /
`OVER_LIMIT` / `UNKNOWN`), computed independently of the existing worsening-aware `Status`
(`VALID`/`VIOLATION`/`UNKNOWN`): `Severity` answers "how close to the real limit is this edge,
full stop", `Status` answers "should this specific candidate edit be blocked". The two are
deliberately orthogonal — a pre-existing, unworsened cliff is `OVER_LIMIT` severity but `VALID`
status, and is marked red (not hidden) in the idle view precisely because it's still a real
over-limit edge, even though editing elsewhere in the survey remains unblocked. `AT_LIMIT` never
freezes a vertex: lowering it (reducing the slope) is always legal, only a further *raise* trips
the existing worsening-aware block — unchanged behavior, now just visible on the amber marker
before the user hits it blind.

**Distinguishing cause: real cliff vs. stale target.** Also added `EdgeFinding.
actualTerrainOverLimit` (`Boolean`, null when either endpoint's real height is unknown): whether
the *real, unmodified* terrain at both endpoints is itself already over the limit, independent of
`preexisting` (which is about the previously committed *target*, possibly stale/not-yet-executed
and not reflecting real terrain at all — e.g. a target saved before this engine existed, or
before the survey's dig/fill was executed). `preexisting=true, actualTerrainOverLimit=false`
means "an old invalid proposal, not a real terrain feature, now correctable"; `actualTerrainOverLimit=true`
means "a genuine existing cliff, grandfathered because nothing the player did made it worse."

**Intermediate digging/filling reachability — still unverified, left as a known limitation, not a
new one.** The validator and solver both only ever check the *final* target shape (and, for
`Mover`/`CMover`, the shape the mouse is live-requesting) — never whether the sequence of
intermediate dig/fill operations needed to reach that shape stays legal at every step along the
way (e.g. if the server applies changes vertex-by-vertex rather than atomically). This was already
flagged as an open question in "What's verified vs. assumed" above and remains exactly that
uncertain after this follow-up — no code here claims otherwise, and nothing was added that would
incorrectly reject a geometrically legal target shape on reachability grounds it can't actually
prove from client-side information.

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
never clamped; and a fractional analytic boundary rounds conservatively rather than outward. Added
in the unit-conversion fix: both files now use the real client `gran = 10f/11f` instead of the
original unrealistic `10f` (see "Regression" above), plus explicit cases for each of the 1/2/10/19/
20-unit (0.1/0.2/1.0/1.9/2.0 m) raises individually, a 1.0 m-vs-2.5 m outside-boundary pair, and an
assertion that `EdgeFinding.candidateDiffMeters` reports real metres (≈2.1) rather than the raw
world-Z value the bug would have produced (≈23.1). `NConfigSyncKeysTest` was updated for the one
new config key.

Added in the actual-vs-target follow-up: raising/lowering a vertex that already has a real 0.3 m
offset from its neighbour, under both Ground Level and Ground Plane initialization (validator
level and, for the raise, drag-solver level too, plus a corner-tilt variant clamped by an
existing boundary offset); an edge already at exactly the limit reports `Severity.AT_LIMIT` via
`validateAbsolute()` without being a blocking violation; raising an already-maxed vertex by one
more unit is a confirmed `OVER_LIMIT`/`VIOLATION`, while lowering it is always legal; reopening a
survey on a previously player-created maximum slope reports `hasAtLimit()` without
`hasViolation()`; a genuine existing cliff (target matches a real over-limit terrain feature) sets
`actualTerrainOverLimit = true`, while a stale/invalid saved target over a flat real terrain sets
it `false` while still `preexisting`.

**Not covered by automated tests** (needs the full widget/game harness, or live verification):
Undo/Redo interaction with a clamped drag end-to-end through `LandSurvey`/`SurveyEditHistory`;
mode changes while a survey window is already open; multiple simultaneous survey sessions;
intermediate dig/fill reachability (see the follow-up section above). `ant jar`/`ant test` were
both run clean (see the PR description for the run).

## Known limitations and proposed follow-ups

- **Diagonal rule unverified** — see "What's verified vs. assumed" above for the exact test.
- **No edge-highlighting**, only vertex markers: `Display` gained a third halo-style render pass
  (three flag bits, 16 = over-limit violation / red, 64 = at-limit / amber, 32 = unverifiable /
  blue) that rings a vertex without touching its existing status-dot color, rather than drawing a
  colored line along the specific edge. A later pass could add a `LINES`-mode overlay keyed off
  `SurveyConstraintResult`'s edge list, the same way the existing selection outline/wireframe
  passes work — deferred here to keep this PR's render-pipeline surface small.
- **Intermediate dig/fill reachability is unverified** (see the actual-vs-target follow-up
  section above) — only the final target shape (and the live drag shape) is checked, never
  whether the server executes a survey's changes in a way that could reject or reorder
  intermediate steps.
- **Paved tiles, buildings, water-adjacency are not checked at all.** `Ridges.brokenp()` is
  captured in every `SurveyTerrainSnapshot` for a future rule to use, but no such rule exists yet.
- **No opt-in developer diagnostic overlay** (a full per-vertex current/requested/applied readout)
  was added; `LandSurvey`'s `terrlbl` status line (edge-over-limit count, worst exceedance, or an
  "unverifiable nearby" message) now also appends the limiting edge's two vertex coordinates while
  Constrain mode is actively clamping a drag (`LandSurvey.lastClamp`), but there's still no
  always-available readout of, e.g., a specific selected vertex's raw dz/terrain values.
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
   perfectly flat ground, to confirm the corrected `metersPerDzUnit()` conversion matches the live
   server — this is the exact check that caught the original unit-conversion regression, so it's
   worth re-confirming specifically: a vertex on flat ground should now accept roughly 20 units
   (2.0 m) before Constrain mode stops it, not 1-2 units as in the regressed build.
7. On terrain with an existing real slope (not flat), raise the already-higher of two connected
   corners with Ground Level active. Confirm it's blocked noticeably *before* the usual ~20 units
   — by roughly the size of the existing real offset — not given a fresh 20 regardless. Repeat
   with Ground Plane active on the same terrain and confirm the flat target still accepts the
   full ~20 units on one corner (the two modes should behave differently here, not identically).
8. Drive a vertex to exactly the 2.0 m limit in Constrain mode, release, then reopen the status
   view (or just let it idle). Confirm the vertex shows the amber at-limit marker (not plain green,
   not red), and that lowering it is still possible while raising it further is still blocked.
9. With that same at-limit vertex, confirm the status line distinguishes "at the limit" wording
   from "over the limit" wording, and that a genuine pre-existing cliff elsewhere in the survey
   still shows red (over-limit) even though editing unrelated vertices remains unblocked.
