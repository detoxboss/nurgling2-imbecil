package nurgling.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketOffersTest {
    @Test
    void buyButtonConditionIsIndependentOfDisplayedStock() {
        // Shopbox.updbtn(): canbuy = res != null && price != null && pnum > 0. Displayed stock is not an input.
        assertTrue(MarketOffers.buyAvailable(true, true, 3));
    }

    @Test
    void noBuyButtonWhenAnythingIsMissingOrFree() {
        assertFalse(MarketOffers.buyAvailable(false, true, 3));
        assertFalse(MarketOffers.buyAvailable(true, false, 3));
        assertFalse(MarketOffers.buyAvailable(true, true, 0));
    }
}
