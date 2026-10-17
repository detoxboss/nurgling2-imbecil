package nurgling.tools;

import haven.Disposable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DisposableSlotTest {
    private static final class Counted implements Disposable {
        int disposed = 0;

        public void dispose() {
            disposed++;
        }
    }

    @Test
    void replacingFreesThePreviousValueOnceAndKeepsTheNewOne() {
        DisposableSlot<Counted> slot = new DisposableSlot<>();
        Counted a = new Counted(), b = new Counted();
        slot.replace(a);
        assertEquals(0, a.disposed, "nothing to free for the first value");
        slot.replace(b);
        assertEquals(1, a.disposed);
        assertEquals(0, b.disposed, "the value now shown must stay usable");
        assertSame(b, slot.get());
    }

    @Test
    void clearFreesAndEmpties() {
        DisposableSlot<Counted> slot = new DisposableSlot<>();
        Counted a = new Counted();
        slot.replace(a);
        slot.clear();
        assertEquals(1, a.disposed);
        assertNull(slot.get());
        slot.clear();
        assertEquals(1, a.disposed, "clearing an empty slot frees nothing twice");
    }

    @Test
    void storingTheSameValueAgainDoesNotFreeIt() {
        DisposableSlot<Counted> slot = new DisposableSlot<>();
        Counted a = new Counted();
        slot.replace(a);
        slot.replace(a);
        assertEquals(0, a.disposed);
        assertSame(a, slot.get());
    }

    @Test
    void repeatedRebuildsFreeEveryReplacedValue() {
        DisposableSlot<Counted> slot = new DisposableSlot<>();
        Counted[] all = new Counted[5];
        for(int i = 0; i < all.length; i++)
            slot.replace(all[i] = new Counted());
        for(int i = 0; i < all.length - 1; i++)
            assertEquals(1, all[i].disposed, "rebuild " + i);
        assertEquals(0, all[all.length - 1].disposed);
    }
}
