package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MapView;
import haven.OCache;
import haven.UI;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NFlowerMenu;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Equip;
import nurgling.actions.GoTo;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.actions.StudyEatOrDrop;
import nurgling.conf.NLpAssistantProp;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.NTask;
import nurgling.tasks.TaskCriticalExitException;
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitLpFirstProduct;
import nurgling.tasks.WaitLpProductDiscovered;
import nurgling.tasks.WaitLpSettlement;
import nurgling.tasks.WaitPoseExclude;
import nurgling.tools.HarvestSpecs;
import nurgling.tools.InventorySnapshot;
import nurgling.tools.LpActionMatcher;
import nurgling.tools.LpExplorer;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;
import nurgling.widgets.bots.LpAssistant;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks to every currently-loaded gob still showing an LP-assistant marker (LpExplorer.
 * hasUndiscoveredProduct) and performs the matching flower-menu action for each of its still-
 * undiscovered products, until a fresh scan finds nothing left.
 */
public class LpAssistantBot implements Action {

    private final Set<Long> clearedGobs = new HashSet<>();
    // gob id -> (product -> skip reason), so the final report can tell the player what's left.
    private final Map<Long, Map<String, String>> skipped = new LinkedHashMap<>();
    // Round 5 hard safety invariant: names AND widget identities the bot must never auto-drop/eat,
    // snapshotted before any harvesting starts - main inventory, both equipped hands, the belt
    // itself, and everything inside the belt (see protectExisting()/its call sites in run()).
    // Confirmed live 2026-08 as the root cause of a real tool loss: the old snapshot covered main
    // inventory only, so an equipped Stone Axe was invisible to it; Equip()'s own tool-swap later
    // moved that axe into inventory to free the hand for a Bonesaw, and the old whole-inventory
    // "anything with an unrecognized name is new, triage it" scan treated the axe as bot output and
    // dropped it. Round 5 replaces that whole-inventory scan with per-harvest scoped triage (see
    // triageHarvestOutput()) precisely so a name/widget having no relation to preexistingItemNames
    // is no longer sufficient to triage it - it must ALSO match the specific product just
    // harvested. This set remains as an explicit, independent safety layer regardless.
    private final Set<String> preexistingItemNames = new HashSet<>();
    private final Set<Integer> preexistingWidgetIds = new HashSet<>();
    // Every currently-configured tool name (Board/Block/Stone/Old Trunk), normalized the same way
    // Equip() resolves them - populated once in run() after prop loads. Checked independently of
    // the startup snapshot above so a tool stays protected even if it was equipped mid-run (e.g.
    // the player manually re-equipped it) rather than present at the exact startup instant.
    private final Set<String> protectedToolNames = new HashSet<>();
    // The most recent harvest's product name and whichever of its newly-produced widgets weren't
    // yet confirmed dropped - lets ensureSpaceFor()'s cleanup fallback retry exactly that known-
    // safe set (see dropHarvestWidgets()) instead of resurrecting a whole-inventory sweep.
    private String lastTriageProduct = null;
    // The most recent harvest's own owned-but-not-yet-confirmed-dropped targets, kept so
    // ensureSpaceFor()'s cleanup fallback can retry exactly this known-safe set (see
    // dropHarvestWidgets()) instead of resurrecting a whole-inventory sweep. See OwnedTargets' own
    // doc for why loose/whole-stack/partial-stack targets are tracked separately (Round 7).
    private final OwnedTargets lastTriagePending = new OwnedTargets();

    /**
     * Round 7: the safely-identifiable ownership result of one harvest, split by how each target
     * must be dropped/confirmed - never a single flat "wdgid set", since a loose item, a wholly new
     * stack, and a specific child merged into a stack all need different drop messages and
     * different confirmation logic (see InventorySnapshot.Delta's own doc for how each is decided).
     */
    private static final class OwnedTargets {
        // Top-level loose item wdgids - dropped individually via NUtils.drop() (whole-slot message).
        final Set<Integer> looseIds = new LinkedHashSet<>();
        // container wdgid -> every child wdgid it held at ownership time. Every child here is
        // proven new (see InventorySnapshot.Delta.newWholeStacks), so the container is droppable as
        // ONE message (the official CTRL-click "drop" protocol on the container's own GItem - see
        // dropHarvestWidgets()'s doc) - but the child ids are preserved so cleanup can still confirm
        // (or recover, per-child, via InventorySnapshot.findAny()) if the container's own identity
        // stops resolving before the drop is confirmed (e.g. it collapses after a partial pickup).
        final Map<Integer, Set<Integer>> wholeStacks = new LinkedHashMap<>();
        // Specific child wdgids proven new inside a stack that also holds a pre-existing/ambiguous
        // child - the container itself must never be dropped; only the second element is Currently
        // used (parent wdgid kept solely for logging/diagnostics, not for lookup - Round 7 resolves
        // every target via InventorySnapshot.findAny(), never a parent-scoped lookup).
        final Map<Integer, Integer> partialChildParents = new LinkedHashMap<>();

        void clear() {
            looseIds.clear();
            wholeStacks.clear();
            partialChildParents.clear();
        }

        boolean isEmpty() {
            return looseIds.isEmpty() && wholeStacks.isEmpty() && partialChildParents.isEmpty();
        }

