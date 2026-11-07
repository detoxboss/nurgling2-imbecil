package nurgling.survey;

import haven.Area;
import haven.Coord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only, on-demand inspection of one survey vertex's local geometry -- actual terrain vs.
 * target heights, for all 4 cardinal and 4 diagonal neighbors, and for each of the 4 tiles
 * sharing the vertex. Exists so the exact numbers behind a suspected "extra room" discrepancy
 * (edge-only Model A missing a diagonal corner-to-corner relationship that Model B, all four
 * corners per tile, would catch) can be read directly in-game instead of inferred from
 * screenshots. Purely informational -- never touches validate()/clamp() or their results.
 */
public final class SurveyVertexDiagnostics {
    private SurveyVertexDiagnostics() {}

    private static final Map<String, Coord> DIRS = new LinkedHashMap<>();
    static {
        DIRS.put("N", Coord.of(0, -1));
        DIRS.put("S", Coord.of(0, 1));
        DIRS.put("E", Coord.of(1, 0));
        DIRS.put("W", Coord.of(-1, 0));
        DIRS.put("NE", Coord.of(1, -1));
        DIRS.put("NW", Coord.of(-1, -1));
        DIRS.put("SE", Coord.of(1, 1));
        DIRS.put("SW", Coord.of(-1, 1));
    }
    private static final String[] CARDINALS = {"N", "S", "E", "W"};
    private static final String[] ALL_DIRS = DIRS.keySet().toArray(new String[0]);

    /** The 4 tiles sharing a vertex, named by the tile's position relative to it (e.g. "NW" is
     *  the tile up-and-left of the vertex, which therefore sits at that tile's bottom-right
     *  corner). Order matches {@link #tileUlOffsets}. */
    private static final String[] TILE_NAMES = {"NW", "NE", "SW", "SE"};
    private static final Coord[] TILE_UL_OFFSETS = {
        Coord.of(-1, -1), Coord.of(0, -1), Coord.of(-1, 0), Coord.of(0, 0),
    };

    public static final class TileInfo {
        public final String name;
        public final Coord ul;
        /** Order: ul, ur, bl, br. Null entries mean that corner's height is unknown. */
        public final Double[] actualCorners, targetCorners;

        TileInfo(String name, Coord ul, Double[] actualCorners, Double[] targetCorners) {
            this.name = name; this.ul = ul;
            this.actualCorners = actualCorners; this.targetCorners = targetCorners;
        }

        private static Double spread(Double[] c) {
            Double lo = null, hi = null;
            for(Double v : c) {
                if(v == null)
                    continue;
                lo = (lo == null) ? v : Math.min(lo, v);
                hi = (hi == null) ? v : Math.max(hi, v);
            }
            return (lo == null) ? null : (hi - lo);
        }

        /** Model B's per-tile figure: max(corners) - min(corners). Null if every corner of this
         *  tile is unknown. */
        public Double actualCornerSpread() { return spread(actualCorners); }
        public Double targetCornerSpread() { return spread(targetCorners); }
    }

    public static final class Relationship {
        public final String dir;
        public final double diffMeters;
        Relationship(String dir, double diffMeters) { this.dir = dir; this.diffMeters = diffMeters; }
    }

    public static final class Report {
        public final Coord vertex;
        public final Double actualHeight, targetHeight;
        public final Map<String, Double> actualByDir, targetByDir;
        public final Map<String, Double> actualDiffByDir, targetDiffByDir;
        public final TileInfo[] tiles; // order matches TILE_NAMES: NW, NE, SW, SE
        /** Which relationship each model considers tightest for this vertex, computed from the
         *  target (dz-derived) heights -- what validate()/clamp() actually compare -- and,
         *  separately, from the real unmodified terrain, for comparison. Null if no neighbor in
         *  that model's set has a known height. */
        public final Relationship modelALimitingTarget, modelBLimitingTarget;
        public final Relationship modelALimitingActual, modelBLimitingActual;
        /** How far (metres) this vertex's target height could be raised, holding every neighbor
         *  fixed, before each model's tightest relationship would hit the limit. Negative means
         *  that relationship is already over the limit (a further raise is blocked immediately;
         *  the magnitude is how far it would need to come back down to reach the limit exactly).
         *  Null if no relevant neighbor height is known. */
        public final Double modelAPermittedRaiseMeters, modelBPermittedRaiseMeters;

