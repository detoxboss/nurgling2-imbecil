package nurgling;

import haven.Coord;
import haven.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives FocusGuard through haven's real event dispatch ({@link Widget.Event#dispatch}): a root
 * focus controller, a "GameUI" wired to the guard the way NGameUI is, windows and text fields.
 * Keys are delivered like {@code UI.keydown}: a KeyDownEvent, then a GlobKeyEvent if unhandled.
 */
class FocusGuardTest {
    private static final Component SRC = new Component() {};

    /** A text field: consumes printable keys, takes focus on click (like TextEntry). */
    static class Field extends Widget {
        final StringBuilder typed = new StringBuilder();
        int escapes = 0;

        Field() {
            super(Coord.of(50, 20));
            setcanfocus(true);
        }

        @Override
        public boolean keydown(KeyDownEvent ev) {
            if(ev.code == KeyEvent.VK_ESCAPE) {
                escapes++;
                return false;
            }
            if(ev.c >= 32) {
                typed.append(ev.c);
                return true;
            }
            return false;
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            parent.setfocus(this);
            return true;
        }
    }

    /** A window: focus controller that takes focus when clicked (like haven.Window). */
    static class Wnd extends Widget {
        Runnable onClick = null;

        Wnd() {
            super(Coord.of(200, 100));
            setfocustab(true);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            ev.propagate(this);
            if(onClick != null)
                onClick.run();
            parent.setfocus(this);
            return true;
        }
    }

    static class DialogWnd extends Wnd implements FocusGuard.TypingDialog {}

    /** Stands in for NGameUI: the same four hooks. */
    static class Gui extends Widget {
        FocusGuard guard;
        final List<Character> hotkeys = new ArrayList<>();

        Gui() {
            super(Coord.of(800, 600));
            setcanfocus(true);
            setfocusctl(true);
        }

        @Override
        public boolean keydown(KeyDownEvent ev) {
            if(guard.intercept(ev)) {
                ev.stop();
                return false;
            }
            return super.keydown(ev);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            boolean handled = ev.propagate(this);
            guard.clicked(ev.c);
            return handled;
        }

        @Override
        public void setfocus(Widget w) {
            super.setfocus(w);
            if(guard != null)
                guard.observe();
        }

        @Override
        public void delfocusable(Widget w) {
            super.delfocusable(w);
            if(guard != null)
                guard.observe();
        }

        @Override
        public boolean globtype(GlobKeyEvent ev) {
            if(ev.c != 0) {
                hotkeys.add(ev.c);
                return true;
            }
            return false;
        }
    }

    final AtomicBoolean enabled = new AtomicBoolean(true);
    Widget root;
    Gui gui;

    @BeforeEach
    void setUp() {
        root = new Widget(Coord.of(800, 600));
        root.setfocusctl(true);
        root.hasfocus = true;
        gui = new Gui();
        gui.guard = new FocusGuard(gui, enabled::get, w -> w instanceof Field);
        root.add(gui, Coord.z);
        root.setfocus(gui);
    }

    /** Window at {@code at} with a field at (10,10) inside it; added like a newly opened window. */
    Field openWindow(Wnd wnd, Coord at) {
        Field f = new Field();
        wnd.add(f, Coord.of(10, 10));
        gui.add(wnd, at);
        gui.setfocus(wnd);
        return f;
    }

    /** As UI.keydown does it. */
    void press(int code, char c) {
        KeyEvent awt = new KeyEvent(SRC, KeyEvent.KEY_PRESSED, 0, 0, code, c);
        if(!new Widget.KeyDownEvent(awt).dispatch(root))
            new Widget.GlobKeyEvent(awt).dispatch(root);
    }

    void type(char c) {
        press(KeyEvent.getExtendedKeyCodeForChar(c), c);
    }

    void click(Coord c) {
        new Widget.MouseDownEvent(c, 1).dispatch(root);
    }

    @Test
    void autoFocusedFieldDoesNotEatHotkeys() {
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        assertSame(f, FocusGuard.leaf(gui), "the field was auto-focused, as in a container window");
        type('x');
        assertEquals("", f.typed.toString());
        assertEquals(List.of('x'), gui.hotkeys, "the key reached the global hotkey path");
    }

    @Test
    void guardOffKeepsLegacyBehaviour() {
        enabled.set(false);
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        type('x');
        assertEquals("x", f.typed.toString());
        assertTrue(gui.hotkeys.isEmpty());
    }

    @Test
    void clickingTheFieldActivatesTyping() {
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        click(Coord.of(115, 115));
        type('a');
        type('b');
        assertEquals("ab", f.typed.toString());
        assertTrue(gui.hotkeys.isEmpty());
    }

    @Test
    void clickingElsewhereInTheWindowDeactivates() {
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        click(Coord.of(115, 115));
        click(Coord.of(250, 180)); // window body, not the field; the field stays focused
        assertSame(f, FocusGuard.leaf(gui));
        type('a');
        assertEquals("", f.typed.toString());
        assertEquals(List.of('a'), gui.hotkeys);
    }

