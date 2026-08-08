package nurgling.tools;

import haven.GItem;
import haven.WItem;
import haven.Widget;
import haven.res.ui.stackinv.ItemStack;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One consistent, instant (never blocking) read of an inventory's matching top-level items, stack
 * children, and cursor - used identically for LP Assistant's harvest baseline, first-product
 * detection, post-interrupt settlement polling, ownership calculation, drop lookup, and cleanup
 * confirmation (see LpAssistantBot), so every one of those stages agrees on the same topology
 * instead of each re-deriving its own notion of "new" from a different, ad-hoc read.
 *
 * Deliberately package-local to nurgling.tools rather than added onto NInventory itself - this is
 * an LP-Assistant-shaped view (identity/ownership deltas against a baseline), not a general
 * inventory capability every NInventory caller needs.
 *
 * Captures identities (GItem.wdgid()) rather than long-lived WItem references: a WItem is a UI
 * widget the client can destroy and recreate for the same server-side item (e.g. a stack
 * container's contents widget re-attaching), so holding onto one across a settlement wait risks
 * acting on a stale/destroyed reference. Every method that needs to actually touch an item
 * therefore re-resolves it live from a wdgid via findTopLevel()/findChild()/findAny().
 *
 * Round 7: ownership is identity-based, not container-shape-based. {@link #allUnitIds} is an
 * alias-INDEPENDENT universe of every unit wdgid observed in the inventory at capture time (every
 * loose item, every stack child, the cursor) - captured regardless of whether it matches this
 * snapshot's alias filter. {@link #diff} uses that universe, not container novelty, to decide
 * ownership: a unit that existed anywhere before (loose, in a different stack, or the same stack)
 * remains pre-existing no matter how its container shape changes; only a unit id genuinely absent
 * from the baseline's universe is new. This is what lets a pre-existing unit survive being
 * re-parented (loose -> stack child, stack child -> loose, or moved to a recreated container)
 * without being misclassified as this harvest's own output.
 */
public final class InventorySnapshot {

    /** One top-level inventory slot: either a loose item or a stack container. */
    public static final class TopLevelEntry {
        public final int wdgid;
        public final boolean isStack;
        // Round 7b: EVERY child currently physically inside the stack, regardless of whether its
        // name has been proven to match this snapshot's alias - including a child whose name is
        // still null/unresolved (not yet loaded) or genuinely a different product. Empty for a
        // loose item. Kept separate from matchingChildWdgids so diff() can require that ALL
        // physical children are proven-matching-and-new before a container is ever treated as a
        // single whole-stack-owned unit - see diff()'s own doc for why container novelty/child
        // COUNT alone was never sufficient.
        public final Set<Integer> physicalChildWdgids;
        // Subset of physicalChildWdgids confirmed (name resolved and matches) to belong to this
        // snapshot's alias. Empty for a loose item.
        public final Set<Integer> matchingChildWdgids;

        TopLevelEntry(int wdgid, boolean isStack, Set<Integer> physicalChildWdgids, Set<Integer> matchingChildWdgids) {
            this.wdgid = wdgid;
            this.isStack = isStack;
            this.physicalChildWdgids = physicalChildWdgids;
            this.matchingChildWdgids = matchingChildWdgids;
        }

        public int unitCount() {
            return isStack ? matchingChildWdgids.size() : 1;
        }
    }

    public enum CursorState { EMPTY, MATCHING, OTHER }

    // Alias-filtered structural view (which top-level slots/children currently match this
    // snapshot's product name) - used for settlement's sameState() and for enumerating what
    // currently exists under this product's name.
    public final Map<Integer, TopLevelEntry> topLevel;
    // Alias-INDEPENDENT identity universe: every unit wdgid observed anywhere in the inventory
    // (loose items, every stack's children, regardless of name) plus the cursor's wdgid if
    // present - see class doc. Used by diff() to answer "did this exact unit exist before,"
    // independent of what it's named or how it's currently parented.
    public final Set<Integer> allUnitIds;
    public final CursorState cursorState;
    public final Integer cursorWdgid;

    private InventorySnapshot(Map<Integer, TopLevelEntry> topLevel, Set<Integer> allUnitIds,
                               CursorState cursorState, Integer cursorWdgid) {
        this.topLevel = topLevel;
        this.allUnitIds = allUnitIds;
        this.cursorState = cursorState;
        this.cursorWdgid = cursorWdgid;
    }

    /**
     * Captures every top-level item (and, for a stack container, every child) whose name matches
     * alias into {@link #topLevel} (alias == null matches everything), PLUS an alias-independent
     * {@link #allUnitIds} universe of every unit in the inventory regardless of name, plus the
     * cursor. Never blocks and never queues an NCore task, unlike NInventory.getItems()/getItem() -
     * safe to call from inside another task's check() (see WaitLpFirstProduct, WaitLpSettlement).
     *
     * Round 7: the cursor is read via {@code inv.ui.gui} - the inventory widget's OWN owning UI/
     * session - never the globally-selected {@code NUtils.getGameUI()}, which can pair one
     * session's inventory with a different session's cursor in a multi-session process. The whole
     * widget-tree walk (and the cursor read, part of the same atomic view) runs inside
     * {@code synchronized (inv.ui)} - the same critical section NInventory's own GetItems task uses
     * around its traversal - so this can't observe a partially-updated widget tree mid-mutation.
     *
     * A stack container whose children don't include any alias match is omitted from topLevel
     * entirely - a pre-existing stack of an unrelated name was never part of this harvest and has
     * no business appearing in its baseline/delta. Its children are still added to allUnitIds,
     * though, since that universe is alias-independent by design.
     */
    public static InventorySnapshot capture(NInventory inv, NAlias alias) {
        Map<Integer, TopLevelEntry> top = new HashMap<>();
        Set<Integer> allIds = new HashSet<>();
        CursorState cursorState = CursorState.EMPTY;
        Integer cursorWdgid = null;

        synchronized (inv.ui) {
            for (Widget w = inv.child; w != null; w = w.next) {
                if (!(w instanceof WItem))
                    continue;
                WItem witem = (WItem) w;
                if (!(witem.item instanceof NGItem))
                    continue;
                NGItem gitem = (NGItem) witem.item;
                if (gitem.contents instanceof ItemStack) {
                    ItemStack stack = (ItemStack) gitem.contents;
                    Set<Integer> physicalChildren = new HashSet<>();
                    Set<Integer> matchingChildren = new HashSet<>();
                    for (GItem child : stack.order) {
                        // Every physical child counts as a unit identity - even one whose name
                        // hasn't resolved yet (not an NGItem, or NGItem.name() still null) - so a
                        // not-yet-loaded/non-matching child can never be silently skipped and later
                        // let a whole stack be misclassified as fully new (Round 7b).
                        int cid = child.wdgid();
                        physicalChildren.add(cid);
                        allIds.add(cid);
                        if (child instanceof NGItem) {
                            String cname = ((NGItem) child).name();
                            if (alias == null || (cname != null && NParser.checkName(cname, alias)))
                                matchingChildren.add(cid);
                        }
                    }
                    if (!matchingChildren.isEmpty())
                        top.put(gitem.wdgid(), new TopLevelEntry(gitem.wdgid(), true, physicalChildren, matchingChildren));
                } else {
                    allIds.add(gitem.wdgid());
                    String name = gitem.name();
                    if (alias == null || (name != null && NParser.checkName(name, alias)))
                        top.put(gitem.wdgid(), new TopLevelEntry(gitem.wdgid(), false, Collections.emptySet(), Collections.emptySet()));
                }
            }

            NGameUI gui = inv.ui.gui;
            WItem hand = gui != null ? gui.vhand : null;
            if (hand != null && hand.item instanceof NGItem) {
                NGItem handItem = (NGItem) hand.item;
                int hid = handItem.wdgid();
                allIds.add(hid);
                String hname = handItem.name();
                boolean matches = alias == null || (hname != null && NParser.checkName(hname, alias));
                cursorState = matches ? CursorState.MATCHING : CursorState.OTHER;
                cursorWdgid = hid;
            }
        }

        return new InventorySnapshot(top, allIds, cursorState, cursorWdgid);
    }

    /**
     * Test-support factory: builds a snapshot directly from already-computed topology, bypassing
     * capture()'s live haven Widget-tree walk. Building a real WItem/GItem/ItemStack tree needs a
     * live UI/resource environment this codebase's test harness doesn't have; diff()/
     * classifyCursor()/sameState() are pure functions over this plain data, so they can - and are,
     * see InventorySnapshotTest - unit tested by constructing snapshots this way instead.
     */
    public static InventorySnapshot of(Map<Integer, TopLevelEntry> topLevel, Set<Integer> allUnitIds,
                                        CursorState cursorState, Integer cursorWdgid) {
        return new InventorySnapshot(new HashMap<>(topLevel), new HashSet<>(allUnitIds), cursorState, cursorWdgid);
    }

    /**
     * Test-support factory for one TopLevelEntry - see of()'s doc. physicalChildWdgids is every
     * child physically in the stack; matchingChildWdgids is the subset confirmed to match the
     * snapshot's alias (must be a subset of physicalChildWdgids). Pass the same set for both when a
     * test doesn't care about the physical-vs-matching distinction (a stack with no unresolved/
     * unmatched children).
     */
    public static TopLevelEntry entry(int wdgid, boolean isStack, Set<Integer> physicalChildWdgids, Set<Integer> matchingChildWdgids) {
        return new TopLevelEntry(wdgid, isStack, new HashSet<>(physicalChildWdgids), new HashSet<>(matchingChildWdgids));
    }

    /** Safely identifiable ownership delta of `this` (settled state) against an earlier baseline. */
    public static final class Delta {
        public final Set<Integer> newLooseItems;
        // container wdgid -> every one of its (alias-matching) child wdgids, only present when
        // EVERY child of that container is proven absent from the baseline's identity universe -
        // the whole container is then safe to drop as one optimized message (see
        // LpAssistantBot.dropHarvestWidgets()), with the child ids preserved purely for
        // confirmation/recovery if the container's own identity doesn't resolve later (collapse).
        public final Map<Integer, Set<Integer>> newWholeStacks;
        // container wdgid -> only the specific child wdgids proven absent from the baseline's
        // identity universe - for a stack that also contains at least one pre-existing/ambiguous
        // child, so the container itself must never be whole-dropped.
        public final Map<Integer, Set<Integer>> newChildrenInStacks;
        // Top-level wdgid present in both snapshots but whose loose/stack shape changed (a
        // collapse/re-parenting edge case on the SAME container identity) - ownership of its
        // contents can't be safely established either way, so it's reported separately and never
        // folded into either "new" map above.
        public final Set<Integer> ambiguousTopLevel;

        Delta(Set<Integer> newLooseItems, Map<Integer, Set<Integer>> newWholeStacks,
              Map<Integer, Set<Integer>> newChildrenInStacks, Set<Integer> ambiguousTopLevel) {
            this.newLooseItems = newLooseItems;
            this.newWholeStacks = newWholeStacks;
            this.newChildrenInStacks = newChildrenInStacks;
            this.ambiguousTopLevel = ambiguousTopLevel;
        }

        public boolean isEmpty() {
            return newLooseItems.isEmpty() && newWholeStacks.isEmpty() && newChildrenInStacks.isEmpty();
        }
    }

    /**
     * Delta of `this` snapshot (the later/settled one) against baseline (the earlier one).
     * Round 7: classification is by UNIT IDENTITY against {@code baseline.allUnitIds}, not by
     * container presence/novelty - see class doc. A loose item is new only if its own wdgid was
     * never observed anywhere in the baseline inventory.
     *
     * Round 7b: a stack's MATCHING children are individually checked against that universe to find
     * which are new, but a container only ever qualifies for `newWholeStacks` (the one-message
     * whole-stack drop optimization) when its complete PHYSICAL child set equals exactly that new-
     * matching set - i.e. every physical child, with no exceptions, is both confirmed to match this
     * product AND proven new. A physical child that's pre-existing, still unresolved (name not yet
     * loaded), or a genuinely different product all block the whole-stack optimization the same
     * way, since any of them would be swept up by a single whole-container drop message. In every
     * other case where at least one matching child is new, those specific new matching child ids go
     * into `newChildrenInStacks` instead, and the container itself is never treated as ownable. If
     * no matching child is new at all (e.g. a recreated container holding only pre-existing
     * children), nothing is reported for it. A pre-existing stack that only LOSES children
     * (collapse/split) never causes any pre-existing unit to look owned, since only genuinely-
     * absent-from-baseline ids are ever reported.
     */
    public Delta diff(InventorySnapshot baseline) {
        Set<Integer> newLoose = new HashSet<>();
        Map<Integer, Set<Integer>> newWhole = new HashMap<>();
        Map<Integer, Set<Integer>> newPartial = new HashMap<>();
        Set<Integer> ambiguous = new HashSet<>();

        for (TopLevelEntry cur : topLevel.values()) {
            TopLevelEntry base = baseline.topLevel.get(cur.wdgid);
            if (base != null && base.isStack != cur.isStack) {
                // Same top-level wdgid, but its own loose/stack shape flipped between snapshots -
                // a genuinely conflicting identity re-use, not just a unit moving elsewhere. Fail
                // safe rather than guess.
                ambiguous.add(cur.wdgid);
                continue;
            }
            if (!cur.isStack) {
                if (!baseline.allUnitIds.contains(cur.wdgid))
                    newLoose.add(cur.wdgid);
                continue;
            }
            Set<Integer> newMatchingChildren = new HashSet<>();
            for (int childId : cur.matchingChildWdgids) {
                if (!baseline.allUnitIds.contains(childId))
                    newMatchingChildren.add(childId);
            }
            if (newMatchingChildren.isEmpty())
                continue; // no matching child is new, whatever this stack's physical composition
            // Whole-stack-eligible only if EVERY physical child (not just the matching ones) is
            // accounted for by newMatchingChildren - a physical child that's unmatched/unresolved/
            // pre-existing is, by construction, absent from newMatchingChildren, so this equality
            // check alone enforces "no exceptions" without a separate loop.
            if (cur.physicalChildWdgids.equals(newMatchingChildren))
                newWhole.put(cur.wdgid, new HashSet<>(newMatchingChildren));
            else
                newPartial.put(cur.wdgid, newMatchingChildren);
        }
        return new Delta(newLoose, newWhole, newPartial, ambiguous);
    }

    /** How the cursor in `this` snapshot relates to an earlier baseline - see class doc's callers. */
    public enum CursorClassification {
        EMPTY,          // no cursor item
        NEW_EXPECTED,   // matches the harvest's product and its wdgid was never observed in
                        // baseline's identity universe at all - provably new
        PRE_EXISTING,   // the exact same wdgid the baseline already had in the cursor slot, OR its
                        // wdgid already existed elsewhere in baseline's identity universe (moved
                        // into the cursor from inventory) - never ours to touch
        AMBIGUOUS       // present, but neither of the above - unknown origin, must not be assumed
                        // safe to clear
    }

    public CursorClassification classifyCursor(InventorySnapshot baseline) {
        if (cursorState == CursorState.EMPTY)
            return CursorClassification.EMPTY;
        if (baseline.cursorState != CursorState.EMPTY && Objects.equals(cursorWdgid, baseline.cursorWdgid))
            return CursorClassification.PRE_EXISTING;
        if (cursorWdgid != null && baseline.allUnitIds.contains(cursorWdgid))
            return CursorClassification.PRE_EXISTING;
        return cursorState == CursorState.MATCHING ? CursorClassification.NEW_EXPECTED : CursorClassification.AMBIGUOUS;
    }

    /** True if this snapshot and other are the same observable state (topology + cursor identity). */
    public boolean sameState(InventorySnapshot other) {
        if (!topLevel.keySet().equals(other.topLevel.keySet()))
            return false;
        for (Map.Entry<Integer, TopLevelEntry> e : topLevel.entrySet()) {
            TopLevelEntry mine = e.getValue();
            TopLevelEntry theirs = other.topLevel.get(e.getKey());
            if (theirs == null || theirs.isStack != mine.isStack
                    || !theirs.physicalChildWdgids.equals(mine.physicalChildWdgids)
                    || !theirs.matchingChildWdgids.equals(mine.matchingChildWdgids))
                return false;
        }
        if (cursorState != other.cursorState)
            return false;
        return cursorState == CursorState.EMPTY || Objects.equals(cursorWdgid, other.cursorWdgid);
    }

    /** Live top-level WItem for wdgid, or null if it's no longer present. Never blocks. */
    public static WItem findTopLevel(NInventory inv, int wdgid) {
        for (Widget w = inv.child; w != null; w = w.next) {
            if (w instanceof WItem && ((WItem) w).item.wdgid() == wdgid)
                return (WItem) w;
        }
        return null;
    }

    /** Live child WItem for childWdgid nested inside the stack container topWdgid, or null. */
    public static WItem findChild(NInventory inv, int topWdgid, int childWdgid) {
        WItem top = findTopLevel(inv, topWdgid);
        if (top == null || !(top.item.contents instanceof ItemStack))
            return null;
        ItemStack stack = (ItemStack) top.item.contents;
        for (Map.Entry<GItem, WItem> e : stack.wmap.entrySet()) {
            if (e.getKey().wdgid() == childWdgid)
                return e.getValue();
        }
        return null;
    }

    /**
     * Live, complete physical child wdgid set of the stack container currently identified by
     * containerWdgid, or null if that wdgid no longer resolves as a top-level stack container at
     * all (gone, or its shape changed to loose). Round 7b: used to revalidate whole-stack ownership
     * immediately before sending the one-message whole-stack drop - the recorded owned child set
     * from ownership-calculation time can be stale by the time cleanup actually runs (an extra,
     * unowned child could have merged in since), so this must be re-read fresh in the same poll that
     * decides whether the drop is still safe to send (see LpAssistantBot.dropHarvestWidgets()).
     */
    public static Set<Integer> physicalChildrenOf(NInventory inv, int containerWdgid) {
        WItem top = findTopLevel(inv, containerWdgid);
        if (top == null || !(top.item.contents instanceof ItemStack))
            return null;
        ItemStack stack = (ItemStack) top.item.contents;
        Set<Integer> ids = new HashSet<>();
        for (GItem child : stack.order)
            ids.add(child.wdgid());
        return ids;
    }

    /**
     * Live resolve of any wdgid this snapshot/delta model can track, top-level OR nested inside
     * ANY stack, regardless of which container it was originally recorded under - unlike
     * findTopLevel()/findChild(), which are scoped to a specific parent. This is the identity-based
     * resolver Round 7's ownership/cleanup logic requires: a unit that was re-parented (merged into
     * a different stack, or became loose) after its baseline/settlement snapshot was taken is still
     * found here, whereas a parent-scoped lookup would wrongly report it gone. Returns null only
     * once the unit is genuinely no longer present anywhere in this inventory (dropped/consumed).
     */
    public static WItem findAny(NInventory inv, int wdgid) {
        for (Widget w = inv.child; w != null; w = w.next) {
            if (!(w instanceof WItem))
                continue;
            WItem witem = (WItem) w;
            if (witem.item.wdgid() == wdgid)
                return witem;
            if (witem.item.contents instanceof ItemStack) {
                ItemStack stack = (ItemStack) witem.item.contents;
                for (Map.Entry<GItem, WItem> e : stack.wmap.entrySet()) {
                    if (e.getKey().wdgid() == wdgid)
                        return e.getValue();
                }
            }
        }
        return null;
    }

}
