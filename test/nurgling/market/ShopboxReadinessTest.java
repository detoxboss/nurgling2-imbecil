package nurgling.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopboxReadinessTest {
    /** Mutable stand-in for one Shopbox: the same fields change over time as server messages and resources arrive. */
    private static final class FakeSlot implements ShopboxReadiness.SlotView {
        boolean product, price, heardProduct, heardPrice, productSprite, priceSprite, names;

        FakeSlot configured() {
            product = price = heardProduct = heardPrice = true;
            return this;
        }

        FakeSlot silent() {
            return this;
        }

        FakeSlot clearedByServer() {
            heardProduct = heardPrice = true;
            return this;
        }

        @Override public boolean hasProduct() { return product; }
        @Override public boolean hasPrice() { return price; }
        @Override public boolean heardProduct() { return heardProduct; }
        @Override public boolean heardPrice() { return heardPrice; }
        @Override public boolean productSpriteReady() { return productSprite; }
        @Override public boolean priceSpriteReady() { return priceSprite; }
        @Override public boolean namesReady() { return names; }
    }

    private static final class FakeClock implements ShopboxReadiness.Clock {
        long now;
        Runnable onSleep = () -> { };

        @Override public long now() { return now; }

        @Override
        public void sleep(long millis) {
            now += millis;
            onSleep.run();
        }
    }

    private static List<FakeSlot> fiveWith(FakeSlot offer) {
        List<FakeSlot> slots = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            slots.add(index == 2 ? offer : new FakeSlot().clearedByServer());
        }
        return slots;
    }

    @Test
    void anOfferWhoseSpriteIsStillBeingBuiltIsPendingNotEmpty() {
        FakeSlot offer = new FakeSlot().configured();      // res + price known, sprite not built yet
        List<FakeSlot> slots = fiveWith(offer);

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.assess(slots, 0);

        assertFalse(verdict.ready());
        assertEquals(ShopboxReadiness.State.PENDING, verdict.states.get(2));
        assertEquals(ShopboxReadiness.State.EMPTY, verdict.states.get(0));
    }

    @Test
    void theOfferIsRetainedOnceItsSpriteBecomesAvailableLater() throws Exception {
        FakeSlot offer = new FakeSlot().configured();
        List<FakeSlot> slots = fiveWith(offer);
        FakeClock clock = new FakeClock();
        // The sprite, price sprite and tooltip names arrive while the scanner is waiting.
        int[] polls = {0};
        clock.onSleep = () -> {
            polls[0]++;
            if (polls[0] == 3) offer.productSprite = true;
            if (polls[0] == 5) { offer.priceSprite = true; offer.names = true; }
        };

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.await(() -> slots, () -> 0L, 15_000, 100, clock);

        assertTrue(verdict.ready());
        assertEquals(ShopboxReadiness.State.READY, verdict.states.get(2));  // retained, not skipped
        assertEquals(4, java.util.Collections.frequency(verdict.states, ShopboxReadiness.State.EMPTY));
        assertEquals(500, clock.now);
    }

    @Test
    void ifReadinessNeverArrivesTheWaitTimesOutAndTheStandIsNotReady() throws Exception {
        FakeSlot offer = new FakeSlot().configured();      // sprite never arrives
        List<FakeSlot> slots = fiveWith(offer);
        FakeClock clock = new FakeClock();

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.await(() -> slots, () -> 0L, 15_000, 100, clock);

        // Not ready => the scanner records the stand as unread (null capture -> unreachable -> allowPartial),
        // and the server carries its previous listing forward. It is never published as an empty scan.
        assertFalse(verdict.ready());
        assertEquals(1, verdict.pending());
        assertTrue(clock.now >= 15_000 && clock.now < 15_200, "bounded: " + clock.now);
    }

    @Test
    void aWindowThatHasHeardNothingYetIsNeverAnEmptyStand() {
        List<FakeSlot> slots = new ArrayList<>();
        for (int index = 0; index < 5; index++) slots.add(new FakeSlot().silent());

        assertFalse(ShopboxReadiness.assess(slots, 60_000).ready()); // not even a long silence proves emptiness
    }

    @Test
    void aGenuinelyEmptyStandIsReadyWhenEverySlotWasClearedByTheServer() {
        List<FakeSlot> slots = new ArrayList<>();
        for (int index = 0; index < 5; index++) slots.add(new FakeSlot().clearedByServer());

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.assess(slots, 0);

        assertTrue(verdict.ready());
        assertEquals(5, java.util.Collections.frequency(verdict.states, ShopboxReadiness.State.EMPTY));
    }

    @Test
    void quietNeverAuthorisesEmptyByDefault() {
        FakeSlot synced = new FakeSlot().configured();
        synced.productSprite = synced.priceSprite = synced.names = true;
        List<FakeSlot> slots = new ArrayList<>();
        slots.add(synced);
        for (int index = 1; index < 5; index++) slots.add(new FakeSlot().silent());

        for (long quiet : new long[]{0, ShopboxReadiness.QUIET_MS, 60_000}) {
            ShopboxReadiness.Verdict verdict = ShopboxReadiness.assess(slots, quiet);
            assertFalse(verdict.ready(), "quiet=" + quiet);
            assertEquals(4, verdict.pending());
        }
    }

    /**
     * Reproducer from review: slot 1 gets its offer and is ready; slots 2-5 have received neither message. After
     * 1.2 s of quiet they were being treated as empty, so a stand could be published before slot 2's configured
     * offer arrived at 2 s, replacing its previous listing.
     */
    @Test
    void aConfiguredOfferThatArrivesAfterTheQuietPeriodIsNotLost() throws Exception {
        FakeSlot first = new FakeSlot().configured();
        first.productSprite = first.priceSprite = first.names = true;
        FakeSlot second = new FakeSlot().silent();                         // its messages arrive at t = 2000 ms
        List<FakeSlot> slots = new ArrayList<>();
        slots.add(first);
        slots.add(second);
        for (int index = 2; index < 5; index++) slots.add(new FakeSlot().clearedByServer());
        FakeClock clock = new FakeClock();
        List<ShopboxReadiness.Verdict> published = new ArrayList<>();
        clock.onSleep = () -> {
            // Would the scanner have been allowed to publish at this moment?
            ShopboxReadiness.Verdict now = ShopboxReadiness.assess(slots, clock.now);
            if (now.ready()) published.add(now);
            if (clock.now == 2_000) {
                second.product = second.price = second.heardProduct = second.heardPrice = true;
            }
            if (clock.now == 2_400) {
                second.productSprite = second.priceSprite = second.names = true;
            }
        };

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.await(() -> slots, () -> clock.now, 15_000, 100, clock);

        assertTrue(verdict.ready());
        assertEquals(ShopboxReadiness.State.READY, verdict.states.get(1));   // the late offer is present
        assertEquals(0, published.size(), "the stand must never be publishable before the late offer is ready");
        assertTrue(clock.now >= 2_400);
    }

    @Test
    void aSlotThatNeverReceivesMessagesTimesOutAsNotReadyInsteadOfEmpty() throws Exception {
        FakeSlot first = new FakeSlot().configured();
        first.productSprite = first.priceSprite = first.names = true;
        List<FakeSlot> slots = new ArrayList<>();
        slots.add(first);
        for (int index = 1; index < 5; index++) slots.add(new FakeSlot().silent());
        FakeClock clock = new FakeClock();

        ShopboxReadiness.Verdict verdict = ShopboxReadiness.await(() -> slots, () -> clock.now, 15_000, 100, clock);

        assertFalse(verdict.ready());                    // => unread, previous listings survive
        assertEquals(4, verdict.pending());
    }

    @Test
    void theSilenceFallbackIsAvailableOnlyWhenExplicitlyEnabled() {
        FakeSlot synced = new FakeSlot().configured();
        synced.productSprite = synced.priceSprite = synced.names = true;
        List<FakeSlot> slots = new ArrayList<>();
        slots.add(synced);
        for (int index = 1; index < 5; index++) slots.add(new FakeSlot().silent());

        assertFalse(ShopboxReadiness.assess(slots, ShopboxReadiness.QUIET_MS - 1, true).ready());
        assertTrue(ShopboxReadiness.assess(slots, ShopboxReadiness.QUIET_MS, true).ready());
        assertFalse(ShopboxReadiness.assess(slots, ShopboxReadiness.QUIET_MS, false).ready());
    }
