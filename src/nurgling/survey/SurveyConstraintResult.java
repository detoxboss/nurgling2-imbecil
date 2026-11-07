package nurgling.survey;

import haven.Coord;

import java.util.Collections;
import java.util.List;

/**
 * Outcome of one {@link SurveyConstraintValidator#validate} call: an overall status plus every
 * non-valid edge found, each tagged with why. {@code findings} only ever holds edges that are
 * not {@link Status#VALID} -- a fully valid shape returns an empty list via {@link #valid()}.
 */
public final class SurveyConstraintResult {
    public enum Status {
        /** Within the verified limit, or not worsening a pre-existing over-limit edge. */
        VALID,
        /** Confirmed to exceed the verified limit, and worse than it already was. */
        VIOLATION,
        /** Can't be verified (terrain data for one side of the edge is unloaded). */
        UNKNOWN,
    }

    /**
     * Where the candidate (target) difference sits relative to the real 2.0 m limit itself,
     * independent of {@link Status}'s worsening-aware blocking decision. An edge can be
     * {@code AT_LIMIT} or even {@code OVER_LIMIT} while still carrying {@link Status#VALID}
     * (e.g. a pre-existing cliff that isn't being made any worse) -- the two are deliberately
     * orthogonal: {@code Severity} answers "how close to/over the real limit is this", {@code
     * Status} answers "should this candidate edit be blocked".
     */
    public enum Severity {
        /** Comfortably under the limit. */
        BELOW_LIMIT,
        /** Within one dz unit's worth of the limit (practically, "one more increment would
         *  violate it") -- deliberately a band, not exact equality, so float/quantization noise
         *  at the boundary doesn't flicker between this and BELOW_LIMIT. */
        AT_LIMIT,
        /** Confirmed over the limit, regardless of whether that's new or pre-existing. */
        OVER_LIMIT,
        /** Can't be verified (terrain data for one side of the edge is unloaded). */
        UNKNOWN,
    }

    public static final class EdgeFinding {
        public final Coord a, b;
        public final Status status;
        public final Severity severity;
        /** NaN when severity is UNKNOWN. */
        public final double candidateDiffMeters;
        public final double limitMeters;
        /** True if this edge already exceeded the limit before the candidate shape was applied
         *  (based on the previously committed *target*, which may or may not match real terrain
         *  -- see {@link #actualTerrainOverLimit} to tell those apart). */
        public final boolean preexisting;
        /** Whether the real (unmodified) terrain at both endpoints is itself already over the
         *  limit -- i.e. a genuine existing cliff/feature, not an artifact of a stale or
         *  not-yet-executed survey target. Null when either endpoint's real terrain height is
         *  unknown (unloaded), so this can never be mistaken for "no, it's fine". */
        public final Boolean actualTerrainOverLimit;

        EdgeFinding(Coord a, Coord b, Status status, Severity severity, double candidateDiffMeters,
                    double limitMeters, boolean preexisting, Boolean actualTerrainOverLimit) {
            this.a = a;
            this.b = b;
            this.status = status;
            this.severity = severity;
            this.candidateDiffMeters = candidateDiffMeters;
            this.limitMeters = limitMeters;
            this.preexisting = preexisting;
            this.actualTerrainOverLimit = actualTerrainOverLimit;
        }
    }

    public final Status overall;
    public final List<EdgeFinding> findings;

    SurveyConstraintResult(Status overall, List<EdgeFinding> findings) {
        this.overall = overall;
        this.findings = Collections.unmodifiableList(findings);
    }

    public static SurveyConstraintResult valid() {
        return new SurveyConstraintResult(Status.VALID, Collections.emptyList());
    }

    public boolean hasViolation() {
        return overall == Status.VIOLATION;
    }

    public boolean hasUnknown() {
        return overall == Status.UNKNOWN;
    }

    /** True if any finding is at the limit (and not already over it) -- an amber-worthy edge
     *  that isn't itself a blocking violation. Independent of {@link #overall}, which tracks
     *  the worsening-aware blocking {@link Status}, not {@link Severity}. */
    public boolean hasAtLimit() {
        for(EdgeFinding f : findings)
            if(f.severity == Severity.AT_LIMIT)
                return true;
        return false;
    }
}
