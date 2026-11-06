package haven.res.ui.surv;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Data.encode() packs target heights as an unsigned byte offset from the lowest point
 * (lo..lo+255). Before this fix the loop just did `dz[i] - lo` with no range check, so a span
 * over 255 silently wrapped (e.g. 256 became 0) and corrupted every height past the wrap point.
 * These lock in the boundary: exactly 255 must still encode losslessly, and anything past it
 * must be rejected rather than wrapped.
 */
class DataHeightRangeTest {
    private static Data data(int w, int h, int... wz) {
        Data d = new Data(Area.sized(Coord.of(0, 0), Coord.of(w, h)), 1f);
        for(int i = 0; i < wz.length; i++)
            d.wz[i] = wz[i];
        d.dupdate();
        return(d);
    }

    @Test
    void maxRepresentableSpanEncodesLosslessly() throws HeightRangeExceededException {
        Data d = data(2, 1, 0, 255);
        Object[] enc = d.encode();
        assertEquals(0, (int)(Integer)enc[0]);
        Data back = data(2, 1, 0, 0);
        back.decode((Integer)enc[0], (byte[])enc[1]);
        assertArrayEquals(new int[] {0, 255}, back.dz);
    }

    @Test
    void spanOfTwoFiftySixIsRejectedNotWrapped() {
        Data d = data(2, 1, 0, 256);
        HeightRangeExceededException ex = assertThrows(HeightRangeExceededException.class, d::encode);
        assertEquals(0, ex.lo);
        assertEquals(256, ex.hi);
    }

    @Test
    void negativeSpanIsAlsoWithinRange() throws HeightRangeExceededException {
        Data d = data(2, 1, -100, 100);
        Object[] enc = d.encode();
        assertEquals(-100, (int)(Integer)enc[0]);
    }
}
