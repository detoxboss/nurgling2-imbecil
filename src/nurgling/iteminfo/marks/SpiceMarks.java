package nurgling.iteminfo.marks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which seasonings an item carries, read from the plain text lines of its tooltip. The server marks a
 * seasoned food with a bare text line ("Salted", "Peppered", "Black-truffled", "White-truffled"), not
 * with a structured tip, so the exact text is the only signal there is.
 */
public final class SpiceMarks {
    private SpiceMarks() {}

    /** In display order, left to right. */
    public enum Kind {
        SALT("Salted", "gfx/invobjs/salt"),
        PEPPER("Peppered", "gfx/invobjs/pepper"),
        WHITE_TRUFFLE("White-truffled", "gfx/invobjs/herbs/truffle-white"),
        BLACK_TRUFFLE("Black-truffled", "gfx/invobjs/herbs/truffle-black");

        /** The tooltip line the server sends. */
        public final String text;
        /** The game's own inventory icon for the ingredient. */
        public final String icon;

        Kind(String text, String icon) {
            this.text = text;
            this.icon = icon;
        }
    }

    /** The seasonings named by these tooltip lines, each once, in display order. Empty if none. */
    public static List<Kind> of(Collection<String> lines) {
        List<Kind> ret = new ArrayList<>();
        if(lines == null)
            return ret;
        for(Kind k : Kind.values()) {
            if(lines.contains(k.text))
                ret.add(k);
        }
        return ret;
    }

    public static boolean has(Collection<String> lines, Kind kind) {
        return lines != null && lines.contains(kind.text);
    }
}
