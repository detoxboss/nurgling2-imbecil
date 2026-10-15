package nurgling.iteminfo.bonus;

import haven.ItemInfo;
import haven.Resource;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;

/**
 * Reads typed {@link AttrBonus}es out of item tooltip info.
 * <p>
 * The {@code ui/tt/attrmod}, {@code ui/tt/slots} and {@code ui/tt/slots-alt} tooltip classes are
 * distributed by the server, and when the local copy's {@code @FromResource} version does not match
 * the server's, the server's copy is loaded from a different class loader. {@code instanceof} then
 * fails against the local classes, so these classes are recognised by name and read by reflection
 * (the same approach {@code NTooltip} uses). {@link haven.Loading} is always propagated so callers
 * can retry.
 * <p>
 * <b>Recognised but unreadable is not "no bonuses".</b> When a class is recognised as an attribute
 * modifier or a gilding list but a field or method the reader relies on is missing (the server changed
 * the structure) or has an unexpected shape, that is recorded in the {@link Report} passed to the
 * method, so a caller can say its totals are incomplete. Without a report such failures are only
 * logged once per cause, which is what the tooltip code (a display, not a total) uses.
 * <p>
 * Only <em>installed</em> gildings ({@code ISlots}) and an item's own {@code AttrMod} are read.
 * Gilding <em>recipes</em> ({@code Slotted}) describe what an ingredient would add and are never
 * counted as bonuses the wearer has.
 */
public final class AttrBonusCollector {
    private AttrBonusCollector() {}

    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Report an unexpected failure once per {@code id}; repeated tooltip rebuilds must not spam. */
    static void warnOnce(String id, String message, Throwable t) {
        if(warned.add(id)) {
            System.err.println("[AttrBonus] " + message + ((t == null) ? "" : (": " + t)));
        }
    }

    /**
     * What an extraction could not understand. One report may be shared by several calls (one item's
     * direct and gilding bonuses); it only ever grows.
     */
    public static final class Report {
        private final Set<String> reasons = new LinkedHashSet<>();

        void unsupported(String reason) {
            reasons.add(reason);
            warnOnce("unsupported:" + reason, "Unsupported item info structure, bonuses may be incomplete (" + reason + ")", null);
        }

        /** True if any recognised structure could not be read completely. */
        public boolean unsupported() {
            return !reasons.isEmpty();
        }

        /** What was not understood, for diagnostics. */
        public Set<String> reasons() {
            return java.util.Collections.unmodifiableSet(reasons);
        }
    }

    public static boolean isAttrMod(Object info) {
        return info != null && isAttrModClass(info.getClass());
    }

    static boolean isAttrModClass(Class<?> c) {
        return c.getSimpleName().equals("AttrMod") && c.getName().contains("attrmod");
    }

    /** Installed gildings: {@code slots.ISlots} and the alternative {@code slots_alt.ISlots}. */
    public static boolean isInstalledGildings(Object info) {
        return info != null && isInstalledGildingsClass(info.getClass());
    }

    static boolean isInstalledGildingsClass(Class<?> c) {
        return c.getSimpleName().equals("ISlots") && c.getName().contains("slots");
    }

    /**
     * Some other tooltip class from a gilding ("slots") package. It is not read, and might have been the
     * installed gildings under a name this reader does not know.
     */
    static boolean isUnknownGildingClass(Object info) {
        return info != null && isUnknownGildingClassType(info.getClass());
    }

    static boolean isUnknownGildingClassType(Class<?> c) {
        return !isInstalledGildingsClass(c) && c.getName().contains(".slots");
    }

    /** One installed gilding and what it contributes. */
    public static final class InstalledGilding {
        public final String name;
        public final List<AttrBonus> bonuses;

        InstalledGilding(String name, List<AttrBonus> bonuses) {
            this.name = name;
            this.bonuses = bonuses;
        }
    }

    /** An item's own modifiers: its top-level {@code AttrMod} tips. */
    public static List<AttrBonus> direct(List<? extends ItemInfo> info) {
        return direct(info, null);
    }

