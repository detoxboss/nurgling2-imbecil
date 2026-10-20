package nurgling.watch.ui;

import haven.Button;
import haven.CheckBox;
import haven.Coord;
import haven.GOut;
import haven.Label;
import haven.TextEntry;
import haven.UI;
import haven.Widget;
import haven.Window;
import nurgling.NGameUI;
import nurgling.actions.bots.VillageWatchBot;
import nurgling.sessions.BotExecutor;
import nurgling.watch.PingTarget;
import nurgling.watch.WatchCategory;
import nurgling.watch.WatchProfile;
import nurgling.watch.WatchProfileStore;
import nurgling.watch.WatchStatus;
import nurgling.watch.WatchTemplate;
import nurgling.watch.WatchTesting;
import nurgling.widgets.NDropbox;
import nurgling.widgets.NTextArea;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Village Watch settings: pick or create a watcher profile, edit its general options and the three
 * message templates, test Discord and screenshots, and start or stop the lookout. Opened from the
 * Bots menu and from a Scenario step; the profile is stored in {@link WatchProfileStore}, shared by
 * every client window, and a running lookout keeps the copy it started with.
 */
public class VillageWatchWindow extends Window {
    private static final int CONTENT_W = UI.scale(450);
    private static final int TAB_H = UI.scale(372);
    private static final int PREVIEW_LINES = 8;

    private final NGameUI gui;
    private final WatchProfileStore store = WatchProfileStore.shared();
    private final Consumer<String> onSaved;
    private final WatchStatus status;

    private WatchProfile editing;
    private boolean loading;
    private List<String> names = new ArrayList<>();

    private NDropbox<String> profileBox;
    private Widget newRow;
    private TextEntry newName;
    private boolean creatingCopy;
    private TextEntry watcherEntry;
    private CheckBox shotBox;
    private final Map<String, NumberField> numbers = new HashMap<>();
    private final Map<WatchCategory, CategoryTab> categoryTabs = new EnumMap<>(WatchCategory.class);
    private final List<Widget> tabs = new ArrayList<>();
    private Label statusLabel;
    private Label messageLabel;
    private Button startBtn, stopBtn;
    private double sinceStatus = 1;

    /**
     * Open (or bring forward) the window for this session.
     *
     * @param profile profile to show first; null means this character's last-used profile
     * @param onSaved told the name of a profile each time it is saved; may be null
     */
    public static void open(NGameUI gui, String profile, Consumer<String> onSaved) {
        synchronized (gui.ui) {
            for (Widget w = gui.child; w != null; w = w.next) {
                if (w instanceof VillageWatchWindow) {
                    VillageWatchWindow existing = (VillageWatchWindow) w;
                    existing.raise();
                    if (profile != null)
                        existing.select(profile);
                    return;
                }
            }
            gui.add(new VillageWatchWindow(gui, profile, onSaved), UI.scale(new Coord(120, 80)));
        }
    }

    private VillageWatchWindow(NGameUI gui, String profile, Consumer<String> onSaved) {
        super(new Coord(CONTENT_W, UI.scale(470)), "Village Watch");
        this.gui = gui;
        this.onSaved = onSaved;
        this.status = WatchStatus.of(gui);

        int y = 0;
        add(new Label("Profile:"), new Coord(0, y + UI.scale(3)));
        names = store.names();
        profileBox = add(new NDropbox<String>(UI.scale(190), 8, UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return names.get(i);
            }

            @Override
            protected int listitems() {
                return names.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                if (item != null && !loading && (editing == null || !item.equals(editing.name)))
                    select(item);
            }
        }, new Coord(UI.scale(55), y));
        add(new Button(UI.scale(50), "New", () -> beginNew(false)), new Coord(UI.scale(255), y));
        add(new Button(UI.scale(50), "Copy", () -> beginNew(true)), new Coord(UI.scale(310), y));
        add(new Button(UI.scale(60), "Delete", this::deleteProfile), new Coord(UI.scale(365), y));
        y += UI.scale(28);

