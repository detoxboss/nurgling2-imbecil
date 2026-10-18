package nurgling.market;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Decides, from Shopbox's own message lifecycle, whether a Barter Stand window has finished loading.
 *
 * A Shopbox slot starts out with {@code res == null && price == null} and is filled by the server's
 * {@code res}, {@code tt}, {@code n} and {@code price} messages; its sprite is built lazily afterwards.
 * "Nothing configured yet" is therefore indistinguishable from "empty slot" unless the messages that
 * would have configured it are known to have been received. This class never treats a slot as empty
 * without that evidence, and never reports a stand as ready while any configured offer is still
 * loading.
 */
public final class ShopboxReadiness {
    /** Silence on every slot for this long, after the server clearly started talking, confirms an untouched slot is empty. */
    public static final long QUIET_MS = 1200;

    private ShopboxReadiness() {
    }

    public enum State {
        /** Confirmed no offer. */
        EMPTY,
        /** Confirmed half-configured (product without price or the reverse): not a purchasable offer. */
        INACTIVE,
        /** Product, price, sprites and names are all available. */
        READY,
        /** Something this slot depends on is still loading. */
        PENDING
    }

    /** What the scanner can observe about one Shopbox, without depending on the widget class. */
    public interface SlotView {
        boolean hasProduct();

        boolean hasPrice();

        /** The server's {@code res} message for this slot has been received (it may have cleared the slot). */
        boolean heardProduct();

        /** The server's {@code price} message for this slot has been received (it may have cleared the price). */
        boolean heardPrice();

        /** The product sprite exists (or could be constructed without {@code Loading}). */
        boolean productSpriteReady();

        /** The price item's sprite can be rendered without {@code Loading}. */
        boolean priceSpriteReady();

        /** Tooltip information has been built and yields both the product and price names. */
        boolean namesReady();
    }

    public static final class Verdict {
        public final List<State> states;

        Verdict(List<State> states) {
            this.states = states;
        }

        public boolean ready() {
            return !states.isEmpty() && !states.contains(State.PENDING);
        }

        public int pending() {
            int count = 0;
            for (State state : states) {
                if (state == State.PENDING) count++;
            }
            return count;
        }

        public String describe() {
            return states.toString();
        }
    }

    public static State assess(SlotView slot, boolean standSynced, long quietMs) {
        boolean product = slot.hasProduct();
        boolean price = slot.hasPrice();
        if (product && price) {
            return slot.heardProduct() && slot.heardPrice() && slot.productSpriteReady()
                    && slot.priceSpriteReady() && slot.namesReady() ? State.READY : State.PENDING;
        }
        if (product) {
            return slot.heardPrice() ? State.INACTIVE : State.PENDING;
        }
        if (price) {
            return slot.heardProduct() ? State.INACTIVE : State.PENDING;
        }
        if (slot.heardProduct() && slot.heardPrice()) {
            return State.EMPTY;
        }
        // No message for this slot at all: empty only once the stand has demonstrably synced and gone quiet.
        return standSynced && quietMs >= QUIET_MS ? State.EMPTY : State.PENDING;
    }

    public static Verdict assess(List<? extends SlotView> slots, long quietMs) {
        boolean synced = false;
        for (SlotView slot : slots) {
            if (slot.heardProduct() && slot.heardPrice()) {
                synced = true;
                break;
            }
        }
        List<State> states = new ArrayList<>();
        for (SlotView slot : slots) {
            states.add(assess(slot, synced, quietMs));
        }
        return new Verdict(states);
    }

    public interface Clock {
        long now();

        void sleep(long millis) throws InterruptedException;
    }

    public static final Clock SYSTEM = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };

    /**
     * Polls until the stand is ready or the bounded timeout passes. Returns the last verdict; the caller
     * records the stand as unread when it is not ready. Interruption propagates.
     */
    public static Verdict await(Supplier<List<? extends SlotView>> slots, Supplier<Long> quietMs, long timeoutMs,
                                long pollMs, Clock clock) throws InterruptedException {
        long deadline = clock.now() + timeoutMs;
        Verdict verdict = assess(slots.get(), quietMs.get());
        while (!verdict.ready() && clock.now() < deadline) {
            clock.sleep(pollMs);
            verdict = assess(slots.get(), quietMs.get());
        }
        return verdict;
    }
}
