package nurgling.iteminfo.bonus;

import haven.res.ui.tt.attrmod.AttrMod;
import haven.res.ui.tt.attrmod.Entry;
import haven.res.ui.tt.attrmod.Mod;
import haven.res.ui.tt.attrmod.intattr;
import haven.res.ui.tt.attrmod.inormattr;
import haven.res.ui.tt.attrmod.normattr;
import haven.res.ui.tt.attrmod.pmattr;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bonus reader reads server-distributed tooltip classes by name and field. These tests pin the
 * structure of the <em>actual</em> classes in this tree (not fakes), so a resource update that changes
 * a field the reader depends on fails here instead of silently making totals incomplete.
 * <p>
 * The real {@code ISlots} cannot be instantiated without a running game (its static initialiser loads a
 * resource), so its structure is checked through reflection on the class, without initialising it.
 */
class ResourceClassStructureTest {
    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ResourceClassStructureTest.class.getClassLoader());
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for(Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch(NoSuchFieldException e) {
                /* superclass */
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    @Test
    void realIslotsHasTheFieldsTheReaderNeeds() throws Exception {
        Class<?> islots = load("haven.res.ui.tt.slots.ISlots");
        assertTrue(AttrBonusCollector.isInstalledGildingsClass(islots), "recognised as installed gildings");
        assertFalse(AttrBonusCollector.isUnknownGildingClassType(islots));
        assertTrue(Collection.class.isAssignableFrom(field(islots, "s").getType()), "ISlots.s is a collection");
        Class<?> sitem = load("haven.res.ui.tt.slots.ISlots$SItem");
        assertTrue(List.class.isAssignableFrom(field(sitem, "info").getType()), "SItem.info is a list");
        assertEquals(String.class, field(sitem, "name").getType());
    }

    @Test
    void realSlottedIsNotMistakenForInstalledGildings() throws Exception {
        Class<?> slotted = load("haven.res.ui.tt.slot.Slotted");
        assertFalse(AttrBonusCollector.isInstalledGildingsClass(slotted));
        assertFalse(AttrBonusCollector.isUnknownGildingClassType(slotted), "a gilding recipe is deliberately ignored, not unsupported");
    }

    @Test
    void realAttrModHasTheFieldsTheReaderNeeds() throws Exception {
        assertTrue(AttrBonusCollector.isAttrModClass(AttrMod.class));
        assertTrue(Collection.class.isAssignableFrom(field(AttrMod.class, "tab").getType()));
        assertEquals(double.class, field(Mod.class, "mod").getType());
        assertEquals(haven.res.ui.tt.attrmod.Attribute.class, field(Entry.class, "attr").getType());
        Method fmtvalue = Entry.class.getMethod("fmtvalue");
        assertEquals(String.class, fmtvalue.getReturnType());
        for(String m : new String[] {"name", "icon"})
            assertNotNull(haven.res.ui.tt.attrmod.Attribute.class.getMethod(m));
        assertEquals(String.class, haven.res.ui.tt.attrmod.Attribute.class.getMethod("format", double.class).getReturnType());
    }

    /** The real attribute classes with the resource lookups replaced, so their own {@code format} runs. */
    private static final class Named {
        static intattr flat(String n) {
            return new intattr(null) {
                public String name() {return n;}
                public BufferedImage icon() {return null;}
            };
        }

        static pmattr permille(String n) {
            return new pmattr(null) {
                public String name() {return n;}
                public BufferedImage icon() {return null;}
            };
        }

        static normattr normal(String n) {
            return new normattr(null) {
                public String name() {return n;}
                public BufferedImage icon() {return null;}
            };
        }

        static inormattr inverse(String n) {
            return new inormattr(null) {
                public String name() {return n;}
                public BufferedImage icon() {return null;}
            };
        }
    }

    private static AttrBonus only(haven.res.ui.tt.attrmod.Attribute attr, double v) {
        AttrMod mod = new AttrMod(null, Collections.<Entry>singletonList(new Mod(attr, v)));
        List<AttrBonus> read = AttrBonusCollector.fromAttrMod(mod);
        assertEquals(1, read.size());
        return read.get(0);
    }

    @Test
    void realAttributeClassesAreClassifiedAndFormattedByTheGamesOwnCode() {
        AttrBonus flat = only(Named.flat("Strength"), 12);
        assertEquals(AttrBonus.Kind.FLAT, flat.kind);
        assertEquals("+12", flat.text());

        AttrBonus pm = only(Named.permille("Speed"), 150);
        assertEquals(AttrBonus.Kind.PERMILLE, pm.kind);
        assertEquals("+15%", pm.text());

        AttrBonus norm = only(Named.normal("Reach"), 0.075);
        assertEquals(AttrBonus.Kind.NORMAL, norm.kind);
        assertEquals("+7.5%", norm.text());

        AttrBonus inv = only(Named.inverse("Cooldown"), 1.0);
        assertEquals(AttrBonus.Kind.INVERSE_NORMAL, inv.kind);
        assertEquals("-50%", inv.text());
        assertTrue(inv.beneficial());
    }

    @Test
    void readingRealStructuresReportsNothingUnsupported() {
        AttrMod mod = new AttrMod(null, Collections.<Entry>singletonList(new Mod(Named.flat("Strength"), 3)));
        AttrBonusCollector.Report report = new AttrBonusCollector.Report();
        assertEquals(1, AttrBonusCollector.fromAttrMod(mod, report).size());
        assertFalse(report.unsupported(), report.reasons().toString());
    }
}
