package nurgling.iteminfo.marks;

import haven.ItemInfo;
import nurgling.iteminfo.NSpiceMarks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpiceMarksTest {
    @Test
    void everySeasoningTheServerNamesIsRecognised() {
        assertEquals(Arrays.asList(SpiceMarks.Kind.SALT), SpiceMarks.of(Arrays.asList("Salted")));
        assertEquals(Arrays.asList(SpiceMarks.Kind.PEPPER), SpiceMarks.of(Arrays.asList("Peppered")));
        assertEquals(Arrays.asList(SpiceMarks.Kind.WHITE_TRUFFLE), SpiceMarks.of(Arrays.asList("White-truffled")));
        assertEquals(Arrays.asList(SpiceMarks.Kind.BLACK_TRUFFLE), SpiceMarks.of(Arrays.asList("Black-truffled")));
    }

    @Test
    void marksComeOutInDisplayOrderWhateverOrderTheLinesArrivedIn() {
        List<SpiceMarks.Kind> kinds = SpiceMarks.of(Arrays.asList("Black-truffled", "Peppered", "Quality", "Salted"));
        assertEquals(Arrays.asList(SpiceMarks.Kind.SALT, SpiceMarks.Kind.PEPPER, SpiceMarks.Kind.BLACK_TRUFFLE), kinds);
    }

    @Test
    void nearMissesAndOtherLinesAreNotSeasoning() {
        assertTrue(SpiceMarks.of(Arrays.asList("salted", "Salted meat", "Well mined", "Peppered ", "Truffle")).isEmpty());
        assertTrue(SpiceMarks.of(Collections.<String>emptyList()).isEmpty());
        assertTrue(SpiceMarks.of(null).isEmpty());
    }

    @Test
    void aRepeatedLineIsOneMark() {
        assertEquals(1, SpiceMarks.of(Arrays.asList("Salted", "Salted")).size());
    }

    @Test
    void iconsAreTheGamesOwnIngredientResources() {
        for(SpiceMarks.Kind k : SpiceMarks.Kind.values())
            assertTrue(k.icon.startsWith("gfx/invobjs/"), k.icon);
        assertEquals("gfx/invobjs/salt", SpiceMarks.Kind.SALT.icon);
    }

    @Test
    void overlayExistsOnlyForSeasonedItems() {
        try {
            Class.forName("haven.Text");
        } catch(ClassNotFoundException | LinkageError e) {
            Assumptions.abort("game resource jars are not on the test classpath: " + e);
        }
        List<ItemInfo> plain = Arrays.<ItemInfo>asList(new ItemInfo.AdHoc(null, "Well mined"));
        assertNull(NSpiceMarks.of(null, plain));
        List<ItemInfo> seasoned = Arrays.<ItemInfo>asList(new ItemInfo.AdHoc(null, "Salted"), new ItemInfo.AdHoc(null, "Peppered"));
        assertNotNull(NSpiceMarks.of(null, seasoned));
    }
}
