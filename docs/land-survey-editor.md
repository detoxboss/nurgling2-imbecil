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
observed limit around 2.0 m between that corner and its (unraised) neighbors. **Caveat added in
Follow-up #4:** "0.1 m per unit" and "19–20 units" both implicitly assume `gran = 10f/11f`; that
test never logged its own live `gran`, and Follow-up #4 separately found a live survey with
`gran == 1` instead (where the same real-world 2.0 m would instead show as 22 dz units at
≈0.091 m each). The *metres* figure (≈2.0 m) is gran-independent and still the best-supported
number; the *dz-unit* figure is not, and should not be read as confirming which `gran` the
original manual test ran under. **Caveat added in Follow-up #5:** even the "metres figure is
gran-independent" framing above is now itself only one of two live hypotheses — a direct in-game
test under `gran = 1` found a real cap of exactly 20 raw dz-ticks (1.818 m), not 22 ticks (2.0 m);
see Follow-up #5 for the full writeup and the diagnostic-only Model C this added to compare against
further testing.

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
SurveyVertexDiagnostics  -- read-only, on-demand per-vertex inspection (actual vs. target height,
                              all 8 neighbors, all 4 sharing tiles, Model A vs. B's limiting
                              relationship); never consulted by validate()/clamp()
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

### Follow-up #3: diagonal-corner discrepancy investigation

A third in-game report, after the actual-vs-target follow-up landed: on real (non-flat) terrain, a
vertex manually raised with soil until the server refused further placement (i.e. already at the
real limit) still showed up to ~0.2 m of additional headroom in the editor before AT_LIMIT, and a
large survey reported some edges as far over the limit as expected while individually-inspected
vertices looked fine within 0.1-0.2 m of their starting height. Primary hypothesis: the validator's
edge-only model (`SurveyConstraintValidator.CHECK_DIAGONALS = false`, unchanged since Phase 2 —
see "What's verified vs. assumed" above) misses a tile's two diagonal corner-pairs, which can be
the tightest relationship in a tile even when every edge-adjacent pair is comfortably under the
limit.

**Confirmed architecturally, not yet confirmed against the server.** Worked a concrete example:
a vertex 2.0 m above a flat plane, with both of its edge-adjacent neighbors at 0.2 m (so each
edge-adjacent difference is 1.8 m — comfortably legal) and its diagonally-opposite corner still at
0 m (so the diagonal difference is the full 2.0 m — already at the true limit). Edge-only Model A
reports 0.2 m of headroom that Model B (every one of a tile's six corner pairs, i.e.
`max(four corners) - min(four corners)`) correctly reports as none. Pushing the spike one more
0.1 m unit past that point: Model A still reports zero violations survey-wide, while Model B
catches it immediately — this is architecturally identical to the reported "extra 0.1-0.2 m before
anything shows" symptom. See
`SurveyConstraintValidatorTest#compareModels_hypotheticalSpike_modelBSeesTighterWorstThanModelA`
and `#compareModels_spikePastDiagonalLimit_modelAMissesIt_modelBCatchesIt` for the worked numbers
(built on a 2x2-tile survey so the spike vertex is strictly interior — a 1x1-tile survey's every
vertex is itself a boundary corner, which independently produces a same-sized boundary edge against
flat outside ground and would mask the exact gap being demonstrated).

**What this does *not* establish:** that the server's real rule actually is "all six corner pairs,
same flat 2.0 m limit, no distance scaling." That remains exactly as unverified as it was in Phase
2 — the task that prompted this investigation explicitly noted the diagonal distance is ~1.41x an
edge's, so a distance-scaled diagonal threshold (e.g. ~2.83 m) is equally consistent with "the
manual soil test blocked around 19-20 units on edge-adjacent corners" as the flat-2.0m-everywhere
Model B implemented here for comparison purposes. **No distance scaling was implemented** — Model B
uses the exact same flat limit for every corner pair, since that's the literal comparison the task
asked for and the manual soil-placement evidence (blocked after the real terrain already had no
room left, consistent with *some* diagonal check existing, but not which threshold it uses)
doesn't distinguish the two. `CHECK_DIAGONALS` stays `false` and CONSTRAIN mode's actual blocking
behavior is **unchanged** by this follow-up — flipping it, or picking a distance-scaled variant,
needs the specific in-game test described below first.

