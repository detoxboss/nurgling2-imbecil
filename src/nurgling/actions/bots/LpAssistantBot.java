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
import nurgling.tasks.WaitCheckable;
import nurgling.tasks.WaitCollectState;
import nurgling.tasks.WaitFirstProgressCycle;
import nurgling.tasks.WaitLpProductDiscovered;
import nurgling.tasks.WaitPoseExclude;
import nurgling.tools.HarvestSpecs;
import nurgling.tools.LpActionMatcher;
import nurgling.tools.LpExplorer;
import nurgling.tools.NAlias;
import nurgling.widgets.bots.LpAssistant;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    // Item names the player already had before this run started - auto-triage only ever touches
    // a name NOT in this set, so it can never eat/study/drop something the player already owned,
    // even if a newly-gathered item happens to share a name with pre-existing inventory (that
    // stack is left alone entirely rather than risk touching the wrong units within it).
    private final Set<String> preexistingItemNames = new HashSet<>();
    // Largest footprint any product this bot gathers can need (boards: 1 wide x 4 tall; blocks: 2
    // wide x 1 tall) - a clear 2-wide x 4-tall block comfortably fits either orientation. Coord.x is
    // the height/y-count and Coord.y the width/x-count, matching every other grid-placement call
    // site's swapped (height, width) convention (see docs/inventory-grid-system.md).
    private static final Coord WORKING_SPACE = new Coord(4, 2);
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

        if (prop.debug)
            openDebugLog();
        try {
            clearHand(gui);
            for (WItem existing : gui.getInventory().getItems()) {
                String name = ((NGItem) existing.item).name();
                if (name != null)
                    preexistingItemNames.add(name);
            }
            log("Starting. " + preexistingItemNames.size() + " pre-existing item name(s) will never be auto-triaged.");

            if (!hasWorkingSpace(gui)) {
                String msg = "LP Assistant bot: not enough free inventory space to run (need a clear "
                        + WORKING_SPACE.y + "x" + WORKING_SPACE.x + " block). Free up space and restart.";
                NUtils.getGameUI().msg(msg);
                log(msg);
                return Results.ERROR("Inventory full");
            }

            int gobsCleared = 0;
            int productsDiscovered = 0;

            while (true) {
                if (!hasWorkingSpace(gui)) {
                    // Should stay clear on its own once auto-drop is keeping up (see StudyEatOrDrop),
                    // but this is the safety net for autoEatNew/autoDropNew both being off, or a
                    // gob giving more before the previous product's triage pass has caught up - fail
                    // loud here instead of silently jamming up on a full inventory deeper in the loop.
                    String msg = "LP Assistant bot: stopping, ran out of free inventory space ("
                            + WORKING_SPACE.y + "x" + WORKING_SPACE.x + " block no longer clear).";
                    NUtils.getGameUI().msg(msg);
                    log(msg);
                    break;
                }

                Gob target = pickNearestCandidate();
                if (target == null)
                    break;

                String gobResName = target.ngob != null ? target.ngob.name : null;
                log("Target: " + gobResName + " (id=" + target.id + ")");

                try {
                    productsDiscovered += processGob(gui, target, gobResName);
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
                    if (tool != null) {
                        if (!new Equip(new NAlias(tool)).run(gui).IsSuccess()) {
                            log("  " + product + ": tool not found, skipping");
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

                    // Board/Block share a single depleting "log HP" pool (cutting either
                    // subtracts from the same total, so leaving the log running could turn it
                    // entirely into whichever product we clicked first, wasting the other
                    // discovery's chance). Leaf and Stone are high-volume for a different reason:
                    // a tree can carry 25-100 leaves (picked a few at a time) and a bumling can
                    // give many stone chips - both were observed live to fill the whole inventory
                    // and hang the bot before ever reaching a natural stop. Every other resource
                    // gives a small fixed quantity (observed 1-6) before its flower option
                    // disappears on its own, so those are left to run to that natural stop instead
                    // of interrupting - simpler, and since no click happens mid-harvest it also
                    // keeps map.clickedGob valid for the whole thing (see WaitLpProductDiscovered's
                    // class doc for why that matters).
                    boolean highVolume = category == LpActionMatcher.Category.BOARD
                            || category == LpActionMatcher.Category.BLOCK
                            || category == LpActionMatcher.Category.LEAF
                            || category == LpActionMatcher.Category.STONE;

                    int expBaseline = WaitLpProductDiscovered.currentExp();
                    log("  " + product + ": selecting \"" + petal.name + "\"");
                    new SelectFlowerAction(petal.name, target).run(gui);
                    Gob player = NUtils.player();
                    if (player != null) {
                        NUtils.getUI().core.addTask(new WaitPoseExclude(player, "idle"));
                    }
                    logClickedGob("  " + product + ": clickedGob right after the harvest click");

                    if (highVolume) {
                        // Wait for the server's own hourglass/percentage indicator (haven.GameUI.
                        // prog - the same signal Forging/LightFire/Craft/TunnelingBot already poll
                        // for a timed action's completion) to finish its first cycle, instead of
                        // guessing a fixed delay - a genuine event, not a duration. Degrades safely
                        // if this particular action never shows one: the task has its own bounded
                        // timeout and returns anyway.
                        NUtils.getUI().core.addTask(new WaitFirstProgressCycle());
                        interruptActivity(gui, target);
                        logClickedGob("  " + product + ": clickedGob after interruptActivity");
                    } else if (player != null) {
                        // Waits until the node's pose returns to idle (nothing left of this
                        // product to give) or inventory runs out of space - same completion
                        // signal CollectBough/CollectBark/CollectLeaf already use via
                        // CollectFromGob, reused directly here instead of reinventing it.
                        NUtils.getUI().core.addTask(new WaitCollectState(target, new Coord(1, 1)));
                    }

                    WaitLpProductDiscovered discovered = new WaitLpProductDiscovered(gobResName, product, expBaseline, highVolume ? 5000 : 3000);
                    NUtils.getUI().core.addTask(discovered);
                    if (discovered.confirmed()) {
                        log("  " + product + ": discovery confirmed (via " + discovered.confirmedVia() + ")");
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
                    clearHand(gui);

                    if (prop.autoEatNew || prop.autoDropNew)
                        triageNewItems(gui);
        }
        return discoveredCount;
    }

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

    // Squares a WORKING_SPACE-sized block would cover (4x2=8) - used as a total-free-square
    // threshold instead of requiring that exact shape to be free in one contiguous block (see
    // hasWorkingSpace's own doc for why the contiguous check was wrong).
    private static final int WORKING_SQUARES = WORKING_SPACE.x * WORKING_SPACE.y;

    /**
     * True if there's roughly a WORKING_SPACE-sized block's worth of free inventory space -
     * enough room for whatever comes next.
     *
     * Originally required getNumberFreeCoord(WORKING_SPACE) > 0 - a literal contiguous 4x2
     * rectangle free somewhere in the grid. Confirmed live 2026-08 to false-positive "inventory
     * full" and abort the bot even when the player had several free squares' worth of total space
     * left (e.g. their own reported 2x4/5x4 empty block): once the bot's own gathered items (or
     * anything else in the inventory) fragment that region into non-rectangular free space, no
     * single 4x2 rectangle exists anymore even though plenty of individual free squares do -
     * and Haven's own item placement never actually needs one contiguous block, it fills whatever
     * free squares fit. A total-free-square count is the correct, non-false-positive proxy for
     * "is there room", matching how items actually get placed.
     */
    private boolean hasWorkingSpace(NGameUI gui) throws InterruptedException {
        return gui.getInventory().getFreeSpace() >= WORKING_SQUARES;
    }

    /** Stashes a cursor/hand-held item into a free inventory slot, or drops it if none exists. */
    private void clearHand(NGameUI gui) throws InterruptedException {
        WItem hand = gui.vhand;
        if (hand == null)
            return;
        // getFreeCoord() blocks (via NCore.addTask, infinite=true, no timeout) until the item's
        // sprite has loaded - normally near-instant, but observed live to hang the whole bot
        // forever when the server couldn't actually grant the pickup (inventory genuinely full)
        // and the resulting stuck cursor item's sprite never resolves. Only attempt it once the
        // sprite is already there; otherwise just drop, which doesn't need the sprite at all.
        if (hand.item.spr != null) {
            Coord pos = gui.getInventory().getFreeCoord(hand);
            if (pos != null) {
                gui.getInventory().dropOn(pos);
                log("  cleared hand item (stashed)");
                return;
            }
        }
        NUtils.drop(hand);
        log("  cleared hand item (dropped)");
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
     * Studies, eats or drops every current inventory item whose name wasn't present when the bot
     * started (see preexistingItemNames), so the bot's own gathering never fills up the inventory.
     * One StudyEatOrDrop call per DISTINCT new name, not per slot: its drop step already clears
     * every slot sharing that name in one action (see its own class doc), so a second call for
     * another slot of the same name would find nothing left to do. Safe to call this whole method
     * repeatedly: anything an earlier call already resolved is simply gone from getItems() by the
     * next call, and any remnant an earlier Eat left behind (e.g. an "eaten apple" core) is itself
     * a name not in preexistingItemNames, so it gets caught and resolved on a later pass too - no
     * separate remnant-tracking logic needed.
     */
    private void triageNewItems(NGameUI gui) throws InterruptedException {
        Set<String> handledThisPass = new HashSet<>();
        for (WItem w : gui.getInventory().getItems()) {
            String name = ((NGItem) w.item).name();
            if (name == null || preexistingItemNames.contains(name) || !handledThisPass.add(name))
                continue;
            log("  triage: " + name);
            new StudyEatOrDrop(w, prop.autoEatNew, prop.autoDropNew).run(gui);
        }
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
