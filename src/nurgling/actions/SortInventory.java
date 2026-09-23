package nurgling.actions;

import haven.*;
import haven.res.ui.stackinv.ItemStack;
import haven.res.ui.tt.stackn.Stack;
import nurgling.*;
import nurgling.sessions.BotExecutor;
import nurgling.tasks.*;
import nurgling.tools.StackSupporter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Sorts inventory items by name, resource name, and quality.
 * Moves all 1x1 items to fill empty slots from top-left, sorted alphabetically and by quality.
 */
public class SortInventory implements Action {
    
    public static final String[] EXCLUDE_WINDOWS = new String[]{
        "Character Sheet",
        "Study",
        "Chicken Coop",
        "Belt",
        "Pouch",
        "Purse",
        "Cauldron",
        "Finery Forge",
        "Fireplace",
        "Frame",
        "Herbalist Table",
        "Kiln",
        "Ore Smelter",
        "Smith's Smelter",
        "Oven",
        "Pane mold",
        "Rack",
        "Smoke shed",
        "Stack Furnace",
        "Steelbox",
        "Tub"
    };
    
    public static final Comparator<WItem> ITEM_COMPARATOR = (a, b) -> {
        // Both items must be NGItem
        if (!(a.item instanceof NGItem) || !(b.item instanceof NGItem)) {
            return 0;
        }

        NGItem itemA = (NGItem) a.item;
        NGItem itemB = (NGItem) b.item;

        // Compare by name first
        String nameA = itemA.name();
        String nameB = itemB.name();

        if (nameA == null) nameA = "";
        if (nameB == null) nameB = "";
        int nameCompare = nameA.compareTo(nameB);
        if (nameCompare != 0) return nameCompare;

        String resA = itemA.res.toString();
        String resB = itemB.res.toString();

        if (resA == null) resA = "";
        if (resB == null) resB = "";

        int resCompare = resA.compareTo(resB);
        if (resCompare != 0) return resCompare;

        // Then by quality (higher quality first)
        // Use stack quality if available, otherwise use item quality
        double qualA = getEffectiveQuality(itemA);
        double qualB = getEffectiveQuality(itemB);
        if (Double.compare(qualB, qualA) != 0) return Double.compare(qualB, qualA);

        int cA;
        GItem.Amount CntA = itemA.getInfo(GItem.Amount.class);
        if (CntA != null && CntA.itemnum() > 0) {
            cA = CntA.itemnum();
        } else {
            cA = 0;
        }

        int cB;
        GItem.Amount CntB = itemB.getInfo(GItem.Amount.class);
        if (CntB != null && CntB.itemnum() > 0) {
            cB = CntB.itemnum();
        } else {
            cB = 0;
        }

        if (cB != cA) return (cB - cA);

        return 0;
    };


    /**
     * Get effective quality for an item, considering stack quality for stacked items
     */
    private static double getEffectiveQuality(NGItem item) {
        // First try to get stack quality (for stacked items)
        Stack stackInfo = item.getInfo(Stack.class);
        if (stackInfo != null && stackInfo.quality > 0) {
            return stackInfo.quality;
        }
        // Fall back to individual item quality
        if (item.quality != null && item.quality > 0) {
            return item.quality;
        }
        return -1; // No quality available
    }
    
    private final NInventory inventory;
    private final boolean deepSort;
    private final boolean consolidate;
    private volatile boolean cancelled = false;
    private static volatile SortInventory current;
    private static final Object lock = new Object();

    public SortInventory(NInventory inventory) {
        this(inventory, false);
    }

    public SortInventory(NInventory inventory, boolean deepSort) {
        this(inventory, deepSort, false);
    }

    public SortInventory(NInventory inventory, boolean deepSort, boolean consolidate) {
        this.inventory = inventory;
        this.deepSort = deepSort;
        this.consolidate = consolidate;
    }
    
    /**
     * Check if cursor is default (not holding anything or special cursor)
     */
    private boolean isDefaultCursor(NGameUI gui) {
        return gui.vhand == null;
    }
    