**What was added, read-only:**
- `SurveyConstraintValidator.edgesWithin(Area, boolean)` — the existing diagonal-aware edge
  enumeration, with the diagonal switch now also takeable as an explicit parameter instead of only
  via the static `CHECK_DIAGONALS` flag, so a comparison can evaluate Model B without touching the
  flag that governs production behavior.
- `SurveyConstraintValidator.compareModels(...)` — evaluates a survey's current committed shape
  under both models (interior tile corner-pairs, and boundary vertices against real outside
  terrain) from the same snapshot, returning each model's violation count and worst difference.
  Never read by `validate()`/`clamp()`; purely an evidence-gathering tool.
- `nurgling.survey.SurveyVertexDiagnostics` — a per-vertex, read-only report: actual terrain and
  target height for the inspected vertex and all 8 neighbors (4 cardinal, 4 diagonal); each of the
  4 tiles sharing it (per-tile corner spread, actual and target); which relationship each model
  considers tightest (`Relationship`, both from target heights — what `validate()`/`clamp()`
  actually compare — and from real terrain, for comparison); and a target-based "how far could
  this vertex still be raised before each model's tightest relationship hits the limit" figure
  (negative if already over). A vertex outside the survey has no target of its own, so its
  "target" is defined as its unchanged real terrain height, per spec.
- An in-game **Diagnostics** toggle button in the Land Survey window, below Undo/Redo: when on, a
  fixed stack of label lines shows the whole-survey Model A-vs-B comparison plus the full
  `SurveyVertexDiagnostics` report for whichever vertex is currently hovered/selected — reusing
  `Display.selected` (already tracked by the existing Idle-mode hover highlight), so it adds no new
  mouse handling and never interferes with editing. Updated once per tick while enabled; cleared to
  a hint message when nothing is selected.

**The in-game test that would actually resolve this** (still not run, same caveat as the original
diagonal-rule question in Phase 2): on a flat multi-tile survey, raise one *interior* vertex (not a
survey corner, so no boundary edge confounds the reading) until its edge-adjacent neighbors show
the dig/fill limit; open the Diagnostics panel on that vertex and compare its Model A vs. Model B
permitted-raise figures against whether the server actually still accepts more soil at that point.
If the server stops accepting soil at Model B's figure rather than Model A's, that's confirmation
to flip `CHECK_DIAGONALS` (or implement whatever distance-scaled variant the evidence actually
supports) in production; if it stops partway between the two, neither model as implemented is
exactly right and the distance-scaling question needs its own follow-up.

### Follow-up #4: unit/threshold semantics -- live `gran` is not always 10/11

