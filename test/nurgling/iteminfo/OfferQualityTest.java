package nurgling.iteminfo;

import haven.ItemInfo;
import haven.res.ui.tt.q.qbuff.QBuff;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfferQualityTest {
    private static QBuff q(double v) {
        return new QBuff(null, null, "Quality", v);
    }

    @Test
    void plainItemUsesItsOwnQuality() {
        assertEquals(37.5, OfferQuality.of(Collections.<ItemInfo>singletonList(q(37.5))));
    }

    @Test
    void filledContainerUsesTheQualityOfItsContents() {
        List<ItemInfo> info = Arrays.<ItemInfo>asList(q(10), new ItemInfo.Contents(null, Collections.<ItemInfo>singletonList(q(55))));
        assertEquals(55.0, OfferQuality.of(info));
    }

    @Test
    void noQualityMeansNoLabel() {
        assertNull(OfferQuality.of(Collections.<ItemInfo>emptyList()));
        assertNull(OfferQuality.of(null));
        assertNull(OfferQuality.of(Collections.<ItemInfo>singletonList(new ItemInfo.Contents(null, Collections.<ItemInfo>emptyList()))));
    }

    @Test
    void labelTruncatesToAWholeNumberLikeTheStandsOwnQuality() {
        assertEquals("Quality: 37", OfferQuality.label(37.9));
        assertEquals("Quality: 0", OfferQuality.label(0.4));
    }
}