    public static List<AttrBonus> direct(List<? extends ItemInfo> info, Report report) {
        List<AttrBonus> ret = new ArrayList<>();
        if(info == null)
            return ret;
        for(ItemInfo ii : info) {
            if(isAttrMod(ii))
                ret.addAll(fromAttrMod(ii, report));
        }
        return ret;
    }

    /** Every installed gilding of the item, each with its own modifiers. */
    public static List<InstalledGilding> installedGildings(List<? extends ItemInfo> info) {
        return installedGildings(info, null);
    }

    public static List<InstalledGilding> installedGildings(List<? extends ItemInfo> info, Report report) {
        List<InstalledGilding> ret = new ArrayList<>();
        if(info == null)
            return ret;
        for(ItemInfo ii : info) {
            if(isUnknownGildingClass(ii)) {
                if(report != null)
                    report.unsupported("unknown gilding class " + ii.getClass().getName());
                continue;
            }
            if(!isInstalledGildings(ii))
                continue;
            Object slots = required(ii, "s", report);
            if(!(slots instanceof Collection)) {
                if(report != null)
                    report.unsupported(ii.getClass().getName() + ".s is not a collection");
                continue;
            }
            for(Object slot : (Collection<?>)slots) {
                if(slot == null)
                    continue;
                Object sinfo = required(slot, "info", report);
                List<AttrBonus> bonuses = new ArrayList<>();
                if(sinfo instanceof Collection) {
                    for(Object si : (Collection<?>)sinfo) {
                        if(isAttrMod(si))
                            bonuses.addAll(fromAttrMod(si, report));
                    }
                } else if(report != null) {
                    report.unsupported(slot.getClass().getName() + ".info is not a collection");
                }
                Object nm = field(slot, "name");
                ret.add(new InstalledGilding((nm instanceof String) ? (String)nm : null, bonuses));
            }
        }
        return ret;
    }

    /** All modifiers of installed gildings, flattened. */
    public static List<AttrBonus> gildings(List<? extends ItemInfo> info) {
        return gildings(info, null);
    }

    public static List<AttrBonus> gildings(List<? extends ItemInfo> info, Report report) {
        List<AttrBonus> ret = new ArrayList<>();
        for(InstalledGilding g : installedGildings(info, report))
            ret.addAll(g.bonuses);
        return ret;
    }

    /** Typed entries of one {@code AttrMod} tip. */
    public static List<AttrBonus> fromAttrMod(Object attrMod) {
        return fromAttrMod(attrMod, null);
    }

    public static List<AttrBonus> fromAttrMod(Object attrMod, Report report) {
        List<AttrBonus> ret = new ArrayList<>();
        Object tab = required(attrMod, "tab", report);
        if(!(tab instanceof Collection)) {
            if(report != null)
                report.unsupported(attrMod.getClass().getName() + ".tab is not a collection");
            return ret;
        }
        for(Object entry : (Collection<?>)tab) {
            AttrBonus b = fromEntry(entry, report);
            if(b != null)
                ret.add(b);
        }
        return ret;
    }

    private static AttrBonus fromEntry(Object entry, Report report) {
        if(entry == null)
            return null;
        Object attr = required(entry, "attr", report);
        if(attr == null)
            return null;
        String name = (String)call(attr, "name", report);
        if(name == null) {
            if(report != null)
                report.unsupported(attr.getClass().getName() + ".name() gave nothing");
            return null;
        }
        BufferedImage icon = (BufferedImage)call(attr, "icon", null);
        String key = attrKey(attr, name);

        Field modField = findField(entry.getClass(), "mod");
        if(modField != null && modField.getType() == double.class) {
            double value;
            try {
                value = modField.getDouble(entry);
            } catch(IllegalAccessException e) {
                warnOnce("mod:" + entry.getClass().getName(), "Cannot read Mod.mod on " + entry.getClass().getName(), e);
                if(report != null)
                    report.unsupported("cannot read " + entry.getClass().getName() + ".mod");
                return null;
            }
            return new AttrBonus(key, name, kindOf(attr.getClass()), value, icon, formatterOf(attr));
        }
        Object text = call(entry, "fmtvalue", report);
        if(text instanceof String) {
            String plain = AttrBonus.plain((String)text);
            if(!plain.isEmpty())
                return AttrBonus.text(key, name, icon, plain);
        }
        if(report != null)
            report.unsupported("entry " + entry.getClass().getName() + " has neither a numeric mod nor readable text");
        return null;
    }

