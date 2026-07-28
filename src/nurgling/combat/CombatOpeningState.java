package nurgling.combat;

/**
 * Validity/freshness semantics for a single observed combat value (opening,
 * IP, cooldown, action availability). "Not found" and "zero" are distinct:
 * only {@link #CONFIRMED_ZERO} and {@link #VALID_NOT_FOUND} are safe to treat
 * as a usable zero value for decision purposes; everything else must fail
 * closed (no automatic action authorized from it).
 */
public enum CombatOpeningState {
    /** The relation/widget positively reports numeric zero. */
    CONFIRMED_ZERO,
    /** Looked up correctly but not present; the protocol removes zero-value opening buffs, so this is also a safe zero. */
    VALID_NOT_FOUND,
    /** The combat widget/relation this value would come from is positively absent. */
    DISPLAY_ABSENT,
    /** Resource loading or lookup failed unexpectedly. */
    SEARCH_ERROR,
    /** The value cannot currently be established. */
    UNKNOWN,
    /** The observation was valid but is no longer safe to act on (age/target change/widget replacement). */
    STALE,
    /** The required action/weapon/resource/configuration is positively unavailable. */
    UNAVAILABLE;

    /** True for states whose associated numeric value is safe to use as a real zero. */
    public boolean isSafeZero() {
        return this == CONFIRMED_ZERO || this == VALID_NOT_FOUND;
    }

    /** True for states that must never authorize an automatic action from the associated value. */
    public boolean isUnsafe() {
        return this == DISPLAY_ABSENT || this == SEARCH_ERROR || this == UNKNOWN || this == STALE || this == UNAVAILABLE;
    }
}
