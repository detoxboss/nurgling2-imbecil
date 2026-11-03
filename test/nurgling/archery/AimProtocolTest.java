package nurgling.archery;

import haven.Coord;
import haven.Coord2d;
import nurgling.archery.AimProtocol.Kind;
import nurgling.archery.AimProtocol.Sender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AimProtocolTest {
    private static final Coord MC = Coord.of(1000, 2000);

    @Test
    void ground_click_from_map() {
        Object[] args = {Coord.of(10, 20), MC, 1, 0};
        assertEquals(Kind.MAP_GROUND_CLICK, AimProtocol.classify(Sender.MAP, "click", args));
        assertEquals(1, AimProtocol.mapButton(args));
        assertEquals(-1, AimProtocol.mapGobId(args));
    }

    @Test
    void gob_click_from_map_or_minimap_names_the_gob() {
        Object[] args = {Coord.of(10, 20), MC, 1, 0, 0, 12345, MC, 0, -1};
        assertEquals(Kind.MAP_GOB_CLICK, AimProtocol.classify(Sender.MAP, "click", args));
        assertEquals(12345, AimProtocol.mapGobId(args));
    }

    @Test
    void portrait_click_is_not_a_map_click_despite_the_name() {
        Object[] args = {12345, 1};
        assertEquals(Kind.FIGHT_PORTRAIT_CLICK, AimProtocol.classify(Sender.FIGHTVIEW, "click", args));
        assertEquals(Kind.OTHER, AimProtocol.classify(Sender.MAP, "click", args));
    }

    @Test
    void act_and_belt_are_actions_only_from_their_own_senders() {
        assertEquals(Kind.MENU_ACT, AimProtocol.classify(Sender.MENU, "act", new Object[] {"x"}));
        assertEquals(Kind.BELT_USE, AimProtocol.classify(Sender.GAMEUI, "belt", new Object[] {0, 1, 0}));
        assertEquals(Kind.OTHER, AimProtocol.classify(Sender.OTHER, "act", new Object[] {"x"}));
        assertEquals(Kind.OTHER, AimProtocol.classify(Sender.MAP, "place", new Object[] {MC, 0, 1, 0}));
        assertEquals(Kind.OTHER, AimProtocol.classify(Sender.MAP, null, null));
    }

    @Test
    void built_messages_classify_as_what_they_imitate() {
        Coord2d p = Coord2d.of(110.0, 220.0);
        Object[] g = AimProtocol.groundClick(p, 1);
        assertEquals(Kind.MAP_GROUND_CLICK, AimProtocol.classify(Sender.MAP, "click", g));
        assertEquals(AimProtocol.GROUND_ARGS, g.length);
        Object[] b = AimProtocol.gobClick(77, p, 1);
        assertEquals(Kind.MAP_GOB_CLICK, AimProtocol.classify(Sender.MAP, "click", b));
        assertEquals(77, AimProtocol.mapGobId(b));
        assertEquals(b[1], b[6]);
        assertEquals(Kind.FIGHT_PORTRAIT_CLICK, AimProtocol.classify(Sender.FIGHTVIEW, "click", AimProtocol.portraitClick(77, 1)));
    }
}