    @Test
    void returningToAPreviouslyArmedFieldNeedsANewClick() {
        Wnd a = new Wnd();
        Field fa = openWindow(a, Coord.of(100, 100));
        click(Coord.of(115, 115));
        assertSame(fa, gui.guard.armed());
        Field fb = openWindow(new Wnd(), Coord.of(400, 100));
        gui.setfocus(a); // back to A without clicking its field
        assertSame(fa, FocusGuard.leaf(gui));
        type('z');
        assertEquals("", fa.typed.toString());
        assertEquals("", fb.typed.toString());
        assertEquals(List.of('z'), gui.hotkeys);
    }

    @Test
    void closingTheFocusedWindowDoesNotActivateTheNextOnesField() {
        Field fa = openWindow(new Wnd(), Coord.of(100, 100));
        click(Coord.of(115, 115));
        Wnd b = new Wnd();
        Field fb = openWindow(b, Coord.of(400, 100));
        click(Coord.of(415, 115));
        b.hide(); // GameUI picks the next window by z-order: A, whose field is still its focus
        assertSame(fa, FocusGuard.leaf(gui));
        type('q');
        assertEquals("", fa.typed.toString());
        assertEquals("", fb.typed.toString());
        assertEquals(List.of('q'), gui.hotkeys);
    }

    @Test
    void escapeStillTakesTheFocusedPath() {
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        press(KeyEvent.VK_ESCAPE, (char)27);
        assertEquals(1, f.escapes, "window-close / clear-search handlers still see Escape");
    }

    @Test
    void hiddenFieldNeverTypesEvenIfArmed() {
        Wnd w = new Wnd();
        Widget panel = new Widget(Coord.of(100, 50));
        Field f = new Field();
        panel.add(f, Coord.z);
        w.add(panel, Coord.of(10, 10));
        gui.add(w, Coord.of(100, 100));
        gui.setfocus(w);
        click(Coord.of(115, 115));
        assertSame(f, gui.guard.armed());
        panel.visible = false; // e.g. the inventory list panel closed by flipping visible
        type('k');
        assertEquals("", f.typed.toString());
        assertEquals(List.of('k'), gui.hotkeys);
    }

    @Test
    void requestedTypingWorksWithoutAClickAndSurvivesTheClickThatOpenedIt() {
        Wnd w = new Wnd();
        Field[] editor = new Field[1];
        w.onClick = () -> {
            if(editor[0] == null) {
                editor[0] = w.add(new Field(), Coord.of(150, 70));
                w.setfocus(editor[0]);
                gui.guard.request(editor[0]);
            }
        };
        gui.add(w, Coord.of(100, 100));
        gui.setfocus(w);
        click(Coord.of(110, 110)); // opens the editor somewhere other than the click
        type('e');
        assertEquals("e", editor[0].typed.toString());
        click(Coord.of(120, 120)); // a later click elsewhere still deactivates it
        type('f');
        assertEquals("e", editor[0].typed.toString());
        assertEquals(List.of('f'), gui.hotkeys);
    }

    @Test
    void typingDialogTypesImmediatelyOnlyTheFirstTime() {
        DialogWnd d = new DialogWnd();
        Field f = openWindow(d, Coord.of(100, 100));
        type('n');
        assertEquals("n", f.typed.toString());
        Field other = openWindow(new Wnd(), Coord.of(400, 100));
        gui.setfocus(d); // revisiting does not silently reactivate
        type('m');
        assertEquals("n", f.typed.toString());
        assertEquals("", other.typed.toString());
        assertEquals(List.of('m'), gui.hotkeys);
    }

    @Test
    void disablingAndReenablingLeavesNoStaleActivation() {
        Field f = openWindow(new Wnd(), Coord.of(100, 100));
        click(Coord.of(115, 115));
        assertSame(f, gui.guard.armed());
        enabled.set(false);
        gui.guard.observe();
        type('a');
        assertEquals("a", f.typed.toString(), "off: legacy typing");
        enabled.set(true);
        type('b');
        assertEquals("a", f.typed.toString(), "on again: the old activation is gone");
        assertEquals(List.of('b'), gui.hotkeys);
    }

    @Test
    void sessionsAreIndependent() {
        Field f1 = openWindow(new Wnd(), Coord.of(100, 100));
        click(Coord.of(115, 115));

        Gui gui2 = new Gui();
        gui2.guard = new FocusGuard(gui2, enabled::get, w -> w instanceof Field);
        Wnd w2 = new Wnd();
        Field f2 = new Field();
        w2.add(f2, Coord.of(10, 10));
        gui2.add(w2, Coord.of(100, 100));
        gui2.setfocus(w2);

        assertSame(f1, gui.guard.armed());
        assertNull(gui2.guard.armed(), "the other session's same-position field is not armed");
    }
}
