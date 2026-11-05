package nurgling;

import haven.*;

import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Keyboard Focus Guard (QoL option {@link NConfig.Key#keyboardFocusGuard}).
 *
 * Haven gives keyboard events to the focused path first and only runs the global hotkeys
 * ({@link Widget.GlobKeyEvent}) when nothing on that path consumed the key. A text field that
 * merely happens to be focused - auto-focused when its window was built, or handed focus again
 * when another window closed - therefore eats every printable hotkey. With the guard on, a text
 * input only receives keys after it has been deliberately activated:
 * <ul>
 * <li>the user clicked it (see {@link #clicked}),</li>
 * <li>code that opens a field for typing on a user action asked for it ({@link #requestTyping}),</li>
 * <li>it is the field of a {@link TypingDialog} that has just taken focus for the first time.</li>
 * </ul>
 * Any change of the focused leaf ends the activation, so leaving a field and coming back to it
 * needs a new click. See docs/keyboard-focus-guard.md.
 *
 * One instance per {@link NGameUI} (session); the widgets it references all belong to that
 * session's widget tree, so they go away with it.
 */
public final class FocusGuard {
    /**
     * Marker for windows that exist to take typed text (name prompts, rename dialogs). Their
     * focused field is activated once, the first time the window takes focus.
     */
    public interface TypingDialog {}

    /** The session's GameUI: the focus controller whose focused path this guard watches. */
    private final Widget gui;
    private final BooleanSupplier enabled;
    private final Predicate<Widget> textInput;
    /** The text input currently allowed to type, or null. */
    private Widget armed = null;
    /** A field {@link #requestTyping} asked for that has not become the focused leaf yet. */
    private Widget pending = null;
    /**
     * {@link #armed} came from a request since the last key or click. A request made while a
     * click is being dispatched (an editor opened by that click) survives {@link #clicked}.
     */
    private boolean requested = false;
    /** The focused leaf seen by the last {@link #observe}, to detect focus transitions. */
    private Widget lastLeaf = null;
    /** TypingDialogs that have already used their one activation. */
    private final Set<Widget> granted = Collections.newSetFromMap(new WeakHashMap<>());

    public FocusGuard(GameUI gui) {
        this(gui, () -> Boolean.TRUE.equals(NConfig.get(NConfig.Key.keyboardFocusGuard)), FocusGuard::isTextInput);
    }

    /** For tests: an arbitrary root, enable switch and text-input classification. */
    FocusGuard(Widget root, BooleanSupplier enabled, Predicate<Widget> textInput) {
        this.gui = root;
        this.enabled = enabled;
        this.textInput = textInput;
    }

    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    /** Widgets that consume printable keys while they hold focus. */
    public static boolean isTextInput(Widget w) {
        return (w instanceof TextEntry) || (w instanceof SSearchBox) || (w instanceof Searchbox)
            || (w instanceof nurgling.widgets.NTextArea);
    }

    /**
     * The widget a focused key event ends up at: follow {@code focused} through focus
     * controllers, exactly as {@link Widget.FocusedKeyEvent} propagates.
     */
    public static Widget leaf(Widget root) {
        Widget w = root;
        while(w.focusctl && (w.focused != null) && (w.focused != w))
            w = w.focused;
        return w;
    }

    /** True if {@code w} is attached under {@code root} and it and all its ancestors are shown. */
    public static boolean shown(Widget w, Widget root) {
        for(Widget p = w; p != null; p = p.parent) {
            if(p == root)
                return true;
            if(!p.visible())
                return false;
        }
        return false;
    }

    /** The armed widget, for diagnostics and tests. */
    public Widget armed() {
        return armed;
    }

    /** Forget all activation state, e.g. when the option is toggled. */
    public void reset() {
        armed = null;
        pending = null;
        requested = false;
        lastLeaf = null;
    }

    /**
     * Track focus transitions. Called whenever the session's focus may have changed
     * (setfocus/delfocusable on the GameUI, each tick, before key and mouse handling).
     */
    public void observe() {
        if(!enabled()) {
            reset();
            return;
        }
        Widget leaf = leaf(gui);
        if(leaf != lastLeaf) {
            lastLeaf = leaf;
            if(armed != leaf) {
                armed = null;
                requested = false;
            }
            grantDialog(leaf);
        }
        if((pending != null) && (pending == leaf)) {
            armed = leaf;
            requested = true;
            pending = null;
        }
    }

    private void grantDialog(Widget leaf) {
        if(!textInput.test(leaf))
            return;
        for(Widget w = gui.focused; (w != null) && (w != leaf); w = w.focused) {
            if(w instanceof TypingDialog) {
                if(granted.add(w)) {
                    armed = leaf;
                    requested = true;
                }
                return;
            }
            if(!w.focusctl)
                break;
        }
    }

    /**
     * Whether this key-down must skip the focused path so the ordinary global hotkeys get it.
     * Escape and Tab still take the normal path: they don't type, and windows use them to close
     * or cycle focus.
     */
    public boolean intercept(Widget.KeyDownEvent ev) {
        if(!enabled())
            return false;
        observe();
        pending = null;
        requested = false;
        Widget leaf = leaf(gui);
        if(!textInput.test(leaf))
            return false;
        if((leaf == armed) && shown(leaf, gui))
            return false;
        if((ev.code == KeyEvent.VK_ESCAPE) || (ev.code == KeyEvent.VK_TAB))
            return false;
        return true;
    }

    /**
     * Called after a mouse-down has been dispatched to the GameUI's children, so the clicked
     * widget has already taken focus. Arms the focused text input if the click landed on it,
     * disarms otherwise.
     */
    public void clicked(Coord c) {
        if(!enabled())
            return;
        observe();
        Widget leaf = leaf(gui);
        boolean keep = requested && (armed == leaf);
        pending = null;
        requested = false;
        if(keep)
            return;
        if(textInput.test(leaf) && shown(leaf, gui) && c.isect(leaf.parentpos(gui), leaf.sz))
            armed = leaf;
        else
            armed = null;
    }

    /**
     * Activate {@code entry} for typing because the user just asked for it (opened a search,
     * a quick-add line, an inline editor). Applies now if it is the focused leaf, otherwise as
     * soon as it becomes it, until the next key or click.
     */
    public void request(Widget entry) {
        if(!enabled())
            return;
        observe();
        if(leaf(gui) == entry) {
            armed = entry;
            requested = true;
        } else {
            pending = entry;
        }
    }

    /** {@link #request} on the guard of the session {@code entry} belongs to, if any. */
    public static void requestTyping(Widget entry) {
        if((entry == null) || (entry.ui == null) || !(entry.ui.gui instanceof NGameUI))
            return;
        ((NGameUI)entry.ui.gui).focusGuard.request(entry);
    }
}