    /**
     * Get item size in inventory cells
     */
    private Coord getItemSize(WItem item) {
        if (item.item.spr != null) {
            return item.item.spr.sz().div(UI.scale(32));
        }
        return new Coord(1, 1);
    }
    
    /**
     * Get item position in inventory grid
     */
    private Coord getItemPos(WItem item) {
        return item.c.sub(1, 1).div(Inventory.sqsz);
    }
    
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Check for default cursor
        if (!isDefaultCursor(gui)) {
            gui.error("Need default cursor to sort inventory!");
            return Results.FAIL();
        }
        
        // Cancel any previous sort operation
        cancel();
        synchronized (lock) {
            current = this;
        }
        
        try {
            doSort(gui);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (RuntimeException re) {
            // Surface it in-game instead of dying silently to the file log (BotExecutor's
            // thread wrapper only catches InterruptedException) — otherwise "pressed the
            // button, nothing happened, no message at all" is indistinguishable from a crash.
            gui.error("Sort failed: " + re);
            throw re;
        } finally {
            synchronized (lock) {
                if (current == this) {
                    current = null;
                }
            }
        }
        
        if (!cancelled) {
            gui.msg(consolidate ? "Stacks maxed and sorted!" : (deepSort ? "Stacks sorted!" : "Inventory sorted!"));
        }
        
        return cancelled ? Results.FAIL() : Results.SUCCESS();
    }
    
    private void doSort(NGameUI gui) throws InterruptedException {
        // Zeroth pass: merge partial stacks/loose items of the same name together up to
        // their max stack size, before the position sort compacts the freed-up slots.
        if (consolidate) {
            consolidateStacks(gui);
            if (cancelled) return;
        }

        // Build grid of blocked cells (including sqmask and multi-cell items)
        boolean[][] grid = new boolean[inventory.isz.x][inventory.isz.y];
        
        // Apply sqmask if present
        boolean[] mask = inventory.sqmask;
        if (mask != null) {
            int mo = 0;
            for (int y = 0; y < inventory.isz.y; y++) {
                for (int x = 0; x < inventory.isz.x; x++) {
                    grid[x][y] = mask[mo++];
                }
            }
        }
        
        // Collect all items and mark multi-cell items as blocked
        List<WItem> items = new ArrayList<>();
        for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
            if (cancelled) return;
            
            if (wdg.visible && wdg instanceof WItem) {
                WItem wItem = (WItem) wdg;
                Coord sz = getItemSize(wItem);
                Coord loc = getItemPos(wItem);
                
                if (sz.x * sz.y == 1) {
                    // 1x1 items can be sorted
                    items.add(wItem);
                } else {
                    // Multi-cell items stay in place, mark cells as blocked
                    for (int x = 0; x < sz.x; x++) {
                        for (int y = 0; y < sz.y; y++) {
                            int gx = loc.x + x;
                            int gy = loc.y + y;
                            if (gx >= 0 && gx < inventory.isz.x && gy >= 0 && gy < inventory.isz.y) {
                                grid[gx][gy] = true;
                            }
                        }
                    }
                }
            }
        }
        
        if (items.isEmpty()) {
            return;
        }
        


        // Sort items and create position mapping
        List<Object[]> sorted = items.stream()
                .filter(witem -> getItemSize(witem).x * getItemSize(witem).y == 1)
                .sorted(Comparator.comparing(witem -> getItemPos(witem), Comparator.reverseOrder()))
                .sorted(ITEM_COMPARATOR)
                .map(witem -> new Object[]{
                        witem,
                        getItemPos(witem),  // current pos
                        new Coord(0, 0)     // target pos (will be filled)
                })
                .collect(Collectors.toList());

        // Assign target positions
        int cur_x = -1, cur_y = 0;
        for (Object[] a : sorted) {
            if (cancelled) return;
            
            while (true) {
                cur_x += 1;
                if (cur_x == inventory.isz.x) {
                    cur_x = 0;
                    cur_y += 1;
                    if (cur_y == inventory.isz.y) break;
                }
                if (!grid[cur_x][cur_y]) {
                    a[2] = new Coord(cur_x, cur_y);
                    break;
                }
            }
            if (cur_y == inventory.isz.y) break;
        }
        
