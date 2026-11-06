package nurgling.survey;

import haven.Area;
import haven.Coord;
import haven.Loading;
import haven.MCache;
import haven.resutil.Ridges;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable snapshot of real terrain state relevant to validating a Land Survey's target shape:
 * current elevations (metres) for every vertex the validator could need -- the survey's own
 * vertex area plus a one-vertex ring immediately outside it, so edges crossing the survey
 * boundary can be checked against undisturbed ground -- and, where known, whether a tile is a
 * pre-existing cliff/ridge tile (haven.resutil.Ridges.brokenp).
 *
 * Captured once per gesture (not re-read per candidate shape during a drag) so the validator
 * never touches MCache directly and a single snapshot can be reused across many candidate shapes
 * without repeatedly risking haven.Loading.
 *
 * A vertex or tile absent from this snapshot means the terrain there is unknown (unloaded),
 * never that it is at height zero -- callers must treat a null/null return as "can't verify",
 * not as a safe default.
 */
public final class SurveyTerrainSnapshot {
    private final Map<Coord, Double> heightByVertex;
    private final Map<Coord, Boolean> cliffByTile;
    public final Area probedVertices;

    private SurveyTerrainSnapshot(Map<Coord, Double> heightByVertex, Map<Coord, Boolean> cliffByTile, Area probedVertices) {
        this.heightByVertex = heightByVertex;
        this.cliffByTile = cliffByTile;
        this.probedVertices = probedVertices;
    }

    /** {@code vArea}: the survey's own vertex area (haven.res.ui.surv.Data#varea). */
    public static SurveyTerrainSnapshot capture(MCache map, Area vArea) {
        Area probed = vArea.margin(1);
        Map<Coord, Double> heights = new HashMap<>();
        for(Coord vc : probed) {
            try {
                // map.getfz() returns world-Z units, which are MCache.tilesz.x times a real
                // metre (see SurveyConstraintValidator#metersPerDzUnit for why) -- divide back
                // out so terrainHeight() genuinely returns metres, matching dz-derived heights.
                heights.put(vc, map.getfz(vc) / MCache.tilesz.x);
            } catch(Loading l) {
                // left absent: unknown, not zero
            }
        }
        Map<Coord, Boolean> cliffs = new HashMap<>();
        Area tarea = Area.corn(probed.ul, probed.br.sub(1, 1));
        for(Coord tc : tarea) {
            try {
                cliffs.put(tc, Ridges.brokenp(map, tc));
            } catch(Loading l) {
                // left absent: unknown
            }
        }
        return new SurveyTerrainSnapshot(heights, cliffs, probed);
    }

    /** Null means unknown (unloaded) -- never treat as zero. */
    public Double terrainHeight(Coord vc) {
        return heightByVertex.get(vc);
    }

    public boolean isProbed(Coord vc) {
        return probedVertices.contains(vc);
    }

    /** Null means unknown; otherwise a verified Ridges.brokenp() reading for that tile. */
    public Boolean isCliffTile(Coord tc) {
        return cliffByTile.get(tc);
    }

    /**
     * Builds a snapshot directly from synthetic data, for tests that need specific (including
     * deliberately missing) terrain heights without a live MCache/game resources. Not used by
     * any production code path.
     */
    public static SurveyTerrainSnapshot forTesting(Map<Coord, Double> heights, Map<Coord, Boolean> cliffs, Area probedVertices) {
        return new SurveyTerrainSnapshot(new HashMap<>(heights), new HashMap<>(cliffs), probedVertices);
    }
}