New in-game screenshots from the Follow-up #3 diagnostics panel contradicted this document's and
the test suite's standing assumption that live `gran` is "always" `10f/11f`. Observed sequence
(raising one vertex by 1 dz unit at a time, on real sloped terrain, against a northern neighbor
whose N/NE/NW relationships all tied so Model A and Model B agreed throughout — ruling out the
Follow-up #3 diagonal hypothesis for *this* symptom specifically):

| | baseline | +1 | +2 | +3 |
|---|---|---|---|---|
| target height | 21.73 | 21.82 | 21.91 | 22.00 |
| northern target diff | 1.82 | 1.91 | 2.00 | 2.09 |
| A/B permitted raise | 0.18 | 0.09 | 0.00 | -0.09 |

The real terrain's own height at that peak stayed 21.73 throughout — it had already been raised to
its true, server-enforced maximum before the survey was even opened, which is the original "extra
0.1-0.2 m of headroom the editor shouldn't be showing" symptom this PR exists to diagnose.

**Root cause: the sequence fits `gran = 1` (raw `mkwidget` `args[2] == 11`), not `gran = 10f/11f`.**
`target = dz / 11` reproduces every one of the eight numbers above to 2 decimal places from the
raw fixture `dz = 239, 240, 241, 242` against a flat northern/NE/NW neighbor at `dz = 219`
(`SurveyVertexDiagnosticsTest#gran1_observedPeakSequenceMatchesScreenshots_modelsAgreeNoDiagonalAdvantage`,
`SurveyConstraintValidatorTest#gran1_observedPeakSequenceReproducesAtLimitThenOverLimitBanding`).
Under the assumed `gran = 10f/11f`, the same dz steps would move the target by exactly 0.10 m each,
not the observed 0.09 m — the screenshots rule that assumption out directly, independent of any
diagonal reasoning. **This is not a bug in `metersPerDzUnit()` or in the validator's arithmetic**:
both already take `gran` as a per-call parameter and compute correctly for whatever value they're
given. The bug was in two places that assumed a specific value instead of using what was passed:

1. A stale doc comment on `SurveyConstraintValidator#metersPerDzUnit` claimed live `gran == 10f/11f`
   was "verified empirically." That claim is now corrected in place.
2. A real, independent bug: `LandSurvey#updmap`'s "Peak to trough" status line hardcoded
   `(max - min) / 10.0`, baking in the same false assumption as a literal constant rather than
   calling `metersPerDzUnit(data.gran)` like every other readout in this file already does. Fixed
   to use the shared conversion, so it now stays correct for whatever `gran` a given survey
   actually has.

**The 2.0 m limit's own provenance is independently traced, not re-derived from the `*11` world-Z
decode.** `MCache.tilesz.x == 11` and the `* 11` literal in `MCache.Grid`'s z-buffer decode (the
protocol's own scale factor, not something derived from `tilesz.x`) are two separately-sourced
facts that happen to share a value; neither one establishes that 2.0 m is the real terraforming
limit, which still traces only to the wiki citation in `SurveyConstraintValidator`'s class doc
comment. The manual soil-placement test that seemed to additionally corroborate 2.0 m never
logged its own live `gran`, so — now that `gran` is known to vary — that test cannot be assumed to
have been run under `10f/11f` either, and does not independently confirm 2.0 m under whatever
`gran` it actually used. Treat 2.0 m as the same best-current-guess it always was, not as
re-verified by this follow-up.

**Both models agreeing here does not clear the diagonal question from Follow-up #3** — it just
means this particular symptom isn't an example of it (N, NE, and NW all tied at the same
diff, so there was no diagonal relationship left for Model B to catch that Model A didn't already
see). Follow-up #3's own open question (whether the server checks diagonals at all, and at what
threshold) remains exactly as open as it was; checklist item 11 below still covers it.

**What was added:**
- The per-vertex diagnostics panel (`SurveyVertexDiagnostics`) now also reports: the raw
  pre-`/11` `mkwidget` `args[2]` value, the derived `gran` and `metersPerDzUnit`, the inspected
  vertex's raw `dz` integer (when it's inside the survey), the 2.0 m limit expressed in all three
  units (metres, dz-units, world-Z), a manually-bumped build tag, and — per direction — both the
  absolute neighbor height and the *signed* difference (vertex minus neighbor), not only the
  unsigned difference as before.
- `LandSurvey` now calls `pack()` after every diagnostics update. The panel's `Label`s resize
  themselves on `settext()` (`haven.Label#settext` already does this), but nothing previously
  re-fit the containing `Window` around them, so long diagnostic lines rendered clipped at the
  window's original (near-empty-text) edge. `pack()` is a no-op once the size stabilizes, so
  calling it every tick the panel is open is cheap.
- `SurveyConstraintValidator#metersPerDzUnit`'s doc comment no longer claims live `gran` is a known
  constant.
- `LandSurvey#updmap`'s "Peak to trough" readout no longer hardcodes `/ 10.0`.

**What this does *not* establish:** which of the two models (edge-only vs. all-corners) the server
actually enforces, or whether it enforces a flat or distance-scaled diagonal threshold — none of
that was resolved by this follow-up, since the reported symptom turned out to be fully explained by
the `gran` mismatch instead. It also does not establish that `gran` is always `1` either, any more
than the earlier assumption that it's always `10f/11f` was justified — the only safe conclusion is
that it varies per survey and must always be read from `mkwidget`'s `args[2]`, never assumed. The
controlled live experiment described in the PR body (recording one accepted soil placement's raw
height change, the next refused attempt, and repeating after lowering one increment and on another
patch) has not been run; nothing in this follow-up should be read as having run it.

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

### Follow-up #5: dz-native raw-tick candidate model (Model C)

