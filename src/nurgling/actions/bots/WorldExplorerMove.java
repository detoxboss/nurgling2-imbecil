package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.Following;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Results;
import nurgling.pf.FrontierPicker;
import nurgling.pf.WaterTiles;
import nurgling.tasks.IsMoving;
import nurgling.tasks.IsMovingBySpeed;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NDebugLog;
import nurgling.tools.NParser;

import static haven.OCache.posres;

/**
 * Momentum-preserving movement primitives for WorldExplorer, parallel to
 * DynamicPf's chained-click pattern. Deliberately does not modify GoTo.java
 * (used broadly elsewhere) - this is a bot-scoped mover with the same
 * click/vehicle-dispatch shape but non-blocking waits, so a boat's momentum
 * carries across a multi-tile straight run instead of stopping every tile.
 */
public class WorldExplorerMove
{
    // Mirrors DynamicPf's existing use of the same threshold for this exact
    // "non-blocking, close-enough-or-moving" semantics (IsMoving/IsMovingBySpeed
    // both already support it; th caps the wait so this can never block forever).
    private static final int MOVE_TH = 20;

    /**
     * Sends one click toward target and returns once the character is moving
     * or already close, without waiting for a full stop.
     */
    public static Results clickAndChase(Coord2d target, NGameUI gui) throws InterruptedException
    {
        gui.map.wdgmsg("click", Coord.z, target.floor(posres), 1, 0);
        Following fl = NUtils.player().getattr(Following.class);
        if (fl != null)
        {
            Gob gob = Finder.findGob(fl.tgt);
            if (gob != null && NParser.isIt(gob, new NAlias("snekkja")))
            {
                NUtils.getUI().core.addTask(new IsMovingBySpeed(target, gob, MOVE_TH));
                return Results.SUCCESS();
            }
        }
        NUtils.getUI().core.addTask(new IsMoving(target, MOVE_TH));
        return Results.SUCCESS();
    }

    /**
     * Walks up to maxLookahead tiles along dir from pltc, requiring both
     * WaterTiles.isSafe and the boundary condition (a neardirs neighbor that's
     * shallow water) to hold at every step, and returns the farthest tile that
     * still satisfies both - letting the caller click one long run instead of
     * one tile at a time. Returns null if this direction isn't boundary-safe
     * even for the first step (i.e. this heading isn't viable at all right
     * now - the caller should try another one), rather than a blind
     * one-tile guess - the primary caller (WorldExplorer's main loop) needs
     * to distinguish "found a real boundary run" from "nothing here."
     */
    public static Coord scanAhead(Coord pltc, Coord dir, Coord[] neardirs, boolean deeperMode, int maxLookahead)
    {
        Coord farthest = null;
        for (int step = 1; step <= maxLookahead; step++)
        {
            Coord cand = pltc.add(dir.mul(step));
            if (!isBoundarySafe(cand, neardirs, deeperMode))
                break;
            farthest = cand;
        }
        if (farthest != null)
            NDebugLog.log("WorldExplorerMove: scanAhead " + pltc + " -> " + farthest + " along " + dir);
        return farthest;
    }

    private static boolean isBoundarySafe(Coord cand, Coord[] neardirs, boolean deeperMode)
    {
        String name = FrontierPicker.safeTileName(tileWorldPos(cand));
        if (name == null || !WaterTiles.isSafe(name, deeperMode))
            return false;
        for (Coord test : neardirs)
        {
            String testName = FrontierPicker.safeTileName(tileWorldPos(cand.add(test)));
            if (testName != null && WaterTiles.isShallow(testName))
                return true;
        }
        return false;
    }

    private static Coord2d tileWorldPos(Coord tc)
    {
        return tc.mul(MCache.tilesz).add(MCache.tilehsz);
    }

    /**
     * Continuous-heading analogue of scanAhead: walks up to maxLookahead
     * tiles along an arbitrary world-space angle (not one of 4 fixed compass
     * directions), requiring WaterTiles.isSafe plus a shallow-water tile
     * within bandTiles perpendicular to the heading at every step - keeps
     * the run hugging near the shoreline rather than drifting into open
     * water or wandering away from it - and returns the farthest point that
     * still satisfies both. Returns null if not even the first step is
     * viable, exactly like scanAhead, so the caller can distinguish "found a
     * run" from "this heading doesn't work right now."
     *
     * Unlike scanAhead's fixed 4-direction/neardirs geometry, this lets the
     * caller try many closely-spaced headings near its current heading, so
     * the chosen direction can curve smoothly with the coastline instead of
     * snapping between 4 axis-aligned options (the source of the zigzag/
     * closed-loop/false "boxed in" failures the fixed-direction version hit
     * in real testing).
     */
    public static Coord2d scanHeading(Coord2d pos, double angleRad, boolean deeperMode, double bandTiles, int maxLookahead)
    {
        double tile = MCache.tilesz.x;
        Coord2d dir = Coord2d.of(Math.cos(angleRad), Math.sin(angleRad));
        Coord2d perp = dir.rot(Math.PI / 2);
        Coord2d farthest = null;
        for (int step = 1; step <= maxLookahead; step++)
        {
            Coord2d cand = pos.add(dir.mul(step * tile));
            if (!isBandSafe(cand, perp, bandTiles * tile, deeperMode))
                break;
            farthest = cand;
        }
        if (farthest != null)
            NDebugLog.log("WorldExplorerMove: scanHeading " + pos + " -> " + farthest
                    + " @ " + Math.round(Math.toDegrees(angleRad)) + "deg");
        return farthest;
    }

    private static boolean isBandSafe(Coord2d worldPos, Coord2d perp, double bandDist, boolean deeperMode)
    {
        String name = FrontierPicker.safeTileName(worldPos);
        if (name == null || !WaterTiles.isSafe(name, deeperMode))
            return false;
        for (double frac : new double[]{0.35, 0.7, 1.0})
        {
            for (int side = -1; side <= 1; side += 2)
            {
                Coord2d probe = worldPos.add(perp.mul(side * frac * bandDist));
                String probeName = FrontierPicker.safeTileName(probe);
                if (probeName != null && WaterTiles.isShallow(probeName))
                    return true;
            }
        }
        return false;
    }
}
