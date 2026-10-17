package nurgling.iteminfo.bonus;

import haven.Utils;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One attribute modifier read from an {@code ui/tt/attrmod} entry, kept as typed data instead of
 * rendered tooltip text.
 * <p>
 * Identity is {@link #key} (the attribute resource name when the game supplies one), never the
 * localized {@link #name}. Two bonuses only merge when both key <em>and</em> {@link Kind} match, so
 * a flat {@code +10} can never be folded into a {@code +10%}. Merging adds the raw game values,
 * because that is how the game aggregates the same attribute.
 */
public final class AttrBonus {
    /**
     * How the raw {@code mod} value is interpreted. Mirrors the attribute classes shipped in
     * {@code ui/tt/attrmod}: {@code intattr}, {@code pmattr}, {@code normattr}, {@code inormattr}.
     */
    public enum Kind {
        /** Whole-number bonus (intattr): {@code +12}. */
        FLAT,
        /** Per-mille (pmattr): value {@code 150} reads as {@code +15%}. */
        PERMILLE,
        /** Fraction (normattr): value {@code 0.15} reads as {@code +15%}. */
        NORMAL,
        /** Inverse fraction (inormattr): shown as {@code 1/(1+v) - 1}. */
        INVERSE_NORMAL,
        /** Anything else, or a non-numeric entry. Carried for display, never summed. */
        OTHER
    }

    public final String key;
    public final String name;
    public final Kind kind;
    public final double value;
    /** Attribute icon; null when the attribute has none. */
    public final BufferedImage icon;
    private final DoubleFunction<String> formatter;
    /** Fixed display text for {@link Kind#OTHER} entries that carry no number. */
    private final String fixedText;

    public AttrBonus(String key, String name, Kind kind, double value, BufferedImage icon, DoubleFunction<String> formatter) {
        this(key, name, kind, value, icon, formatter, null);
    }

    private AttrBonus(String key, String name, Kind kind, double value, BufferedImage icon, DoubleFunction<String> formatter, String fixedText) {
        this.key = key;
        this.name = name;
        this.kind = kind;
        this.value = value;
        this.icon = icon;
        this.formatter = formatter;
        this.fixedText = fixedText;
    }

    /** A bonus the game only describes as text (for example a Transfer entry). Never merged. */
    public static AttrBonus text(String key, String name, BufferedImage icon, String text) {
        return new AttrBonus(key, name, Kind.OTHER, 0, icon, null, text);
    }

    /** Merge key: same attribute <em>and</em> same value semantics. */
    public String groupKey() {
        return key + "|" + kind;
    }

    public boolean mergeable() {
        return kind != Kind.OTHER && fixedText == null;
    }

    /** This bonus plus another one with the same {@link #groupKey()}. */
    public AttrBonus plus(AttrBonus o) {
        if(!mergeable() || !o.mergeable() || !groupKey().equals(o.groupKey()))
            throw new IllegalArgumentException("Cannot merge " + groupKey() + " with " + o.groupKey());
        return new AttrBonus(key, name, kind, value + o.value, (icon != null) ? icon : o.icon, (formatter != null) ? formatter : o.formatter);
    }

    /** Display text such as {@code +12} or {@code -3.5%}; colour markup already stripped. */
    public String text() {
        if(fixedText != null)
            return fixedText;
        if(formatter != null) {
            String s = null;
            try {
                s = plain(formatter.apply(value));
            } catch(RuntimeException e) {
                if(e instanceof haven.Loading)
                    throw e;
                AttrBonusCollector.warnOnce("format:" + key, "Attribute formatter failed for " + key, e);
            }
            if(s != null && !s.isEmpty())
                return s;
        }
        return defaultText(kind, value);
    }

    /**
     * Whether the game would colour this as a buff. For {@link Kind#INVERSE_NORMAL} a positive raw
     * value is a reduction, which the game shows as a buff.
     */
    public boolean beneficial() {
        if(fixedText != null)
            return !fixedText.startsWith("-");
        return (kind == Kind.INVERSE_NORMAL) ? (value > 0) : (value >= 0);
    }

    /** Add up mergeable bonuses by {@link #groupKey()}; unmergeable ones are kept as they are. */
    public static List<AttrBonus> sum(Iterable<AttrBonus> bonuses) {
        Map<String, AttrBonus> merged = new LinkedHashMap<>();
        List<AttrBonus> loose = new ArrayList<>();
        for(AttrBonus b : bonuses) {
            if(!b.mergeable()) {
                loose.add(b);
                continue;
            }
            AttrBonus prev = merged.get(b.groupKey());
            merged.put(b.groupKey(), (prev == null) ? b : prev.plus(b));
        }
        List<AttrBonus> ret = new ArrayList<>(merged.values());
        ret.addAll(loose);
        return ret;
    }

    /** Whether a summed bonus nets to nothing and should not be listed. */
    public boolean isZero() {
        return mergeable() && value == 0;
    }

    @Override
    public String toString() {
        return key + " " + kind + " " + value;
    }

    private static final Pattern COLORED = Pattern.compile("\\$col\\[[^\\]]*\\]\\{([^}]*)\\}");

    /** Strip RichText colour markup, leaving the plain value text. */
    static String plain(String rich) {
        if(rich == null)
            return null;
        Matcher m = COLORED.matcher(rich);
        StringBuilder sb = new StringBuilder();
        while(m.find())
            sb.append(m.group(1));
        return (sb.length() > 0) ? sb.toString() : rich;
    }

    /** Same shapes the {@code ui/tt/attrmod} attribute classes produce, used when the game's own formatter is unavailable. */
    static String defaultText(Kind kind, double v) {
        switch(kind) {
        case FLAT:
            return signed(v < 0, Integer.toString(Math.abs((int)v)));
        case PERMILLE:
            return signed(v < 0, (Math.abs(v) >= 10000) ? ("×" + Utils.odformat2(Math.abs(v) * 0.001, 1)) : (Utils.odformat2(Math.abs(v) * 0.1, 1) + "%"));
        case NORMAL:
            return signed(v < 0, (Math.abs(v) >= 10) ? ("×" + Utils.odformat2(Math.abs(v), 1)) : (Utils.odformat2(Math.abs(v) * 100, 1) + "%"));
        case INVERSE_NORMAL: {
            double d = (1.0 / (1.0 + v)) - 1.0;
            return signed(d < 0, (Math.abs(d) >= 10) ? ("×" + Utils.odformat2(Math.abs(d), 1)) : (Utils.odformat2(Math.abs(d) * 100, 1) + "%"));
        }
        default:
            return Utils.odformat2(v, 2);
        }
    }

    private static String signed(boolean negative, String body) {
        return (negative ? "-" : "+") + body;
    }
}
