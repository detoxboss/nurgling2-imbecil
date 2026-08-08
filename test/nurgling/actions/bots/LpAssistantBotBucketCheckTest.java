package nurgling.actions.bots;

import org.junit.jupiter.api.Test;

import static nurgling.actions.bots.LpAssistantBot.BucketFillState.DEFINITELY_EMPTY;
import static nurgling.actions.bots.LpAssistantBot.BucketFillState.INDETERMINATE;
import static nurgling.actions.bots.LpAssistantBot.BucketFillState.NOT_A_BUCKET;
import static nurgling.actions.bots.LpAssistantBot.BucketFillState.NOT_EMPTY;
import static nurgling.actions.bots.LpAssistantBot.classifyBucketFill;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for LpAssistantBot.classifyBucketFill() - the startup empty-bucket detection
 * decision (Round 7c), extracted specifically so it can be tested without a live haven UI/Resource/
 * WItem tree (see that method's own doc for why). isDefinitelyEmptyBucket()'s live polling loop
 * around this pure function is not covered here - it needs a real NGItem to poll.
 */
class LpAssistantBotBucketCheckTest {

    @Test
    void non_bucket_item_is_never_flagged() {
        assertEquals(NOT_A_BUCKET, classifyBucketFill("Bonesaw", false, true));
        assertEquals(NOT_A_BUCKET, classifyBucketFill(null, false, true));
    }

    @Test
    void content_present_is_never_empty_even_if_quality_unresolved() {
        // A non-empty content() read can never be a false positive, whether or not the tooltip's
        // quality sub-field has resolved yet - this must short-circuit to NOT_EMPTY regardless.
        assertEquals(NOT_EMPTY, classifyBucketFill("Bucket", true, false));
        assertEquals(NOT_EMPTY, classifyBucketFill("Bucket", true, true));
    }

    @Test
    void empty_content_before_tooltip_loads_is_indeterminate_not_empty() {
        // The exact race this method exists to avoid: content() reads empty, but the readiness
        // signal (quality) hasn't resolved yet - must NOT be concluded as definitely empty.
        assertEquals(INDETERMINATE, classifyBucketFill("Bucket", false, false));
    }

    @Test
    void empty_content_after_tooltip_loads_is_definitely_empty() {
        assertEquals(DEFINITELY_EMPTY, classifyBucketFill("Bucket", false, true));
    }
}