@Test
    void aProductWhosePriceMessageHasNotArrivedIsPendingAndOnceHeardIsAConfirmedIncompleteOffer() {
        FakeSlot slot = new FakeSlot();
        slot.product = slot.heardProduct = true;

        assertEquals(ShopboxReadiness.State.PENDING, ShopboxReadiness.assess(slot, true, 5_000, false));
        slot.heardPrice = true; // "price" message arrived with no price
        assertEquals(ShopboxReadiness.State.INACTIVE, ShopboxReadiness.assess(slot, true, 5_000, false));
    }

    @Test
    void interruptionWhileWaitingIsPropagated() {
        FakeSlot offer = new FakeSlot().configured();
        List<FakeSlot> slots = fiveWith(offer);
        ShopboxReadiness.Clock clock = new ShopboxReadiness.Clock() {
            @Override public long now() { return 0; }
            @Override public void sleep(long millis) throws InterruptedException { throw new InterruptedException("stop"); }
        };

        assertThrows(InterruptedException.class,
                () -> ShopboxReadiness.await(() -> slots, () -> 0L, 15_000, 100, clock));
    }

    @Test
    void messageLogTracksWhichServerMessagesEachWidgetReceived() {
        ShopMessageLog log = new ShopMessageLog();
        Object first = new Object();
        Object second = new Object();
        log.record(first, "res", 100);
        log.record(first, "price", 250);

        assertTrue(log.heard(first, "res") && log.heard(first, "price"));
        assertFalse(log.heard(first, "tt"));
        assertFalse(log.heard(second, "res"));
        assertEquals(250, log.lastMessageAt(first));
        assertEquals("res@+0ms price@+150ms", log.trace(first));
        assertEquals("(no messages)", log.trace(second));
        assertEquals(0, log.lastMessageAt(second));
    }
}
