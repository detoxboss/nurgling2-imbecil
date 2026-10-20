package nurgling.watch;

import haven.Composite;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.res.ui.obj.buddy.Buddy;
import nurgling.NGameUI;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads one session's loaded gobs and turns each live player into a {@link ThreatTracker.Sighting}.
 * Everything is read through the {@link NGameUI} it is given - never through the calling thread's
 * "current" session - so a lookout in a background session reads its own world.
 */
public final class GobScanner {
    private static final NAlias PLAYER = new NAlias("borka");
    private static final NAlias NOT_LIVE = new NAlias("dead", "manneq", "skel");

    public static final class Scan {
        public final List<ThreatTracker.Sighting> sightings = new ArrayList<>();
        /** Live players seen this scan, including ones not yet classifiable or not alertable. */
        public int playersInView;
        /** Players whose model, buddy record or surrounding map was still loading. */
        public int pending;
    }

    private GobScanner() {
    }

    public static Scan scan(NGameUI gui) {
        Scan out = new Scan();
        if (gui == null || gui.map == null || gui.map.glob == null || gui.map.glob.oc == null)
            return out;

        // The map-loaded check is the same one the local alarm uses to avoid false alarms while a
        // character is still logging in; until it passes, an unmemorized player is only "pending".
        boolean mapLoaded = gui.alarmWdg != null && gui.alarmWdg.isMapFullyLoaded();
        long self = gui.map.plgob;

        List<Gob> gobs = new ArrayList<>();
        synchronized (gui.map.glob.oc) {
            for (Gob g : gui.map.glob.oc)
                gobs.add(g);
        }
        for (Gob gob : gobs) {
            String name = gob.ngob == null ? null : gob.ngob.name;
            if (name == null || !NParser.checkName(name, PLAYER))
                continue;

            WatchClassifier.Observation o = observe(gui, gob, self, mapLoaded);
            WatchClassifier.Verdict v = WatchClassifier.classify(o);
            if (v == WatchClassifier.Verdict.IGNORE)
                continue;
            out.playersInView++;
            if (v == WatchClassifier.Verdict.PENDING) {
                out.pending++;
                continue;
            }
            WatchCategory category = v.category();
            if (category == null)
                continue;
            out.sightings.add(new ThreatTracker.Sighting(gob.id, category, memorizedName(gui, gob), coords(gui, gob)));
        }
        return out;
    }

    private static WatchClassifier.Observation observe(NGameUI gui, Gob gob, long self, boolean mapLoaded) {
        WatchClassifier.Observation o = new WatchClassifier.Observation();
        String pose = gob.pose();
        boolean notLive = gob.id == self
                || gui.map.glob.party.memb.containsKey(gob.id)
                || (pose != null && NParser.checkName(pose, NOT_LIVE));
        o.live = !notLive;
        Composite c = gob.getattr(Composite.class);
        o.modelLoaded = pose != null && c != null && c.comp != null && !c.comp.cmod.isEmpty();
        o.mapLoaded = mapLoaded;
        o.buddyListLoaded = gui.buddies != null;

        Buddy attr = gob.getattr(Buddy.class);
        o.hasBuddyAttr = attr != null;
        if (attr != null && gui.buddies != null) {
            // Look the record up directly: Buddy.b is only filled in when the nameplate is drawn,
            // and a background session never draws.
            haven.BuddyWnd.Buddy rec = gui.buddies.find(attr.id);
            o.group = rec == null ? null : rec.group;
        }
        return o;
    }

    private static String memorizedName(NGameUI gui, Gob gob) {
        Buddy attr = gob.getattr(Buddy.class);
        if (attr == null || gui.buddies == null)
            return null;
        haven.BuddyWnd.Buddy rec = gui.buddies.find(attr.id);
        return rec == null ? null : rec.name;
    }

    /** World coordinates in the same space as the "show player world coordinates" HUD; null when unknown. */
    private static String coords(NGameUI gui, Gob gob) {
        try {
            if (gui.mmap == null || gui.mmap.sessloc == null || gob.rc == null)
                return null;
            Coord2d world = gob.rc.add(new Coord2d(gui.mmap.sessloc.tc).mul(MCache.tilesz));
            return String.format("(%.0f, %.0f)", world.x, world.y);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
