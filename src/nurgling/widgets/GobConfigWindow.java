package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.tools.GobCustomize;

import java.awt.Color;

/**
 * Settings for a gob, reached through the Ctrl+RMB context menu's "Configure" entry, in one of two
 * scopes picked with the radio buttons at the top:
 *
 * <ul>
 * <li>"This object" (the default) - an instance override for the one physical gob that was clicked,
 * keyed by its {@link nurgling.NGob#hash}.
 * <li>"All objects of this type" - the original resource-wide setting, keyed by {@link #res}.
 * </ul>
 *
 * <p>The window deliberately holds the resource name, hash and owning {@link Glob} rather than the
 * {@link haven.Gob} it was opened from - the object it was opened on may well walk away, be
 * destroyed or scroll out of view while the window is still up. {@code hash} and {@code owner} are
 * enough to find the same physical gob again later (see {@link GobCustomize#applyOne}) without
 * keeping it alive.
 */
public class GobConfigWindow extends Window {
    private static final Text.Foundry pathf =
            new Text.Foundry(Text.sans, 11, new Color(170, 170, 170)).aa(true);
    private static final int WIDTH = UI.scale(300);

    private enum Scope {INSTANCE, TYPE}

    private final String res;
    /** The clicked gob's persistent identity, or null if it could not be resolved yet - see
     * {@link nurgling.NGob#hash}. Null disables the "this object" scope entirely, since there is
     * nothing to key an instance override on. */
    private final String hash;
    /** The clicked gob's own world, so a reapply can never cross into another session/world. */
    private final Glob owner;

    private Scope scope;
    private final HSlider scale;
    private final Label scaleval;
    private final CheckBox tint;
    private final NColorWidget tintColor;
    private final CheckBox marker;
    private final CheckBox label;
    private final TextEntry labelText;

    public GobConfigWindow(String res, String hash, Glob owner) {
        super(UI.scale(new Coord(300, 200)), L10n.get("gobconf.title") + ": " + prettyName(res));
        this.res = res;
        this.hash = hash;
        this.owner = owner;
        this.scope = (hash != null) ? Scope.INSTANCE : Scope.TYPE;

        Widget prev = add(new Label(shortenPath(res), pathf), Coord.z);

        if (hash != null) {
            prev = add(new Label(L10n.get("gobconf.scope")), prev.pos("bl").adds(0, 8));
            boolean[] ready = {false};
            RadioGroup grp = new RadioGroup(this) {
                @Override
                public void changed(int btn, String lbl) {
                    if (!ready[0])
                        return;
                    GobConfigWindow.this.scope = (btn == 0) ? Scope.INSTANCE : Scope.TYPE;
                    sync();
                }
            };
            Widget rb0 = grp.add(L10n.get("gobconf.scope_instance"), prev.pos("bl").adds(0, 4));
            Widget rb1 = grp.add(L10n.get("gobconf.scope_type"), rb0.pos("bl").adds(0, 2));
            grp.check(0);
            ready[0] = true;
            prev = rb1;
        }

        GobCustomize.Settings s = current();

        /* Display size. */
        prev = add(new Label(L10n.get("gobconf.size")), prev.pos("bl").adds(0, 8));
        scaleval = new Label(s.scale + "%");
        scale = new HSlider(UI.scale(190), GobCustomize.SCALE_MIN, GobCustomize.SCALE_MAX, s.scale) {
            @Override
            public void changed() {
                // Live while dragging: memory only, so a drag does not write the config file
                // once per frame (NCore flushes a dirty config on the very next tick).
                scaleval.settext(this.val + "%");
                updateLive(current().withScale(this.val));
            }

            @Override
            public void fchanged() {
                commitScope();
            }
        };
        addhl(prev.pos("bl").adds(0, 4), WIDTH, scale, scaleval);
        prev = scale;

        /* Colour highlight. */
        tint = new CheckBox(L10n.get("gobconf.tint")) {
            @Override
            public void changed(boolean val) {
                writeScope(current().withTint(val));
            }
        };
        tint.a = s.tint;
        prev = add(tint, prev.pos("bl").adds(0, 12));

        tintColor = add(new NColorWidget(L10n.get("gobconf.tint_color")) {
            @Override
            public void tick(double dt) {
                super.tick(dt);
                // The picker runs a Swing dialog on its own thread and just assigns `color`,
                // so polling is the only way to notice the user chose something.
                if (!color.equals(current().tintColor))
                    writeScope(current().withTintColor(color));
            }
        }, prev.pos("bl").adds(12, 4));
        tintColor.color = s.tintColor;
        tintColor.cb.colorChooser.setColor(s.tintColor);
        prev = tintColor;

        /* Search-style marker above the object. */
        marker = new CheckBox(L10n.get("gobconf.marker")) {
            @Override
            public void changed(boolean val) {
                writeScope(current().withMarker(val));
            }
        };
        marker.a = s.marker;
        prev = add(marker, prev.pos("bl").adds(-12, 8));

        /* Caption drawn under the object. */
        label = new CheckBox(L10n.get("gobconf.label")) {
            @Override
            public void changed(boolean val) {
                writeScope(current().withLabel(val));
            }
        };
        label.a = s.label;
        prev = add(label, prev.pos("bl").adds(0, 8));

        labelText = add(new TextEntry(WIDTH - UI.scale(12), s.labelText) {
            @Override
            protected void changed() {
                super.changed();
                // Live as it is typed; the caption sprite reads the text back every frame.
                // Saved on Enter, on losing focus and when the window closes, rather than per
                // keystroke - a commit rewrites the whole config file.
                updateLive(current().withLabelText(text()));
            }

            @Override
            public void activate(String text) {
                super.activate(text);
                commitScope();
            }

            @Override
            public void lostfocus() {
                super.lostfocus();
                commitScope();
            }
        }, prev.pos("bl").adds(12, 4));
        prev = labelText;

        add(new Button(UI.scale(90), L10n.get("gobconf.reset")) {
            @Override
            public void click() {
                writeScope(GobCustomize.DEFAULTS);
                sync();
            }
        }, prev.pos("bl").adds(-12, 12));

        pack();
    }

