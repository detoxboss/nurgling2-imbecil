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
}
