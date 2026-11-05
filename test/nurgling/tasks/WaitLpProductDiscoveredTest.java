package nurgling.tasks;

import nurgling.discovery.DiscoveryLedger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiscoveryLedger.isDone(name) is true for KNOWN/LEGACY/MANUAL as well as DISCOVERED (see
 * DiscoveryLedger.State's rank/done fields), but only DISCOVERED means an LP rise was actually
 * paired with this harvest. Treating any "done" state as a fresh discovery would let LP Assistant
 * credit itself for a product that was already established, with zero new LP gained this harvest
 * (2026-11-05 post-sync correction). These tests lock in the classification
 * WaitLpProductDiscovered.check()/isNewLpDiscovery() depends on, without needing a live NGameUI
 * (check() itself is not exercised here - see its own class doc for why that's not independently
 * testable in this harness).
 */
class WaitLpProductDiscoveredTest {

    @Test
    void discoveredStateIsANewDiscovery() {
        assertTrue(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.DISCOVERED));
    }

    @Test
    void knownStateIsNotANewDiscovery() {
        assertFalse(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.KNOWN));
    }

    @Test
    void legacyImportedStateIsNotANewDiscovery() {
        assertFalse(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.LEGACY));
    }

    @Test
    void manuallyMarkedStateIsNotANewDiscovery() {
        assertFalse(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.MANUAL));
    }

    @Test
    void notYetDoneStatesAreNotANewDiscoveryEither() {
        // Never actually reached through check() (the done-guard excludes them before this is
        // consulted), but the classifier itself must not misreport them as a new discovery if
        // ever called directly.
        assertFalse(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.SEEN));
        assertFalse(WaitLpProductDiscovered.isNewDiscoveryState(DiscoveryLedger.State.HELD));
    }

    @Test
    void outcomeEnumMatchesIsNewLpDiscoveryForEachCase() {
        WaitLpProductDiscovered exp = new WaitLpProductDiscovered("res", "product", 0);
        assertFalse(exp.isNewLpDiscovery(), "unresolved (NONE) must not count as a new discovery");
        assertFalse(exp.confirmed(), "unresolved (NONE) must not report confirmed");
    }
}