        // Move items to their target positions
        for (Object[] a : sorted) {
            if (cancelled) return;
            
            Coord currentPos = (Coord) a[1];
            Coord targetPos = (Coord) a[2];
            
            // Skip if already in right place
            if (currentPos.equals(targetPos)) {
                continue;
            }
            
            WItem wItem = (WItem) a[0];
            
            // Check if item is still valid
            if (wItem.item == null) {
                continue;
            }
            
            // Take item to hand
            NUtils.takeItemToHand(wItem);
            
            Object[] handu = a;
            while (handu != null) {
                if (cancelled) {
                    // Drop item back if cancelled
                    if (gui.vhand != null) {
                        NUtils.dropToInv(inventory);
                    }
                    return;
                }
                
                Coord dropPos = (Coord) handu[2];
                
                // Drop item at target position
                inventory.wdgmsg("drop", dropPos);
                
                // Find item that was at the target position (it's now in hand)
                Object[] b = null;
                for (Object[] x : sorted) {
                    if (((Coord) x[1]).equals(dropPos)) {
                        b = x;
                        break;
                    }
                }
                
                // Update current position
                handu[1] = handu[2];
                handu = b;
            }
            
            // Wait for hand to be free after chain is complete
            if (gui.vhand != null) {
                NUtils.getUI().core.addTask(new WaitFreeHand());
            }
        }

