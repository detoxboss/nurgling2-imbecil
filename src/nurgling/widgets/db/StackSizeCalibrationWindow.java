package nurgling.widgets.db;

import haven.*;
import haven.Button;
import haven.Label;
import nurgling.NCore;
import nurgling.db.service.StackSizeService;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browse and correct the shared, self-correcting item stack-size table
 * ({@code nurgling/db/service/StackSizeService.java}) that overrides {@code
 * nurgling.tools.StackSupporter}'s static table wherever a database-backed answer is available.
 * Edits saved here sync to every client sharing this database, the same way {@code NArea} edits do
 * - see {@code docs/inventory-grid-system.md} §8 for the feature this backs.
 *
 * <p>Unlike {@link VillagersWindow} (which waits on an async {@code listAsync()} round trip), the
 * list here is read straight off {@link StackSizeService#snapshotForUi()} - a synchronous in-memory
 * map read, since the service's cache already IS the live data (kept fresh by its own background
 * sync). Only the write paths (Save/Reset) are async and route status back through {@link
 * #pendingStatus}, the same "results arrive on database threads; widgets are only ever touched from
 * tick()" discipline {@link VillagersWindow} already follows.
 */
public class StackSizeCalibrationWindow extends Window {
    private static final Coord WINDOW_SIZE = UI.scale(new Coord(600, 440));
    private static final int GAP = UI.scale(6);
    private static final int MAX_ENTRY_W = UI.scale(40);
    private static final int TOGGLE_W = UI.scale(75);
    private static final int SAVE_W = UI.scale(50);
    private static final int RESET_W = UI.scale(55);

    /**
     * One row's live data. Mutated in place on refresh (see {@link #refreshRows}) rather than
     * replaced, so a row's identity - and therefore its {@link Row} widget, and any edit in
     * progress inside it - survives across refreshes. {@link SListBox} diffs by identity.
     */
    private static final class Entry {
        final String name;
        volatile int maxStack;
        volatile boolean stackable;
        volatile String provenance;

        Entry(String name, StackSizeService.StackInfo info) {
            this.name = name;
            apply(info);
        }

        void apply(StackSizeService.StackInfo info) {
            this.maxStack = info.maxStack;
            this.stackable = info.stackable;
            this.provenance = info.provenance;
        }
    }

    private final Map<String, Entry> entriesByName = new LinkedHashMap<>();
    private final List<Entry> rows = new ArrayList<>();
    private final int rowHeight = measureRowHeight();

    private final Label status;
    private final TextEntry newName;
    private final TextEntry newMax;
    private final Button newStackableToggle;
    private boolean newStackable = true;

    /* Results arrive on database threads; widgets are only ever touched from tick(). */
    private volatile String pendingStatus = null;
    private volatile Color pendingStatusColor = Color.WHITE;

    /** Seconds since the list was last re-read from the service's cache. */
    private double sinceRefresh = 999;

    public StackSizeCalibrationWindow() {
        super(WINDOW_SIZE, "Stack Size Calibration");
        int y = UI.scale(5);

        status = add(new Label(""), new Coord(UI.scale(5), y));
        y += UI.scale(20);

        add(new EntryList(new Coord(WINDOW_SIZE.x - UI.scale(20), UI.scale(300)), rowHeight),
            new Coord(UI.scale(5), y));
        y += UI.scale(308);

        add(new Label("Add / override:"), new Coord(UI.scale(5), y + UI.scale(3)));
        newName = add(new TextEntry(UI.scale(230), ""), new Coord(UI.scale(100), y));
        newName.settip("Exact item name, e.g. \"Iron Nugget\"");
        newMax = add(new TextEntry(UI.scale(45), ""), new Coord(UI.scale(340), y));
        newMax.settip("Max stack size");
        newStackableToggle = add(new Button(TOGGLE_W, "Stackable") {
            public void click() {
                super.click();
                newStackable = !newStackable;
                change(newStackable ? "Stackable" : "Not stackable");
            }
        }, new Coord(UI.scale(395), y));
        Button addButton = add(new Button(UI.scale(70), "Save") {
            public void click() {
                super.click();
                addOrOverride();
            }
        }, new Coord(UI.scale(480), y));

        int addRowH = Math.max(Math.max(newName.sz.y, newMax.sz.y),
                               Math.max(newStackableToggle.sz.y, addButton.sz.y));
        resize(new Coord(WINDOW_SIZE.x, y + addRowH + UI.scale(8)));

        refreshRows();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void show() {
        refreshRows();
        super.show();
    }

    // ---- service access --------------------------------------------------------------------

    private static StackSizeService service() {
        return (NCore.databaseManager == null) ? null : NCore.databaseManager.getStackSizeService();
    }

    private boolean ready() {
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()) {
            setStatus("Not connected to a database.", Color.ORANGE);
            return false;
        }
        StackSizeService svc = service();
        if (svc == null) {
            setStatus("Stack size sync is unavailable on this database.", Color.ORANGE);
            return false;
        }
        return true;
    }

    /**
     * Re-read the service's cache. Synchronous - the cache is already the live data, kept fresh by
     * the service's own background sync, so this never blocks. Existing {@link Entry} objects are
     * updated in place; only genuinely new names get a new one (see the class doc on why that
     * matters for row widget identity).
     */
    private void refreshRows() {
        StackSizeService svc = service();
        if (svc == null) {
            entriesByName.clear();
            rows.clear();
            return;
        }
        Map<String, StackSizeService.StackInfo> snapshot = svc.snapshotForUi();

        entriesByName.keySet().retainAll(snapshot.keySet());
        for (Map.Entry<String, StackSizeService.StackInfo> e : snapshot.entrySet()) {
            Entry existing = entriesByName.get(e.getKey());
            if (existing != null) {
                existing.apply(e.getValue());
            } else {
                entriesByName.put(e.getKey(), new Entry(e.getKey(), e.getValue()));
            }
        }

        List<Entry> sorted = new ArrayList<>(entriesByName.values());
        sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        rows.clear();
        rows.addAll(sorted);
    }

    // ---- actions ----------------------------------------------------------------------------

    private void addOrOverride() {
        if (!ready()) return;
        String name = newName.text().trim();
        if (name.isEmpty()) {
            setStatus("Enter an item name first.", Color.ORANGE);
            return;
        }
        int maxStack;
        try {
            maxStack = Integer.parseInt(newMax.text().trim());
        } catch (NumberFormatException e) {
            setStatus("Max stack must be a whole number.", Color.ORANGE);
            return;
        }
        if (maxStack < 1) {
            setStatus("Max stack must be at least 1.", Color.ORANGE);
            return;
        }
        save(name, maxStack, newStackable);
    }

    private void save(String name, int maxStack, boolean stackable) {
        if (!ready()) return;
        // A max stack of 1 unambiguously means "doesn't stack", regardless of what the Stackable
        // toggle happens to still say - the two controls are independent widgets, so it's easy to
        // edit only the number and leave the toggle at whatever a previous (possibly wrong) value
        // set it to, silently saving a self-contradictory row. Confirmed live (2026-09): doing
        // exactly that for Dried Morels re-saved stackable=true alongside max=1, and
        // StackSupporter.isStackable() only ever reads the flag, so TransferToContainer kept trying
        // to merge it. Normalizing here, once, covers both this window's Save buttons.
        boolean effectiveStackable = stackable && maxStack > 1;
        service().setManualAsync(name, maxStack, effectiveStackable)
            .thenRun(() -> {
                setStatus("Saved " + name + ".", Color.GREEN);
                refreshRows();
            })
            .exceptionally(e -> {
                setStatus("Could not save " + name + ": " + rootMessage(e), Color.ORANGE);
                return null;
            });
        // Optimistic: the service's cache is already updated synchronously by setManualAsync()
        // before the future above resolves, so the list can refresh immediately.
        refreshRows();
    }

    private void resetToStatic(String name) {
        if (!ready()) return;
        service().deleteAsync(name)
            .thenRun(() -> {
                setStatus(name + " reset to the built-in table.", Color.YELLOW);
                refreshRows();
            })
            .exceptionally(e -> {
                setStatus("Could not reset " + name + ": " + rootMessage(e), Color.ORANGE);
                return null;
            });
        refreshRows();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c)
            c = c.getCause();
        String m = c.getMessage();
        return (m == null || m.isEmpty()) ? String.valueOf(c) : m;
    }

    // ---- ui -----------------------------------------------------------------------------------

    private void setStatus(String text, Color color) {
        pendingStatus = text;
        pendingStatusColor = color;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);

        String st = pendingStatus;
        if (st != null) {
            status.settext(st);
            status.setcolor(pendingStatusColor);
            pendingStatus = null;
        }

        sinceRefresh += dt;
        if (sinceRefresh >= 2.0) {
            sinceRefresh = 0;
            refreshRows();
        }
    }

    private static int measureRowHeight() {
        Button probe = new Button(SAVE_W, "");
        return Math.max(probe.sz.y, Text.std.height()) + UI.scale(4);
    }

    private static String clip(String text, int maxWidth) {
        if (text == null) return "";
        if (Text.std.strsize(text).x <= maxWidth) return text;
        String s = text;
        while (s.length() > 1 && Text.std.strsize(s + "...").x > maxWidth)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static Color provenanceColor(String provenance) {
        if ("manual".equals(provenance)) return Color.GREEN;
        if ("learned".equals(provenance)) return Color.CYAN;
        return Color.LIGHT_GRAY; // seed, or unrecognized
    }

    // ---- rows -----------------------------------------------------------------------------

    public class Row extends Widget {
        final Entry entry;
        private final Label infoLabel;
        private final TextEntry maxEntry;
        private final Button toggle;
        private final int infoW;
        private boolean lastShownStackable;

        Row(Entry e, Coord sz) {
            super(sz);
            this.entry = e;

            int resetX = sz.x - GAP - RESET_W;
            int saveX = resetX - GAP - SAVE_W;
            int toggleX = saveX - GAP - TOGGLE_W;
            int maxX = toggleX - GAP - MAX_ENTRY_W;
            int textRight = maxX - GAP;
            int textLeft = GAP;
            int textWidth = Math.max(textRight - textLeft, UI.scale(60));
            int nameW = (textWidth * 55) / 100;
            this.infoW = textWidth - nameW;

            addCentered(new Label(clip(e.name, nameW - GAP)), textLeft, sz.y);
            infoLabel = addCentered(new Label(""), textLeft + nameW, sz.y);

            maxEntry = add(new TextEntry(MAX_ENTRY_W, String.valueOf(e.maxStack)), Coord.z);
            maxEntry.move(new Coord(maxX, Math.max(0, (sz.y - maxEntry.sz.y) / 2)));
            maxEntry.settip("Max stack size");

            // Writes straight to entry.stackable (a live, mutable field, not a click-local copy) so
            // this button's own tick() below can always display the truth - including a value that
            // changed for a reason other than this button (a background sync poll, or save()'s own
            // max<=1-forces-not-stackable normalization landing after a refresh) - rather than a
            // label frozen at whatever entry.stackable happened to be when this row was first built.
            toggle = add(new Button(TOGGLE_W, e.stackable ? "Stackable" : "Not stackable") {
                public void click() {
                    super.click();
                    entry.stackable = !entry.stackable;
                }
            }, Coord.z);
            toggle.move(new Coord(toggleX, Math.max(0, (sz.y - toggle.sz.y) / 2)));

            Button save = add(new Button(SAVE_W, "Save") {
                public void click() {
                    super.click();
                    int maxStack;
                    try {
                        maxStack = Integer.parseInt(maxEntry.text().trim());
                    } catch (NumberFormatException ex) {
                        setStatus("Max stack must be a whole number.", Color.ORANGE);
                        return;
                    }
                    if (maxStack < 1) {
                        setStatus("Max stack must be at least 1.", Color.ORANGE);
                        return;
                    }
                    save(entry.name, maxStack, entry.stackable);
                }
            }, Coord.z);
            save.move(new Coord(saveX, Math.max(0, (sz.y - save.sz.y) / 2)));

            Button reset = add(new Button(RESET_W, "Reset") {
                public void click() {
                    super.click();
                    resetToStatic(entry.name);
                }
            }, Coord.z);
            reset.move(new Coord(resetX, Math.max(0, (sz.y - reset.sz.y) / 2)));
            reset.tooltip = Text.render("Delete this override - falls back to the built-in table.").tex();

            lastShownStackable = e.stackable;
            updateInfoLabel(infoW);
        }

        private void updateInfoLabel(int infoW) {
            String text = "max " + entry.maxStack + (entry.stackable ? "" : " (not stackable)")
                + " - " + entry.provenance;
            infoLabel.settext(clip(text, infoW - GAP));
            infoLabel.setcolor(provenanceColor(entry.provenance));
        }

        @Override
        public void tick(double dt) {
            super.tick(dt);
            updateInfoLabel(infoW);
            // Refresh the toggle's label whenever entry.stackable changed for any reason since we
            // last displayed it - a click on this same button, a background sync poll picking up
            // someone else's edit, or save()'s own max<=1 normalization landing after a refresh.
            if (entry.stackable != lastShownStackable) {
                lastShownStackable = entry.stackable;
                toggle.change(entry.stackable ? "Stackable" : "Not stackable");
            }
        }

        private Label addCentered(Label label, int x, int rowH) {
            add(label, new Coord(x, Math.max(0, (rowH - label.sz.y) / 2)));
            return label;
        }

        @Override
        public void draw(GOut g) {
            // A faint stripe behind anything that isn't a deliberate manual entry, so a player can
            // see at a glance which rows are still just the seed table's or passive learning's guess.
            if (!"manual".equals(entry.provenance)) {
                g.chcolor(255, 255, 255, 10);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            super.draw(g);
        }
    }

    public class EntryList extends SListBox<Entry, Widget> {
        EntryList(Coord sz, int itemh) {
            super(sz, itemh);
        }

        protected List<Entry> items() {
            return rows;
        }

        protected Widget makeitem(Entry item, int idx, Coord sz) {
            return new ItemWidget<Entry>(this, sz, item) {
                {
                    add(new Row(item, sz));
                }
            };
        }
    }
}
