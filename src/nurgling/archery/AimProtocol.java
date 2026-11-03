package nurgling.archery;

import haven.Coord;
import haven.Coord2d;
import haven.OCache;

/**
 * The client-to-server message shapes the archery aim diagnostic watches for or sends. Pure functions
 * over message names and argument arrays, so they can be tested without a running client.
 *
 * Argument layouts, as current source builds them (see docs/archery-aim-diagnostic.md for the trace):
 * <ul>
 *   <li>Map ground click ({@code MapView.Click.hit}, {@code MiniMap.mvclick} without a gob):
 *       {@code "click", pc, mc, button, modflags}</li>
 *   <li>Map gob click (same senders, a gob was hit or its icon clicked):
 *       {@code "click", pc, mc, button, modflags, overlayFlag, gobId, gobRc, overlayId, meshId}</li>
 *   <li>Combat portrait ({@code Fightview.Relbox/Mainrel.wdgmsg}): {@code "click", gobId, button}
 *       sent from the Fightview widget, not the map</li>
 *   <li>Menu action ({@code MenuGrid.PagButton.use}): {@code "act", ad..., [modflags, mc, [clickargs]]}</li>
 *   <li>Action belt slot ({@code GameUI.BeltSlot.use}): {@code "belt", slot, button, modflags, [mc, [clickargs]]}</li>
 * </ul>
 */
public final class AimProtocol {
    private AimProtocol() {}

    /** Which widget sent a message, as far as the diagnostic cares. */
    public enum Sender { MAP, FIGHTVIEW, MENU, GAMEUI, OTHER }

    public enum Kind {
        /** "click" on the map with no gob identity: walking, or a ground-targeted action. */
        MAP_GROUND_CLICK,
        /** "click" on the map naming a gob (world gob click or a minimap/map-window icon click). */
        MAP_GOB_CLICK,
        /** "click" on the Fightview: a combat portrait. Not a map click despite the shared name. */
        FIGHT_PORTRAIT_CLICK,
        /** "act" from the menu grid: activates an action such as Shoot. */
        MENU_ACT,
        /** "belt" from the game UI: an action belt slot, which may carry a map position. */
        BELT_USE,
        OTHER
    }

    /** Length of a ground click's argument list: pc, mc, button, modflags. */
    public static final int GROUND_ARGS = 4;
    /** Length of a gob click's argument list: ground args plus the five gob click args. */
    public static final int GOB_ARGS = 9;
    /** Index of the gob id within a gob click's arguments. */
    public static final int GOB_ID_IDX = 5;

    public static Kind classify(Sender sender, String msg, Object[] args) {
        if (msg == null)
            return Kind.OTHER;
        int n = (args == null) ? 0 : args.length;
        switch (sender) {
            case MAP:
                if (!msg.equals("click"))
                    return Kind.OTHER;
                if (n >= GOB_ARGS && args[GOB_ID_IDX] instanceof Integer)
                    return Kind.MAP_GOB_CLICK;
                if (n >= GROUND_ARGS)
                    return Kind.MAP_GROUND_CLICK;
                return Kind.OTHER;
            case FIGHTVIEW:
                return (msg.equals("click") && n >= 2) ? Kind.FIGHT_PORTRAIT_CLICK : Kind.OTHER;
            case MENU:
                return msg.equals("act") ? Kind.MENU_ACT : Kind.OTHER;
            case GAMEUI:
                return msg.equals("belt") ? Kind.BELT_USE : Kind.OTHER;
            default:
                return Kind.OTHER;
        }
    }

    /** Button of a map click, or -1. */
    public static int mapButton(Object[] args) {
        return (args != null && args.length >= GROUND_ARGS && args[2] instanceof Integer) ? (Integer) args[2] : -1;
    }

    /** Gob id named by a map gob click, or -1. */
    public static long mapGobId(Object[] args) {
        return (args != null && args.length >= GOB_ARGS && args[GOB_ID_IDX] instanceof Integer) ? (Integer) args[GOB_ID_IDX] : -1;
    }

    /**
     * Ground click toward {@code target}. {@code pc} is a screen coordinate in a real click; scripted
     * clicks across this codebase send {@link Coord#z} and the server is not known to use it.
     */
    public static Object[] groundClick(Coord2d target, int button) {
        return new Object[] {Coord.z, target.floor(OCache.posres), button, 0};
    }

    /**
     * Gob click on gob {@code gobId} at {@code gobRc}, in the shape {@code MiniMap.mvclick} and
     * {@code NUtils.clickGob} send: no overlay, mesh id -1 ("unspecified"). A real world click would put
     * the terrain hit point in mc and the struck mesh in the last argument.
     */
    public static Object[] gobClick(long gobId, Coord2d gobRc, int button) {
        Coord rc = gobRc.floor(OCache.posres);
        return new Object[] {Coord.z, rc, button, 0, 0, (int) gobId, rc, 0, -1};
    }

    /** Combat portrait click, as {@code Fightview.Relbox.wdgmsg} sends it. */
    public static Object[] portraitClick(long gobId, int button) {
        return new Object[] {(int) gobId, button};
    }

    /** Readable one-line rendering of an argument list for the log. */
    public static String fmt(Object[] args) {
        if (args == null)
            return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0)
                sb.append(", ");
            Object a = args[i];
            if (a instanceof Object[])
                sb.append(fmt((Object[]) a));
            else if (a instanceof String)
                sb.append('"').append(a).append('"');
            else
                sb.append(a);
        }
        return sb.append(']').toString();
    }
}