Further screenshots from the same gran=1 session showed the *same symptom persisting* after
Follow-up #4's fix: a vertex raised on otherwise-flat ground against an untouched, flat neighbor
was reported by the server as already at its real maximum — matching the user's own direct in-game
test, which found the proven cap was exactly **20 raw dz-ticks** of soil placement (on a 5x2-tile,
4-center-vertex plateau, 20 dirt per vertex, 80 total) — while the Diagnostics panel still showed
0.18 m of apparent headroom at that same vertex:

| vertex dz | neighbor dz | raw tick diff | target diff (gran=1, mpd=1/11) | Model A/B permitted raise |
|---|---|---|---|---|
| 241 (proven real cap) | 221 | 20 | 1.818 m | +0.18 m |
| 242 (+1) | 221 | 21 | 1.909 m | +0.09 m |
| 243 (+2) | 221 | 22 | 2.000 m | 0.00 m |
| 244 (+3) | 221 | 23 | 2.091 m | -0.09 m |

Hand arithmetic directly from these screenshots (`20.09 m / (1/11) = 221` ticks; `241 - 221 = 20`)
confirms the real achieved cap is exactly 20 raw dz-ticks — two ticks short of the 22 ticks (2.0 m
/ `metersPerDzUnit(gran=1)`) Model A/B predict. Model A/B were never wrong about `gran`-aware
arithmetic (that was Follow-up #4); the discrepancy here is a *different* assumption: that the
2.0 m limit is metres-native and should be re-derived into dz-ticks via whatever `gran` the current
survey happens to report, rather than being a fixed dz-tick count the server checks directly
(`gran` being purely a client/UI display parameter unrelated to the server's own internal height
step).

**Why 20 ticks, not a coincidence:** if the historical "2.0 m" wiki figure was itself measured
under the long-assumed `gran = 10f/11f` convention, `20 ticks * metersPerDzUnit(10f/11f)` (`0.1`)
also equals exactly 2.0 m — the *same* raw-tick count reconciles both the old figure and this new
gran=1 observation, which a metres-native limit re-divided by the current gran cannot do (it
predicts a different tick count, 22, at gran=1). That is suggestive, not proof: the old figure's
own `gran` was never logged (Follow-up #4), so this reconciliation rests on an assumption about it,
not a second confirmed observation, and no explicit "21st placement refused" message has been
captured yet either.

**Added as a new candidate model, not a production change:** `SurveyConstraintValidator` gained
`RAW_TICK_LIMIT_HYPOTHESIS = 20` and a Model C (same cardinal-only edge topology as Model A, raw
`dz`-tick differences compared directly against that constant, no `gran`/`mpd` conversion between
two in-survey endpoints — a boundary edge against undisturbed outside terrain still converts that
real-metres height into the current survey's own tick-equivalent, so Model C is only fully
gran-invariant for edges entirely inside the survey). `compareModels()`'s `ModelComparison` gained
`modelCViolations`/`modelCWorstTicks`; `SurveyVertexDiagnostics.Report` gained
`rawTickDiffByDir`/`modelCLimiting`/`modelCPermittedRaiseTicks`; the Diagnostics panel gained a
line showing Model C's limiting relationship and permitted raise in ticks, and the whole-survey
summary line now also reports Model C's violation count and worst tick diff. **This does not
change `validate()`/`clamp()`/`maxSlopeMeters` or any blocking behavior** — Model C is diagnostic
only, exactly like Model B, until further live evidence (a third `gran` data point, or an explicit
refusal-message log) either confirms or refutes it.

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

Added in the diagonal-corner discrepancy investigation: `SurveyConstraintValidatorTest` gained
`compareModels_*` cases building the task's exact hypothetical spike (and a push past it) on a
2x2-tile survey so the spike vertex is strictly interior, a mild uniform slope where both models
agree, a spike whose diagonal partner is lower than its edge-adjacent neighbors (so the diagonal
is strictly the tightest relationship, not just tied), and a boundary tile whose diagonal partner
is outside the survey (confirming the boundary comparison uses real outside terrain, same as the
existing cardinal boundary check already did). A new `SurveyVertexDiagnosticsTest` covers the same
spike scenario from the inspected vertex's own report (confirming Model A's and B's differing
limiting relationship and permitted-raise figures), a tile's corner-spread figure reflecting all
four corners rather than only its edges, an unknown real-terrain neighbor never being treated as a
known zero, and the panel's whole-survey summary line matching `compareModels()` directly (i.e.
the diagnostic UI reads the real comparison rather than reimplementing it).