        Report(Coord vertex, Double actualHeight, Double targetHeight,
               Map<String, Double> actualByDir, Map<String, Double> targetByDir,
               Map<String, Double> actualDiffByDir, Map<String, Double> targetDiffByDir,
               TileInfo[] tiles,
               Relationship modelALimitingTarget, Relationship modelBLimitingTarget,
               Relationship modelALimitingActual, Relationship modelBLimitingActual,
               Double modelAPermittedRaiseMeters, Double modelBPermittedRaiseMeters) {
            this.vertex = vertex; this.actualHeight = actualHeight; this.targetHeight = targetHeight;
            this.actualByDir = actualByDir; this.targetByDir = targetByDir;
            this.actualDiffByDir = actualDiffByDir; this.targetDiffByDir = targetDiffByDir;
            this.tiles = tiles;
            this.modelALimitingTarget = modelALimitingTarget; this.modelBLimitingTarget = modelBLimitingTarget;
            this.modelALimitingActual = modelALimitingActual; this.modelBLimitingActual = modelBLimitingActual;
            this.modelAPermittedRaiseMeters = modelAPermittedRaiseMeters;
            this.modelBPermittedRaiseMeters = modelBPermittedRaiseMeters;
        }

        public Double targetMinusActual() {
            return ((targetHeight == null) || (actualHeight == null)) ? null : (targetHeight - actualHeight);
        }
    }

    /** A vertex's target-shape height: dz-derived if it's inside the survey, otherwise the
     *  unchanged real terrain height -- a vertex outside the survey has no target of its own. */
    private static Double targetHeightAt(Area varea, int[] dz, double mpd, SurveyTerrainSnapshot terrain, Coord c) {
        if(varea.contains(c))
            return dz[varea.ridx(c)] * mpd;
        return terrain.terrainHeight(c);
    }

    public static Report compute(Area varea, float gran, int[] dz, SurveyTerrainSnapshot terrain,
                                   double maxSlopeMeters, Coord vc) {
        double mpd = SurveyConstraintValidator.metersPerDzUnit(gran);
        Double actualHeight = terrain.terrainHeight(vc);
        Double targetHeight = targetHeightAt(varea, dz, mpd, terrain, vc);

        Map<String, Double> actualByDir = new LinkedHashMap<>(), targetByDir = new LinkedHashMap<>();
        Map<String, Double> actualDiffByDir = new LinkedHashMap<>(), targetDiffByDir = new LinkedHashMap<>();
        for(Map.Entry<String, Coord> e : DIRS.entrySet()) {
            Coord nc = vc.add(e.getValue());
            Double na = terrain.terrainHeight(nc);
            Double nt = targetHeightAt(varea, dz, mpd, terrain, nc);
            actualByDir.put(e.getKey(), na);
            targetByDir.put(e.getKey(), nt);
            actualDiffByDir.put(e.getKey(), ((actualHeight == null) || (na == null)) ? null : Math.abs(actualHeight - na));
            targetDiffByDir.put(e.getKey(), ((targetHeight == null) || (nt == null)) ? null : Math.abs(targetHeight - nt));
        }

        TileInfo[] tiles = new TileInfo[4];
        for(int i = 0; i < 4; i++) {
            Coord ul = vc.add(TILE_UL_OFFSETS[i]);
            Coord[] corners = {ul, ul.add(1, 0), ul.add(0, 1), ul.add(1, 1)};
            Double[] ac = new Double[4], tc = new Double[4];
            for(int j = 0; j < 4; j++) {
                ac[j] = terrain.terrainHeight(corners[j]);
                tc[j] = targetHeightAt(varea, dz, mpd, terrain, corners[j]);
            }
            tiles[i] = new TileInfo(TILE_NAMES[i], ul, ac, tc);
        }

        Relationship modelALimitingTarget = limiting(targetDiffByDir, CARDINALS);
        Relationship modelBLimitingTarget = limiting(targetDiffByDir, ALL_DIRS);
        Relationship modelALimitingActual = limiting(actualDiffByDir, CARDINALS);
        Relationship modelBLimitingActual = limiting(actualDiffByDir, ALL_DIRS);

        Double modelAPermitted = permittedRaise(targetHeight, targetByDir, CARDINALS, maxSlopeMeters);
        Double modelBPermitted = permittedRaise(targetHeight, targetByDir, ALL_DIRS, maxSlopeMeters);

        return new Report(vc, actualHeight, targetHeight, actualByDir, targetByDir, actualDiffByDir, targetDiffByDir,
                           tiles, modelALimitingTarget, modelBLimitingTarget, modelALimitingActual, modelBLimitingActual,
                           modelAPermitted, modelBPermitted);
    }

    private static Relationship limiting(Map<String, Double> diffByDir, String[] dirs) {
        String bestDir = null;
        double best = -1;
        for(String d : dirs) {
            Double v = diffByDir.get(d);
            if((v != null) && (v > best)) { best = v; bestDir = d; }
        }
        return (bestDir == null) ? null : new Relationship(bestDir, best);
    }

