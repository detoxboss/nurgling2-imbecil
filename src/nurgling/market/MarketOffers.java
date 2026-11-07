package nurgling.market;

/** Offer-state rules shared by the scanner and its tests. */
public final class MarketOffers {
    private MarketOffers() {
    }

    /**
     * Mirrors the condition under which {@code haven.res.ui.barterbox.Shopbox.updbtn()} creates the Buy
     * button: {@code res != null && price != null && pnum > 0}. It is independent of the displayed
     * stock counter ({@code leftNum}), which Haven reports as zero for stock supplied through a Taking
     * Hand area even though the item can still be bought. Zero displayed stock must therefore never be
     * read as "sold out".
     */
    public static boolean buyAvailable(boolean hasProduct, boolean hasPrice, int priceAmount) {
        return hasProduct && hasPrice && priceAmount > 0;
    }
}
