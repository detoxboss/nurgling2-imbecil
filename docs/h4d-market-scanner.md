# H4D Market Scanner

> Kind: **current implementation** (scanner side) for H4D Market server **v0.4.0**. The bot has not been
> run in-game by the author of this revision; see [Verification status](#verification-status).

The `H4D Market Scanner` bot reads the barter stands in a market NArea and publishes one snapshot to
the H4D Market service. It is registered both in the normal bot grid and as the Scenario step ID
`h4d_market_scan`, so a saved Scenario can be launched by
[`hnh-scheduler`](https://github.com/aleksandrsvoboda/hnh-scheduler) without scheduler changes.

## What it reuses

The scanner does not emulate or scrape the game protocol. It uses Nurgling's existing:

- NArea persistence and ChunkNav navigation (`navigateToArea`, `planToGridCoord`, `navigateWithPath`);
- the local obstacle-aware `PathFinder` (with `learnedBlocks`, so people and cargo that block an aisle
  are routed around);
- container opening (`OpenTargetContainer`);
- live `Shopbox` widgets for the five offer slots;
- Haven's item tooltip/resource model and `ItemTex.sprimg` renderer.

## Three things that are kept separate

| Concern | Where it lives | Changes when |
| --- | --- | --- |
| **Identity**: permanent `standId`, cluster number, stall number (`C07-S3`) | server `stands` table | a person registers, renumbers or retires a stand |
| **Physical position**: Haven *grid id* + offset inside the grid | server `stand_physical` table | a person commissions or deliberately remaps a stand |
| **Customer-map presentation**: marker positions, section artwork | server `clusters` table and layout file | the map is redrawn |

The scanner only ever uses the first two. Redrawing the customer map, moving a marker or changing the
artwork cannot change which physical stand is identified as `Cxx-Sx` (see `StandMatcherTest`).

Gob ids are not stable between sessions and `Gob.rc` is session-relative, so neither is stored. A grid
id is persistent (the same property NArea and the mining overlay memory rely on).

## One-time setup

1. In Nurgling, create one NArea around the **entire** present and future market floor. Empty
   expansion space is fine. It must not contain unrelated barter stands. `H4D Market` is the
   recommended name.
2. Make sure the market is reachable through the same ChunkNav data used by `Go to area`.
3. Open **Nurgling Settings > Autorunner > Scenarios** and create or edit a Scenario.
4. Add **H4D Market Scanner** and configure:

   | Setting | Default | Meaning |
   | --- | --- | --- |
   | Market NArea | `H4D Market`, when found | Area containing the full market |
   | API URL | `https://h4d.shop` | H4D Market public origin, without an API suffix |
   | Ingest token | blank | Bearer token matching `MARKET_INGEST_TOKEN` on the VPS |

   Optional keys can be added to the step's settings in `scenarios.nurgling.json`:

   | Key | Default | Meaning |
   | --- | --- | --- |
   | `standTolerance` | `2.0` | Max distance (world units, clamped 0.5–4) between a detected stand and its saved position |
   | `sweepSpacingTiles` | `40` | Spacing of the waypoints used to search the area |
   | `maxStandAttempts` | `3` | Bounded attempts per stand (1–6) before it is recorded as unreachable |
   | `publishPartial` | `true` | `false` leaves the scan open (resumable) instead of publishing carried-forward data |

5. Commission the stands once (next section), then run the Scenario and select it in HnH Scheduler.

## Commissioning: register the physical stands

A fresh server knows each stand's identity (for example the 108 stands in 31 clusters) but not where
each one physically is. Until a stand has a physical position it is *unbound* and is never scanned.

1. Run the Scenario/bot once. It sweeps the whole NArea, finds every barter stand, and uploads a
   **discovery report** (every detected stand with grid id, offset and classification). The same data is
   written to `h4d-market-discovery-<areaId>.json` in the Nurgling data directory. With nothing
   commissioned it ends with a clear message and scans nothing.
2. Open `https://<your-host>/admin` and enter the ingest token. The plot shows the detected stands.
3. Select the stands of one cluster (click, Shift-click, or drag a box; *Select connected unknown* only
   proposes a group by distance), type the cluster number, check the stand numbers, press **Assign**.
   Stand numbers default to reading order (top→bottom, left→right) and are editable per stand.
4. Repeat for each cluster. Known stands are labelled on the plot.

Nothing is assigned automatically: distance alone is never used to decide cluster ownership, because
neighbouring clusters can be close together.

## Normal runs

1. Navigate to the NArea (ChunkNav).
2. **Sweep** the NArea in sections (`AreaSweepPlanner`). `Finder.findGobs` only sees gobs that are loaded,
   so a large NArea cannot be assumed visible from one spot. Each section's grid-local rectangle is covered
   by a waypoint lattice; stands are de-duplicated by physical position, so all of them never need to be
   loaded simultaneously.
3. **Identify** with `StandMatcher`: a stand is matched only when exactly one detection is within
   tolerance of exactly one saved position in the same grid. Everything else is classified:
   - *unknown* — near nothing saved: reported, never scanned, never published under a guessed number;
   - *moved* — near a saved stand that was not detected: offered to the admin for an explicit remap;
   - *ambiguous* — two candidates for one position: reported, not guessed;
   - *missing* — saved but not detected: **unknown scan state**, not "gone" and not "sold out". The bot
     takes one more targeted look at the saved position before giving up on it.
4. **Report** the discovery to the server.
5. **Route** over the matched stands with `MarketRoutePlanner`: a cluster-level zig-zag (rows by
   physical position, alternating direction, then bounded 2-opt), starting from where the walk begins.
   It uses only physical positions, so it is independent of cluster numbers and of the customer map, and
   unbuilt positions simply never appear.
6. **Re-acquire, then scan** each stand with bounded attempts (`maxStandAttempts`). Gob handles collected during
   the sweep may have unloaded since, so every attempt starts from the stand's *saved* physical reference
   (`StandReacquirer`): navigate toward the saved grid/offset (ChunkNav when the grid is unloaded or far, then
   local `PathFinder`), **verify arrival** near that point, wait (bounded) for the section and its objects to
   load, and require exactly one stand within tolerance. Only then is the live stand pathed to (obstacle-aware,
   `learnedBlocks`) and opened. A failed attempt waits (people move) and repeats the whole sequence. A stand
   that still fails is recorded as unreachable and the route continues. There is no unbounded retry loop, and
   interruption/cancellation is propagated everywhere.
   Sweep waypoints use the same arrival check: `ChunkNavExecutor` may finish at the overall NArea instead of the
   requested point, so a waypoint counts as reached only if the player is within about six tiles of it.
7. **Wait for the Barter Stand to finish loading** (`ShopboxReadiness`) before reading it. A Shopbox slot starts
   as `res == null && price == null`, and its sprite is built lazily, so "no sprite yet" is not "no offer". The
   scanner records the server's `res`/`tt`/`n`/`price` messages per widget through the existing `UiEvents`
   seam (`ShopMessageLog`, registered before the window opens; no change to the server-distributed Shopbox
   class) and classifies each slot:
   - *empty* — the server's `res` and `price` messages were received and cleared the slot (or the stand has
     demonstrably synced and gone quiet for 1.2 s);
   - *inactive* — product without price or the reverse, confirmed by the received messages;
   - *ready* — product and price known, both sprites constructible, names available;
   - *pending* — anything else, including a window that has heard nothing yet.
   The wait is bounded (15 s). If it times out the stand is **not** published as an empty scan: it is recorded
   as unread and its previous listing is kept (carried forward by the server).
8. **Publish** (below).

## Publication semantics

The scan is uploaded stand by stand to an open (non-public) scan and then completed:

`POST /api/v1/scans/begin` → `PUT /api/v1/scans/{id}/stands/{standId}` → `POST /api/v1/scans/{id}/complete`

- If every commissioned stand was read, the scan completes normally.
- If some were not read (unreachable, missing, ambiguous) the bot completes with `allowPartial: true`.
  The server then **carries forward each unread stand's last known listing** with its original capture
  time (marked "last seen" on the website) and flags the snapshot as partial. An incomplete scan can
  therefore never erase valid data.
- Without `allowPartial` the server refuses an incomplete scan with HTTP 409 listing the missing stands.
- With `publishPartial=false` the bot refuses to publish a partial result and leaves the scan open.
- Unknown (unregistered) stands never affect publication.

Before each stand PUT its payload is saved to `h4d-market-scan-<areaId>.json`. After a crash, network
loss or a Scheduler timeout the next run retries the pending stand and continues the same scan **if the
layout version is unchanged** and every recorded stand is still commissioned. Any layout change
(stands registered, renumbered, remapped) starts a new scan, because the server refuses to complete a
scan begun under an older layout. A missing server-side scan (restored VPS database) causes one clean
restart. Spools from the previous house-id era are ignored.

## Captured fields

For every active Shopbox slot the bot records product and price names, the displayed stock counter
(`Shopbox.leftNum`) and **Buy-button availability as separate fields**, stack quantity and quality,
price amount (`pnum`), minimum quality (`pq`), slot index, session-relative stand position and UTC
capture time.

`Shopbox.updbtn()` creates the Buy button when `res != null && price != null && pnum > 0`
(verified in `src/haven/res/ui/barterbox/Shopbox.java`); `MarketOffers.buyAvailable` mirrors exactly
that. Meaning on the website:

- Buy button present, stock shows 0 → **Available\*** (Haven reports 0 for Taking-Hand area stock);
- no Buy button → **Unavailable** (sold out, or a hand connection needs attention);
- stand not read this time → *last seen*, never "unavailable".

Each encountered sprite is rendered to PNG by the client and uploaded under the original resource path
plus a short SHA-256 suffix, so dynamic resources such as custom-minted coins do not overwrite one
another.

## Token handling

Entering the token in the Scenario editor stores it in `scenarios.nurgling.json` as plain text; treat
that file as a secret. To keep it out of the Scenario leave the field blank and set
`H4D_MARKET_INGEST_TOKEN` in the environment that launches Nurgling or HnH Scheduler (restart
Scheduler after changing it on Windows). `H4D_MARKET_ENDPOINT` overrides the URL; the JVM property
`h4d.market.ingestToken` is also accepted.

## Operational notes

- Use Scheduler's **Skip** overlap policy for this Scenario; concurrent scans are unsupported.
- Give the scheduled run enough maximum duration for the sweep, every stand and network retries.
- A stand with no offers is uploaded with an empty list, so sold-out stalls are still part of the snapshot.
- Adding or rearranging stands later: see the server's `README.md` ("Adding or rearranging stands").

## Verification status

- **Automated (`ant test`)**: identity matching for the 108 stands / 31 clusters, arbitrary cluster
  shapes, extra/unknown stands, identity preservation, missing and moved stands, independence from
  customer-map data, route ordering, area sweep over a simulated partially-loaded world, spool resume
  rules, Buy-button rule, discovery report.
- **Not verified in-game** (also: whether the server really sends es/price for empty slots, which decides how often an empty stand needs the 1.2 s quiet fallback): the live `GameSource` (grid lookup, `Finder`-style gob scan, `PathFinder`
  and ChunkNav legs, `Shopbox` capture) compiles and reuses existing mechanisms but has only been
  exercised through its pure collaborators. See the PR description for the in-game test checklist.