Added in the Follow-up #4 unit/threshold investigation: `metersPerDzUnit_gran1EqualsOneEleventhNotOneTenth`
in both `SurveyConstraintValidatorTest` and `SurveyVertexDiagnosticsTest` — pure conversion
arithmetic (`gran = 1` gives `1/11 m` per dz unit, not `1/10`), deliberately kept separate from any
claim about the server's real limit. Separately, two regression tests reproduce the exact
Follow-up #4 screenshot sequence under `gran = 1`: `SurveyVertexDiagnosticsTest
#gran1_observedPeakSequenceMatchesScreenshots_modelsAgreeNoDiagonalAdvantage` checks the
diagnostics `Report`'s target height, northern/NE/NW diff, and Model A/B permitted-raise figures
match the screenshots' baseline/+1/+2/+3 numbers to 2 decimal places and that A and B agree
(diagonals tie, not exceed); `SurveyConstraintValidatorTest
#gran1_observedPeakSequenceReproducesAtLimitThenOverLimitBanding` checks the same dz fixture
through `validateAbsolute()` directly, reproducing the AT_LIMIT-then-OVER_LIMIT severity/status
transition at +2/+3. Both are explicitly commented as assuming the current best-guess 2.0 m limit,
not as independent confirmation of it.

Added in the Follow-up #5 Model C investigation: `rawTickLimitHypothesis_isTwenty` (the constant
itself); `gran1_observedCapSequence_modelCDisagreesWithModelA` reproduces the 20/21/22/23-tick
sequence via `compareModels()` on an interior center vertex (isolated from boundary-vs-outside
contamination, which a 1x1-tile survey's all-boundary vertices would otherwise introduce), showing
Model C calls the +1 case a violation while Model A still reports it `VALID`; and
`gran1_observedCap_modelCPermittedRaiseIsZeroTicksAtTheRealLimit` checks
`SurveyVertexDiagnostics.compute()` directly: at the proven real cap, Model C's permitted raise is
exactly 0 ticks while Model A's is still the false +0.18 m. `ant test`: 424/424 (up from 421).

**Not covered by automated tests** (needs the full widget/game harness, or live verification):
Undo/Redo interaction with a clamped drag end-to-end through `LandSurvey`/`SurveyEditHistory`;
mode changes while a survey window is already open; multiple simultaneous survey sessions;
intermediate dig/fill reachability (see the follow-up section above). `ant jar`/`ant test` were
both run clean (see the PR description for the run).

## Known limitations and proposed follow-ups

- **Diagonal rule unverified** — see "What's verified vs. assumed" above and "Follow-up #3" for
  the exact in-game test, and `SurveyConstraintValidator.compareModels`/`SurveyVertexDiagnostics`
  for the read-only tooling added to gather that evidence without changing production behavior.
  Even once "some diagonal check exists" is confirmed, whether it's a flat 2.0 m limit on every
  corner pair (as Model B assumes for comparison) or a distance-scaled one is a separate, still
  entirely open question.
- **`gran` is not a known constant** — Follow-up #4 found a live survey with `gran == 1` (raw
  `mkwidget` `args[2] == 11`), contradicting this document's own prior assumption that it was
  always `10f/11f`. Both are now confirmed to be *possible* values; neither is confirmed as *the*
  value for any survey other than the one each screenshot came from. Every conversion already
  takes `gran` as a parameter rather than hardcoding it, except the two now-fixed spots noted in
  Follow-up #4 (a stale doc comment, and `LandSurvey#updmap`'s literal `/ 10.0`) — but any *new*
  code touching dz/metres conversions must keep reading `data.gran`, never assume a value.
- **The 2.0 m limit itself remains wiki-sourced only** — Follow-up #4 found that the manual
  soil-placement test once cited as corroborating it never logged its own live `gran`, so it can
  no longer be assumed to have run under `10f/11f` either. The controlled live experiment
  described in that follow-up (recording accepted vs. refused soil placements with their raw
  height changes) has not been run and would be the way to actually re-confirm 2.0 m independent
  of the wiki.
