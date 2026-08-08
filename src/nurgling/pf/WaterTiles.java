package nurgling.pf;

/**
 * Water-tile classification for boat/water bots. Two independent categories -
 * freshwater and ocean - each with three tiers (shallow/deep/deeper); the
 * "deeper" tier of each is unsafe/damaging to a boat and NOT interchangeable
 * with the other category's tiles for anything other than raw passability -
 * a river is not evidence of an inter-continent ocean crossing, even though
 * both categories are equally safe to sail on.
 *
 * Tile-name taxonomy confirmed directly by the user, not derived from any
 * in-repo source (an earlier draft wrongly assumed only ocean had a third,
 * dangerous tier - freshwater has its own "deeper"/"Deeper Water" too).
 */
public class WaterTiles
{
    public static boolean isFreshShallow(String name) { return name != null && name.startsWith("gfx/tiles/water"); }
    public static boolean isFreshDeep(String name) { return name != null && name.equals("gfx/tiles/deep"); }
    public static boolean isFreshDeeper(String name) { return name != null && name.equals("gfx/tiles/deeper"); }

    public static boolean isOceanShallow(String name) { return name != null && name.startsWith("gfx/tiles/owater"); }
    public static boolean isOceanDeep(String name) { return name != null && name.equals("gfx/tiles/odeep"); }
    public static boolean isOceanDeeper(String name) { return name != null && name.equals("gfx/tiles/odeeper"); }

    public static boolean isShallow(String name) { return isFreshShallow(name) || isOceanShallow(name); }
    public static boolean isDeep(String name) { return isFreshDeep(name) || isOceanDeep(name); }
    public static boolean isDeeper(String name) { return isFreshDeeper(name) || isOceanDeeper(name); }

    public static boolean isOcean(String name) { return isOceanShallow(name) || isOceanDeep(name) || isOceanDeeper(name); }
    public static boolean isFresh(String name) { return isFreshShallow(name) || isFreshDeep(name) || isFreshDeeper(name); }

    /**
     * @param deeperMode true for "Deep and Deeper" mode (includes the unsafe
     *                    deeper tier), false for "Deep and Shallow" mode.
     */
    public static boolean isSafe(String name, boolean deeperMode)
    {
        return deeperMode
                ? (isShallow(name) || isDeep(name) || isDeeper(name))
                : (isShallow(name) || isDeep(name));
    }
}
