# H4D Market Scanner

The `H4D Market Scanner` bot reads a complete in-game barter market and publishes one atomic
snapshot to the H4D Market service. It is registered both in the normal bot grid and as the
Scenario step ID `h4d_market_scan`, so a saved Scenario can be launched by
[`hnh-scheduler`](https://github.com/aleksandrsvoboda/hnh-scheduler) without scheduler changes.

## What it reuses

The scanner does not emulate or scrape the game protocol. It uses Nurgling's existing:

- NArea persistence and ChunkNav navigation (`GotoArea`);
- local pathfinder (`PathFinder`);
- barter stand opening and load wait (`OpenTargetContainer` and `FindBarterStand`);
- live `Shopbox` fields for the five offer slots;
- Haven item tooltip/resource model and `ItemTex.sprimg` renderer.

The scanner adds deterministic stall numbering, JSON conversion, PNG icon delivery, authenticated
HTTP upload, and crash-safe progress.

## One-time setup

1. In Nurgling, create an NArea around all market barter stands. It should contain the stands and
   no unrelated barter stands. `H4D Market` is the recommended name.
2. Make sure the market is reachable through the same ChunkNav data used by `Go to area`.
3. Open **Nurgling Settings > Autorunner > Scenarios** and create or edit a Scenario.
4. Add **H4D Market Scanner** and configure:

   | Setting | Default | Meaning |
   | --- | --- | --- |
   | Market NArea | `H4D Market`, when found | Area containing the full market |
   | API URL | `https://h4d.shop` | H4D Market public origin, without an API suffix |
   | Ingest token | blank | Bearer token matching `MARKET_INGEST_TOKEN` on the VPS |
   | Layout | Server-managed | The current H4D layout contains 27 renter clusters and 89 physical stands |

5. Run the Scenario manually once. The first successful run calibrates the server layout against
   the exact in-game coordinates; later runs use those saved coordinates.
6. Select that Scenario and character in HnH Scheduler and assign the desired schedule.

The scheduler already reads `scenarios.nurgling.json`, passes its numeric `scenarioId` through the
`-bots` launch configuration, and Nurgling resolves each step through `BotRegistry`. No scheduler
fork or plugin is required.

## Token handling

Entering the token in the Scenario editor stores it in `scenarios.nurgling.json` as plain text.
That file must be treated as a secret and must not be committed or shared.

To keep the token out of the Scenario, leave the field blank and set
`H4D_MARKET_INGEST_TOKEN` in the environment that launches Nurgling or HnH Scheduler. The optional
`H4D_MARKET_ENDPOINT` variable overrides the default URL. A JVM property named
`h4d.market.ingestToken` is also accepted.

On Windows, restart HnH Scheduler after adding or changing an environment variable so new Nurgling
processes inherit it.

## Stable cluster and stand identity

The server layout assigns every physical stand a stable key such as `C07-S3` (Cluster 7, Stand 3).
The bot no longer invents numbers by sorting the currently visible objects into rows.

On the first run, the server supplies the public floor-plan coordinates without game-world
coordinates. The client compares the complete 89-stand shape against the loaded barter stands,
tests every rotation/reflection, and accepts the match only when one result is clearly within the
safety threshold. It then uploads the exact world coordinates to the authenticated layout endpoint.
Later runs match against those saved coordinates and stop if a stand is missing or has moved beyond
the configured tolerance.

The bot also stops before opening a scan unless all 89 configured stands are loaded. The API rejects
completion of layout-aware scans unless every stand was uploaded, preventing partial snapshots.

## Captured fields

For every active Shopbox slot the bot records:

- product and price display names;
- stock remaining (`Shopbox.leftNum`);
- product stack quantity and quality from item tooltip data;
- price amount (`Shopbox.pnum`) and minimum quality (`Shopbox.pq`);
- slot index, stand world coordinates, and UTC capture time.

Each encountered sprite is rendered to PNG by the client. The upload key is the original game
resource path plus a short SHA-256 suffix. The suffix keeps visually different dynamic resources,
including custom-minted coins that share a base resource, from overwriting one another.

## Atomic publication and recovery

The bot uses the resumable H4D API:

1. `POST /api/v1/scans/begin`
2. `PUT /api/v1/scans/{scanId}/stands/{houseId}` for every stable `Cxx-Sx` stand
3. `POST /api/v1/scans/{scanId}/complete`

Open scans are not public. Before each stand PUT, its payload is atomically saved to
`h4d-market-scan-<areaId>.json` in the Nurgling data directory. Successful stand IDs are persisted
there as well. If the client exits, loses network connectivity, or is stopped by Scheduler's maximum
duration, the next run retries the pending stand and continues the same scan. The spool is removed
only after the completion request succeeds.

HTTP PUT, icon, and completion operations retry transient failures with bounded backoff. A missing
server-side scan (for example after restoring the VPS database) causes one clean restart from stand
#1. Authentication and validation errors fail the Scenario immediately and remain visible in the
client/Scheduler logs.

## Operational notes

- Use Scheduler's **Skip** overlap policy for this Scenario. Concurrent scans of the same market are
  intentionally unsupported.
- Give the scheduled run enough maximum duration to path through every stand plus network retries.
- A stand with no active offers is uploaded with an empty offer list, so sold-out stalls are still
  part of the complete snapshot.
- The exact rendered icons are uploaded to the H4D VPS; the browser never needs access to the Haven
  resource server or a player's local resource cache.
