package nurgling.actions.bots;

import haven.Coord2d;
import haven.MCache;
import haven.UI;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.StuckDetector;
import nurgling.conf.NWorldExplorerProp;
import nurgling.pf.CoastFollower;
import nurgling.pf.TileField;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.NDebugLog;

/**
 * Coastline explorer: follows the shore at a fixed offset by tracing an
 * iso-contour of a distance-to-land field (see {@link TileField} and
 * {@link CoastFollower}), rather than by chasing the boundary between two
 * water tile types.
 *
 * That distinction is the whole point. Earlier versions steered by the
 * shallow/deep waterline, which fails wherever the world does not provide
 * one - land meeting deep water directly, beaches, wide shallow flats - and
 * in testing those gaps caused the boat to stall, loop, or double back.
 * Land is always present along a coast, so a field measured from land is
 * defined everywhere the shallow/deep line is not.
 *
 * Consequences worth noting, because they replace explicit special cases
 * that used to exist here:
 *  - Reversal is structurally impossible along open coast: the direction of
 *    travel is the contour tangent, whose sign is a fixed chirality constant.
 *  - A dead-end inlet needs no handling; the contour runs in, around the
 *    tip, and back out.
 *  - Stuck-recovery backs off along the field gradient, i.e. provably away
 *    from the nearest land, instead of guessing a side from config (which
 *    used to drive the boat further into the shore it was already stuck on).
 */
public class WorldExplorer implements Action {

    /** How far ahead the contour is traced each iteration, in tiles. */
    private static final int CONTOUR_STEPS = 60;

    /** How far (perpendicular to travel) to look for a far shore for crossings. */
    private static final double CROSSING_SENSOR_RANGE = MCache.tilesz.x * 150;

    /**
     * Hard floor on main-loop iteration time. A real incident during testing
     * showed the loop can spin unboundedly fast under some conditions,
     * flooding chat/audio and overwhelming the client's UI thread badly
     * enough to crash it. Enforced unconditionally so it cannot regress.
     */
    private static final long MIN_ITERATION_MS = 300;