    /** Resource name when the attribute is a {@code resattr}; otherwise class plus display name. */
    static String attrKey(Object attr, String name) {
        Object res = field(attr, "res");
        if(res instanceof Resource)
            return ((Resource)res).name;
        return attr.getClass().getName() + ":" + name;
    }

    static AttrBonus.Kind kindOf(Class<?> attrClass) {
        for(Class<?> c = attrClass; (c != null) && (c != Object.class); c = c.getSuperclass()) {
            switch(c.getSimpleName()) {
            case "intattr":
                return AttrBonus.Kind.FLAT;
            case "pmattr":
                return AttrBonus.Kind.PERMILLE;
            case "inormattr":
                return AttrBonus.Kind.INVERSE_NORMAL;
            case "normattr":
                return AttrBonus.Kind.NORMAL;
            }
        }
        return AttrBonus.Kind.OTHER;
    }

    private static DoubleFunction<String> formatterOf(Object attr) {
        final Method m;
        try {
            m = attr.getClass().getMethod("format", double.class);
        } catch(NoSuchMethodException e) {
            return null;
        }
        return (v) -> {
            try {
                Object r = m.invoke(attr, v);
                return (r instanceof String) ? (String)r : null;
            } catch(InvocationTargetException e) {
                if(e.getCause() instanceof RuntimeException)
                    throw (RuntimeException)e.getCause();
                throw new IllegalStateException(e.getCause());
            } catch(IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static Object call(Object o, String method, Report report) {
        try {
            Method m = o.getClass().getMethod(method);
            return m.invoke(o);
        } catch(NoSuchMethodException | IllegalAccessException e) {
            warnOnce("call:" + o.getClass().getName() + "." + method, "Cannot call " + method + " on " + o.getClass().getName(), e);
            if(report != null)
                report.unsupported("no readable " + o.getClass().getName() + "." + method + "()");
            return null;
        } catch(InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException)
                throw (RuntimeException)e.getCause();
            warnOnce("callx:" + o.getClass().getName() + "." + method, method + " failed on " + o.getClass().getName(), e.getCause());
            if(report != null)
                report.unsupported(method + "() failed on " + o.getClass().getName());
            return null;
        }
    }

    private static Field findField(Class<?> c, String name) {
        for(; (c != null) && (c != Object.class); c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch(NoSuchFieldException e) {
                /* try the superclass */
            }
        }
        return null;
    }

    /** A field that may legitimately be absent. */
    private static Object field(Object o, String name) {
        Field f = findField(o.getClass(), name);
        if(f == null)
            return null;
        try {
            return f.get(o);
        } catch(IllegalAccessException e) {
            warnOnce("field:" + o.getClass().getName() + "." + name, "Cannot read " + name + " on " + o.getClass().getName(), e);
            return null;
        }
    }

    /**
     * A field the recognised structure must have. If the class has no such field (or it cannot be read)
     * the structure is not the one this reader knows, which is recorded as unsupported. A field that
     * exists but holds {@code null} is simply null.
     */
    private static Object required(Object o, String name, Report report) {
        Field f = findField(o.getClass(), name);
        if(f == null) {
            warnOnce("nofield:" + o.getClass().getName() + "." + name, "No field " + name + " on " + o.getClass().getName(), null);
            if(report != null)
                report.unsupported("no field " + o.getClass().getName() + "." + name);
            return null;
        }
        try {
            return f.get(o);
        } catch(IllegalAccessException e) {
            warnOnce("field:" + o.getClass().getName() + "." + name, "Cannot read " + name + " on " + o.getClass().getName(), e);
            if(report != null)
                report.unsupported("cannot read " + o.getClass().getName() + "." + name);
            return null;
        }
    }
}
