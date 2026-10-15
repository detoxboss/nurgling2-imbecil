package nurgling.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipStatsTest {
    @Test
    void subtletyIsIntelligenceTimesStealth() {
        assertEquals(Integer.valueOf(1200), EquipStats.subtlety(40, 30));
    }

    @Test
    void aMissingAttributeIsNotShownAsZero() {
        assertNull(EquipStats.subtlety(null, 30));
        assertNull(EquipStats.subtlety(40, null));
        assertNull(EquipStats.subtlety(null, null));
    }

    @Test
    void aGenuineZeroStillShows() {
        assertEquals(Integer.valueOf(0), EquipStats.subtlety(40, 0));
    }

    private static EquipStats.Armored slot(Object id, boolean intact, int hard, int soft) {
        return new EquipStats.Armored() {
            public Object id() {return id;}
            public boolean intact() {return intact;}
            public int hard() {return hard;}
            public int soft() {return soft;}
        };
    }

    @Test
    void anItemInSeveralSlotsIsCountedOnce() {
        Object plate = new Object(), helm = new Object();
        int[] total = EquipStats.armor(java.util.Arrays.asList(
            slot(plate, true, 10, 4), slot(plate, true, 10, 4), slot(helm, true, 3, 1)));
        assertArrayEquals(new int[] {13, 5}, total);
    }

    @Test
    void separateItemsWithEqualValuesAreBothCounted() {
        int[] total = EquipStats.armor(java.util.Arrays.asList(
            slot(new Object(), true, 5, 2), slot(new Object(), true, 5, 2)));
        assertArrayEquals(new int[] {10, 4}, total);
    }

    @Test
    void brokenItemsGiveNoArmor() {
        int[] total = EquipStats.armor(java.util.Arrays.asList(
            slot(new Object(), false, 9, 9), slot(new Object(), true, 1, 2)));
        assertArrayEquals(new int[] {1, 2}, total);
        assertArrayEquals(new int[] {0, 0}, EquipStats.armor(java.util.Collections.<EquipStats.Armored>emptyList()));
    }
}