    /** Consecutive plan failures tolerated before aborting rather than spinning. */
    private static final int MAX_NO_PLAN = 6;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        nurgling.widgets.bots.WorldExplorerWnd w = null;
        NWorldExplorerProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.WorldExplorerWnd()), UI.scale(200, 200))));
            prop = w.prop;
        } catch (InterruptedException e) {
            throw e;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null) {
            return Results.ERROR("No config");
        }

        boolean deeperMode = prop.deeper;
        double bandTiles = Math.max(1, prop.bandTiles);
        // Chirality: which rotational sense the coast is circled in. Chosen
        // once here and never re-decided, which is what makes doubling back
        // impossible. Unlike the previous build, this genuinely controls the
        // direction of travel rather than only a search order.
        int chirality = prop.clockwise ? 1 : -1;

        WorldExplorerFrontier frontier = new WorldExplorerFrontier(prop);
        CrossingCandidateTracker crossing = new CrossingCandidateTracker();
        StuckDetector stuck = new StuckDetector();
        stuck.timeoutS = prop.stuckTimeoutS;
        stuck.reset();

        Coord2d startPos = NUtils.player().rc;
        TileField startField = TileField.scan(startPos, deeperMode);
        Coord2d heading = CoastFollower.initialHeading(startField, startPos, chirality);
        if (heading == null) {
            // No land within scan range: nothing to hug yet. Hold a course
            // until coastline streams into view.
            heading = Coord2d.of(1, 0);
            NDebugLog.logAndChat("WorldExplorer: no coast in range at start, holding course until one appears");
        }

        int consecutiveStuck = 0;
        int consecutiveNoPlan = 0;

        NDebugLog.newRun();
        NDebugLog.logAndChat("WorldExplorer: starting coast-following (mode=" + (deeperMode ? "Deep&Deeper" : "Deep&Shallow")
                + ", band=" + (int) bandTiles + " tiles, " + (prop.clockwise ? "clockwise" : "counterclockwise")
                + "). Full diagnostic detail: " + NDebugLog.path());

        while (true) {
            long iterStart = System.currentTimeMillis();
            Coord2d pos = NUtils.player().rc;
            frontier.markVisited(pos);

            TileField field = TileField.scan(pos, deeperMode);
            CoastFollower.Plan plan = CoastFollower.plan(field, pos, heading, chirality, bandTiles, CONTOUR_STEPS);

            if (plan != null) {
                consecutiveNoPlan = 0;
                heading = plan.heading;
                NDebugLog.log(String.format("WorldExplorer: shore=%.1ft contour=%dt run=%.0f heading=%.0fdeg",
                        plan.shoreDistance, plan.contourTiles, pos.dist(plan.target),
                        Math.toDegrees(Math.atan2(heading.y, heading.x))));

                Coord2d perp = heading.rot(chirality * Math.PI / 2);
                crossing.scanForCrossing(pos, perp, CROSSING_SENSOR_RANGE);
                WorldExplorerMove.clickAndChase(plan.target, gui);
            } else {
                consecutiveNoPlan++;
                if (consecutiveNoPlan >= MAX_NO_PLAN) {
                    NDebugLog.logAndChat("WorldExplorer: no navigable coast ahead after " + consecutiveNoPlan
                            + " attempts - stopping rather than looping in place");
                    return Results.ERROR("No navigable coast ahead");
                }
                NDebugLog.logAndChat("WorldExplorer: no contour ahead, backing off from shore");
                backOffFromShore(gui, field, pos, heading, prop);
            }

            if (stuck.check(NUtils.player().rc)) {
                consecutiveStuck++;
                // Only genuine progress clears this counter. A recovery that
                // merely found somewhere to click is not evidence the boat
                // actually moved, and treating it as such is why the
                // three-strikes abort never used to fire.
                if (consecutiveStuck >= 3) {
                    return Results.ERROR("Stuck: unable to clear obstacle");
                }
                NDebugLog.logAndChat("WorldExplorer: stuck (attempt " + consecutiveStuck + "), backing off from shore");
                backOffFromShore(gui, field, NUtils.player().rc, heading, prop);
                stuck.reset();
            } else {
                consecutiveStuck = 0;
            }

            long remaining = MIN_ITERATION_MS - (System.currentTimeMillis() - iterStart);
            if (remaining > 0) {
                final long waitUntil = System.currentTimeMillis() + remaining;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        return System.currentTimeMillis() >= waitUntil;
                    }
                });
            }
        }
    }

    /**
     * Moves directly away from the nearest land, using the distance field's
     * gradient. This is the correct escape direction by construction, which
     * the previous implementation could not guarantee: it chose the swing
     * side from the clockwise setting alone, so whenever land happened to be
     * on that side it drove the boat harder into the shore it was stuck
     * against and never recovered.
     */
    private static boolean backOffFromShore(NGameUI gui, TileField field, Coord2d pos,
                                            Coord2d heading, NWorldExplorerProp prop) throws InterruptedException {
        double tile = MCache.tilesz.x;
        Coord2d away = field.awayFromBlocked(pos);
        if (away == null)
            away = heading.mul(-1); // no land in range; simply give back ground

        // Try straight out first, then fan sideways, and prefer the longest
        // clear run so one escape actually leaves the obstacle behind.
        for (double angleDeg : new double[]{0, 25, -25, 50, -50}) {
            Coord2d dir = away.rot(Math.toRadians(angleDeg));
            for (int d = Math.max(2, prop.backupTiles); d >= 1; d--) {
                Coord2d cand = pos.add(dir.mul(d * tile));
                if (field.navigableAt(cand) && field.lineClear(pos, cand)) {
                    WorldExplorerMove.clickAndChase(cand, gui);
                    NDebugLog.log("WorldExplorer: backed off " + d + "t at " + (int) angleDeg + "deg from shore");
                    return true;
                }
            }
        }
        NDebugLog.logAndChat("WorldExplorer: no open water to back off into");
        return false;
    }
}