- **Whether the limit is metres-native or dz-tick-native is now an open question, not just its
  numeric value** — Follow-up #5 found a direct in-game cap of exactly 20 raw dz-ticks under
  `gran=1` (1.818 m), not the 22 ticks (2.0 m) Model A/B predict. `RAW_TICK_LIMIT_HYPOTHESIS = 20`
  / Model C encodes the alternative (fixed tick count, gran-invariant) as a diagnostic-only
  candidate. Neither hypothesis is confirmed over the other yet: it would take a third `gran` data
  point, or an explicit refusal-message log at the boundary, to distinguish "the true limit is a
  fixed 20 ticks" from "the true limit is a fixed ~1.818 m and 2.0 m was always an imprecise
  wiki rounding."
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
10. Click the new **Diagnostics** button (below Undo/Redo) and hover a vertex. Confirm the panel
    fills in with real numbers (not "?" placeholders) for actual/target height, all 8 neighbor
    directions, the 4 sharing tiles' corner spreads, and both models' limiting relationship and
    permitted-raise figures, and that it clears/shows the hint text when nothing is hovered.
    Confirm toggling it off/on doesn't affect editing in any way.
11. This is the test that would actually resolve the diagonal-rule question (see "Follow-up #3"):
    on real, non-flat terrain, raise an *interior* vertex of a multi-tile survey (not a corner of
    the survey itself) until the server refuses more soil. Open the Diagnostics panel on that
    vertex and compare its Model A vs. Model B permitted-raise figures (the last diag line)
    against what the server actually still allows. Report back whether the server's real stopping
    point matches Model B's figure, Model A's, or neither.
12. On a large survey with a mix of comfortably-legal and at/over-limit terrain, compare the
    Diagnostics panel's whole-survey "A viol=.../B viol=..." summary line against what Warn mode's
    own status line and markers report, to confirm the two independent code paths agree on today's
    (edge-only, Model A) production rule.
13. Open the Diagnostics panel on any survey and read its new "Units:" line. Confirm `raw_arg`,
    `gran`, and `mpd` are sane for whatever that survey's real `gran` turns out to be (don't assume
    it matches either `10f/11f` or the `1` observed in Follow-up #4 — that's exactly the point).
    Confirm the panel no longer clips any line at the window's edge (Follow-up #4's `pack()` fix),
    and that the "Peak to trough" readout above the buttons still reads a sane metre value on
    sloped terrain (not silently off by ~10% the way the old hardcoded `/10.0` would be if a
    survey's `gran` isn't `10f/11f`).
14. The controlled experiment Follow-up #4 asked for but did not run: on flat, unobstructed
    terrain, raise a vertex by one soil increment and note the exact accepted height change; keep
    raising until the server refuses one; note the last accepted height and the refused attempt's
    message. Repeat after lowering by one increment, and again on a different, unrelated patch.
    This is what would actually re-confirm (or correct) the 2.0 m limit independent of the wiki.
15. The single most diagnostic missing artifact from Follow-up #5: on the exact plateau where 20
    dirt placements were accepted, attempt one more (the 21st) and capture whatever the game shows
    (a refusal message, a silent no-op, or something else). This is what would distinguish "the
    real limit is a fixed 20 dz-ticks" (Model C) from "the real limit is a fixed ~1.818 m and the
    20-tick figure was gran=1-specific" — both currently fit the one data point in hand equally
    well.
16. Repeat the Diagnostics panel's new Model C line (limiting relationship, permitted raise in
    ticks) on a survey opened under a **different** live `gran` than 1 or `10f/11f`, if one is ever
    observed. A third gran value where Model C's 20-tick prediction and Model A/B's metres-based
    prediction diverge, with a confirmed in-game accept/refuse result, would settle Follow-up #5
    either way.
15. Reopen a survey previously edited under this PR's old `/10.0`-based "Peak to trough" display
    (if one exists from before this fix) and confirm the readout changes only if that survey's real
    `gran` isn't `10f/11f` — on a survey where it is, the fix should produce an identical number to
    before, not a regression.
