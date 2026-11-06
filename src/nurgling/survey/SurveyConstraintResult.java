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

    public static final class EdgeFinding {
        public final Coord a, b;
        public final Status status;
        /** NaN when status is UNKNOWN. */
        public final double candidateDiffMeters;
        public final double limitMeters;
        /** True if this edge already exceeded the limit before the candidate shape was applied. */
        public final boolean preexisting;

        EdgeFinding(Coord a, Coord b, Status status, double candidateDiffMeters, double limitMeters, boolean preexisting) {
            this.a = a;
            this.b = b;
            this.status = status;
            this.candidateDiffMeters = candidateDiffMeters;
            this.limitMeters = limitMeters;
            this.preexisting = preexisting;
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
}
