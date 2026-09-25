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
                "Entrails", "Feather", "Fine Feather", " Meat",
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
        catExceptions.add("Lynx Claws");
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
        // Categorized under "Edible Mushroom" (putAll(4, ...) above) like any fresh mushroom, but
        // confirmed live (2026-09) not to stack at all - same category-mismatch pattern "Troll
        // Mushrooms" above already corrects. Reported after a passive-learning bug (fixed separately,
        // see NInventory.observeStackSizesForLearning()) additionally taught the DB it stacks to 4;
        // this static-table entry is the independent root-cause fix, not a workaround for that bug.
        catExceptions.add("Dried Morels");
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
        if (NParser.checkName(win.cap, unstackableContainers)
            || NParser.checkName(name, new NAlias("Lynx Claws"))
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
     * generator for the shared DB-backed override table (migration 13,
     * nurgling/db/migration/MigrationManager.java) and as {@link #getFullStackSize}'s fallback.
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
     * the shared DB-backed override table (migration 13,
     * nurgling/db/migration/MigrationManager.java) with a starting point equivalent to this static
     * table's current answers — nothing else should need this.
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
