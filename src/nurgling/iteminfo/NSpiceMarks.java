package nurgling.iteminfo;

import haven.*;
import nurgling.NConfig;
import nurgling.iteminfo.marks.SpiceMarks;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small icons on an inventory item showing its seasonings: salt, pepper and the two truffles. It uses
 * the same item overlay mechanism as the other marks ({@code WellMined}, the variety mark), and the
 * game's own ingredient icons, so no art is added.
 * <p>
 * The icons are scaled once and cached by resource name. A resource that is still loading is simply
 * retried on the next frame; one that does not exist is skipped, so a wrong path costs one icon, not
 * the others.
 */
public class NSpiceMarks extends ItemInfo.Tip implements GItem.OverlayInfo<Tex> {
    private static final int ICON = 12;
    private static final int GAP = 1;

    /** Loaded, scaled icons by resource name; {@link #MISSING} for ones that can never load. */
    private static final Map<String, BufferedImage> icons = new ConcurrentHashMap<>();
    private static final BufferedImage MISSING = TexI.mkbuf(new Coord(1, 1));

    private final List<SpiceMarks.Kind> kinds;
    private Tex composed = null;

    private NSpiceMarks(Owner owner, List<SpiceMarks.Kind> kinds) {
        super(owner);
        this.kinds = kinds;
    }

    /** The mark for an item's freshly built info, or null if it has no seasoning. */
    public static NSpiceMarks of(Owner owner, List<? extends ItemInfo> info) {
        List<String> lines = new ArrayList<>();
        for(ItemInfo ii : info) {
            if(ii instanceof ItemInfo.AdHoc)
                lines.add(((ItemInfo.AdHoc)ii).str.text);
        }
        List<SpiceMarks.Kind> kinds = SpiceMarks.of(lines);
        return kinds.isEmpty() ? null : new NSpiceMarks(owner, kinds);
    }

    private static BufferedImage icon(SpiceMarks.Kind kind) {
        BufferedImage ret = icons.get(kind.icon);
        if(ret != null)
            return ret;
        try {
            Resource res = Resource.remote().load(kind.icon).get();
            Resource.Image img = res.layer(Resource.imgc);
            ret = (img == null) ? MISSING : PUtils.convolvedown(img.img, UI.scale(new Coord(ICON, ICON)), CharWnd.iconfilter);
        } catch(Loading l) {
            return null;
        } catch(RuntimeException e) {
            ret = MISSING;
        }
        icons.put(kind.icon, ret);
        return ret;
    }

    /** All icons side by side, or null while any is still loading (or none could be loaded). */
    private Tex compose() {
        List<BufferedImage> parts = new ArrayList<>();
        for(SpiceMarks.Kind k : kinds) {
            BufferedImage ic = icon(k);
            if(ic == null)
                return null;
            if(ic != MISSING)
                parts.add(ic);
        }
        if(parts.isEmpty())
            return null;
        int h = 0, w = -UI.scale(GAP);
        for(BufferedImage p : parts) {
            h = Math.max(h, p.getHeight());
            w += p.getWidth() + UI.scale(GAP);
        }
        BufferedImage row = TexI.mkbuf(new Coord(w, h));
        java.awt.Graphics g = row.getGraphics();
        int x = 0;
        for(BufferedImage p : parts) {
            g.drawImage(p, x, (h - p.getHeight()) / 2, null);
            x += p.getWidth() + UI.scale(GAP);
        }
        g.dispose();
        return new TexI(row);
    }

    @Override
    public Tex overlay() {
        if(composed == null)
            composed = compose();
        return composed;
    }

    /** False until the icons are ready, which makes the item widget ask for the overlay again. */
    @Override
    public boolean tick(double dt) {
        return composed != null || (composed = compose()) != null;
    }

    @Override
    public void drawoverlay(GOut g, Tex data) {
        if(Boolean.FALSE.equals(NConfig.get(NConfig.Key.showSpiceMarks)))
            return;
        Tex t = (data != null) ? data : overlay();
        if(t != null)
            g.aimage(t, new Coord(g.sz().x, 0), 1, 0);
    }
}
