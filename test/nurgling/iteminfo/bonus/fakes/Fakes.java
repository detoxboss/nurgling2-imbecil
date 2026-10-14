package nurgling.iteminfo.bonus.fakes;

import haven.res.ui.tt.attrmod.Attribute;

import java.awt.image.BufferedImage;

/**
 * Test doubles for the game's attribute classes. The collector recognises {@code intattr},
 * {@code pmattr}, {@code normattr} and {@code inormattr} by simple name, so these stand in for the
 * real ones without needing a loaded {@code Resource}.
 */
public final class Fakes {
    private Fakes() {}

    /** Shared plumbing; the nested subclasses below carry the recognised simple names. */
    public abstract static class Base implements Attribute {
        private final String nm;

        Base(String nm) {
            this.nm = nm;
        }

        public String name() {
            return nm;
        }

        public BufferedImage icon() {
            return null;
        }

        static String rich(double val, String body) {
            return String.format("$col[128,255,128]{%s%s}", (val < 0) ? "-" : "+", body);
        }
    }

    public static class intattr extends Base {
        public intattr(String nm) {super(nm);}
        public String format(double val) {return rich(val, Integer.toString(Math.abs((int)val)));}
    }

    public static class pmattr extends Base {
        public pmattr(String nm) {super(nm);}
        public String format(double val) {return rich(val, haven.Utils.odformat2(Math.abs(val) * 0.1, 1) + "%");}
    }

    public static class normattr extends Base {
        public normattr(String nm) {super(nm);}
        public String format(double val) {return rich(val, (Math.abs(val) * 100) + "%");}
    }

    public static class inormattr extends Base {
        public inormattr(String nm) {super(nm);}
        public String format(double val) {
            double d = (1.0 / (1.0 + val)) - 1.0;
            return rich(d, (Math.abs(d) * 100) + "%");
        }
    }
}