    /** Effective settings under the currently selected scope - the base for the next edit. */
    private GobCustomize.Settings current() {
        return (scope == Scope.INSTANCE) ? GobCustomize.effectiveSettings(res, hash) : GobCustomize.settings(res);
    }

    /** Live, memory-only update (drag/typing) under the currently selected scope. */
    private void updateLive(GobCustomize.Settings s) {
        if (scope == Scope.INSTANCE)
            GobCustomize.updateInstance(owner, hash, s);
        else
            GobCustomize.update(res, s);
    }

    /** Immediate, persisted update (checkbox/reset) under the currently selected scope. */
    private void writeScope(GobCustomize.Settings s) {
        if (scope == Scope.INSTANCE)
            GobCustomize.setInstance(owner, hash, s);
        else
            GobCustomize.set(res, s);
    }

    private void commitScope() {
        if (scope == Scope.INSTANCE)
            GobCustomize.commitInstance();
        else
            GobCustomize.commit();
    }

    /** Both layers, regardless of which scope is currently selected - used on close, so a scope
     * switched away from right after a drag can never leave that drag's live edit unsaved. */
    private void commitAll() {
        GobCustomize.commit();
        GobCustomize.commitInstance();
    }

    /** Pulls the controls back in line with the stored settings after a scope or wholesale change. */
    private void sync() {
        GobCustomize.Settings s = current();
        scale.val = s.scale;
        scaleval.settext(s.scale + "%");
        tint.a = s.tint;
        marker.a = s.marker;
        tintColor.color = s.tintColor;
        tintColor.cb.colorChooser.setColor(s.tintColor);
        label.a = s.label;
        labelText.settext(s.labelText);
    }

    public String res() {
        return res;
    }

    /** Last path element, capitalised - "gfx/terobjs/trees/oak" reads as "Oak". */
    private static String prettyName(String res) {
        if (res == null || res.isEmpty())
            return "?";
        String s = res.substring(res.lastIndexOf('/') + 1);
        if (s.isEmpty())
            return res;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Keeps the tail of a long resource path, which is the part that identifies the object. */
    private static String shortenPath(String res) {
        if (res == null)
            return "";
        if (res.startsWith("gfx/"))
            return res.substring(4);
        return res;
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            commitAll();
            ui.destroy(this);
        } else {
            super.wdgmsg(msg, args);
        }
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (key_esc.match(ev)) {
            commitAll();
            ui.destroy(this);
            return true;
        }
        return super.keydown(ev);
    }

    /**
     * Opens the window for a gob, or raises/retargets the one already open. Windows are identified
     * by {@code hash} when available (so configuring cupboard A and then cupboard B - same resource,
     * different physical objects - opens two independent targets rather than one shared one),
     * falling back to {@code res} only for the rare gob with no resolvable hash.
     */
    public static void open(String res, String hash, Glob owner) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || res == null)
            return;
        String identity = (hash != null) ? hash : res;
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof GobConfigWindow) {
                GobConfigWindow existing = (GobConfigWindow) w;
                String existingIdentity = (existing.hash != null) ? existing.hash : existing.res;
                if (identity.equals(existingIdentity)) {
                    w.raise();
                    return;
                }
                gui.ui.destroy(w);
                break;
            }
        }
        GobConfigWindow wnd = new GobConfigWindow(res, hash, owner);
        Coord pos = gui.sz.sub(wnd.sz).div(2);
        gui.add(wnd, new Coord(Math.max(0, pos.x), Math.max(0, pos.y)));
        wnd.raise();
    }
}
