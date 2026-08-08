# Live Harvest-Availability Signals: What Actually Exists

Reference for any bot that needs to know "does this specific gob instance still have
something in it right now", as opposed to "does this resource type have anything
undiscovered/harvestable in general". Written 2026-08 after a live-testing question
about whether `LpAssistantBot`'s hitbox-retry fix (see `docs/lp-assistant-bot.md`,
"Log/old-trunk hitbox" section) gave the codebase real per-instance depletion
tracking. **It didn't** - this doc exists so that question doesn't get re-asked (or
wrongly assumed "yes") by a future session.

## The short answer

There is no live per-instance depletion counter anywhere in this codebase for logs,
stones, or old trunks. `nurgling.tools.HarvestSpecs.LOG`/`STONE`/`OLDTRUNK` are all
backed by `ProductListHarvestSpec`, and that class's own doc says so directly: "no
live per-instance state". A depleted log and a fresh log of the same species look
identical to every piece of code that just inspects the `Gob` - `LpExplorer.
allUndiscoveredProducts()`, the always-on harvest overlay, the minimap marker, all of
it. This isn't a nurgling gap specifically - **upstream's own LP Assistant marker
feature has the exact same blind spot**: it shows the same icon on a picked-clean log
as on a full one, because the underlying data model has nothing to check.

Trees and bushes are the one partial exception: `HarvestState.hasSeedBit(sdt)` /
`hasLeafBit(sdt)` decode a real per-instance bitmask off the gob's drawable state
(`Sprite.decnum(d.sdt)`), so seed/leaf presence specifically *is* live per-instance
data. Bough and bark aren't bit-gated at all (assumed always available on a mature
tree) - see `LpExplorer.allUndiscoveredProducts()` for the exact logic. Nothing
equivalent exists for logs/stones/old-trunks.

## The only real live signal: the flower menu itself

Since there's no queryable depletion state, the only way to find out "is there
actually anything left to harvest on this specific gob" is to attempt the
interaction and see what the server's flower menu offers back:

- Right-click the gob, stamp `map.clickedGob` (see `LpAssistantBot`'s own doc comment
  on why that stamp is needed for bot-driven clicks), and read `NFlowerMenu.nopts`.
- An empty menu, or one missing the expected petal, means either (a) the resource is
  genuinely depleted, or (b) the click just didn't land - see below.
- This is exactly what `LpAssistantBot.processGob()` already does per-product before
  recording a skip.

## Case (b): a "no petal" result can be a hitbox miss, not real depletion

Logs and old trunks have a wide, non-circular hitbox. `PathFinder` can report
"reached" while the player is still too far from the actual clickable geometry for
the harvest click to land - confirmed live 2026-08, reliably reproducible by
approaching a log from certain sides. Treating a single empty-flower-menu result as
proof of depletion was wrong for these two categories specifically; it silently
skipped resources that were actually still there.

**Fix in `LpAssistantBot.processGob()`**: for BOARD/BLOCK/OLDTRUNK products only, retry
up to 3 times, backing off and re-approaching from a ~70-degree rotated angle each
attempt (`repositionAround()`) before accepting the skip as real. Trees/bushes/stone
use round hitboxes and weren't reported to have this failure mode, so they stay
single-attempt. **This is a false-negative filter on the interaction-time signal, not
a new depletion-tracking mechanism** - it doesn't tell you anything about a gob you
haven't walked up to and clicked.

## Guidance for a future bot needing this

1. There's no cheap pre-check. You cannot know from a distance whether a specific
   log/stone/old-trunk instance still has material - the marker/overlay/discovery
   code can't tell you either, upstream's can't either. Budget for walking up to each
   candidate.
2. Once there, the flower menu at interaction time is truth. Don't cache "this gob
   is empty" from a menu read that might have been a missed click rather than a real
   empty result, if the gob has a large/irregular hitbox.
3. For LOG/OLDTRUNK-shaped hitboxes specifically, retry with repositioning before
   trusting an empty result - copy the pattern in `LpAssistantBot.processGob()` /
   `repositionAround()` rather than reinventing it.
4. If you're tempted to add real depletion tracking (e.g. inferring remaining HP from
   some server-sent attribute), that would be new ground for this codebase, not an
   extension of anything that exists today - check whether the server actually sends
   a usable signal for that (unverified either way as of this writing) before
   assuming it's just wiring.
