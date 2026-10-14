package nurgling.iteminfo.bonus;

import haven.GItem;
import haven.ItemInfo;
import haven.Loading;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Totals the attribute bonuses of everything a character currently wears: each item's own
 * {@code AttrMod} plus its installed gildings.
 * <p>
 * Items are de-duplicated by identity, so a two-handed weapon or any other item that occupies
 * several slots is counted once. An item whose tooltip data has not arrived (or is still
 * {@link Loading}) is reported as pending rather than treated as contributing nothing; callers must
 * not present a result with {@link Result#pending} &gt; 0 as final.
 */
public final class EquipmentBonuses {
    private EquipmentBonuses() {}

    /** What the calculation needs to know about one equipped item. */
    public interface Source {
        /** Identity of the item; two sources with the same identity are the same item. */
        Object id();

        /** True once the server has sent this item's tooltip data. */
        boolean hasInfo();

        /** Increases whenever the item's tooltip data changed. */
        int infoSeq();

        /** May throw {@link Loading}. */
        List<? extends ItemInfo> info();
    }

    public static final class Result {
        /** Net totals in display order; attributes that sum to zero are omitted. */
        public final List<AttrBonus> totals;
        public final int items;
        public final int pending;

        Result(List<AttrBonus> totals, int items, int pending) {
            this.totals = totals;
            this.items = items;
            this.pending = pending;
        }

        public boolean complete() {
            return pending == 0;
        }
    }

    public static Result compute(Collection<? extends Source> sources) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<AttrBonus> all = new ArrayList<>();
        int items = 0, pending = 0;
        for(Source s : sources) {
            if(s == null || seen.put(s.id(), Boolean.TRUE) != null)
                continue;
            items++;
            if(!s.hasInfo()) {
                pending++;
                continue;
            }
            try {
                List<? extends ItemInfo> info = s.info();
                all.addAll(AttrBonusCollector.direct(info));
                all.addAll(AttrBonusCollector.gildings(info));
            } catch(Loading l) {
                pending++;
            }
        }
        List<AttrBonus> totals = new ArrayList<>();
        for(AttrBonus b : AttrBonus.sum(all)) {
            if(!b.isZero())
                totals.add(b);
        }
        totals.sort(AttrOrder.COMPARATOR);
        return new Result(totals, items, pending);
    }

    /**
     * Cheap change detector: differs whenever an item was added, removed or replaced, or any item's
     * tooltip data changed.
     */
    public static long signature(Collection<? extends Source> sources) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long h = 0;
        for(Source s : sources) {
            if(s == null || seen.put(s.id(), Boolean.TRUE) != null)
                continue;
            long x = System.identityHashCode(s.id());
            x = 31 * x + s.infoSeq();
            x = 31 * x + (s.hasInfo() ? 1 : 0);
            /* summed, so the order the slots are visited in does not matter */
            h += (x + 1) * 0x9E3779B97F4A7C15L;
        }
        return h;
    }

    /** Adapter over a live game item. */
    public static Source of(GItem item) {
        return new Source() {
            public Object id() {return(item);}
            public boolean hasInfo() {return(item.infoseq > 0);}
            public int infoSeq() {return(item.infoseq);}
            public List<? extends ItemInfo> info() {return(item.info());}
        };
    }
}