    /** Max raise (metres), holding every listed neighbor's height fixed, before that
     *  relationship's absolute difference would exceed {@code limit}. Negative if already over.
     *  Null if no listed neighbor's height is known. */
    private static Double permittedRaise(Double vcHeight, Map<String, Double> neighborByDir, String[] dirs, double limit) {
        if(vcHeight == null)
            return null;
        Double min = null;
        for(String d : dirs) {
            Double nh = neighborByDir.get(d);
            if(nh == null)
                continue;
            double allowed = limit - (vcHeight - nh);
            min = (min == null) ? allowed : Math.min(min, allowed);
        }
        return min;
    }

    private static String fmt(Double v) {
        return (v == null) ? "?" : String.format("%.2fm", v);
    }

    private static String fmtRel(Relationship r) {
        return (r == null) ? "?" : String.format("%s=%.2fm", r.dir, r.diffMeters);
    }

    /**
     * Compact multi-line rendering, one survey-wide summary line (Model A vs. B violation counts
     * and worst difference over the whole survey, from {@link SurveyConstraintValidator#compareModels})
     * followed by this vertex's own report. Designed to map 1:1 onto a fixed stack of Label
     * widgets (see haven.res.ui.surv.LandSurvey) -- always returns exactly {@link #LINE_COUNT}
     * lines.
     */
    public static final int LINE_COUNT = 11;

    public static String[] formatLines(Report r, SurveyConstraintValidator.ModelComparison whole) {
        String[] lines = new String[LINE_COUNT];
        lines[0] = String.format("Whole survey: A viol=%d (worst %s)  B viol=%d (worst %s)",
                                  whole.modelAViolations, fmt(whole.modelAWorstMeters),
                                  whole.modelBViolations, fmt(whole.modelBWorstMeters));
        lines[1] = String.format("Vertex %s: actual=%s target=%s (target-actual=%s)",
                                  r.vertex, fmt(r.actualHeight), fmt(r.targetHeight), fmt(r.targetMinusActual()));
        lines[2] = String.format("N: t=%s a=%s   S: t=%s a=%s",
                                  fmt(r.targetDiffByDir.get("N")), fmt(r.actualDiffByDir.get("N")),
                                  fmt(r.targetDiffByDir.get("S")), fmt(r.actualDiffByDir.get("S")));
        lines[3] = String.format("E: t=%s a=%s   W: t=%s a=%s",
                                  fmt(r.targetDiffByDir.get("E")), fmt(r.actualDiffByDir.get("E")),
                                  fmt(r.targetDiffByDir.get("W")), fmt(r.actualDiffByDir.get("W")));
        lines[4] = String.format("NE: t=%s a=%s   NW: t=%s a=%s",
                                  fmt(r.targetDiffByDir.get("NE")), fmt(r.actualDiffByDir.get("NE")),
                                  fmt(r.targetDiffByDir.get("NW")), fmt(r.actualDiffByDir.get("NW")));
        lines[5] = String.format("SE: t=%s a=%s   SW: t=%s a=%s",
                                  fmt(r.targetDiffByDir.get("SE")), fmt(r.actualDiffByDir.get("SE")),
                                  fmt(r.targetDiffByDir.get("SW")), fmt(r.actualDiffByDir.get("SW")));
        lines[6] = String.format("Tile NW spread: t=%s a=%s   Tile NE spread: t=%s a=%s",
                                  fmt(r.tiles[0].targetCornerSpread()), fmt(r.tiles[0].actualCornerSpread()),
                                  fmt(r.tiles[1].targetCornerSpread()), fmt(r.tiles[1].actualCornerSpread()));
        lines[7] = String.format("Tile SW spread: t=%s a=%s   Tile SE spread: t=%s a=%s",
                                  fmt(r.tiles[2].targetCornerSpread()), fmt(r.tiles[2].actualCornerSpread()),
                                  fmt(r.tiles[3].targetCornerSpread()), fmt(r.tiles[3].actualCornerSpread()));
        lines[8] = String.format("Model A limiting: target %s  actual %s",
                                  fmtRel(r.modelALimitingTarget), fmtRel(r.modelALimitingActual));
        lines[9] = String.format("Model B limiting: target %s  actual %s",
                                  fmtRel(r.modelBLimitingTarget), fmtRel(r.modelBLimitingActual));
        lines[10] = String.format("Permitted raise before limit (target-based): A=%s  B=%s",
                                   fmt(r.modelAPermittedRaiseMeters), fmt(r.modelBPermittedRaiseMeters));
        return lines;
    }
}
