package nurgling.tools;

import nurgling.NFlowerMenu;
import nurgling.conf.NLpAssistantProp;

/**
 * Maps an LpExplorer-reported undiscovered product to (a) the flower-menu petal that harvests it
 * and (b) the tool required to perform that petal, without ever guessing: a product only resolves
 * to a petal when exactly one live option unambiguously matches, and only resolves to a tool when
 * the caller's NLpAssistantProp has one configured for that category.
 *
 * Petal text is server-sent and not present anywhere client-side in general - but for bough/bark/
 * leaf/board/block/stone specifically, this codebase already ships bots that perform these exact
 * actions today (CollectBough/CollectBark/CollectLeaf/PrepareBoards/PrepareBlocks/Chipper), so
 * their literal petal strings are known-good, not guessed. None of them equip a tool for bough/
 * bark/leaf/seed - only board (saw), block (axe) and stone (pickaxe) do.
 *
 * SEED is the one category with no fixed literal at all: live testing turned up "Pick berries",
 * "Pick pomes", "Pick cone", "Pick catkin", "Pick crabapple", "Pick seeds", "Pick fruits" across
 * different species, with no end in sight - an exact-list approach doesn't scale to it. Every one
 * of those starts with "Pick " and none of them are the leaf petal ("Pick leaf"/"Pick leaves", the
 * one other category that also starts with "Pick "), so findSeedPetal() matches on that shape
 * instead: starts with "Pick ", isn't the leaf petal, and - same safety rule as everywhere else -
 * only resolves if it's the single such petal on the menu.
 *
 * "Take branch" is deliberately NOT a general bough candidate: live testing showed it's a generic,
 * always-present petal unrelated to the discoverable bough curio on most species (selecting it
 * never produced a discovery there) - "Take bough" alone is the confirmed real action for those.
 * Olive is the sole confirmed exception: its bough-equivalent product is actually named "Olive
 * Branch" (not "Olive Bough" - see LpExplorer.isBoughProduct()), and "Take branch" is genuinely
 * its harvest petal, so it's listed second in ACTIONS_BOUGH as a fallback tried only when "Take
 * bough" isn't on the menu at all (which is the case for olive and never for any other BOUGH-
 * classified species, since classify() only assigns BOUGH when the product name says so).
 */
public class LpActionMatcher {
    public enum Category {
        SEED, LEAF, BOUGH, BARK, BOARD, BLOCK, STONE, OLDTRUNK
    }

    // Confirmed verbatim from the existing bots / live testing named in the class doc above.
    private static final String[] ACTIONS_LEAF = {"Pick leaf", "Pick leaves"};
    private static final String[] ACTIONS_BOUGH = {"Take bough", "Take branch"};
    private static final String[] ACTIONS_BARK = {"Take bark"};
    private static final String[] ACTIONS_BOARD = {"Make boards"};
    private static final String[] ACTIONS_BLOCK = {"Chop into blocks"};
    private static final String[] ACTIONS_STONE = {"Chip stone"};

    private static final String SEED_PREFIX = "Pick ";
    private static final String[] SEED_EXCLUDE = {"leaf", "leaves"};

    /** Which category a still-undiscovered product on this gob belongs to. */
    public static Category classify(String gobResName, String product) {
        HarvestSpec spec = HarvestSpecs.forResource(gobResName);
        if (spec == HarvestSpecs.LOG)
            // Real product names are "Board of <species>"/"Block of <species>", not the bare
            // words - an exact-equals check here never matches Block at all (see class doc).
            return product.contains("Block") ? Category.BLOCK : Category.BOARD;
        if (spec == HarvestSpecs.STONE)
            return Category.STONE;
        if (spec == HarvestSpecs.OLDTRUNK)
            return Category.OLDTRUNK;

        // Tree/bush: same product-name substring rules LpExplorer uses internally.
        if (product.contains("Leaf") || product.contains("Leaves"))
            return Category.LEAF;
        if (product.contains("Bough") || product.equals("Olive Branch"))
            return Category.BOUGH;
        if (product.equals(HarvestState.getBarkProductName(gobResName)))
            return Category.BARK;
        return Category.SEED;
    }

    /**
     * The known-good candidate petal strings for every category except SEED (see findSeedPetal)
     * and OLDTRUNK (no reference bot exists, so it falls back to the product's own name).
     */
    public static String[] candidateActions(Category category, String product) {
        switch (category) {
            case LEAF:
                return ACTIONS_LEAF;
            case BOUGH:
                return ACTIONS_BOUGH;
            case BARK:
                return ACTIONS_BARK;
            case BOARD:
                return ACTIONS_BOARD;
            case BLOCK:
                return ACTIONS_BLOCK;
            case STONE:
                return ACTIONS_STONE;
            case OLDTRUNK:
            default:
                return new String[]{product};
        }
    }

    /**
     * The single flower-menu petal exactly matching one of the given candidates, or null if none
     * do - never guessed via substring/keyword matching (see class doc for why that broke).
     */
    public static NFlowerMenu.NPetal findPetal(NFlowerMenu fm, String[] candidates) {
        if (fm == null || fm.nopts == null)
            return null;

        for (String candidate : candidates) {
            NFlowerMenu.NPetal exact = null;
            int exactCount = 0;
            for (NFlowerMenu.NPetal petal : fm.nopts) {
                if (candidate.equals(petal.name)) {
                    exact = petal;
                    exactCount++;
                }
            }
            if (exactCount == 1)
                return exact;
        }
        return null;
    }

    /**
     * SEED-only matcher: the single petal starting with "Pick " that isn't the leaf petal - see
     * class doc for why SEED can't use a fixed candidate list. Still refuses to guess: more than
     * one such petal (shouldn't normally happen) returns null, same as findPetal.
     */
    public static NFlowerMenu.NPetal findSeedPetal(NFlowerMenu fm) {
        if (fm == null || fm.nopts == null)
            return null;
        NFlowerMenu.NPetal found = null;
        for (NFlowerMenu.NPetal petal : fm.nopts) {
            if (petal.name == null || !petal.name.startsWith(SEED_PREFIX))
                continue;
            String lower = petal.name.toLowerCase();
            boolean excluded = false;
            for (String ex : SEED_EXCLUDE) {
                if (lower.contains(ex)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded)
                continue;
            if (found != null)
                return null; // ambiguous
            found = petal;
        }
        return found;
    }

    /** The configured tool alias for this category, or null if that category needs no tool. */
    public static String requiredTool(Category category, NLpAssistantProp prop) {
        switch (category) {
            case BOARD:
                return blankToNull(prop.boardTool);
            case BLOCK:
                return blankToNull(prop.blockTool);
            case STONE:
                return blankToNull(prop.stoneTool);
            case OLDTRUNK:
                return blankToNull(prop.oldtrunkTool);
            case SEED:
            case LEAF:
            case BOUGH:
            case BARK:
            default:
                return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private LpActionMatcher() {}
}