        int size() {
            int n = looseIds.size() + partialChildParents.size();
            for (Set<Integer> children : wholeStacks.values())
                n += children.size();
            return n;
        }
    }
    // Internal-only signal from processGob() up to run()'s main loop: thrown when a product's
    // footprint genuinely can't be made to fit (even after giving cleanup a chance to settle - see
    // ensureSpaceFor), so the run should stop the same way the old top-of-loop space check used to,
    // instead of being reported as "error processing gob, skipping it" like an ordinary failure.
    private static final class OutOfSpaceException extends RuntimeException {
        OutOfSpaceException(String message) { super(message); }
    }
    // Internal-only signal: the cursor could not be confirmed clear after a harvest (stash and
    // ground-drop both failed to confirm within their bounded waits - see clearHandAfterHarvest).
    // Continuing to triage, equip, or right-click anything else with the cursor in an unknown/
    // occupied state risks an item-use interaction instead of the intended action, so this always
    // stops the whole run rather than just skipping one gob - a stuck cursor isn't scoped to the
    // gob that produced it.
    private static final class CursorStuckException extends RuntimeException {
        CursorStuckException(String message) { super(message); }
    }
    private NLpAssistantProp prop;
    private BufferedWriter debugLog;
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss");

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        LpAssistant w = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new LpAssistant()), UI.scale(200, 200))));
            prop = w.prop;
        } catch (InterruptedException e) {
            throw e;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null) {
            return Results.ERROR("No config");
        }

        // Defensive: if something external re-invokes run() on this same instance (observed live
        // - a bot queue/executor re-running the same object produced 3 back-to-back "cleared 0
        // gobs" reports repeating the first run's stale skip list verbatim, because these fields
        // were never cleared between calls), start every call from a genuinely clean slate rather
        // than trusting this is only ever called once per instance.
        clearedGobs.clear();
        skipped.clear();
        preexistingItemNames.clear();
        preexistingWidgetIds.clear();
        lastTriageProduct = null;
        lastTriagePending.clear();

        protectedToolNames.clear();
        for (LpActionMatcher.Category cat : new LpActionMatcher.Category[]{
                LpActionMatcher.Category.BOARD, LpActionMatcher.Category.BLOCK,
                LpActionMatcher.Category.STONE, LpActionMatcher.Category.OLDTRUNK}) {
            String tool = LpActionMatcher.requiredTool(cat, prop);
            if (tool != null)
                protectedToolNames.add(tool);
        }
        if (!protectedToolNames.isEmpty())
            log("Protected tool name(s): " + protectedToolNames);

        if (prop.debug)
            openDebugLog();
        try {
            // Round 7c: refuse to run at all if either equipped hand definitely holds an empty
            // bucket - confirmed live 2026-08 as the trigger for Equip()'s hand-freeing swap to hang
            // (DropOn -> TaskCriticalExitException), leaving the bucket stuck in the cursor with no
            // recovery. Must run before clearHandAtStartup() (cursor handling), any Equip() call,
            // walking, or gob processing - see checkEquippedBucketsAtStartup()'s own doc.
            Results bucketCheck = checkEquippedBucketsAtStartup(gui);
            if (!bucketCheck.IsSuccess())
                return bucketCheck;

            // Never silently discard an item the player was already holding when they started the
            // bot (confirmed live 2026-08: the old unconditional clearHand() here would drop it to
            // the ground if it didn't happen to fit in a free slot) - either stash it, or refuse to
            // start with a clear instruction.
            Results startupHand = clearHandAtStartup(gui);
            if (!startupHand.IsSuccess())
                return startupHand;
            for (WItem existing : gui.getInventory().getItems())
                protectExisting(existing);
            // Round 5: also snapshot equipped hands, the belt itself, and the belt's own contents -
            // not just main inventory (see preexistingItemNames' class doc for the tool-loss bug
            // this fixes). A currently-equipped item never appears in getInventory().getItems() at
            // all, so the old main-inventory-only snapshot could never protect it.
            protectExisting(NUtils.getEquipment().findItem(NEquipory.Slots.HAND_LEFT.idx));
            protectExisting(NUtils.getEquipment().findItem(NEquipory.Slots.HAND_RIGHT.idx));
            WItem wbelt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
            protectExisting(wbelt);
            if (wbelt != null && wbelt.item.contents instanceof NInventory) {
                for (WItem beltItem : ((NInventory) wbelt.item.contents).getItems())
                    protectExisting(beltItem);
            }
            log("Starting. " + preexistingItemNames.size() + " pre-existing item name(s) (inventory+equipped+belt) will never be auto-triaged.");

            // A fixed sanity gate, independent of any specific product's own footprint (a 1x4 board
            // fitting proves nothing about whether a 2x1 block fits, or vice versa - see
            // footprintFor()'s doc): if a clear WORKING_REGION-sized area isn't available at all,
            // there's no point starting. The per-product checks inside processGob (footprintFor()/
            // ensureSpaceFor()) are the ones that gate each individual harvest against its own real
            // shape; this is only the one-time "is there basically any room to work with" check.
            if (!ensureSpaceFor(gui, WORKING_REGION)) {
                String msg = "LP Assistant bot: not enough contiguous inventory space to run (couldn't fit a "
                        + WORKING_REGION.y + "x" + WORKING_REGION.x + " block). Free up space and restart.";
                NUtils.getGameUI().msg(msg);
                log(msg);
                return Results.ERROR("Inventory full");
            }

            int gobsCleared = 0;
            int productsDiscovered = 0;

            while (true) {
                // No loop-top space gate here (Round 4): checking a fixed footprint before even
                // knowing the next product's actual shape doesn't prove anything about what that
                // product needs (see footprintFor()'s doc), so it moved to where it belongs -
                // processGob()'s per-product check, immediately before each harvest. OutOfSpaceException
                // (thrown there when cleanup can't restore the required shape) is this loop's stop
                // condition now.
                Gob target = pickNearestCandidate();
                if (target == null)
                    break;

                String gobResName = target.ngob != null ? target.ngob.name : null;
                log("Target: " + gobResName + " (id=" + target.id + ")");

                try {
                    productsDiscovered += processGob(gui, target, gobResName);
                } catch (OutOfSpaceException e) {
                    String msg = "LP Assistant bot: stopping, no room for the next product's footprint ("
                            + e.getMessage() + ") even after triage.";
                    NUtils.getGameUI().msg(msg);
                    log(msg);
                    break;
                } catch (CursorStuckException e) {
                    String msg = "LP Assistant bot: stopping, cursor could not be confirmed clear after a harvest ("
                            + e.getMessage() + ") - refusing to risk the next action with an unknown cursor state.";
                    NUtils.getGameUI().msg(msg);
                    log(msg);
                    break;
                } catch (TaskCriticalExitException e) {
                    // A bounded NCore task (e.g. DropOn, when the server rejects a drop - "the
                    // bucket must be carried when not empty" was the confirmed live 2026-08 case)
                    // hit its own timeout - NCore.addTask() signals this with the dedicated
                    // TaskCriticalExitException subtype specifically so it can be told apart from a
                    // genuine stop-button/bot-cancel InterruptedException without relying on the
                    // thread's interrupt flag (which Object.wait() clears for both cases - see that
                    // exception's own class doc). This turns "one task inside one product hung/
                    // timed out" back into a recoverable per-gob skip instead of silently ending the
                    // whole run.
                    log("  " + gobResName + " (id=" + target.id + "): a task timed out processing this gob, skipping it - " + e);
                    recordSkip(target.id, "*", "error: task timeout");
                } catch (InterruptedException e) {
                    // Anything reaching here is a real cancellation (TaskCriticalExitException,
                    // the only synthetic InterruptedException this codebase's task machinery
                    // produces, is caught above) - always propagate, never swallow.
                    throw e;
                } catch (RuntimeException e) {
                    // BotExecutor only catches InterruptedException around the whole run() call
                    // (see BotExecutor.runWithSupports) - any other exception previously killed
                    // this thread outright with no message shown to the player, which read as
                    // "the bot just stops/finishes" (confirmed live 2026-08, reliably reproduced by
                    // a bugged VSpec entry that made a gob's data disagree with itself - fixed at
                    // the data level too, but this gob-level isolation is the general fix: one bad
                    // gob, of any cause, must never take the whole run down silently).
                    log("  " + gobResName + " (id=" + target.id + "): error processing gob, skipping it - " + e);
                    recordSkip(target.id, "*", "error: " + e);
                }

                clearedGobs.add(target.id);
                gobsCleared++;
            }

            report(gobsCleared, productsDiscovered);
            return Results.SUCCESS();
        } finally {
            closeDebugLog();
        }
    }

    /**
     * Every still-undiscovered product on one gob - the body of the main loop's per-target work.
     * Returns how many of them were confirmed discovered this pass.
     */
    private int processGob(NGameUI gui, Gob target, String gobResName) throws InterruptedException {
        List<String> products;
        try {
            products = LpExplorer.allUndiscoveredProducts(target);
        } catch (Loading l) {
            log("  sprite still loading, retrying next scan");
            return 0; // sprite not loaded yet - retry on the next scan
        }

        int discoveredCount = 0;
        for (String product : products) {
                    if (alreadySkipped(target.id, product))
                        continue;

                    LpActionMatcher.Category category = LpActionMatcher.classify(gobResName, product);
                    String tool = LpActionMatcher.requiredTool(category, prop);
                    log("  " + product + ": category=" + category + (tool != null ? ", needs tool=" + tool : ""));

                    // Don't even start walking/equipping for a product whose result genuinely has
                    // nowhere to go - check its actual shape against actual free space (not a total
                    // free-square count, which doesn't prove a multi-cell item fits - see
                    // footprintFor()/ensureSpaceFor() class docs), giving cleanup one chance to
                    // settle first if it doesn't fit right away.
                    Coord footprint = footprintFor(category);
                    if (!ensureSpaceFor(gui, footprint)) {
                        throw new OutOfSpaceException(footprint.y + "x" + footprint.x + " for " + category);
                    }

                    if (tool != null) {
                        Results equipResult = new Equip(new NAlias(tool)).run(gui);
                        if (!equipResult.IsSuccess()) {
                            // Equip() itself already surfaced a descriptive reason (e.g. a non-empty
                            // bucket protected in hand - see Equip's own doc) via NUtils.getGameUI().
                            // error() when it built that Results, so this log line intentionally
                            // doesn't repeat guesswork about *why* it failed.
                            log("  " + product + ": tool not available, skipping");
                            recordSkip(target.id, product, "missing tool: " + tool);
                            continue;
                        }
                    }

                    // Logs/old trunks have a wide, finicky rectangular hitbox (confirmed live
                    // 2026-08): approaching from certain sides leaves the player somewhere
                    // PathFinder considers "reached" but too far from the actual clickable hitbox
                    // for the harvest click to land, opening no flower menu (or one without the
                    // expected petal) even though the resource is genuinely still there. A single
                    // failed attempt isn't proof of "nothing left" for these categories, so back
                    // off and re-approach from a rotated angle before accepting the skip - trees/
                    // bushes/stone use round hitboxes and don't show this failure mode, so they
                    // stay single-attempt.
                    boolean retryableHitbox = category == LpActionMatcher.Category.BOARD
                            || category == LpActionMatcher.Category.BLOCK
                            || category == LpActionMatcher.Category.OLDTRUNK;
                    int maxAttempts = retryableHitbox ? 3 : 1;

                    NFlowerMenu fm = null;
                    NFlowerMenu.NPetal petal = null;
                    boolean unreachable = false;
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        boolean lastAttempt = attempt == maxAttempts;
                        if (attempt > 1)
                            repositionAround(gui, target, attempt);

                        // Re-path before every attempt, not just once per gob: interruptActivity()
                        // walks the player a step away from the gob after each product to cancel
                        // any still-running repeat action, so later products need re-approaching
                        // too, same as a failed hitbox attempt does.
                        if (!new PathFinder(target).run(gui).IsSuccess()) {
                            unreachable = true;
                            log("  " + product + ": unreachable" + (lastAttempt ? ", skipping rest of this gob" : ", retrying approach"));
                            if (lastAttempt)
                                break;
                            continue;
                        }
                        unreachable = false;

                        NUtils.rclickGob(target);
                        // LpExplorer.checkLpExplorer() (the thing that actually writes the discovery
                        // record the green marker depends on) only fires within a short window after
                        // map.clickedGob points at a harvestable gob - see recentHarvestClick() in that
                        // class. That field is normally set by MapView.Click.hit(), which only runs for
                        // a real local mouse click; NUtils.rclickGob()/lclick() and GoTo's movement
                        // click all send their "click" wdgmsg directly to the server and never trigger
                        // it. Bot-driven harvesting was therefore relying entirely on whatever stale
                        // value a real click happened to leave behind before the bot started (matched
                        // by resource TYPE, not gob identity - see recentHarvestClick()'s own doc) -
                        // explaining both why discovery sometimes recorded for the wrong-seeming
                        // product and why it often silently never recorded at all (the fallback marker/
                        // minimap icon depends solely on this record, not on the exp signal below, so a
                        // missing record here is exactly "icon never clears"). Stamping it explicitly
                        // for the gob we're actually about to harvest - the same public field/
                        // constructor a real click would populate - fixes this at the source instead of
                        // working around it. Re-stamped fresh for every attempt, each giving its own
                        // 10s window (LpExplorer.HARVEST_CLICK_WINDOW).
                        gui.map.clickedGob = new MapView.ClickedGob(target, 3);
                        fm = NUtils.getFlowerMenu();
                        if (fm == null || fm.nopts == null || fm.nopts.length == 0) {
                            if (fm != null) {
                                fm.wdgmsg("cl", -1);
                                NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                            }
                            fm = null;
                            log("  " + product + ": no flower menu opened" + (lastAttempt ? "" : ", retrying from a different angle"));
                            continue;
                        }

                        // SEED has no fixed wording (species-specific: "Pick berries"/"Pick pomes"/
                        // "Pick cone"/... - see LpActionMatcher's class doc) so it gets its own
                        // shape-based matcher instead of a candidate list.
                        petal = category == LpActionMatcher.Category.SEED
                                ? LpActionMatcher.findSeedPetal(fm)
                                : LpActionMatcher.findPetal(fm, LpActionMatcher.candidateActions(category, product));
                        fm.wdgmsg("cl", -1);
                        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());

                        if (petal != null)
                            break;
                        log("  " + product + ": no matching petal on attempt " + attempt + (lastAttempt ? "" : ", retrying from a different angle"));
                    }

                    if (unreachable) {
                        recordSkip(target.id, "*", "unreachable");
                        break;
                    }

                    if (fm == null) {
                        log("  " + product + ": no flower menu opened after " + maxAttempts + " attempt(s), skipping");
                        recordSkip(target.id, product, "no flower menu");
                        continue;
                    }

                    if (petal == null) {
                        log("  " + product + ": no matching petal after " + maxAttempts + " attempt(s), live petals were: " + petalNames(fm));
                        recordSkip(target.id, product, "no matching action, petals were: " + petalNames(fm));
                        continue;
                    }

                    // Round 5: LP Assistant only needs to confirm a resource CAN produce one
                    // specific undiscovered item, not harvest it to exhaustion. Board/Block
                    // additionally share one depleting "log HP" pool between the two of them
                    // (cutting either subtracts from the same total), so letting either run for
                    // several units before stopping - the old per-category split below - risked
                    // using up a log's second, still-undiscovered product before ever reaching it
                    // (confirmed live 2026-08: 4-5 boards cut before interruption). Superseded:
                    /*
                     * boolean highVolume = category == BOARD || BLOCK || LEAF || STONE;
                     * (WaitFirstProgressCycle+interruptActivity for highVolume,
                     *  WaitCollectState-to-natural-stop otherwise)
                     */
                    // See docs/lp-assistant-bot.md's Round 5 section for the full writeup.
                    NAlias productAlias = new NAlias(product);
                    InventorySnapshot harvestBaseline = InventorySnapshot.capture(gui.getInventory(), productAlias);
                    int expBaseline = WaitLpProductDiscovered.currentExp();
                    log("  " + product + ": selecting \"" + petal.name + "\"");
                    new SelectFlowerAction(petal.name, target).run(gui);
                    Gob player = NUtils.player();
                    if (player != null) {
                        NUtils.getUI().core.addTask(new WaitPoseExclude(player, "idle"));
                    }
                    logClickedGob("  " + product + ": clickedGob right after the harvest click");

                    WaitLpFirstProduct firstProduct = new WaitLpFirstProduct(gui.getInventory(), productAlias,
                            harvestBaseline, gobResName, product, expBaseline, FIRST_PRODUCT_TIMEOUT_MS);
                    NUtils.getUI().core.addTask(firstProduct);
                    log("  " + product + ": first-product signal = " + firstProduct.signal());

                    // Always interrupt (and let GoTo's own completion be the bounded confirmation
                    // that the walk-away actually happened) before ever touching the cursor - a
                    // still-repeating action can otherwise refill it the instant it looks clear.
                    interruptActivity(gui, target);
                    log("  " + product + ": extraction interrupt confirmed (moved away)");
                    // Bounded, state-driven settlement (Round 6) instead of a fixed sleep - absorbs
                    // one already-in-flight unit committed server-side just before the interrupt
                    // landed, without waiting long enough to risk folding in a second production
                    // cycle. Polls the same InventorySnapshot topology every other stage uses (see
                    // WaitLpSettlement's own doc).
                    WaitLpSettlement settlement = new WaitLpSettlement(gui.getInventory(), productAlias,
                            SETTLEMENT_QUIET_MS, SETTLEMENT_MAX_MS);
                    NUtils.getUI().core.addTask(settlement);
                    InventorySnapshot settled = settlement.result();
                    log("  " + product + ": settlement ended (" + settlement.endReason() + ")");

                    boolean discovered = firstProduct.signal() == WaitLpFirstProduct.Signal.DISCOVERY;
                    if (discovered) {
                        log("  " + product + ": discovery confirmed (via first-product signal)");
                    } else {
                        WaitLpProductDiscovered discovery =
                                new WaitLpProductDiscovered(gobResName, product, expBaseline, DISCOVERY_CONFIRM_TIMEOUT_MS);
                        NUtils.getUI().core.addTask(discovery);
                        discovered = discovery.confirmed();
                        if (discovered)
                            log("  " + product + ": discovery confirmed (via " + discovery.confirmedVia() + ")");
                    }
                    if (discovered) {
                        discoveredCount++;
                    } else {
                        log("  " + product + ": discovery NOT confirmed (timed out)");
                        logClickedGob("  " + product + ": clickedGob at timeout");
                        try {
                            log("  " + product + ": cross-check allUndiscoveredProducts=" + LpExplorer.allUndiscoveredProducts(target));
                        } catch (Loading l) {
                            log("  " + product + ": cross-check allUndiscoveredProducts=<loading>");
                        }
                        recordSkip(target.id, product, "discovery not confirmed");
                    }

                    // A picked-up item that couldn't auto-stack (no free inventory space) sits in
                    // the cursor/hand (gui.vhand) rather than the inventory. Left there, it
                    // hijacks the next right-click on a gob into an item-use interaction instead
                    // of the gob's real flower menu. Clear it before attempting the next product.
                    // forceStopExtraction=false: extraction was already unconditionally interrupted
                    // and confirmed above (Round 5), so this only re-interrupts in the rarer case
                    // the cursor is still observed occupied at this point. cursorClass is decided
                    // from the same settled/baseline snapshot pair as ownership below (Round 6) -
                    // never assume any cursor content is "ours" just because it's present.
                    // Round 7: clearHandAfterHarvest() re-verifies the LIVE cursor against
                    // settled.cursorWdgid immediately before acting - see its own doc for why
                    // (a different item can arrive in the window between the settlement snapshot
                    // and this call).
                    InventorySnapshot.CursorClassification cursorClass = settled.classifyCursor(harvestBaseline);
                    ClearHandResult handResult = clearHandAfterHarvest(gui, target, false, cursorClass, settled.cursorWdgid);
                    log("  " + product + ": cursor-clear result = " + handResult + " (classification=" + cursorClass + ")");
                    if (handResult == ClearHandResult.STUCK) {
                        // Cursor state is unknown/occupied and couldn't be resolved within the
                        // bounded wait - must not triage, equip, or right-click anything else (the
                        // next right-click could hit an item-use interaction instead of a flower
                        // menu). Record the skip and let this propagate out of processGob entirely;
                        // run()'s CursorStuckException handler stops the whole run rather than just
                        // this gob, since cursor state isn't scoped to one gob.
                        recordSkip(target.id, product, "cursor stuck after harvest");
                        throw new CursorStuckException(product + " on gob id=" + target.id);
                    }

                    // Round 7: triage must use a FRESH post-cursor-handling snapshot, not `settled`.
                    // If clearHandAfterHarvest() just stashed a newly-harvested cursor item into a
                    // free inventory slot, that item is absent from `settled` (captured before the
                    // stash) and would silently never be triaged. If it was dropped to the ground
                    // instead, it's correctly absent from this fresh capture too - either way this
                    // is the true final state to compute ownership/drop targets against.
                    InventorySnapshot finalState = InventorySnapshot.capture(gui.getInventory(), productAlias);
                    if (prop.autoEatNew || prop.autoDropNew)
                        triageHarvestOutput(gui, product, harvestBaseline, finalState);
        }
        return discoveredCount;
    }

    // Bound for waiting out the first sign of this harvest's output (see WaitLpFirstProduct) -
    // generous enough for the slowest observed category (Board/Block cutting) while still far
    // shorter than letting either run to a natural stop.
    private static final long FIRST_PRODUCT_TIMEOUT_MS = 6000;
    // Bound for the follow-up discovery confirmation when the first-product signal itself wasn't
    // DISCOVERY - the item has already appeared by this point, so the record/exp update is
    // expected promptly.
    private static final long DISCOVERY_CONFIRM_TIMEOUT_MS = 3000;
    // WaitLpSettlement's quiet period (state must hold steady this long to finish early) and hard
    // wall-clock cap (never wait longer than this regardless of quiet state) - see its call site's
    // doc. The quiet period only needs to cover one already-in-flight unit's arrival latency, not a
    // whole new production cycle; the cap is a safety backstop, not the expected common case.
    private static final long SETTLEMENT_QUIET_MS = 300;
    private static final long SETTLEMENT_MAX_MS = 1500;

    /**
     * Backs the player off target and re-approaches from a different side - used by the
     * BOARD/BLOCK/OLDTRUNK hitbox-retry loop above. Rotates the offset angle a further ~70 degrees
     * each attempt so a retry doesn't just re-walk the same failed angle: a log's hitbox is a wide
     * rectangle, not a circle, so a side that failed to click is often fine from elsewhere around it.
     */
    private void repositionAround(NGameUI gui, Gob target, int attempt) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null || target == null)
            return;
        Coord2d fromTarget = player.rc.sub(target.rc);
        if (fromTarget.x == 0 && fromTarget.y == 0)
            fromTarget = new Coord2d(1, 0);
        else
            fromTarget = fromTarget.norm();
        double angle = Math.toRadians(70 * (attempt - 1));
        double cos = Math.cos(angle), sin = Math.sin(angle);
        Coord2d rotated = new Coord2d(fromTarget.x * cos - fromTarget.y * sin, fromTarget.x * sin + fromTarget.y * cos);
        Coord2d away = target.rc.add(rotated.mul(11));
        new GoTo(away).run(gui);
    }

    /** Steps the player one tile away from target, to interrupt any still-running repeat action. */
    private void interruptActivity(NGameUI gui, Gob target) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null || target == null)
            return;
        Coord2d towardTarget = target.rc.sub(player.rc);
        if (towardTarget.x == 0 && towardTarget.y == 0)
            towardTarget = new Coord2d(1, 0);
        else
            towardTarget = towardTarget.norm();
        Coord2d away = player.rc.sub(towardTarget.mul(11));
        new GoTo(away).run(gui);
    }

    /** Nearest still-actionable gob, after collapsing identical duplicate instances. */
    private Gob pickNearestCandidate() throws InterruptedException {
        ArrayList<Gob> candidates = new ArrayList<>();
        synchronized (NUtils.getGameUI().ui.sess.glob.oc) {
            for (Gob gob : NUtils.getGameUI().ui.sess.glob.oc) {
                if (gob instanceof OCache.Virtual || gob.attr.isEmpty() || gob.ngob == null || gob.ngob.name == null)
                    continue;
                if (clearedGobs.contains(gob.id))
                    continue;
                if (HarvestSpecs.forResource(gob.ngob.name) == null)
                    continue;
                candidates.add(gob);
            }
        }
        if (candidates.isEmpty())
            return null;

        // Group by (resource, current undiscovered-product list) and keep only the nearest
        // instance per group - collapses redundant duplicates before any pathing decision.
        Map<String, Gob> nearestByGroup = new HashMap<>();
        for (Gob gob : candidates) {
            List<String> products;
            try {
                products = LpExplorer.allUndiscoveredProducts(gob);
            } catch (Loading l) {
                continue;
            } catch (RuntimeException e) {
                // One gob in a transient bad state (e.g. attrs not fully attached yet on a
                // just-loaded-in gob) must not take the whole candidate scan down with it - see
                // the matching guard in run()'s main loop for why this matters (a bot thread that
                // dies from an uncaught exception here shows the player nothing at all, looking
                // exactly like "the bot silently finished").
                log("  candidate scan error on gob id=" + gob.id + ": " + e + ", skipping it this scan");
                continue;
            }
            products = withoutSkipped(gob.id, products);
            if (products.isEmpty())
                continue;

            String key = gob.ngob.name + "|" + String.join(",", products);
            Gob best = nearestByGroup.get(key);
            if (best == null || dist(gob) < dist(best))
                nearestByGroup.put(key, gob);
        }
        if (nearestByGroup.isEmpty())
            return null;

        Gob nearest = null;
        for (Gob gob : nearestByGroup.values()) {
            if (nearest == null || dist(gob) < dist(nearest))
                nearest = gob;
        }
        return nearest;
    }

    private double dist(Gob gob) {
        Gob player = NUtils.player();
        if (player == null)
            return Double.MAX_VALUE;
        return gob.rc.dist(player.rc);
    }

    private List<String> withoutSkipped(long gobId, List<String> products) {
        Map<String, String> reasons = skipped.get(gobId);
        if (reasons == null || reasons.isEmpty())
            return products;
        List<String> remaining = new ArrayList<>();
        for (String p : products) {
            if (!reasons.containsKey(p))
                remaining.add(p);
        }
        return remaining;
    }

    private boolean alreadySkipped(long gobId, String product) {
        Map<String, String> reasons = skipped.get(gobId);
        return reasons != null && reasons.containsKey(product);
    }

    private void recordSkip(long gobId, String product, String reason) {
        skipped.computeIfAbsent(gobId, k -> new LinkedHashMap<>()).put(product, reason);
    }

    /** Adds one item's name/wdgid to the startup protection snapshot - see preexistingItemNames' doc. */
    private void protectExisting(WItem item) {
        if (item == null || !(item.item instanceof NGItem))
            return;
        String name = ((NGItem) item.item).name();
        if (name != null)
            preexistingItemNames.add(name);
        preexistingWidgetIds.add(item.item.wdgid());
    }

    /** True if this name must never be auto-dropped/eaten by LP Assistant - see the class doc. */
    private boolean isProtectedName(String name) {
        return name == null || preexistingItemNames.contains(name) || protectedToolNames.contains(name);
    }

    // Fixed startup sanity gate, independent of any specific product's footprint - see
    // footprintFor()'s doc for why one product's shape can't stand in for another's. Same value as
    // the original design's WORKING_SPACE: a 2-wide x 4-tall clear area. Coord.x is height/row-
    // count, Coord.y is width/column-count (see docs/inventory-grid-system.md's swapped convention).
    private static final Coord WORKING_REGION = new Coord(4, 2);

    /**
     * The grid footprint the product of this category actually needs, in the same swapped
     * (height, width) convention every grid-placement call site uses (Coord.x = height/y-count,
     * Coord.y = width/x-count - see docs/inventory-grid-system.md). Confirmed live 2026-08: Board
     * is 1 wide x 4 tall, Block (and Old Trunk's block, same item shape) is 2 wide x 1 tall, Bough
     * is 1 wide x 2 tall; everything else this bot gathers is 1x1. A total-free-square count (the
     * previous check here) never proves any of these multi-cell shapes actually fits - fragmented
     * free space can add up to plenty of squares without a single spot wide/tall enough for one.
     * No one shape here is "the hardest" in general - a spot that fits a 1x4 column doesn't prove a
     * 2x1 row fits, or vice versa - which is why this is checked per-product, immediately before
     * that product's own harvest (see ensureSpaceFor()'s call site in processGob()), never
     * substituted with another category's footprint. WORKING_REGION above is the one place a single
     * fixed shape is intentionally used instead, as a startup-only "is there room at all" gate.
     */
    private static Coord footprintFor(LpActionMatcher.Category category) {
        switch (category) {
            case BOARD:
                return new Coord(4, 1);
            case BLOCK:
            case OLDTRUNK:
                return new Coord(1, 2);
            case BOUGH:
                return new Coord(2, 1);
            default:
                return new Coord(1, 1);
        }
    }

    /** Whether a footprint fits, doesn't fit, or can't be determined yet - see checkSpace()'s doc. */
    private enum SpaceState { FITS, NO_FIT, NOT_READY }

    // Bound for waiting out a transient "not ready" grid read (some item's sprite still loading) -
    // short and small-stepped, since this normally resolves within a frame or two once the item
    // actually exists in the grid; see checkSpace()/waitForSpaceState()'s doc for why this must
    // never be treated the same as a real "doesn't fit".
    private static final int GRID_READY_RETRIES = 20;
    private static final long GRID_READY_RETRY_MS = 50;

    /**
     * One live read of whether footprint fits right now - never blocks. NInventory.
     * containerMatrix() (and therefore findFreeCoord()) returns null, not "doesn't fit", while any
     * top-level item's sprite hasn't loaded yet (NInventory.isGridReady()) - conflating that with
     * "no placement exists" would misreport a transient, self-resolving condition as a genuinely
     * full inventory.
     */
    private SpaceState checkSpace(NGameUI gui, Coord footprint) {
        if (!gui.getInventory().isGridReady())
            return SpaceState.NOT_READY;
        return gui.getInventory().findFreeCoord(footprint) != null ? SpaceState.FITS : SpaceState.NO_FIT;
    }

    /** Bounded (GRID_READY_RETRIES x GRID_READY_RETRY_MS) wait for a definitive FITS/NO_FIT answer. */
    private SpaceState waitForSpaceState(NGameUI gui, Coord footprint) throws InterruptedException {
        for (int i = 0; i < GRID_READY_RETRIES; i++) {
            SpaceState state = checkSpace(gui, footprint);
            if (state != SpaceState.NOT_READY)
                return state;
            Thread.sleep(GRID_READY_RETRY_MS);
        }
        return SpaceState.NOT_READY;
    }

    /**
     * True once a real placement check (NInventory.findFreeCoord - the same live, shape-aware
     * scan the actual item-placement code uses) finds room for this exact footprint. If it
     * doesn't fit right away, gives the bot's own cleanup one chance to catch up first: the usual
     * reason a shape doesn't fit yet is the *previous* product's own gathered items still sitting
     * there because their drop hasn't been confirmed (see StudyEatOrDrop/triageNewItems), not a
     * genuinely full inventory. If the grid state still can't be read after the bounded retry
     * (some sprite still loading), this reports "no" too (fail closed, matching the rest of this
     * bot's conservative bias) but logs it distinctly from a real "doesn't fit" so a stopped run's
     * message doesn't misreport which one actually happened.
     */
    private boolean ensureSpaceFor(NGameUI gui, Coord footprint) throws InterruptedException {
        SpaceState state = waitForSpaceState(gui, footprint);
        if (state == SpaceState.FITS)
            return true;
        if (state == SpaceState.NOT_READY) {
            log("  space check: inventory grid state not ready (sprite still loading) after "
                    + (GRID_READY_RETRIES * GRID_READY_RETRY_MS) + "ms, treating as not fitting for now");
            return false;
        }
        if (!lastTriagePending.isEmpty() && lastTriageProduct != null) {
            // Round 5: no more whole-inventory sweep here (see class doc) - the only safe thing
            // left to retry is the most recent harvest's own already-proven-safe target set, in
            // case its drop hadn't confirmed yet by the time the next product needed the space.
            log("  space check: retrying pending drop for " + lastTriageProduct + " ("
                    + lastTriagePending.size() + " unit(s) not yet confirmed gone)");
            if (dropHarvestWidgets(gui, lastTriagePending))
                lastTriagePending.clear();
            return waitForSpaceState(gui, footprint) == SpaceState.FITS;
        }
        return false;
    }

    /**
     * Non-blocking: null if the item's sprite hasn't loaded yet or nothing fits its footprint -
     * never waits, unlike NInventory.getFreeCoord()/GetFreePlace (see clearHandAfterHarvest's doc
     * for why that matters here).
     */
    private Coord nonBlockingFreeCoord(NGameUI gui, WItem hand) {
        if (hand.item.spr == null)
            return null;
        return gui.getInventory().findFreeCoord(hand);
    }

    // Bound for both cursor-recovery waits below - long enough for a normal drop/stash round trip,
    // short enough that a server that's silently ignoring the request (e.g. rejecting a bucket
    // put-away) doesn't hang the bot instead of being reported and skipped.
    private static final long CURSOR_CLEAR_TIMEOUT_MS = 5000;

    /**
     * Bounded, wall-clock wait for the cursor to become empty - the ground-drop confirmation path
     * (a dropped-to-ground item has no slot to confirm, only the cursor clearing). Deliberately does
     * NOT go through NInventory.dropOn() (which sends the "drop" wdgmsg itself, then blocks inside
     * NCore.addTask(new DropOn(...)) - a task bounded by a poll *count*, not wall-clock time, tied
     * to however often NCore.tick() actually runs). Stacking that unbounded-in-wall-clock-terms wait
     * in front of this method's own 5s wall-clock wait would make the real worst-case bound unknown
     * - confirmed live 2026-08 as the reason a cursor-clear attempt could take far longer than the
     * intended 5s cap. The "drop" wdgmsg is sent directly by the caller instead (same message
     * NInventory.dropOn() itself sends), and this method alone provides the bound.
     *
     * Round 7b: the STASH confirmation path (does the item actually land somewhere, not just leave
     * the cursor) moved to the identity-based waitCursorStashSettledByIdentity() - a slot/name check
     * here was wrong the moment a stash merged into a pre-existing stack instead of landing in the
     * exact free slot it was sent to (see that method's own doc).
     */
    private boolean waitCursorSettled(NGameUI gui, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                if (System.currentTimeMillis() >= deadline)
                    return true;
                return gui.vhand == null;
            }
        });
        return gui.vhand == null;
    }

    /**
     * Bounded, wall-clock wait for a cursor STASH to be confirmed by the stashed item's own
     * identity (Round 7b), not by whether the specific slot it was sent to ended up occupied.
     * waitCursorSettled()'s slot-occupied check (isItemInSlot/isSlotFree) is wrong for a stash that
     * successfully MERGES into a pre-existing stack elsewhere instead of landing in the exact free
     * slot it was sent to - findFreeCoord() picks an empty slot without knowing the server might
     * auto-merge the item into a same-name stack instead, which leaves the originally-requested
     * slot genuinely empty even though the stash fully succeeded. That previously meant a full
     * CURSOR_CLEAR_TIMEOUT_MS (5s) wasted wait, misreported as "stash not confirmed" and treated as
     * if the item still needed a fallback ground-drop, when it had already safely stashed instantly.
     * Confirms instead via: cursor cleared AND the item's own wdgid resolves anywhere in this
     * inventory via InventorySnapshot.findAny() - true the moment the merge (or the plain slot
     * placement) actually lands, wherever it landed.
     */
    private boolean waitCursorStashSettledByIdentity(NGameUI gui, int expectedWdgid, long timeoutMs)
            throws InterruptedException {
        NInventory inv = gui.getInventory();
        long deadline = System.currentTimeMillis() + timeoutMs;
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                if (System.currentTimeMillis() >= deadline)
                    return true;
                return stashConfirmedByIdentity(gui, inv, expectedWdgid);
            }
        });
        return stashConfirmedByIdentity(gui, inv, expectedWdgid);
    }

    private static boolean stashConfirmedByIdentity(NGameUI gui, NInventory inv, int expectedWdgid) {
        return gui.vhand == null && InventorySnapshot.findAny(inv, expectedWdgid) != null;
    }

    // Bounded settle wait for the startup empty-bucket check (see isDefinitelyEmptyBucket()) - a
    // one-time startup check, so affording it up to a second is cheap; matches the shape of this
    // file's other bounded-retry constants (e.g. GRID_READY_RETRIES/GRID_READY_RETRY_MS).
    private static final int BUCKET_CHECK_RETRIES = 10;
    private static final long BUCKET_CHECK_RETRY_MS = 100;

    /**
     * Round 7c startup safety gate: refuses to run at all if either equipped hand definitely holds
     * an EMPTY bucket. Equip()'s hand-freeing swap (see its own class doc) can send a hand item into
     * a belt/inventory slot via NInventory.dropOn() to make room for a tool - runtime-confirmed
     * 2026-08 to hang (DropOn's bounded wait timing out as TaskCriticalExitException) specifically
     * for an equipped EMPTY bucket, leaving it stuck in the cursor with no automatic recovery: that
     * failure happens mid-Equip(), a different code path from processGob()'s own per-product cursor-
     * classification/CursorStuckException machinery, so nothing else in this bot notices or fixes it
     * (the player had to manually correct the hand state before the run could continue). A NON-empty
     * bucket is unaffected - already protected inside Equip() itself (isNonEmptyBucket() excludes it
     * from the swap entirely, and this is separately runtime-confirmed live 2026-08 to correctly stay
     * equipped through a tool swap) - so this gate is deliberately scoped to only the one case that
     * isn't already safe, rather than touching Equip()'s own logic.
     *
     * Never moves the bucket, never calls Equip(), never processes a gob - just inspects and, if
     * unsafe, stops the whole bot cleanly with an explicit message telling the player how to fix it.
     */
    private Results checkEquippedBucketsAtStartup(NGameUI gui) throws InterruptedException {
        WItem lhand = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_LEFT.idx);
        WItem rhand = NUtils.getEquipment().findItem(NEquipory.Slots.HAND_RIGHT.idx);
        if (isDefinitelyEmptyBucket(lhand) || isDefinitelyEmptyBucket(rhand)) {
            String msg = "LP Assistant bot: an equipped water bucket is empty. Fill it with water or "
                    + "unequip it, keep a clear 2x4 inventory area, and restart.";
            NUtils.getGameUI().msg(msg);
            log(msg);
            return Results.ERROR("Equipped bucket empty");
        }
        return Results.SUCCESS();
    }

    /** Outcome of classifyBucketFill() - see that method's own doc. Package-private for testing. */
    enum BucketFillState { NOT_A_BUCKET, NOT_EMPTY, DEFINITELY_EMPTY, INDETERMINATE }

    /**
     * Pure classification, deliberately separated from the live polling loop below so it can be
     * unit tested without a live haven UI/Resource/WItem tree (see
     * test/nurgling/actions/bots/LpAssistantBotBucketCheckTest.java). Two separate,
     * independently-loaded signals are involved here and must not be conflated:
     *
     * - `name` (NGItem.name()) resolves from the item's RESOURCE's own bundled default tooltip text
     *   (haven.ItemInfo.Name.Default.get() reads Resource.tooltip directly - confirmed by reading
     *   ItemInfo.java) - independent of, and typically much faster than, the server's per-instance
     *   tooltip data. It tells us "this is a Bucket," nothing about fill state.
     * - `hasContent` (from NGItem.content()) is populated from the server's own per-item tooltip
     *   payload (NGItem.updateraw(), driven by the "tt" wire message - confirmed by reading
     *   NGItem.java/haven.GItem.java) - this is genuinely asynchronous and can still read as empty
     *   for a fully-loaded, actually-full bucket if that payload simply hasn't arrived yet. Relying
     *   on content().isEmpty() alone the instant a hand is inspected, with no readiness signal, is
     *   exactly the race this method must not fall into.
     * - `qualityResolved` (from NGItem.quality != null) is populated by that exact same server
     *   tooltip payload/updateraw() call as content() (same "tt" message, same switch statement) -
     *   NWItem.autoDrop() already treats a null quality as "tooltip not loaded yet, re-check later"
     *   for the identical reason, so reusing it here as the readiness gate is an existing,
     *   established idiom, not a new assumption.
     *
     * Search confirmed no separate empty-vs-filled RESOURCE identity exists anywhere in this
     * codebase or its bundled resources (e.g. no "bucket"/"bucket-water" resource pair) - content()
     * paired with a readiness gate is the most reliable already-available signal, matching every
     * other bucket fill-state check in this codebase (FillWaterskins, FillEmptyContainersAction).
     *
     * Returns NOT_EMPTY the moment hasContent is true - that can never be a false positive, whether
     * or not quality has resolved yet. Returns DEFINITELY_EMPTY only once qualityResolved is also
     * true (tooltip has genuinely loaded, and it says empty). Otherwise INDETERMINATE - the caller
     * must keep polling (bounded) rather than concluding empty from unproven state.
     */
    static BucketFillState classifyBucketFill(String name, boolean hasContent, boolean qualityResolved) {
        if (name == null || !NParser.checkName(name, "Bucket"))
            return BucketFillState.NOT_A_BUCKET;
        if (hasContent)
            return BucketFillState.NOT_EMPTY;
        return qualityResolved ? BucketFillState.DEFINITELY_EMPTY : BucketFillState.INDETERMINATE;
    }

    /**
     * True only when this hand item is PROVABLY a Bucket with no content - never a guess. Polls up
     * to BUCKET_CHECK_RETRIES times (BUCKET_CHECK_RETRY_MS apart), re-classifying via
     * classifyBucketFill() each time, until a definitive NOT_A_BUCKET/NOT_EMPTY/DEFINITELY_EMPTY
     * answer is reached. If the bound is exhausted still INDETERMINATE, this returns false and logs
     * the ambiguity - it never concludes "definitely empty" from unproven state, matching this bot's
     * standing conservative bias elsewhere (see e.g. InventorySnapshot's AMBIGUOUS classification).
     */
    private boolean isDefinitelyEmptyBucket(WItem hand) throws InterruptedException {
        if (hand == null || !(hand.item instanceof NGItem))
            return false;
        NGItem ngItem = (NGItem) hand.item;
        for (int i = 0; i < BUCKET_CHECK_RETRIES; i++) {
            BucketFillState state = classifyBucketFill(ngItem.name(), !ngItem.content().isEmpty(), ngItem.quality != null);
            switch (state) {
                case NOT_A_BUCKET:
                case NOT_EMPTY:
                    return false;
                case DEFINITELY_EMPTY:
                    return true;
                case INDETERMINATE:
                default:
                    break; // tooltip not loaded yet - keep polling within the bound
            }
            Thread.sleep(BUCKET_CHECK_RETRY_MS);
        }
        log("  startup bucket check: tooltip for an equipped Bucket never resolved within "
                + (BUCKET_CHECK_RETRIES * BUCKET_CHECK_RETRY_MS) + "ms - cannot confirm empty/full, proceeding");
        return false;
    }

    /**
     * Startup-only cursor handling: never discards an item the player was already holding when
     * they started the bot (confirmed live 2026-08: the old unconditional clearHand() dropped it
     * to the ground on the spot if it didn't happen to fit in a free slot - a real risk for
     * anything the player was mid-task with). Only ever stashes; if it doesn't fit, refuses to
     * start rather than guess what the player wanted done with their own item. Round 7b: stash
     * confirmation is identity-based (see waitCursorStashSettledByIdentity()'s doc) so a merge into
     * an existing stack of the player's own item confirms promptly instead of a false 5s timeout.
     */
    private Results clearHandAtStartup(NGameUI gui) throws InterruptedException {
        WItem hand = gui.vhand;
        if (hand == null)
            return Results.SUCCESS();
        Coord pos = nonBlockingFreeCoord(gui, hand);
        if (pos != null) {
            int handWdgid = hand.item.wdgid();
            gui.getInventory().wdgmsg("drop", pos);
            if (waitCursorStashSettledByIdentity(gui, handWdgid, CURSOR_CLEAR_TIMEOUT_MS)) {
                log("  stashed pre-held cursor item before starting");
                return Results.SUCCESS();
            }
        }
        String msg = "LP Assistant bot: you're holding an item that won't fit in inventory - "
                + "clear your cursor and restart.";
        NUtils.getGameUI().msg(msg);
        log(msg);
        return Results.ERROR("Cursor occupied");
    }

    /** Whether clearHandAfterHarvest() confirmed the cursor clear, or gave up with it still stuck. */
    private enum ClearHandResult { CLEAR, STUCK }

    /**
     * Mid-run cursor handling. Round 6: the cursor item here is no longer assumed to be this bot's
     * own freshly-gathered pickup just because something is present - cursorClass (computed by the
     * caller from the same settled/baseline InventorySnapshot pair used for ownership below) says
     * which of four states it's actually in. Round 7: PRE_EXISTING and AMBIGUOUS both now stop the
     * whole run instead of PRE_EXISTING quietly returning CLEAR - a non-empty cursor hijacks the
     * next right-click into an item-use interaction regardless of *whose* item it is, so "leave it
     * untouched" and "safe to continue" are not the same thing; only a verified-empty or verified-
     * NEW_EXPECTED-and-successfully-cleared cursor is safe to proceed past:
     *  - EMPTY (per cursorClass, computed at settlement time): nothing to do, unless the LIVE
     *    cursor now reads occupied anyway (see below) - the settlement-time read and the live read
     *    can disagree if something arrived in the gap between them, which must not be assumed safe.
     *  - NEW_EXPECTED: provably this harvest's own new pickup as of the settlement snapshot - but
     *    still re-verified against the LIVE cursor immediately below before being touched (see
     *    "revalidate" below).
     *  - PRE_EXISTING: the exact same wdgid the baseline already had (in the cursor, or already
     *    present anywhere in the inventory) - never touched, AND never returns CLEAR while it's
     *    still occupying the cursor; stops the whole run instead.
     *  - AMBIGUOUS: present, but neither of the above - unknown origin, stops the whole run.
     *
     * Revalidate before acting (Round 7): cursorClass reflects the settlement-time snapshot, which
     * can be moments stale by the time this method actually runs. Before stashing/dropping, the
     * LIVE gui.vhand item's own wdgid is compared against the classified cursorWdgid the caller
     * passed in - if they don't match (a different item is now in the cursor than what was
     * classified), this refuses to guess and stops the run, rather than acting on an unclassified
     * item that arrived after the settlement snapshot was taken.
     *
     * For a verified NEW_EXPECTED item, dropping it to the ground when it doesn't fit is the
     * correct, intended outcome, not a risk to the player's own items (unlike clearHandAtStartup,
     * which never drops).
     *
     * Replaces the old getFreeCoord()/GetFreePlace-based wait, which blocks (infinite=true, no
     * timeout) until a fitting coordinate exists - confirmed live 2026-08 to wait forever once the
     * item's sprite had loaded but genuinely nothing on the grid fit its footprint, since that
     * condition doesn't resolve itself if nothing is freeing space. nonBlockingFreeCoord() answers
     * the fits-or-not question immediately from live grid state instead of waiting on it.
     *
     * forceStopExtraction is true when the caller's WaitCollectState ended in NOFREESPACE - the
     * repeating collection must be interrupted even if gui.vhand instantaneously reads null right
     * here, because the server's own cursor-occupied update can still be in flight, and a still-
     * running repeat action would otherwise refill the cursor the instant it looks clear. Otherwise
     * activity is only interrupted once the cursor is actually observed occupied (same
     * interruptActivity() step already used between products for the high-volume categories).
     *
     * Returns STUCK - never throws directly for the drop/stash-unconfirmed case - if a verified
     * NEW_EXPECTED cursor couldn't be confirmed clear within the bounded waits; the caller must
     * treat that as "cursor state unknown" and stop rather than guess (see run()'s
     * CursorStuckException handling). Every other unsafe case (PRE_EXISTING still occupied,
     * AMBIGUOUS, live/classified mismatch) throws CursorStuckException directly instead.
     */
    private ClearHandResult clearHandAfterHarvest(NGameUI gui, Gob target, boolean forceStopExtraction,
                                                   InventorySnapshot.CursorClassification cursorClass,
                                                   Integer expectedCursorWdgid)
            throws InterruptedException {
        if (forceStopExtraction || gui.vhand != null)
            interruptActivity(gui, target);
        WItem hand = gui.vhand;
        if (hand == null)
            return ClearHandResult.CLEAR;

        switch (cursorClass) {
            case PRE_EXISTING:
                throw new CursorStuckException("cursor still holds a pre-existing item after this harvest - "
                        + "never ours to touch, and continuing with it occupied would hijack the next interaction");
            case AMBIGUOUS:
                throw new CursorStuckException("cursor content could not be classified safely (neither the "
                        + "pre-existing item nor a confirmed-new match for this harvest's product)");
            case EMPTY:
                // Settlement's own read saw no cursor item, but the LIVE read here shows one -
                // something arrived in the gap between the settlement snapshot and this call that
                // was never classified. Refuse to guess what it is.
                throw new CursorStuckException("cursor became occupied after the settlement snapshot with an "
                        + "unclassified item");
            case NEW_EXPECTED:
            default:
                break;
        }

        // Revalidate the LIVE cursor against what was actually classified before touching it - see
        // this method's own doc. A wdgid mismatch means a different item arrived after settlement.
        if (expectedCursorWdgid == null || !(hand.item instanceof NGItem) || hand.item.wdgid() != expectedCursorWdgid) {
            throw new CursorStuckException("live cursor content changed since the settlement snapshot - "
                    + "refusing to act on an unverified item");
        }

        Coord pos = nonBlockingFreeCoord(gui, hand);
        if (pos != null) {
            int handWdgid = hand.item.wdgid();
            gui.getInventory().wdgmsg("drop", pos);
            // Round 7b: identity-based confirmation (see waitCursorStashSettledByIdentity()'s doc) -
            // a stash that merges into a pre-existing stack elsewhere leaves the requested slot
            // empty, which the old slot-occupied check would misreport as unconfirmed for the full
            // 5s timeout even though the stash actually succeeded immediately.
            if (waitCursorStashSettledByIdentity(gui, handWdgid, CURSOR_CLEAR_TIMEOUT_MS)) {
                log("  cleared hand item (stashed)");
                return ClearHandResult.CLEAR;
            }
        }
        NUtils.drop(hand);
        if (waitCursorSettled(gui, CURSOR_CLEAR_TIMEOUT_MS)) {
            log("  cleared hand item (dropped)");
            return ClearHandResult.CLEAR;
        }
        log("  cleared hand item: drop did not confirm within " + CURSOR_CLEAR_TIMEOUT_MS + "ms - cursor still occupied");
        return ClearHandResult.STUCK;
    }

    /** Debug-only: logs what map.clickedGob currently points at, to diagnose discovery misses. */
    private void logClickedGob(String label) {
        if (prop == null || !prop.debug)
            return;
        MapView.ClickedGob cg = NUtils.getGameUI().map.clickedGob;
        String state;
        if (cg == null)
            state = "null";
        else if (cg.gob == null)
            state = "gob=null";
        else if (cg.gob.ngob == null)
            state = "gob id=" + cg.gob.id + " (no ngob)";
        else
            state = cg.gob.ngob.name + " (id=" + cg.gob.id + ")";
        log(label + " = " + state);
    }

    /**
     * Studies/eats and drops ONLY the units safely identifiable as this harvest's own output -
     * never a whole-inventory or whole-name sweep (see class doc on preexistingItemNames for the
     * real tool-loss bug that pattern caused). isProtectedName() is checked first as an explicit,
     * independent hard stop even though a harvest product should structurally never collide with a
     * protected name.
     *
     * Round 7: ownership is the InventorySnapshot.Delta of `finalState` (captured AFTER cursor
     * handling - see this method's caller) against `baseline`, identity-based rather than
     * container-shape-based (see InventorySnapshot's own doc): a new loose item, a wholly new
     * stack's children (dropped as one container message, see dropHarvestWidgets()), and specific
     * new children merged into a mixed/pre-existing stack are all tracked separately in an
     * OwnedTargets, since each needs a different drop message and confirmation path. Ambiguous
     * top-level entries (a wdgid whose loose/stack shape flipped) are logged and left untouched
     * rather than guessed.
     */
    private void triageHarvestOutput(NGameUI gui, String product, InventorySnapshot baseline, InventorySnapshot finalState)
            throws InterruptedException {
        if (isProtectedName(product)) {
            log("  triage: " + product + " - refused, this name is protected (should not happen for a harvest product)");
            return;
        }
        InventorySnapshot.Delta delta = finalState.diff(baseline);
        if (!delta.ambiguousTopLevel.isEmpty())
            log("  triage: " + product + " - " + delta.ambiguousTopLevel.size()
                    + " top-level item(s) changed loose/stack shape ambiguously, leaving untouched: "
                    + delta.ambiguousTopLevel);

        OwnedTargets owned = new OwnedTargets();
        owned.looseIds.addAll(delta.newLooseItems);
        owned.wholeStacks.putAll(delta.newWholeStacks);
        for (Map.Entry<Integer, Set<Integer>> e : delta.newChildrenInStacks.entrySet())
            for (int childId : e.getValue())
                owned.partialChildParents.put(childId, e.getKey());

        lastTriageProduct = product;
        lastTriagePending.clear();
        lastTriagePending.looseIds.addAll(owned.looseIds);
        lastTriagePending.wholeStacks.putAll(owned.wholeStacks);
        lastTriagePending.partialChildParents.putAll(owned.partialChildParents);
        if (owned.isEmpty()) {
            log("  triage: " + product + " - no newly-produced unit identified this pass, nothing to do");
            return;
        }
        log("  triage: " + product + " - " + owned.size() + " newly-produced unit(s) ("
                + owned.looseIds.size() + " loose, " + owned.wholeStacks.size() + " whole new stack(s), "
                + owned.partialChildParents.size() + " merged child(ren))");

        NInventory inv = gui.getInventory();
        if (prop.autoEatNew) {
            // Round 7: Study/Eat exactly once per product, on one representative live LEAF unit -
            // never the stack container (StudyEatOrDrop's own getItems(alias) call would never see
            // a container passed directly to it as a match - see docs/inventory-grid-system.md §2 -
            // so passing a container silently did nothing under Round 6). Preference order is
            // arbitrary among owned units; any one of them is equally valid as "the" Study/Eat pick
            // for this harvest.
            WItem eatCandidate = null;
            for (int id : owned.looseIds) {
                eatCandidate = InventorySnapshot.findAny(inv, id);
                if (eatCandidate != null)
                    break;
            }
            if (eatCandidate == null) {
                for (int childId : owned.partialChildParents.keySet()) {
                    eatCandidate = InventorySnapshot.findAny(inv, childId);
                    if (eatCandidate != null)
                        break;
                }
            }
            if (eatCandidate == null) {
                for (Set<Integer> children : owned.wholeStacks.values()) {
                    for (int childId : children) {
                        eatCandidate = InventorySnapshot.findAny(inv, childId);
                        if (eatCandidate != null)
                            break;
                    }
                    if (eatCandidate != null)
                        break;
                }
            }
            if (eatCandidate != null)
                // autoDrop=false: only the Study/Eat interaction on this exact widget runs here -
                // the drop step below is our own widget-scoped one, not this shared by-name one
                // (see dropHarvestWidgets()'s doc for why that distinction matters).
                new StudyEatOrDrop(eatCandidate, true, false).run(gui);
            else
                log("  triage: " + product + " - no live owned unit resolvable for Study/Eat this pass");
        }
        if (prop.autoDropNew) {
            boolean confirmed = dropHarvestWidgets(gui, lastTriagePending);
            if (confirmed)
                lastTriagePending.clear();
            log("  triage: " + product + " - drop " + (confirmed ? "confirmed" : "not confirmed within "
                    + HARVEST_DROP_DEADLINE_MS + "ms, will retry if space is needed"));
        }
    }

    // Never faster than this between drop sends - same flood-safe interval StudyEatOrDrop/
    // NWItem's own auto-dropper already established as safe (NWItem.AUTODROP_INTERVAL_MS). Never
    // weakened by the state-driven retry below - re-querying live state more often does not mean
    // sending drops more often, and a whole owned stack is always exactly one message regardless of
    // how many units it contains (see dropHarvestWidgets()'s doc). LP Assistant's own harvest drops
    // and StudyEatOrDrop's (invoked from LP with autoDrop=false, so it never itself sends a drop
    // here) do not currently run concurrently on the same thread of execution - see
    // docs/lp-assistant-bot.md's drop-throttling section for why a shared cross-bot limiter isn't
    // introduced in this pass.
    private static final long HARVEST_DROP_INTERVAL_MS = 150;
    // One overall bound for the whole cleanup, not a fresh wait per retry (Round 5 - see class doc).
    private static final long HARVEST_DROP_DEADLINE_MS = 4000;

    /**
     * Drops exactly the given owned targets and nothing else - never a by-name sweep, so a
     * protected item sharing this product's name can never be caught by it.
     *
     * Round 7: every target is resolved live via InventorySnapshot.findAny() - an IDENTITY-based
     * lookup that searches the whole inventory (top-level AND every stack's children), not a
     * lookup scoped to whatever parent it was originally recorded under. A unit that was
     * re-parented (merged into a different stack, or became loose) since ownership was computed is
     * still found this way; a parent-scoped lookup (the Round 6 findTopLevel()/findChild() pair)
     * would have wrongly reported it "gone" the instant its original parent stopped resolving,
     * even though the unit itself was still sitting in inventory waiting to be dropped.
     *
     * A wholly new stack (owned.wholeStacks) is dropped as ONE message on the container's own
     * GItem - `container.wdgmsg("drop", Coord.z, 1)`, the same two-argument-plus-count protocol
     * haven.WItem.mousedown() sends for a real CTRL-click drop (confirmed source: `item.wdgmsg(
     * "drop", ev.c, n)`), not NUtils.drop()'s four-argument ground-drop form - see class doc/
     * docs/lp-assistant-bot.md's Round 7 section for why the two differ and why the CTRL-click
     * protocol is the correct match for "drop this whole owned top-level slot in place." Its
     * recorded child ids are never iterated for the drop itself (one message per whole stack, not
     * one per contained item - required even for a large stack); they exist purely so confirmation
     * can keep checking for them individually via findAny() if the container's own wdgid stops
     * resolving before the drop is confirmed (e.g. the stack collapses after a partial pickup) -
     * cleanup then falls back to per-child drops for whichever of those children are still found,
     * rather than either wrongly declaring the whole target "gone" or being unable to finish it.
     *
     * Round 7b: a whole-stack target's membership is REVALIDATED against the container's LIVE
     * physical children (InventorySnapshot.physicalChildrenOf()) in the very same poll that decides
     * whether to send the drop - the recorded owned child set was only proven complete at
     * ownership-calculation time, and an extra pre-existing/unowned child could have merged into
     * that same container since. The whole-container message is only ever sent when the live
     * physical set is non-empty and entirely contained in the recorded owned set; otherwise the
     * still-resolvable recorded children are downgraded to individual per-child targets and every
     * unowned/extra child is left completely untouched.
     *
     * A loose item (owned.looseIds) is dropped via NUtils.drop() - the established whole-slot
     * ground-drop pattern already used by Dropper.java/FreeInventory2.java/NWItem.autoDrop()'s
     * ALWAYS branch for a single top-level item.
     *
     * A specific child merged into a mixed/pre-existing stack (owned.partialChildParents) is
     * dropped via that exact child GItem's own `wdgmsg("drop", Coord.z, 1)` - the same per-child
     * protocol NWItem.autoDrop()'s quality-threshold branch already uses, and (per the class doc
     * above) the same message shape a real CTRL-click sends - never by touching the stack container
     * itself, which could be pre-existing.
     *
     * Round 7b: for both of the two individual-unit cases above, which PROTOCOL to actually send
     * (top-level ground-drop vs. per-child stack-drop) is decided from the candidate's CURRENT live
     * topology (`InventorySnapshot.findTopLevel()` on its id, checked fresh at send time), never
     * from which bucket (`looseIds` vs. `partialChildParents`) its id was originally recorded under
     * - a unit can move between loose and nested since ownership was computed (see
     * InventorySnapshot's own re-parenting doc), and sending the wrong message shape for its CURRENT
     * form would be incorrect regardless of where it started.
     *
     * Sends at most one drop message per HARVEST_DROP_INTERVAL_MS (one whole-stack container drop
     * counts as a single message, same rate-limit slot as any other single target), bounded by one
     * overall HARVEST_DROP_DEADLINE_MS deadline. Mutates `targets` in place: whatever remains
     * un-confirmed when this returns is exactly what's still safe to retry later (see
     * ensureSpaceFor()'s call site) - it never falsely empties a target just because its original
     * parent/container stopped resolving.
     */
    private boolean dropHarvestWidgets(NGameUI gui, OwnedTargets targets) throws InterruptedException {
        if (targets.isEmpty())
            return true;
        NInventory inv = gui.getInventory();
        long deadline = System.currentTimeMillis() + HARVEST_DROP_DEADLINE_MS;
        long[] lastDrop = {0};
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                WItem dropCandidate = null;
                int dropCandidateId = -1;
                boolean candidateIsWholeStackContainer = false;

                // Whole-new-stack containers: confirm via their recorded child ids (identity-based,
                // never just "is the container's own wdgid still findable"), since the container
                // can legitimately stop resolving before every child is actually gone (e.g. drop
                // confirmed server-side but the client's own container widget already recycled).
                Iterator<Map.Entry<Integer, Set<Integer>>> wsIt = targets.wholeStacks.entrySet().iterator();
                while (wsIt.hasNext()) {
                    Map.Entry<Integer, Set<Integer>> e = wsIt.next();
                    int containerId = e.getKey();
                    Set<Integer> recordedChildren = e.getValue();
                    boolean anyRecordedChildStillPresent = false;
                    for (int childId : recordedChildren) {
                        if (InventorySnapshot.findAny(inv, childId) != null) {
                            anyRecordedChildStillPresent = true;
                            break;
                        }
                    }
                    if (!anyRecordedChildStillPresent) {
                        wsIt.remove();
                        continue;
                    }
                    if (dropCandidate != null)
                        continue; // already have this poll's candidate - revisit next poll

                    // Round 7b: revalidate whole-stack membership fresh, in THIS SAME poll, right
                    // before considering the drop - the recorded child set was only proven complete
                    // at ownership-calculation time, and an extra (pre-existing/unowned) child could
                    // have merged in since. Only safe to send the one-message whole-stack drop when
                    // the container's LIVE physical child set is non-empty and every one of those
                    // live children is among the recorded owned set - never the reverse (extra
                    // recorded children missing live is fine, that's just partial pickup/collapse).
                    Set<Integer> livePhysical = InventorySnapshot.physicalChildrenOf(inv, containerId);
                    if (livePhysical != null && !livePhysical.isEmpty() && recordedChildren.containsAll(livePhysical)) {
                        WItem container = InventorySnapshot.findTopLevel(inv, containerId);
                        if (container != null) {
                            dropCandidate = container;
                            candidateIsWholeStackContainer = true;
                            continue;
                        }
                    }
                    // Not safe to whole-drop this poll (an extra/unowned child appeared, the
                    // container no longer resolves as a stack, or it's gone) - downgrade whichever
                    // RECORDED (owned) children are still resolvable to individual cleanup, leaving
                    // every unowned/extra child completely untouched, and stop tracking this as a
                    // whole-stack target.
                    for (int childId : recordedChildren) {
                        if (InventorySnapshot.findAny(inv, childId) != null)
                            targets.partialChildParents.put(childId, containerId);
                    }
                    wsIt.remove();
                }

                // Loose items and specific merged-stack children: identity-resolved individually.
                // Round 7b: which drop PROTOCOL to send is decided below from the candidate's
                // CURRENT live topology (top-level vs. nested), never from which bucket its id
                // originally came from - a unit may have moved since ownership was recorded (this
                // also covers children just downgraded above, and any looseIds/partialChildParents
                // unit that got re-parented in the meantime).
                Iterator<Integer> looseIt = targets.looseIds.iterator();
                while (looseIt.hasNext()) {
                    int id = looseIt.next();
                    WItem w = InventorySnapshot.findAny(inv, id);
                    if (w == null) {
                        looseIt.remove();
                        continue;
                    }
                    if (dropCandidate == null) {
                        dropCandidate = w;
                        dropCandidateId = id;
                        candidateIsWholeStackContainer = false;
                    }
                }
                Iterator<Integer> childIt = targets.partialChildParents.keySet().iterator();
                while (childIt.hasNext()) {
                    int id = childIt.next();
                    WItem w = InventorySnapshot.findAny(inv, id);
                    if (w == null) {
                        childIt.remove();
                        continue;
                    }
                    if (dropCandidate == null) {
                        dropCandidate = w;
                        dropCandidateId = id;
                        candidateIsWholeStackContainer = false;
                    }
                }

                if (targets.isEmpty())
                    return true;
                long now = System.currentTimeMillis();
                if (now >= deadline)
                    return true;
                if (dropCandidate != null && now - lastDrop[0] >= HARVEST_DROP_INTERVAL_MS) {
                    if (candidateIsWholeStackContainer)
                        // Official CTRL-click "drop" protocol (haven.WItem.mousedown():
                        // item.wdgmsg("drop", ev.c, n)) applied to the container's own GItem - one
                        // message drops the whole owned stack, never iterated per child.
                        dropCandidate.item.wdgmsg("drop", Coord.z, 1);
                    else if (InventorySnapshot.findTopLevel(inv, dropCandidateId) != null)
                        // Currently a top-level loose item (regardless of which bucket it came
                        // from) - the established whole-slot ground-drop pattern.
                        NUtils.drop(dropCandidate);
                    else
                        // Currently nested inside a stack - same per-child protocol NWItem.
                        // autoDrop() uses for a single stack unit.
                        dropCandidate.item.wdgmsg("drop", Coord.z, 1);
                    lastDrop[0] = now;
                }
                return false;
            }
        });
        return targets.isEmpty();
    }

    private String petalNames(NFlowerMenu fm) {
        StringBuilder sb = new StringBuilder();
        for (NFlowerMenu.NPetal petal : fm.nopts) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(petal.name);
        }
        return sb.toString();
    }

    /**
     * Chat has a limited scrollback (older lines get silently dropped, confirmed by the player
     * losing the start of a run's log mid-session), so debug output also goes to a plain text
     * file that keeps the whole run regardless of chat history length.
     */
    private void openDebugLog() {
        try {
            String path = NUtils.getDataFile("lpassistant_debug.log");
            debugLog = new BufferedWriter(new FileWriter(path, true));
            debugLog.write("=== LP Assistant run started " + new Date() + " ===");
            debugLog.newLine();
            debugLog.flush();
            NUtils.getGameUI().msg("[LP] Debug log: " + path);
        } catch (IOException e) {
            debugLog = null;
        }
    }

    private void closeDebugLog() {
        if (debugLog != null) {
            try {
                debugLog.close();
            } catch (IOException ignored) {
            }
            debugLog = null;
        }
    }

    private void log(String msg) {
        if (prop == null || !prop.debug)
            return;
        NUtils.getGameUI().msg("[LP] " + msg);
        if (debugLog != null) {
            try {
                debugLog.write("[" + logTimeFormat.format(new Date()) + "] " + msg);
                debugLog.newLine();
                debugLog.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private void report(int gobsCleared, int productsDiscovered) {
        StringBuilder sb = new StringBuilder();
        sb.append("LP Assistant bot: cleared ").append(gobsCleared)
                .append(" gob(s), confirmed ").append(productsDiscovered).append(" new discovery(ies).");
        int skippedCount = 0;
        for (Map<String, String> reasons : skipped.values())
            skippedCount += reasons.size();
        if (skippedCount > 0) {
            sb.append(" Skipped ").append(skippedCount).append(": ");
            boolean first = true;
            for (Map<String, String> reasons : skipped.values()) {
                for (Map.Entry<String, String> e : reasons.entrySet()) {
                    if (!first)
                        sb.append("; ");
                    sb.append(e.getKey()).append(" (").append(e.getValue()).append(")");
                    first = false;
                }
            }
        }
        NUtils.getGameUI().msg(sb.toString());
        log(sb.toString());
    }
}
