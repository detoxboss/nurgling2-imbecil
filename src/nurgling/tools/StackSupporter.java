package nurgling.tools;

import haven.Coord;
import haven.WItem;
import haven.Window;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.NUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class StackSupporter {

    // category -> stack size
    private static final Map<String, Integer> categorySize = new HashMap<>();

    private static final HashSet<String> catExceptions = new HashSet<>();
    private static final HashMap<String, Integer> customStackSizes = new HashMap<>();

    static {
        // Custom stack sizes for items that differ from their category defaults
        customStackSizes.put("Earthworm", 5);
        customStackSizes.put("Wolf's Claw", 4);
        customStackSizes.put("Clove of Garlic", 5);
        customStackSizes.put("Stinging Nettle", 4);
        customStackSizes.put("Yarrow", 4);
        customStackSizes.put("Reeds", 4);
        customStackSizes.put("Gooseneck Barnacle", 4);
        customStackSizes.put("River Pearl Mussel", 4);
        customStackSizes.put("Tuft of Squirrel's Finest Hair", 3);
        customStackSizes.put("Forest Lizard", 3);
        customStackSizes.put("Cavebulb", 4);
        customStackSizes.put("Dusk Fern", 4);
        customStackSizes.put("Straw", 5);
        customStackSizes.put("Standing Grass", 4);
        customStackSizes.put("Frog", 3);
        customStackSizes.put("Toad", 3);
        customStackSizes.put("Waybroad", 4);
        customStackSizes.put("Green Kelp", 4);
        customStackSizes.put("Cattail Roots", 4);
        customStackSizes.put("Heartwood Leaves", 4);
        customStackSizes.put("Oyster", 4);
        customStackSizes.put("Petrified Seashell", 3);
        customStackSizes.put("Dead Wood Scorpion", 4);
        customStackSizes.put("Odd Honeycomb", 3);
        // meat-clam + meat-jotunclam. In no VSpec category; a meat, and the server stacks it 5 deep.
        customStackSizes.put("Jotun Clam Meat", 5);
        // Registered under "Stackable Curiosities" (stack size 4), but the server stacks it 5 deep.
        customStackSizes.put("Curious Needle", 5);
        // Registered under "Stackable Curiosities" (stack size 4), but the server stacks these shallower.
        customStackSizes.put("Small Brain", 3);
        customStackSizes.put("Brain", 2);
        customStackSizes.put("Aurochs Hair", 3);
        // gfx/invobjs/peapod. In no VSpec category.
        customStackSizes.put("Peapod", 3);
        customStackSizes.put("Adder's Lying Tongue", 3);
        // gfx/invobjs/branch. Sits in "Wicker" for what it crafts into, but the server
        // stacks it 5 deep, not 3 like the rest of that category.
        customStackSizes.put("Branch", 5);

        putAll(3,
                "Tuber", "Onion", "Beetroot", "Carrot", "Cucumber",
                "Salad Greens", "Malted Grains", "Millable Seed", "Egg",
                "Gellant", "Stuffing", "Dried Fruit", "Flour",
                "Giant Ant", "Royal Ant", "Fishline", "Sweetener",
                "Thatching Material", "Vegetable Oil", "Solid Fat",
                "Snail", "Edible Seashell", "Candle", "Pearl",
                "Finer Plant Fibre", "Wicker", "Cloth", "Pigment",
                "Fine Clay", "Any Brick", "Clay", "Casting Material",
                "Ore", "Stone", "Lures", "Hooks", "Dried Fish",
                "Medicine", "Intestines", "Bait", "Pipeweed", "Animal Fat"
        );

        putAll(4,
                "Hide Fresh", "Prepared Animal Hide", "Bone Material",
                "Coal", "Wool", "Leaf", "Flower", "String", "Berry",
                "Edible Mushroom", "Spices", "Fruit", "Fruit or Berry",
                "Mantle", "Seed of Tree or Bush",
                "Decent-sized Conifer Cone", "Tree Bough",
                "Forageable", "Bug", "Miscellaneous",
                "Bark", "Shellfish", "Fish Fresh Water", "Fish Ocean",
                "Fish Cave", "Fish", "Cured Tea", "Stackable Curiosities",
                "Chitin", "Olive"
        );

        putAll(5,
                /* "Meat" (the weird-meat category: Ant Meat, Cave Louse Meat, Chasm Conch Meat),
                 * not " Meat". PR #11 renamed VSpec's own category key from " Meat" to "Meat" but
                 * left this one with its leading space, and categorySize is an exact Map lookup
                 * (VSpec.getCategory compares with String.equals, no trimming), so the entry had
                 * been dead ever since: every weird meat reported unstackable/1. Corrected here
                 * rather than deferred because MigrationManager's migration 15 uses this very table
                 * as the seed/reconciliation source of truth. */
                "Entrails", "Feather", "Fine Feather", "Meat",
                "Raw Meat", "Bollock", "Filet of ", "Raw Chevon",
                "Raw Beef", "Raw Mutton", "Raw Pork", "Raw Horsemeat",
                "Raw ", "Crab Meat", "Poultry", "Soil", "Mulch", "Nuts"
        );

        putAll(10,
                "Nugget of a Precious Metal",
                "Nugget of Bronze, Iron or Steel",
                "Nugget of Any Common Metal",
                "Nugget of Any Metal"
        );
    }

    private static void putAll(int size, String... cats) {
        for (String c : cats) {
            categorySize.put(c, size);
        }
    }

    static {
        catExceptions.add("Moose Antlers");
        catExceptions.add("Red Deer Antlers");
        catExceptions.add("Reindeer Antlers");
        catExceptions.add("Roe Deer Antlers");
        catExceptions.add("Wolf's Claw");
        catExceptions.add("Silkworm");
        catExceptions.add("Female Silkmoth");
        catExceptions.add("Male Silkmoth");
        catExceptions.add("Tick");
        catExceptions.add("Bloated Tick");
        catExceptions.add("Bog Turtle Shell");
        catExceptions.add("Mole's Pawbone");
        catExceptions.add("Lobster");
        catExceptions.add("Leech");
        catExceptions.add("Dried Filet");
        catExceptions.add("Billygoat Horn");
        catExceptions.add("Wildgoat Horn");
        catExceptions.add("Ant Chitin");
        catExceptions.add("Cave Louse Chitin");
        catExceptions.add("Driftkelp");
        catExceptions.add("A Beautiful Dream");
        catExceptions.add("Opened Oyster");
        catExceptions.add("Boiled River Pearl Mussel");
        catExceptions.add("Mammoth Tusk");
        catExceptions.add("Troll Mushrooms");
        catExceptions.add("Boreworm Beak");
    }

    private static final NAlias unstackableContainers = new NAlias(
            "Smith's Smelter", "Ore Smelter", "Herbalist Table", "Tub",
            "Oven", "Steelbox", "Frame", "Kiln", "Smoke Shed", "Stack furnace",
            "Extraction Press"
    );

    public static boolean isStackable(NInventory inv, String name) {
        Window win = inv.getparent(Window.class);
        if (win == null) {
            return false;
        }
        // Context/substring vetoes: about *where* the item sits or a name *fragment*, not a fact
        // about the exact item name, so these can never be represented as a per-name DB row (see
        // isStackableByName below). Always run first, unconditionally.
        //
        // "Lynx Claws" used to be vetoed here and listed in catExceptions. Upstream removed both in
        // the 2026-09-30 sync range (it does stack); the catExceptions removal auto-merged, this one
        // was the conflict. It never belonged in this block anyway - it is an exact-name fact, not a
        // context or substring one, so it is now simply a plain FineBones category item.
        if (NParser.checkName(win.cap, unstackableContainers)
            || name.equals("Silkworm")
            || name.equals("Tick")
            || name.contains("Dried Filet")) {
            return false;
        }
        nurgling.db.service.StackSizeService.StackInfo info = stackSizeInfo(name);
        if (info != null) {
            return info.stackable;
        }
        return isStackableByName(name);
    }

    /**
     * Exact-name stackability — no window/context dependency, so it also serves as the seed data
     * generator for the shared DB-backed override table (migration 15,
     * nurgling/db/migration/MigrationManager.java) and as {@link #getFullStackSize}'s fallback.
     *
     * <p>Static-table only: it never consults {@link #stackSizeInfo}, which is what makes it safe
     * to call from inside a migration.
     *
     * <p>Checks the exception set before the custom-size table. {@code isStackable} and {@code
     * getFullStackSize} used to check these in opposite orders (custom size first in the latter) —
     * a latent inconsistency that only ever mattered for "Wolf's Claw" (present in both tables) and
     * never surfaced because every caller gates on {@code isStackable} first. This is now the one
     * place that order is decided, which changes {@code getFullStackSize("Wolf's Claw")}'s static
     * fallback answer from 4 to 1.
     */
    public static boolean isStackableByName(String name) {
        if (catExceptions.contains(name)) {
            return false;
        }
        // An explicit custom stack size is itself a declaration that the item stacks.
        // Some such items (e.g. Standing Grass, whose only category "Weavable Grass" is
        // not in categorySize) would otherwise be reported unstackable and never stacked,
        // making their customStackSizes entry dead. Honor the custom size directly here.
        if (customStackSizes.containsKey(name)) {
            return true;
        }
        ArrayList<String> categories = VSpec.getCategory(name);
        for (String cat : categories) {
            if (categorySize.containsKey(cat)) {
                return true;
            }
        }
        return false;
    }

    public static int getFullStackSize(String name) {
        nurgling.db.service.StackSizeService.StackInfo info = stackSizeInfo(name);
        if (info != null) {
            return info.maxStack;
        }
        return getFullStackSizeStatic(name);
    }

    /**
     * {@link #getFullStackSize} with the shared DB-backed override deliberately bypassed — the
     * built-in table's own answer and nothing else.
     *
     * <p>Exists so migration-time seeding and reconciliation
     * (nurgling/db/migration/MigrationManager.java, migration 15) can read the static table
     * explicitly instead of calling the DB-aware method and relying on
     * {@code StackSizeService} not being constructed yet. That ordering does hold today —
     * {@code DatabaseManager} runs migrations before it builds any service, so
     * {@code getStackSizeService()} is still null while a migration runs — but depending on it
     * silently would make seeding wrong the moment that order changed.
     *
     * <p>Pure function over the static tables: it never touches the database and cannot throw.
     */
    public static int getFullStackSizeStatic(String name) {
        if (catExceptions.contains(name)) {
            return 1;
        }

        Integer custom = customStackSizes.get(name);
        if (custom != null) {
            return custom;
        }

        ArrayList<String> categories = VSpec.getCategory(name);
        for (String cat : categories) {
            Integer size = categorySize.get(cat);
            if (size != null) {
                return size;
            }
        }

        return 1;
    }

    /** One item's static-table facts — see {@link #staticSeedSnapshot}. */
    public static final class StaticStackFact {
        public final int maxStack;
        public final boolean stackable;

        StaticStackFact(int maxStack, boolean stackable) {
            this.maxStack = maxStack;
            this.stackable = stackable;
        }
    }

    /**
     * The whole static table as a name -&gt; {@link StaticStackFact} map: every name
     * {@link #seedCandidateNames()} knows about, with the answer {@link #isStackableByName} and
     * {@link #getFullStackSizeStatic} would give for it.
     *
     * <p>This is the explicit static-only view migration 15 seeds and reconciles {@code
     * stack_sizes} from. Best-effort by inheritance from {@code seedCandidateNames()}: a category
     * VSpec cannot resolve is skipped rather than failing, so callers must treat an absent name as
     * "not known here", never as "delete it".
     */
    public static java.util.Map<String, StaticStackFact> staticSeedSnapshot() {
        java.util.Map<String, StaticStackFact> out = new java.util.LinkedHashMap<>();
        for (String name : seedCandidateNames()) {
            boolean stackable = isStackableByName(name);
            out.put(name, new StaticStackFact(stackable ? getFullStackSizeStatic(name) : 1, stackable));
        }
        return out;
    }

    /**
     * The shared DB-backed table's answer for this name, or null if no service is available (DB
     * disabled/unavailable, or the optional migration that creates {@code stack_sizes} was refused)
     * or it simply has no opinion on this name yet — either way, callers fall back to the static
     * table unchanged.
     */
    private static nurgling.db.service.StackSizeService.StackInfo stackSizeInfo(String name) {
        nurgling.db.DatabaseManager dbm = nurgling.NCore.databaseManager;
        if (dbm == null) {
            return null;
        }
        nurgling.db.service.StackSizeService svc = dbm.getStackSizeService();
        return (svc != null) ? svc.lookup(name) : null;
    }

    /**
     * Every exact item name this table has an opinion about, stackable or not. Used only to seed
     * and reconcile the shared DB-backed override table (migration 15,
     * nurgling/db/migration/MigrationManager.java) with a starting point equivalent to this static
     * table's current answers — nothing else should need this directly; prefer
     * {@link #staticSeedSnapshot()}.
     */
    public static java.util.Set<String> seedCandidateNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        names.addAll(customStackSizes.keySet());
        names.addAll(catExceptions);
        for (String cat : categorySize.keySet()) {
            try {
                ArrayList<String> content = VSpec.getCategoryContent(cat);
                if (content != null) {
                    names.addAll(content);
                }
            } catch (RuntimeException e) {
                // VSpec may not have this category loaded yet (e.g. seeding runs before the
                // server's item catalog is fully populated) - skip it. Passive learning and manual
                // calibration fill in anything missed here; this is a best-effort starting point.
            }
        }
        return names;
    }

    public static boolean isSameExist(NAlias items, NInventory inv) throws InterruptedException {
        if (items.keys.size() > 1) {
            return false;
        }

        ArrayList<String> categories = VSpec.getCategory(items.getDefault());
        if (categories.contains("Hide Fresh"))
            categories.add("Prepared Animal Hide");
        else if (categories.contains("Prepared Animal Hide"))
            categories.add("Hide Fresh");

        for (String cat : categories) {
            ArrayList<String> categoryContent = new ArrayList<>(VSpec.getCategoryContent(cat));
            categoryContent.removeAll(items.keys);
            if (!categoryContent.isEmpty()) {
                NAlias same = new NAlias(categoryContent);
                if (!inv.getItems(same).isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Exact-name variant of {@link #isSameExist}. Only counts a sibling as present when an
     * inventory item's name equals it outright.
     *
     * isSameExist() resolves siblings through NAlias, which matches by substring, so any item
     * whose name contains a sibling's name reports a collision with itself - "Pumpkin Flesh"
     * and "Pumpkin" share the "Crops - other" category, so a pure load of flesh always looked
     * like a mixed one. Callers that know the exact item name they are moving should use this.
     */
    public static boolean isSameExistExact(String name, NInventory inv) throws InterruptedException {
        if (name == null) {
            return false;
        }

        ArrayList<String> categories = VSpec.getCategory(name);
        if (categories.contains("Hide Fresh"))
            categories.add("Prepared Animal Hide");
        else if (categories.contains("Prepared Animal Hide"))
            categories.add("Hide Fresh");

        for (String cat : categories) {
            ArrayList<String> categoryContent = new ArrayList<>(VSpec.getCategoryContent(cat));
            categoryContent.remove(name);
            if (categoryContent.isEmpty())
                continue;

            // getItems() still pre-filters by substring - it is the only lookup available - so
            // re-check each hit by exact name before treating it as a real sibling.
            NAlias same = new NAlias(categoryContent);
            for (WItem item : inv.getItems(same)) {
                if (same.matchesExact(((NGItem) item.item).name()))
                    return true;
            }
        }
        return false;
    }

    /**
     * Universal method to calculate optimal item capacity considering stacking
     */
    public static int getOptimalItemCapacity(NInventory inventory, String itemName, Coord itemSize, int targetCount) throws InterruptedException {
        int freeSlots = inventory.getNumberFreeCoord(itemSize);

        if (((NInventory) NUtils.getGameUI().maininv).bundle.a && isStackable(inventory, itemName)) {
            int maxStackSize = getFullStackSize(itemName);
            int maxCapacity = freeSlots * maxStackSize;
            return Math.min(targetCount, maxCapacity);
        }

        return Math.min(targetCount, freeSlots);
    }
}