        // Second pass: sort individual items across same-type stacks by quality
        if (!cancelled && (deepSort || consolidate)) {
            sortWithinStacks(gui);
        }
    }
    
    /**
     * Cancel the current sort operation
     */
    public static void cancel() {
        synchronized (lock) {
            if (current != null) {
                current.cancelled = true;
                current = null;
            }
        }
    }
    
    /**
     * Check if a sort operation is currently running
     */
    public static boolean isRunning() {
        synchronized (lock) {
            return current != null;
        }
    }
    
    /**
     * Sort a specific inventory (positional sort only)
     */
    public static void sort(NInventory inv) {
        if (!isValidInventory(inv)) {
            return;
        }

        NGameUI gui = NUtils.getGameUI();
        if (gui == null) return;

        if (gui.vhand != null) {
            gui.error("Need default cursor to sort inventory!");
            return;
        }

        BotExecutor.runAsync("InventorySorter", new SortInventory(inv));
    }

    /**
     * Deep sort: positional sort + redistribute items across same-type stacks
     * so highest quality items are concentrated in the first stacks.
     */
    public static void sortDeep(NInventory inv) {
        if (!isValidInventory(inv)) {
            return;
        }

        NGameUI gui = NUtils.getGameUI();
        if (gui == null) return;

        if (gui.vhand != null) {
            gui.error("Need default cursor to sort inventory!");
            return;
        }

        BotExecutor.runAsync("StackSorter", new SortInventory(inv, true));
    }

    /**
     * Stack to max: merge partial stacks/loose items of the same name up to their max
     * stack size (fewest possible slots), then positionally sort, then redistribute
     * quality within same-type stacks so the highest quality is concentrated first.
     */
    public static void sortAndStack(NInventory inv) {
        if (!isValidInventory(inv)) {
            return;
        }

        NGameUI gui = NUtils.getGameUI();
        if (gui == null) return;

        if (gui.vhand != null) {
            gui.error("Need default cursor to sort inventory!");
            return;
        }

        BotExecutor.runAsync("StackConsolidator", new SortInventory(inv, true, true));
    }

    /**
     * Check if inventory is valid for sorting (not in excluded windows)
     */
    private static boolean isValidInventory(NInventory inv) {
        if (inv == null) return false;

        Window wnd = inv.getparent(Window.class);
        if (wnd != null) {
            String caption = wnd.cap;
            if (caption != null) {
                for (String excluded : EXCLUDE_WINDOWS) {
                    if (caption.contains(excluded)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // =========================================================================
    // Stack Consolidation (Zeroth Pass) — merge partial stacks up to max size
    // =========================================================================

    /**
     * Returns how many physical units occupy this top-level slot: a stack's child
     * count, or 1 for a single loose item.
     */
    private static int slotUnitCount(WItem w) {
        if (w.item.contents instanceof ItemStack) {
            return ((ItemStack) w.item.contents).wmap.size();
        }
        return 1;
    }

    /**
     * For every item name present in more slots than its max stack size requires,
     * greedily drains the smallest slot into the fullest non-full slot, one unit at
     * a time, until the item occupies the minimum possible number of slots. Which
     * physical units end up where is irrelevant here — {@link #sortWithinStacks}
     * concentrates quality afterward.
     *
     * The max stack size used is {@code max(StackSupporter.getFullStackSize(name),
     * largest slot of that name already observed in this inventory)}, not the
     * table alone: that table is a hand-maintained, known-incomplete heuristic
     * (docs/inventory-grid-system.md §3) — if the inventory already visibly holds a
     * 4-stack of something the table has never heard of (returns its 1-unit
     * default), trusting the table alone would silently skip an item this exact
     * feature exists to consolidate.
     */
    private void consolidateStacks(NGameUI gui) throws InterruptedException {
        Map<String, Integer> maxByName = new HashMap<>();
        for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
            if (cancelled) return;
            if (!(wdg instanceof WItem)) continue;
            WItem w = (WItem) wdg;
            if (!(w.item instanceof NGItem)) continue;
            String name = ((NGItem) w.item).name();
            if (name == null) continue;

            int candidate = Math.max(StackSupporter.getFullStackSize(name), slotUnitCount(w));
            if (candidate > 1) {
                maxByName.merge(name, candidate, Math::max);
            }
        }

        if (maxByName.isEmpty()) {
            gui.msg("Stack to Max: nothing stackable found to consolidate.");
            return;
        }

        for (Map.Entry<String, Integer> e : maxByName.entrySet()) {
            if (cancelled) return;
            consolidateOne(gui, e.getKey(), e.getValue());
        }
    }

    private void consolidateOne(NGameUI gui, String name, int max) throws InterruptedException {
        if (max <= 1) return;

        boolean announced = false;
        int guard = 0;
        while (!cancelled) {
            List<Coord> slots = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
                if (!(wdg instanceof WItem)) continue;
                WItem w = (WItem) wdg;
                if (!(w.item instanceof NGItem)) continue;
                if (!name.equals(((NGItem) w.item).name())) continue;
                slots.add(getItemPos(w));
                counts.add(slotUnitCount(w));
            }
            if (slots.size() < 2) return;

            int total = 0;
            for (int c : counts) total += c;
            int targetSlots = (total + max - 1) / max;
            if (!announced) {
                announced = true;
                if (slots.size() > targetSlots) {
                    gui.msg("Stacking " + name + ": " + total + " units in " + slots.size()
                            + " slots (max " + max + "/stack) -> target " + targetSlots);
                }
            }
            if (slots.size() <= targetSlots) return; // already the minimum possible slot count

            // Donor = slot with the fewest units; receiver = the fullest slot that still has room.
            int donorIdx = 0;
            for (int i = 1; i < slots.size(); i++) {
                if (counts.get(i) < counts.get(donorIdx)) donorIdx = i;
            }
            int receiverIdx = -1;
            for (int i = 0; i < slots.size(); i++) {
                if (i == donorIdx) continue;
                if (counts.get(i) < max && (receiverIdx < 0 || counts.get(i) > counts.get(receiverIdx))) {
                    receiverIdx = i;
                }
            }
            if (receiverIdx < 0) return; // shouldn't happen given slots.size() > targetSlots, but be safe

            if (gui.vhand != null) {
                gui.error("Stack consolidation failed: hand not empty. Drop held item and retry.");
                return;
            }

            Coord donorPos = slots.get(donorIdx);
            Coord receiverPos = slots.get(receiverIdx);

            takeItemFromSlot(donorPos, (Float) null);
            if (gui.vhand == null) {
                // Nothing was actually picked up (slot changed under us) — rescan and retry.
                if (++guard > 5000) {
                    gui.msg("Stack consolidation: too many steps for " + name + ", aborting");
                    return;
                }
                continue;
            }
            addItemToSlot(receiverPos);

            if (++guard > 5000) {
                gui.msg("Stack consolidation: too many steps for " + name + ", aborting");
                return;
            }
        }
    }

    // =========================================================================
    // Within-Stack Sorting (Second Pass) — Cycle-Chase Algorithm
    // =========================================================================

    private static class BufferLocation {
        final NInventory inv;
        final Coord coord;

        BufferLocation(NInventory inv, Coord coord) {
            this.inv = inv;
            this.coord = coord;
        }
    }

    /**
     * Sorts individual items across same-type stacks so that the highest
     * quality items are concentrated in the first stacks (descending).
     * Uses a cycle-chase permutation sort with a single-slot buffer.
     */
    private void sortWithinStacks(NGameUI gui) throws InterruptedException {
        // Wait a moment for the first-pass to fully settle in the UI
        NUtils.getUI().core.addTask(new WaitTicks(3));

        // Find item names that have at least one stack
        Set<String> namesWithStacks = new HashSet<>();
        for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
            if (cancelled) return;
            if (!(wdg instanceof WItem)) continue;
            WItem w = (WItem) wdg;
            if (!(w.item instanceof NGItem)) continue;
            NGItem ng = (NGItem) w.item;
            if (ng.name() != null && w.item.contents instanceof ItemStack) {
                namesWithStacks.add(ng.name());
            }
        }
        if (namesWithStacks.isEmpty()) return;

        for (String itemName : namesWithStacks) {
            if (cancelled) return;

            // Determine item size from any stack of this type
            Coord itemSize = getStackedItemSize(itemName);
            if (itemSize == null) continue;

            // Find buffer slot matching this item's size
            BufferLocation buffer = findBuffer(gui, itemSize);
            if (buffer == null) {
                gui.msg("Need 1 free " + itemSize.x + "x" + itemSize.y
                        + " slot to sort " + itemName + " stacks");
                continue;
            }
            performCycleSort(gui, itemName, buffer);
        }
    }

    private List<List<Float>> computeTargetState(List<Float> sortedQualities, List<Integer> slotSizes) {
        List<List<Float>> target = new ArrayList<>();
        int idx = 0;
        for (int size : slotSizes) {
            List<Float> slot = new ArrayList<>();
            for (int j = 0; j < size && idx < sortedQualities.size(); j++, idx++) {
                slot.add(sortedQualities.get(idx));
            }
            target.add(slot);
        }
        return target;
    }

    private boolean multisetEquals(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) return false;
        List<Float> bCopy = new ArrayList<>(b);
        for (float v : a) {
            int idx = findFloatIdx(bCopy, v);
            if (idx < 0) return false;
            bCopy.remove(idx);
        }
        return true;
    }

    /**
     * Scans the inventory fresh for all slots of the given item type.
     * Returns a list of (position, qualities) pairs, sorted by position.
     * Only includes slots that have at least one item with non-null quality.
     */
    private List<Object[]> freshScan(String itemName) {
        List<Object[]> slots = new ArrayList<>();
        for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
            if (!(wdg instanceof WItem)) continue;
            WItem w = (WItem) wdg;
            if (!(w.item instanceof NGItem)) continue;
            NGItem ng = (NGItem) w.item;
            if (!itemName.equals(ng.name())) continue;

            Coord pos = getItemPos(w);
            List<Float> quals = getSlotQualities(pos);
            if (!quals.isEmpty()) {
                slots.add(new Object[]{pos, quals});
            }
        }
        // Sort by position (top-to-bottom, left-to-right) for stable ordering
        slots.sort((a, b) -> {
            Coord pa = (Coord) a[0], pb = (Coord) b[0];
            return pa.y != pb.y ? Integer.compare(pa.y, pb.y) : Integer.compare(pa.x, pb.x);
        });
        return slots;
    }

    /**
     * Cycle-chase sorting: repeatedly find misplaced items and resolve
     * permutation cycles using a single-slot buffer.
     *
     * Each cycle starts with a FRESH inventory scan so positions, sizes,
     * and qualities are never stale.
     */
    private void performCycleSort(NGameUI gui, String itemName,
            BufferLocation buffer) throws InterruptedException {

        int cycleNum = 0;
        boolean announced = false;
        while (!cancelled) {
            cycleNum++;

            // === Fresh scan each cycle ===
            List<Object[]> scan = freshScan(itemName);
            if (scan.size() < 2) break;

            List<Coord> positions = new ArrayList<>();
            List<List<Float>> current = new ArrayList<>();
            List<Integer> slotSizes = new ArrayList<>();
            List<Float> allQualities = new ArrayList<>();

            for (Object[] entry : scan) {
                Coord pos = (Coord) entry[0];
                @SuppressWarnings("unchecked")
                List<Float> quals = (List<Float>) entry[1];
                positions.add(pos);
                current.add(quals);
                slotSizes.add(quals.size());
                allQualities.addAll(quals);
            }

            if (allQualities.size() < 2) break;

            // Compute target: all qualities sorted descending, distributed by slot sizes
            List<Float> sortedQualities = new ArrayList<>(allQualities);
            sortedQualities.sort(Collections.reverseOrder());
            List<List<Float>> target = computeTargetState(sortedQualities, slotSizes);

            // Find a misplacement
            int fromSlot = -1;
            float excessQ = 0;
            int toSlot = -1;

            outer:
            for (int s = 0; s < current.size(); s++) {
                List<Float> excess = multisetDiff(current.get(s), target.get(s));
                for (float q : excess) {
                    for (int t = 0; t < target.size(); t++) {
                        if (t == s) continue;
                        List<Float> deficit = multisetDiff(target.get(t), current.get(t));
                        if (containsFloat(deficit, q)) {
                            fromSlot = s;
                            excessQ = q;
                            toSlot = t;
                            break outer;
                        }
                    }
                }
            }

            if (fromSlot < 0) {
                break;
            }

            // Safety: hand must be empty before starting a cycle
            if (gui.vhand != null) {
                gui.error("Stack sort failed: hand not empty. Drop held item and retry.");
                break;
            }

            if (!announced) {
                gui.msg("Sorting within " + itemName + " stacks...");
                announced = true;
            }

            // --- Execute one cycle ---

            // Step 1: take excess item → buffer
            takeItemFromSlot(positions.get(fromSlot), excessQ);
            // Verify we actually picked something up
            if (gui.vhand == null) {
                continue;
            }
            dropToBuffer(buffer);
            int bufferTarget = toSlot;
            int vacancy = fromSlot;

            // Step 2: chain — fill each vacancy from another slot
            // Use the target computed at cycle start (stable within this cycle)
            int chainStep = 0;
            while (bufferTarget != vacancy && !cancelled) {
                chainStep++;

                // Re-scan only the current state, keep target fixed
                List<List<Float>> chainCurrent = new ArrayList<>();
                for (Coord pos : positions) {
                    chainCurrent.add(getSlotQualities(pos));
                }

                List<Float> vacancyDeficit = multisetDiff(target.get(vacancy), chainCurrent.get(vacancy));

                float fillerQ = 0;
                int fillerSlot = -1;
                for (float needed : vacancyDeficit) {
                    for (int s = 0; s < chainCurrent.size(); s++) {
                        if (s == vacancy) continue;
                        List<Float> excess = multisetDiff(chainCurrent.get(s), target.get(s));
                        if (containsFloat(excess, needed)) {
                            fillerQ = needed;
                            fillerSlot = s;
                            break;
                        }
                    }
                    if (fillerSlot >= 0) break;
                }

                if (fillerSlot < 0) {
                    break;
                }

                takeItemFromSlot(positions.get(fillerSlot), fillerQ);
                if (gui.vhand == null) {
                    break;
                }
                addItemToSlot(positions.get(vacancy));
                vacancy = fillerSlot;
            }

            // Step 3: close cycle — buffer item → vacancy
            if (!cancelled) {
                retrieveFromBuffer(buffer);
                addItemToSlot(positions.get(vacancy));
            } else {
                if (gui.vhand == null) {
                    retrieveFromBuffer(buffer);
                }
                if (gui.vhand != null) {
                    NUtils.dropToInv(inventory);
                    NUtils.addTask(new WaitFreeHand());
                }
                return;
            }

            if (cycleNum > 500) {
                gui.msg("Stack sort: too many cycles, aborting");
                break;
            }
        }
    }

    // --- Buffer operations ---

    private BufferLocation findBuffer(NGameUI gui, Coord itemSize) throws InterruptedException {
        // Prefer a free area in the inventory being sorted
        Coord freeCoord = inventory.findFreeCoord(itemSize);
        if (freeCoord != null) {
            return new BufferLocation(inventory, freeCoord);
        }

        // Fall back to player inventory (when sorting a container)
        if (inventory != gui.maininv) {
            NInventory playerInv = gui.getInventory();
            if (playerInv != null) {
                Coord playerFree = playerInv.findFreeCoord(itemSize);
                if (playerFree != null) {
                    return new BufferLocation(playerInv, playerFree);
                }
            }
        }

        return null;
    }

    private void dropToBuffer(BufferLocation buffer) throws InterruptedException {
        if (NUtils.getGameUI().vhand == null) return;
        buffer.inv.wdgmsg("drop", buffer.coord);
        NUtils.addTask(new WaitFreeHand());
    }

    private void retrieveFromBuffer(BufferLocation buffer) throws InterruptedException {
        WItem item = findSlotItemAtPos(buffer.inv, buffer.coord);
        if (item != null) {
            NUtils.takeItemToHand(item);
        }
    }

    // --- Slot operations ---

    /**
     * Takes a specific item (identified by quality) from a slot to hand.
     * Handles stacks (2+), stacks dissolving (2→1), and single items.
     */
    private void takeItemFromSlot(Coord pos, float quality) throws InterruptedException {
        takeItemFromSlot(pos, (Float) quality);
    }

    /**
     * Takes a unit from a slot to hand. A {@code null} quality takes whichever unit
     * is first/only in the slot (used by stack consolidation, which doesn't care which
     * physical unit moves); a non-null quality takes that specific unit (used by the
     * within-stack quality sort, which does).
     * Handles stacks (2+), stacks dissolving (2→1), and single items.
     */
    private void takeItemFromSlot(Coord pos, Float quality) throws InterruptedException {
        WItem slotItem = findSlotItemAtPos(pos);
        if (slotItem == null) return;

        if (slotItem.item.contents instanceof ItemStack) {
            ItemStack stack = (ItemStack) slotItem.item.contents;
            int originalSize = stack.wmap.size();

            WItem target = null;
            if (quality == null) {
                if (!stack.order.isEmpty()) {
                    target = stack.wmap.get(stack.order.get(0));
                }
            } else {
                for (GItem gi : stack.order) {
                    if (gi instanceof NGItem) {
                        NGItem ng = (NGItem) gi;
                        if (ng.quality != null && Math.abs(ng.quality - quality) < 0.001f) {
                            target = stack.wmap.get(gi);
                            break;
                        }
                    }
                }
            }
            if (target == null) return;

            NUtils.takeItemToHand(target);

            if (originalSize <= 2) {
                if (stack.parent != null) {
                    NUtils.addTask(new ISRemovedLoftar(
                            ((GItem.ContentsWindow) stack.parent).cont.wdgid(),
                            stack, originalSize));
                }
            } else {
                NUtils.addTask(new StackSizeChanged(stack, originalSize));
            }
        } else {
            // Safety: if the item has contents (some container/stack we don't recognize),
            // never take the whole thing — that would pick up an entire stack
            if (slotItem.item.contents != null) {
                return;
            }
            // Verify quality matches before taking a single item (skipped when quality is null)
            if (quality != null && slotItem.item instanceof NGItem) {
                Float itemQ = ((NGItem) slotItem.item).quality;
                if (itemQ == null || Math.abs(itemQ - quality) >= 0.001f) {
                    return;
                }
            }
            int wdgid = slotItem.item.wdgid();
            NUtils.takeItemToHand(slotItem);
            NUtils.addTask(new ISRemoved(wdgid));
        }
    }

    /**
     * Adds the hand item to a slot. Handles empty slots, single items
     * (creates a stack), and existing stacks (grows the stack).
     */
    private void addItemToSlot(Coord pos) throws InterruptedException {
        if (NUtils.getGameUI().vhand == null) return;

        WItem slotItem = findSlotItemAtPos(pos);

        if (slotItem == null) {
            inventory.wdgmsg("drop", pos);
            NUtils.addTask(new WaitFreeHand());
        } else if (slotItem.item.contents instanceof ItemStack) {
            ItemStack stack = (ItemStack) slotItem.item.contents;
            int oldSize = stack.wmap.size();
            NUtils.itemact(slotItem);
            NUtils.addTask(new WaitFreeHand());
            NUtils.addTask(new StackSizeChanged(stack, oldSize));
        } else {
            NUtils.itemact(slotItem);
            NUtils.addTask(new WaitFreeHand());
        }
    }

    // --- Scan and lookup helpers ---

    /**
     * Returns the inventory cell size of items for the given item name
     * by finding any stack or single item of that name in the inventory.
     */
    private Coord getStackedItemSize(String itemName) {
        for (Widget wdg = inventory.lchild; wdg != null; wdg = wdg.prev) {
            if (!(wdg instanceof WItem)) continue;
            WItem w = (WItem) wdg;
            if (!(w.item instanceof NGItem)) continue;
            if (itemName.equals(((NGItem) w.item).name())) {
                return getItemSize(w);
            }
        }
        return null;
    }

    private List<Float> getSlotQualities(Coord pos) {
        WItem slotItem = findSlotItemAtPos(pos);
        if (slotItem == null) return new ArrayList<>();

        List<Float> qualities = new ArrayList<>();
        if (slotItem.item.contents instanceof ItemStack) {
            ItemStack stack = (ItemStack) slotItem.item.contents;
            for (GItem gi : stack.order) {
                if (gi instanceof NGItem && ((NGItem) gi).quality != null) {
                    qualities.add(((NGItem) gi).quality);
                }
            }
        } else if (slotItem.item instanceof NGItem) {
            NGItem ng = (NGItem) slotItem.item;
            if (ng.quality != null) {
                qualities.add(ng.quality);
            }
        }
        return qualities;
    }

    private WItem findSlotItemAtPos(Coord gridPos) {
        return findSlotItemAtPos(inventory, gridPos);
    }

    private static WItem findSlotItemAtPos(NInventory inv, Coord gridPos) {
        for (Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
            if (wdg instanceof WItem) {
                WItem w = (WItem) wdg;
                Coord pos = w.c.sub(1, 1).div(Inventory.sqsz);
                if (pos.equals(gridPos)) {
                    return w;
                }
            }
        }
        return null;
    }

    // --- Multiset utilities for quality comparison ---

    /**
     * Returns elements in {@code a} that are not matched in {@code b} (multiset difference).
     */
    private static List<Float> multisetDiff(List<Float> a, List<Float> b) {
        List<Float> bCopy = new ArrayList<>(b);
        List<Float> diff = new ArrayList<>();
        for (float v : a) {
            int idx = findFloatIdx(bCopy, v);
            if (idx >= 0) {
                bCopy.remove(idx);
            } else {
                diff.add(v);
            }
        }
        return diff;
    }

    private static boolean containsFloat(List<Float> list, float val) {
        return findFloatIdx(list, val) >= 0;
    }

    private static int findFloatIdx(List<Float> list, float val) {
        for (int i = 0; i < list.size(); i++) {
            if (Math.abs(list.get(i) - val) < 0.001f) {
                return i;
            }
        }
        return -1;
    }
}
