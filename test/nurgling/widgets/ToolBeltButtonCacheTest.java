package nurgling.widgets;

import haven.IButton;
import nurgling.equipment.EquipmentPreset;
import nurgling.scenarios.Scenario;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolBeltButtonCacheTest {
    /** Stands in for the real buttons: counts disposal, and needs no icon art. */
    private static final class Probe extends IButton {
        int disposed = 0;

        Probe() {
            super(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB),
                  new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), null);
        }

        @Override
        public void dispose() {
            disposed++;
            super.dispose();
        }
    }

    /** The cache with the global custom-icon store and real buttons replaced. */
    private static final class TestCache extends ToolBeltButtonCache {
        final Map<String, BufferedImage> icons = new HashMap<>();

        @Override
        protected BufferedImage customImage(String iconId) {
            return (iconId == null) ? null : icons.get(iconId);
        }

        @Override
        protected IButton makeScenario(Scenario scenario) {
            return new Probe();
        }

        @Override
        protected IButton makePreset(EquipmentPreset preset) {
            return new Probe();
        }
    }

    private static Probe probe(IButton b) {
        return (Probe) b;
    }

    @Test
    void drawingTheSameSlotRepeatedlyBuildsOnce() {
        TestCache cache = new TestCache();
        Scenario s = new Scenario(1, "Haul logs");
        IButton first = cache.scenario(3, s);
        for(int frame = 0; frame < 1000; frame++)
            assertSame(first, cache.scenario(3, s));
        assertEquals(1, cache.builds());
        assertEquals(0, probe(first).disposed, "a reused button must stay usable");

        EquipmentPreset p = new EquipmentPreset("Mining kit");
        IButton firstPreset = cache.preset(4, p);
        for(int frame = 0; frame < 1000; frame++)
            assertSame(firstPreset, cache.preset(4, p));
        assertEquals(2, cache.builds());
    }

    @Test
    void renamingAScenarioRebuildsItAndFreesTheOldButton() {
        TestCache cache = new TestCache();
        Scenario s = new Scenario(1, "Haul logs");
        IButton before = cache.scenario(0, s);
        s.setName("Haul boards");
        IButton after = cache.scenario(0, s);
        assertNotSame(before, after);
        assertEquals(1, probe(before).disposed);
        assertEquals(0, probe(after).disposed);
        assertSame(after, cache.scenario(0, s));
        assertEquals(2, cache.builds());
    }

    @Test
    void renamingAPresetRebuildsIt() {
        TestCache cache = new TestCache();
        EquipmentPreset p = new EquipmentPreset("Mining kit");
        IButton before = cache.preset(0, p);
        p.setName("Farming kit");
        assertNotSame(before, cache.preset(0, p));
        assertEquals(1, probe(before).disposed);
    }

    @Test
    void changingTheIconRebuilds() {
        TestCache cache = new TestCache();
        BufferedImage a = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        cache.icons.put("a", a);
        cache.icons.put("b", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        Scenario s = new Scenario(1, "Haul logs");

        IButton text = cache.scenario(0, s);
        s.setCustomIconId("a");
        IButton withA = cache.scenario(0, s);
        assertNotSame(text, withA, "switching from the generated icon to a custom one");
        assertSame(withA, cache.scenario(0, s));

        s.setCustomIconId("b");
        IButton withB = cache.scenario(0, s);
        assertNotSame(withA, withB, "switching to another custom icon");

        s.setCustomIconId(null);
        assertNotSame(withB, cache.scenario(0, s), "going back to the generated icon");
        assertEquals(3, probeDisposed(text, withA, withB));
    }

    @Test
    void aRegeneratedCustomIconImageRebuilds() {
        TestCache cache = new TestCache();
        cache.icons.put("a", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        Scenario s = new Scenario(1, "Haul logs");
        s.setCustomIconId("a");
        IButton before = cache.scenario(0, s);
        assertSame(before, cache.scenario(0, s));

        /* CustomIcon.invalidateCache() makes the next getImage() produce a new image. */
        cache.icons.put("a", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        IButton after = cache.scenario(0, s);
        assertNotSame(before, after);
        assertEquals(1, probe(before).disposed);
    }

    @Test
    void aDeletedCustomIconFallsBackToTheGeneratedOne() {
        TestCache cache = new TestCache();
        cache.icons.put("a", new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        Scenario s = new Scenario(1, "Haul logs");
        s.setCustomIconId("a");
        IButton custom = cache.scenario(0, s);
        cache.icons.remove("a");
        assertNotSame(custom, cache.scenario(0, s));
    }

    @Test
    void aReloadedCopyWithTheSameNameIsADifferentSource() {
        TestCache cache = new TestCache();
        IButton before = cache.scenario(0, new Scenario(1, "Haul logs"));
        IButton after = cache.scenario(0, new Scenario(1, "Haul logs"));
        assertNotSame(before, after);
        assertEquals(1, probe(before).disposed);
    }

    @Test
    void aDeletedSourceEvictsAndFrees() {
        TestCache cache = new TestCache();
        IButton b = cache.scenario(2, new Scenario(1, "Haul logs"));
        assertEquals(1, cache.size());
        assertNull(cache.scenario(2, null));
        assertEquals(0, cache.size());
        assertEquals(1, probe(b).disposed);

        IButton p = cache.preset(2, new EquipmentPreset("Mining kit"));
        assertNull(cache.preset(2, null));
        assertEquals(1, probe(p).disposed);
        assertEquals(0, cache.size());
    }

    @Test
    void evictingAnEmptySlotIsHarmlessAndFreesOnce() {
        TestCache cache = new TestCache();
        cache.evict(5);
        IButton b = cache.scenario(5, new Scenario(1, "Haul logs"));
        cache.evict(5);
        cache.evict(5);
        assertEquals(1, probe(b).disposed);
    }

    @Test
    void slotsAreIndependent() {
        TestCache cache = new TestCache();
        Scenario s = new Scenario(1, "Haul logs");
        EquipmentPreset p = new EquipmentPreset("Mining kit");
        IButton a = cache.scenario(0, s);
        IButton b = cache.preset(1, p);
        cache.evict(0);
        assertEquals(1, probe(a).disposed);
        assertEquals(0, probe(b).disposed);
        assertSame(b, cache.preset(1, p));
    }

    @Test
    void aSlotChangingKindRebuilds() {
        TestCache cache = new TestCache();
        IButton scenario = cache.scenario(0, new Scenario(1, "Haul logs"));
        IButton preset = cache.preset(0, new EquipmentPreset("Haul logs"));
        assertNotSame(scenario, preset);
        assertEquals(1, probe(scenario).disposed);
        assertEquals(1, cache.size());
    }

    @Test
    void clearFreesEverythingOnce() {
        TestCache cache = new TestCache();
        IButton a = cache.scenario(0, new Scenario(1, "Haul logs"));
        IButton b = cache.preset(1, new EquipmentPreset("Mining kit"));
        cache.clear();
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(1, probe(a).disposed);
        assertEquals(1, probe(b).disposed);
    }

    @Test
    void cachesOfDifferentBeltsShareNothing() {
        TestCache one = new TestCache(), two = new TestCache();
        Scenario s = new Scenario(1, "Haul logs");
        IButton a = one.scenario(0, s);
        IButton b = two.scenario(0, s);
        assertNotSame(a, b);
        one.clear();
        assertEquals(0, probe(b).disposed, "clearing one belt (one session) must not free another's buttons");
        assertSame(b, two.scenario(0, s));
    }

    @Test
    void realButtonsAreBuiltOnceAndKeepTheirSource() {
        ToolBeltButtonCache cache = new ToolBeltButtonCache();
        Scenario s = new Scenario(1, "Haul logs");
        EquipmentPreset p = new EquipmentPreset("Mining kit");
        IButton sb = cache.scenario(0, s);
        IButton pb = cache.preset(1, p);
        assertTrue(sb instanceof NScenarioButton);
        assertTrue(pb instanceof NEquipmentPresetButton);
        assertSame(s, ((NScenarioButton) sb).getScenario());
        assertSame(p, ((NEquipmentPresetButton) pb).getPreset());
        for(int frame = 0; frame < 100; frame++) {
            assertSame(sb, cache.scenario(0, s));
            assertSame(pb, cache.preset(1, p));
        }
        assertEquals(2, cache.builds());
    }

    private static int probeDisposed(IButton... buttons) {
        int n = 0;
        for(IButton b : buttons)
            n += probe(b).disposed;
        return n;
    }
}
