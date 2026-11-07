package nurgling.survey;

import haven.Area;
import haven.Coord;
import haven.MCache;

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

    /** Model C counterpart of {@link Relationship}: a raw dz-tick difference instead of a
     *  metres one (Follow-up #5/#6, see {@link SurveyConstraintValidator#RAW_TICK_LIMIT}). */
    public static final class TickRelationship {
        public final String dir;
        public final int diffTicks;
        TickRelationship(String dir, int diffTicks) { this.dir = dir; this.diffTicks = diffTicks; }
    }

    public static final class Report {
        public final Coord vertex;
        public final Double actualHeight, targetHeight;
        public final Map<String, Double> actualByDir, targetByDir;
        public final Map<String, Double> actualDiffByDir, targetDiffByDir;
        /** Signed (vertex minus neighbor) counterparts of the above -- positive means the
         *  inspected vertex is higher. Added for Follow-up #4 (unit/threshold investigation):
         *  the absolute-only diffs can't tell a reader which side is which without separately
         *  checking actualByDir/targetByDir. */
        public final Map<String, Double> actualSignedDiffByDir, targetSignedDiffByDir;
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
        /** Unit-provenance fields (Follow-up #4): the exact {@code gran} and derived
         *  metres-per-dz-unit used for every conversion above, the raw pre-"/11" mkwidget
         *  args[2] value (purely for display -- never itself used in arithmetic), the limit
         *  these comparisons were run against, and this vertex's own raw dz integer if it's
         *  inside the survey (null if it's an outside/terrain-only vertex). Exist so an in-game
         *  reading can be cross-checked by hand against screenshots without guessing what gran
         *  or build produced it. */
        public final float gran, rawGranArg;
        public final double mpd, maxSlopeMeters;
        public final Integer vertexRawDz;
        /** Model C (Follow-up #5/#6, see {@link SurveyConstraintValidator#RAW_TICK_LIMIT}):
         *  raw dz-tick diffs for the 4 cardinal directions (same topology as Model A), this
         *  vertex's tightest such relationship, and how many more raw ticks it could be raised
         *  (holding neighbors fixed) before hitting {@code RAW_TICK_LIMIT}. Since Model A's own
         *  limit is now also derived from the same constant (via {@code limitMetersFor}), this
         *  is an integer-exact cross-check of Model A's metres-based figure, not a competing
         *  model. Null fields mean no neighbor's raw-tick-equivalent was computable (unknown
         *  outside terrain). */
        public final Map<String, Integer> rawTickDiffByDir;
        public final TickRelationship modelCLimiting;
        public final Integer modelCPermittedRaiseTicks;

        Report(Coord vertex, Double actualHeight, Double targetHeight,
               Map<String, Double> actualByDir, Map<String, Double> targetByDir,
               Map<String, Double> actualDiffByDir, Map<String, Double> targetDiffByDir,
               Map<String, Double> actualSignedDiffByDir, Map<String, Double> targetSignedDiffByDir,
               TileInfo[] tiles,
               Relationship modelALimitingTarget, Relationship modelBLimitingTarget,
               Relationship modelALimitingActual, Relationship modelBLimitingActual,
               Double modelAPermittedRaiseMeters, Double modelBPermittedRaiseMeters,
               float gran, float rawGranArg, double mpd, double maxSlopeMeters, Integer vertexRawDz,
               Map<String, Integer> rawTickDiffByDir, TickRelationship modelCLimiting, Integer modelCPermittedRaiseTicks) {
            this.vertex = vertex; this.actualHeight = actualHeight; this.targetHeight = targetHeight;
            this.actualByDir = actualByDir; this.targetByDir = targetByDir;
            this.actualDiffByDir = actualDiffByDir; this.targetDiffByDir = targetDiffByDir;
            this.actualSignedDiffByDir = actualSignedDiffByDir; this.targetSignedDiffByDir = targetSignedDiffByDir;
            this.tiles = tiles;
            this.modelALimitingTarget = modelALimitingTarget; this.modelBLimitingTarget = modelBLimitingTarget;
            this.modelALimitingActual = modelALimitingActual; this.modelBLimitingActual = modelBLimitingActual;
            this.modelAPermittedRaiseMeters = modelAPermittedRaiseMeters;
            this.modelBPermittedRaiseMeters = modelBPermittedRaiseMeters;
            this.gran = gran; this.rawGranArg = rawGranArg; this.mpd = mpd; this.maxSlopeMeters = maxSlopeMeters;
            this.vertexRawDz = vertexRawDz;
            this.rawTickDiffByDir = rawTickDiffByDir; this.modelCLimiting = modelCLimiting;
            this.modelCPermittedRaiseTicks = modelCPermittedRaiseTicks;
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

    /** @param rawGranArg the raw, pre-"/11" value of mkwidget's args[2] -- purely for the
     *  report's unit-provenance display; never used in any conversion here (gran already is the
     *  authoritative derived value). Tests that don't care about this display field can pass any
     *  placeholder (e.g. {@code gran * 11}). */
    public static Report compute(Area varea, float gran, float rawGranArg, int[] dz, SurveyTerrainSnapshot terrain,
                                   double maxSlopeMeters, Coord vc) {
        double mpd = SurveyConstraintValidator.metersPerDzUnit(gran);
        Double actualHeight = terrain.terrainHeight(vc);
        Double targetHeight = targetHeightAt(varea, dz, mpd, terrain, vc);
        Integer vertexRawDz = varea.contains(vc) ? dz[varea.ridx(vc)] : null;

        Map<String, Double> actualByDir = new LinkedHashMap<>(), targetByDir = new LinkedHashMap<>();
        Map<String, Double> actualDiffByDir = new LinkedHashMap<>(), targetDiffByDir = new LinkedHashMap<>();
        Map<String, Double> actualSignedDiffByDir = new LinkedHashMap<>(), targetSignedDiffByDir = new LinkedHashMap<>();
        for(Map.Entry<String, Coord> e : DIRS.entrySet()) {
            Coord nc = vc.add(e.getValue());
            Double na = terrain.terrainHeight(nc);
            Double nt = targetHeightAt(varea, dz, mpd, terrain, nc);
            actualByDir.put(e.getKey(), na);
            targetByDir.put(e.getKey(), nt);
            actualDiffByDir.put(e.getKey(), ((actualHeight == null) || (na == null)) ? null : Math.abs(actualHeight - na));
            targetDiffByDir.put(e.getKey(), ((targetHeight == null) || (nt == null)) ? null : Math.abs(targetHeight - nt));
            actualSignedDiffByDir.put(e.getKey(), ((actualHeight == null) || (na == null)) ? null : (actualHeight - na));
            targetSignedDiffByDir.put(e.getKey(), ((targetHeight == null) || (nt == null)) ? null : (targetHeight - nt));
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

        Integer vTicks = SurveyConstraintValidator.rawTicksAt(varea, dz, mpd, terrain, vc);
        Map<String, Integer> neighborTicksByDir = new LinkedHashMap<>(), rawTickDiffByDir = new LinkedHashMap<>();
        for(String d : CARDINALS) {
            Coord nc = vc.add(DIRS.get(d));
            Integer nTicks = SurveyConstraintValidator.rawTicksAt(varea, dz, mpd, terrain, nc);
            neighborTicksByDir.put(d, nTicks);
            rawTickDiffByDir.put(d, ((vTicks == null) || (nTicks == null)) ? null : Math.abs(vTicks - nTicks));
        }
        TickRelationship modelCLimiting = limitingTicks(rawTickDiffByDir);
        Integer modelCPermitted = permittedRaiseTicks(vTicks, neighborTicksByDir, SurveyConstraintValidator.RAW_TICK_LIMIT);

        return new Report(vc, actualHeight, targetHeight, actualByDir, targetByDir, actualDiffByDir, targetDiffByDir,
                           actualSignedDiffByDir, targetSignedDiffByDir,
                           tiles, modelALimitingTarget, modelBLimitingTarget, modelALimitingActual, modelBLimitingActual,
                           modelAPermitted, modelBPermitted, gran, rawGranArg, mpd, maxSlopeMeters, vertexRawDz,
                           rawTickDiffByDir, modelCLimiting, modelCPermitted);
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

    private static TickRelationship limitingTicks(Map<String, Integer> diffByDir) {
        String bestDir = null;
        int best = -1;
        for(Map.Entry<String, Integer> e : diffByDir.entrySet()) {
            Integer v = e.getValue();
            if((v != null) && (v > best)) { best = v; bestDir = e.getKey(); }
        }
        return (bestDir == null) ? null : new TickRelationship(bestDir, best);
    }

    /** Model C counterpart of {@link #permittedRaise}: max raise (raw dz-ticks), holding every
     *  listed neighbor's tick-equivalent fixed, before that relationship's absolute difference
     *  would exceed {@code limitTicks}. Negative if already over. Null if {@code vTicks} or
     *  every listed neighbor's ticks is unknown. */
    private static Integer permittedRaiseTicks(Integer vTicks, Map<String, Integer> neighborTicksByDir, int limitTicks) {
        if(vTicks == null)
            return null;
        Integer min = null;
        for(Integer nTicks : neighborTicksByDir.values()) {
            if(nTicks == null)
                continue;
            int allowed = limitTicks - (vTicks - nTicks);
            min = (min == null) ? allowed : Math.min(min, allowed);
        }
        return min;
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

    private static String fmtSigned(Double v) {
        return (v == null) ? "?" : String.format("%+.2fm", v);
    }

    private static String fmtRel(Relationship r) {
        return (r == null) ? "?" : String.format("%s=%.2fm", r.dir, r.diffMeters);
    }

    private static String fmtTickRel(TickRelationship r) {
        return (r == null) ? "?" : String.format("%s=%dticks", r.dir, r.diffTicks);
    }

    /** One build-identifying tag, bumped by hand whenever this diagnostic's own data model
     *  changes -- lets a screenshot be matched back to the exact jar that produced it (Follow-up
     *  #4 asked for "build/commit identity" alongside the raw numbers; this file has no runtime
     *  access to the actual git commit, so this manually-maintained tag is the practical
     *  substitute). */
    private static final String BUILD_TAG = "followup4-units";

    private static String dirLine(String dir, Report r) {
        return String.format("%-2s: tgt vc=%s nbr=%s d=%s(|%s|)  act vc=%s nbr=%s d=%s(|%s|)",
                              dir,
                              fmt(r.targetHeight), fmt(r.targetByDir.get(dir)),
                              fmtSigned(r.targetSignedDiffByDir.get(dir)), fmt(r.targetDiffByDir.get(dir)),
                              fmt(r.actualHeight), fmt(r.actualByDir.get(dir)),
                              fmtSigned(r.actualSignedDiffByDir.get(dir)), fmt(r.actualDiffByDir.get(dir)));
    }

    /**
     * Compact multi-line rendering, one survey-wide summary line (Model A vs. B violation counts
     * and worst difference over the whole survey, from {@link SurveyConstraintValidator#compareModels})
     * followed by this vertex's own report. Designed to map 1:1 onto a fixed stack of Label
     * widgets (see haven.res.ui.surv.LandSurvey, which now re-{@code pack()}s around them every
     * update so long lines are no longer clipped at the window edge) -- always returns exactly
     * {@link #LINE_COUNT} lines.
     */
    public static final int LINE_COUNT = 18;

    public static String[] formatLines(Report r, SurveyConstraintValidator.ModelComparison whole) {
        String[] lines = new String[LINE_COUNT];
        lines[0] = String.format("Whole survey: A viol=%d (worst %s)  B viol=%d (worst %s)  C viol=%d (worst %dticks)",
                                  whole.modelAViolations, fmt(whole.modelAWorstMeters),
                                  whole.modelBViolations, fmt(whole.modelBWorstMeters),
                                  whole.modelCViolations, whole.modelCWorstTicks);
        lines[1] = String.format("Vertex %s: actual=%s target=%s (target-actual=%s)",
                                  r.vertex, fmt(r.actualHeight), fmt(r.targetHeight), fmt(r.targetMinusActual()));
        // Follow-up #4: raw units, so a screenshot's numbers can be hand-checked against the
        // exact gran/mpd that produced them instead of an assumed constant.
        lines[2] = String.format("Units: raw_arg=%.3f gran=%.6f mpd=%.6fm/dz vertex_dz=%s  build=%s",
                                  r.rawGranArg, r.gran, r.mpd,
                                  (r.vertexRawDz == null) ? "?(outside survey)" : r.vertexRawDz.toString(),
                                  BUILD_TAG);
        lines[3] = String.format("Limit: %.4fm = %.4f dz-units = %.4f world-Z (tilesz.x=%d)",
                                  r.maxSlopeMeters, r.maxSlopeMeters / r.mpd,
                                  r.maxSlopeMeters * MCache.tilesz.x, (int)MCache.tilesz.x);
        lines[4] = dirLine("N", r);
        lines[5] = dirLine("S", r);
        lines[6] = dirLine("E", r);
        lines[7] = dirLine("W", r);
        lines[8] = dirLine("NE", r);
        lines[9] = dirLine("NW", r);
        lines[10] = dirLine("SE", r);
        lines[11] = dirLine("SW", r);
        lines[12] = String.format("Tile NW spread: t=%s a=%s   Tile NE spread: t=%s a=%s",
                                   fmt(r.tiles[0].targetCornerSpread()), fmt(r.tiles[0].actualCornerSpread()),
                                   fmt(r.tiles[1].targetCornerSpread()), fmt(r.tiles[1].actualCornerSpread()));
        lines[13] = String.format("Tile SW spread: t=%s a=%s   Tile SE spread: t=%s a=%s",
                                   fmt(r.tiles[2].targetCornerSpread()), fmt(r.tiles[2].actualCornerSpread()),
                                   fmt(r.tiles[3].targetCornerSpread()), fmt(r.tiles[3].actualCornerSpread()));
        lines[14] = String.format("Model A limiting: target %s  actual %s",
                                   fmtRel(r.modelALimitingTarget), fmtRel(r.modelALimitingActual));
        lines[15] = String.format("Model B limiting: target %s  actual %s",
                                   fmtRel(r.modelBLimitingTarget), fmtRel(r.modelBLimitingActual));
        lines[16] = String.format("Permitted raise before limit (target-based): A=%s  B=%s",
                                   fmt(r.modelAPermittedRaiseMeters), fmt(r.modelBPermittedRaiseMeters));
        lines[17] = String.format("Model C (dz-native cross-check, limit=%dticks): limiting %s  permitted raise=%s",
                                   SurveyConstraintValidator.RAW_TICK_LIMIT, fmtTickRel(r.modelCLimiting),
                                   (r.modelCPermittedRaiseTicks == null) ? "?" : (r.modelCPermittedRaiseTicks + "ticks"));
        return lines;
    }
}