        newRow = add(new Widget(new Coord(CONTENT_W, UI.scale(24))), new Coord(0, y));
        newRow.add(new Label("Name:"), new Coord(0, UI.scale(3)));
        newName = newRow.add(new TextEntry(UI.scale(190), ""), new Coord(UI.scale(55), 0));
        newRow.add(new Button(UI.scale(60), "Create", this::createProfile), new Coord(UI.scale(255), 0));
        newRow.add(new Button(UI.scale(60), "Cancel", () -> newRow.hide()), new Coord(UI.scale(320), 0));
        newRow.hide();
        y += UI.scale(28);

        int tabY = y;
        String[] tabNames = {"General", "Unknown", "White", "Red"};
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            add(new Button(UI.scale(105), tabNames[i], () -> showTab(idx)), new Coord(i * UI.scale(110), tabY));
        }
        y += UI.scale(30);

        tabs.add(buildGeneralTab(y));
        for (WatchCategory c : WatchCategory.values()) {
            CategoryTab t = new CategoryTab(c);
            categoryTabs.put(c, t);
        }
        tabs.add(buildCategoryTab(WatchCategory.UNKNOWN, y));
        tabs.add(buildCategoryTab(WatchCategory.WHITE, y));
        tabs.add(buildCategoryTab(WatchCategory.RED, y));
        y += TAB_H + UI.scale(8);

        messageLabel = add(new Label("", CONTENT_W), new Coord(0, y));
        y += UI.scale(20);
        add(new Button(UI.scale(90), "Save", this::save), new Coord(0, y));
        startBtn = add(new Button(UI.scale(130), "Save & Start", this::saveAndStart), new Coord(UI.scale(100), y));
        stopBtn = add(new Button(UI.scale(130), "Stop watching", this::stopWatching), new Coord(UI.scale(100), y));
        add(new Button(UI.scale(90), "Close", this::reqclose), new Coord(UI.scale(360), y));
        pack();

        showTab(0);
        select(profile != null && names.contains(profile) ? profile : store.profileFor(gui.chrid));
    }

    @Override
    public void reqclose() {
        reqdestroy();
    }

    /* ---------------------------------------------------------------- tabs */

    private void showTab(int idx) {
        for (int i = 0; i < tabs.size(); i++) {
            if (i == idx)
                tabs.get(i).show();
            else
                tabs.get(i).hide();
        }
    }

    private Widget buildGeneralTab(int y) {
        Widget t = add(new Widget(new Coord(CONTENT_W, TAB_H)), new Coord(0, y));
        int row = 0;
        t.add(new Label("Watcher name / location (shown as {watcher}):"), new Coord(0, row));
        row += UI.scale(18);
        watcherEntry = t.add(new TextEntry(UI.scale(300), "") {
            @Override
            protected void changed() {
                if (!loading && editing != null)
                    editing.watcher = text();
            }
        }, new Coord(0, row));
        row += UI.scale(36);

        numberRow(t, row, "scan", "Scan interval (s):", WatchProfile.SCAN_MIN, WatchProfile.SCAN_MAX,
                "How frequently the lookout checks for threats.");
        row += UI.scale(30);
        numberRow(t, row, "grace", "Absence grace (s):", WatchProfile.GRACE_MIN, WatchProfile.GRACE_MAX,
                "How long a previously detected player must remain unseen before that sighting ends.");
        row += UI.scale(30);
        numberRow(t, row, "cooldown", "Re-alert cooldown (s):", WatchProfile.COOLDOWN_MIN, WatchProfile.COOLDOWN_MAX,
                "How long to wait before sending another notification about the same continuously present player. "
                        + "A newly confirmed threat is always reported immediately.");
        row += UI.scale(36);

        shotBox = t.add(new CheckBox("Attach screenshot (only from a rendered client window)") {
            @Override
            public void set(boolean a) {
                super.set(a);
                if (!loading && editing != null)
                    editing.attachScreenshot = a;
            }
        }, new Coord(0, row));
        row += UI.scale(34);

        t.add(new Button(UI.scale(150), "Test Discord", () -> {
            if (editing != null)
                WatchTesting.testDiscord(gui, editing.copy().normalized());
        }), new Coord(0, row));
        t.add(new Button(UI.scale(150), "Test screenshot", () -> {
            if (editing != null)
                WatchTesting.testScreenshot(gui, editing.copy().normalized());
        }), new Coord(UI.scale(160), row));
        row += UI.scale(38);

        t.add(new Label("Status:"), new Coord(0, row));
        statusLabel = t.add(new Label("", CONTENT_W - UI.scale(10)), new Coord(0, row + UI.scale(18)));
        return t;
    }

    private void numberRow(Widget parent, int y, String key, String caption, int min, int max, String help) {
        Label lbl = parent.add(new Label(caption), new Coord(0, y + UI.scale(3)));
        lbl.settip(help, false);
        NumberField f = new NumberField(min, max);
        f.entry = parent.add(new TextEntry(UI.scale(60), "") {
            @Override
            protected void changed() {
                f.edited();
            }
        }, new Coord(UI.scale(170), y));
        f.entry.settip(help, false);
        f.hint = parent.add(new Label(min + " - " + max), new Coord(UI.scale(240), y + UI.scale(3)));
        f.key = key;
        numbers.put(key, f);
    }

    /** A whole-number text field that highlights its range hint in red while the text is invalid. */
    private final class NumberField {
        final int min, max;
        String key;
        TextEntry entry;
        Label hint;
        boolean valid = true;

        NumberField(int min, int max) {
            this.min = min;
            this.max = max;
        }

        void edited() {
            if (loading || editing == null)
                return;
            Integer v = WatchProfile.parseInRange(entry.text(), min, max);
            valid = v != null;
            hint.setcolor(valid ? Color.WHITE : Color.RED);
            if (!valid)
                return;
            switch (key) {
                case "scan":
                    editing.scanIntervalSec = v;
                    break;
                case "grace":
                    editing.absenceGraceSec = v;
                    break;
                default:
                    editing.cooldownSec = v;
            }
        }

        void load(int value) {
            entry.settext(Integer.toString(value));
            valid = true;
            hint.setcolor(Color.WHITE);
        }
    }

    private Widget buildCategoryTab(WatchCategory cat, int y) {
        CategoryTab ct = categoryTabs.get(cat);
        Widget t = add(new Widget(new Coord(CONTENT_W, TAB_H)), new Coord(0, y));
        // Not named "c": inside a Widget subclass that would resolve to Widget.c, the position.
        ct.enabled = t.add(new CheckBox("Send alerts for " + cat.label.toLowerCase() + " players"
                + (cat == WatchCategory.UNKNOWN ? " (not memorized)" : " (kin group)")) {
            @Override
            public void set(boolean a) {
                super.set(a);
                if (!loading && editing != null)
                    editing.category(cat).enabled = a;
            }
        }, new Coord(0, 0));
        t.add(new Label("Placeholders: {watcher} {player} {status} {time} {gob_id} {coords}"), new Coord(0, UI.scale(26)));
        ct.editor = t.add(new NTextArea(new Coord(CONTENT_W, UI.scale(120)), ""), new Coord(0, UI.scale(46)));
        ct.editor.onchange = () -> {
            if (!loading && editing != null)
                editing.category(cat).template = ct.editor.text();
            ct.refreshPreview();
        };
        t.add(new Label("Preview (sample data):"), new Coord(0, UI.scale(176)));
        t.add(new Button(UI.scale(130), "Restore default", () -> {
            ct.editor.settext(WatchTemplate.defaultFor(cat));
            if (editing != null)
                editing.category(cat).template = WatchTemplate.defaultFor(cat);
            ct.refreshPreview();
        }), new Coord(UI.scale(320), UI.scale(172)));
        ct.preview = t.add(new Widget(new Coord(CONTENT_W, UI.scale(PREVIEW_LINES * 15 + 6))), new Coord(0, UI.scale(198)));

        // Ping row. Pings only fire on a new sighting or an escalation, never on cooldown repeats.
        int py = UI.scale(332);
        t.add(new Label("Ping:"), new Coord(0, py + UI.scale(3)));
        ct.pingBox = t.add(new NDropbox<PingTarget>(UI.scale(130), PingTarget.values().length, UI.scale(20)) {
            @Override
            protected PingTarget listitem(int i) {
                return PingTarget.values()[i];
            }

            @Override
            protected int listitems() {
                return PingTarget.values().length;
            }

            @Override
            protected void drawitem(GOut g, PingTarget item, int i) {
                g.text(item.label, Coord.z);
            }

            @Override
            public void change(PingTarget item) {
                super.change(item);
                if (item != null && !loading && editing != null) {
                    editing.category(cat).ping = item;
                    ct.refreshPingId();
                }
            }
        }, new Coord(UI.scale(40), py));
        ct.pingBox.settip("Who Discord notifies on a new or escalated sighting. Repeat alerts never ping. "
                + "Needs the webhook's channel to allow the mention, and members' own notification settings decide whether it rings.", false);
        ct.idEntry = t.add(new TextEntry(UI.scale(150), "") {
            @Override
            protected void changed() {
                if (!loading && editing != null)
                    editing.category(cat).pingId = text().trim();
                ct.refreshPingId();
            }
        }, new Coord(UI.scale(180), py));
        ct.idEntry.settip("Discord ID (17-20 digits). Enable Developer Mode in Discord (Settings > Advanced), "
                + "then right-click the role or user > Copy ID.", false);
        ct.idHint = t.add(new Label(""), new Coord(UI.scale(335), py + UI.scale(3)));
        ct.testPing = t.add(new Button(UI.scale(110), "Test ping", () -> testPing(cat)), new Coord(UI.scale(340), py));
        return t;
    }

    /** Test ping needs a second click within 5 s: it really notifies people. */
    private WatchCategory pingArmed;
    private double pingArmedFor;

    private void testPing(WatchCategory cat) {
        if (editing == null)
            return;
        WatchProfile.Category c = editing.category(cat);
        if (!c.ping.usable(c.pingId)) {
            say("Choose a ping target (and a valid ID for role/user) first", true);
            return;
        }
        if (pingArmed != cat) {
            pingArmed = cat;
            pingArmedFor = 5;
            say("This WILL notify " + c.ping.label + (c.ping.needsId() ? " " + c.pingId : "") + ". Click Test ping again within 5 s to send.", true);
            return;
        }
        pingArmed = null;
        WatchTesting.testPing(gui, editing.copy().normalized(), cat);
        say("Ping test sent for " + cat.label + " (check Discord)", false);
    }

    private final class CategoryTab {
        final WatchCategory category;
        CheckBox enabled;
        NTextArea editor;
        Widget preview;
        NDropbox<PingTarget> pingBox;
        TextEntry idEntry;
        Label idHint;
        Button testPing;

        /** Shows the ID box only for role/user and flags an invalid ID. */
        void refreshPingId() {
            if (pingBox == null || editing == null)
                return;
            WatchProfile.Category c = editing.category(category);
            boolean needs = c.ping.needsId();
            if (needs) {
                idEntry.show();
                idHint.show();
            } else {
                idEntry.hide();
                idHint.hide();
            }
            if (needs) {
                boolean ok = PingTarget.validId(idEntry.text());
                idHint.settext(ok ? "ok" : "bad ID");
                idHint.setcolor(ok ? Color.WHITE : Color.RED);
            }
        }

        CategoryTab(WatchCategory category) {
            this.category = category;
        }

        void refreshPreview() {
            if (preview == null || editing == null)
                return;
            for (Widget w = preview.child; w != null; ) {
                Widget next = w.next;
                w.destroy();
                w = next;
            }
            String text = WatchTemplate.render(editor.text(), WatchTemplate.sampleValues(editing.watcher, category)).replace("**", "");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length && i < PREVIEW_LINES; i++) {
                String line = lines[i];
                if (i == PREVIEW_LINES - 1 && lines.length > PREVIEW_LINES)
                    line = line + " ...";
                preview.add(new Label(line.length() > 90 ? line.substring(0, 87) + "..." : line), new Coord(0, UI.scale(i * 15)));
            }
        }
    }

    /* ------------------------------------------------------------ profiles */

    private void select(String name) {
        WatchProfile p = store.get(name);
        if (p == null)
            p = store.get(WatchProfile.DEFAULT_NAME);
        editing = p;
        names = store.names();
        loading = true;
        try {
            profileBox.change(p.name);
            watcherEntry.settext(p.watcher);
            shotBox.set(p.attachScreenshot);
            numbers.get("scan").load(p.scanIntervalSec);
            numbers.get("grace").load(p.absenceGraceSec);
            numbers.get("cooldown").load(p.cooldownSec);
            for (WatchCategory c : WatchCategory.values()) {
                CategoryTab ct = categoryTabs.get(c);
                ct.enabled.set(p.enabled(c));
                ct.editor.settext(p.category(c).template);
                ct.pingBox.change(p.category(c).ping);
                ct.idEntry.settext(p.category(c).pingId);
            }
        } finally {
            loading = false;
        }
        pingArmed = null;
        for (CategoryTab ct : categoryTabs.values()) {
            ct.refreshPreview();
            ct.refreshPingId();
        }
        say("Loaded profile '" + p.name + "'", false);
    }

    private void beginNew(boolean copy) {
        creatingCopy = copy;
        newName.settext(copy && editing != null ? editing.name + " 2" : "");
        newRow.show();
    }

    private void createProfile() {
        String name = newName.text().trim();
        if (!WatchProfile.validName(name)) {
            say("Profile name must be 1-" + WatchProfile.MAX_NAME_LENGTH + " characters", true);
            return;
        }
        for (String existing : store.names()) {
            if (existing.equalsIgnoreCase(name)) {
                say("A profile named '" + existing + "' already exists", true);
                return;
            }
        }
        WatchProfile p = (creatingCopy && editing != null) ? editing.copy() : new WatchProfile(name);
        p.name = name;
        if (!creatingCopy || p.watcher == null || p.watcher.equals(editing.name))
            p.watcher = name;
        try {
            store.save(p);
        } catch (IOException e) {
            say("Could not save: " + e.getMessage(), true);
            return;
        }
        newRow.hide();
        select(name);
        say("Created profile '" + name + "'", false);
    }

    private void deleteProfile() {
        if (editing == null)
            return;
        String name = editing.name;
        try {
            if (!store.delete(name)) {
                say(WatchProfile.DEFAULT_NAME.equalsIgnoreCase(name) ? "The Default profile cannot be deleted" : "Profile not found", true);
                return;
            }
        } catch (IOException e) {
            say("Could not delete: " + e.getMessage(), true);
            return;
        }
        select(WatchProfile.DEFAULT_NAME);
        say("Deleted profile '" + name + "'", false);
    }

    private boolean persist() {
        if (editing == null)
            return false;
        for (NumberField f : numbers.values()) {
            if (!f.valid) {
                say("Fix the highlighted values first (allowed range shown beside each)", true);
                showTab(0);
                return false;
            }
        }
        for (WatchCategory c : WatchCategory.values()) {
            WatchProfile.Category pc = editing.category(c);
            if (pc.ping.needsId() && !PingTarget.validId(pc.pingId)) {
                say(c.label + " ping: enter a valid Discord " + pc.ping.label.toLowerCase().replace("specific ", "") + " ID (17-20 digits)", true);
                showTab(c.ordinal() + 1);
                return false;
            }
        }
        try {
            store.save(editing);
            store.assign(gui.chrid, editing.name);
        } catch (IOException e) {
            say("Could not save: " + e.getMessage(), true);
            return false;
        }
        if (onSaved != null)
            onSaved.accept(editing.name);
        return true;
    }

    private void save() {
        if (persist())
            say("Saved '" + editing.name + "'. Running lookouts keep the settings they started with.", false);
    }

    private void saveAndStart() {
        if (!persist())
            return;
        if (status.running()) {
            say("Already watching in this session", true);
            return;
        }
        Map<String, Object> settings = new HashMap<>();
        settings.put(VillageWatchBot.SETTING_PROFILE, editing.name);
        BotExecutor.runAsync("Village Watch", new VillageWatchBot(settings));
        say("Started '" + editing.name + "'", false);
    }

    private void stopWatching() {
        Thread t = status.thread;
        if (t != null)
            t.interrupt();
        say("Stopping...", false);
    }

    private void say(String text, boolean error) {
        if (messageLabel == null)
            return;
        messageLabel.settext(text);
        messageLabel.setcolor(error ? Color.RED : Color.WHITE);
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (pingArmed != null && (pingArmedFor -= dt) <= 0)
            pingArmed = null;
        sinceStatus += dt;
        if (sinceStatus >= 0.5 && statusLabel != null) {
            sinceStatus = 0;
            statusLabel.settext(status.text());
            boolean running = status.running();
            if (running) {
                startBtn.hide();
                stopBtn.show();
            } else {
                startBtn.show();
                stopBtn.hide();
            }
        }
    }
}
