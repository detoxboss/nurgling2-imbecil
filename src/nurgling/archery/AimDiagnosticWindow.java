package nurgling.archery;

import haven.*;
import nurgling.archery.AimDiagnostic.Arrow;
import nurgling.archery.AimDiagnostic.Method;
import nurgling.archery.AimDiagnostic.Restart;
import nurgling.archery.AimDiagnostic.Trigger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Controls for the archery re-aim diagnostic. The diagnostic exists only while this window is open:
 * opening it attaches one to this session, closing it cancels any test and detaches. None of the
 * buttons here send anything to the server; only an armed test's single correction does.
 */
public class AimDiagnosticWindow extends Window {
    private static final int W = UI.scale(460);
    private static final double GOB_RANGE = 11 * 20;  // 20 tiles
    private static final Color WARN = new Color(230, 180, 90);

    private final GameUI gui;
    private final AimDiagnostic diag;
    private final ConcurrentLinkedQueue<String> pendingLog = new ConcurrentLinkedQueue<>();
    private volatile String pendingStatus = null;

    private Method method = Method.GROUND;
    private Trigger trigger = Trigger.DELAY;
    private boolean riskOk = false;
    private boolean requireDraw = true;
    private Coord2d point = null;
    private long gobId = -1;
    private long relationId = -1;

    private final Button methodBtn, triggerBtn;
    private final Label riskLbl, pointLbl, gobLbl, relLbl, statusLbl, resultLbl;
    private final TextEntry delayEntry, progEntry, cursorEntry;
    private final Textlog log;

    public static void open(GameUI gui) {
        synchronized (gui.ui) {
            for (Widget w = gui.child; w != null; w = w.next) {
                if (w instanceof AimDiagnosticWindow) {
                    w.raise();
                    return;
                }
            }
            gui.add(new AimDiagnosticWindow(gui), UI.scale(new Coord(140, 90)));
        }
    }

    private AimDiagnosticWindow(GameUI gui) {
        super(Coord.of(W, UI.scale(600)), "Archery Aim Diagnostic");
        this.gui = gui;
        this.diag = AimDiagnosticHub.attach(gui, pendingLog::add, s -> pendingStatus = s);
        int y = 0, gap = UI.scale(4), half = W / 2;

        add(new Label("Temporary experiment: one correction during one manual shot. Off by default."), 0, y);
        y += UI.scale(18);
        Widget en = add(new CheckBox("Diagnostic mode").state(diag::enabled).set(diag::setEnabled), 0, y);
        y += en.sz.y + gap;

        methodBtn = add(new Button(half - gap, "", this::cycleMethod), 0, y);
        triggerBtn = add(new Button(half - gap, "", this::cycleTrigger), half, y);
        y += methodBtn.sz.y + gap;
        riskLbl = add(new Label("", W), 0, y);
        y += UI.scale(16);
        Widget rk = add(new CheckBox("I understand this method's risk").state(() -> riskOk).set(v -> riskOk = v), 0, y);
        y += rk.sz.y + gap;

        add(new Label("Delay ms:"), 0, y + UI.scale(2));
        delayEntry = add(new TextEntry(UI.scale(60), "500"), UI.scale(70), y);
        add(new Label("Progress %:"), UI.scale(150), y + UI.scale(2));
        progEntry = add(new TextEntry(UI.scale(40), "50"), UI.scale(230), y);
        Widget rd = add(new CheckBox("Require draw progress").state(() -> requireDraw).set(v -> requireDraw = v), UI.scale(290), y + UI.scale(2));
        y += Math.max(delayEntry.sz.y, rd.sz.y) + gap;
        add(new Label("Shoot cursor filter (blank = any non-default):"), 0, y + UI.scale(2));
        cursorEntry = add(new TextEntry(UI.scale(150), ""), UI.scale(300), y);
        y += cursorEntry.sz.y + gap * 2;

        Button pb = add(new Button(UI.scale(150), "Set B = my position", this::setPoint), 0, y);
        pointLbl = add(new Label("B: not set", W - UI.scale(160)), UI.scale(160), y + UI.scale(4));
        y += pb.sz.y + gap;
        Button gb = add(new Button(UI.scale(150), "Next nearby gob", this::nextGob), 0, y);
        gobLbl = add(new Label("Gob: none", W - UI.scale(160)), UI.scale(160), y + UI.scale(4));
        y += gb.sz.y + gap;
        Button rb = add(new Button(UI.scale(150), "Next combat relation", this::nextRelation), 0, y);
        relLbl = add(new Label("Relation: none", W - UI.scale(160)), UI.scale(160), y + UI.scale(4));
        y += rb.sz.y + gap * 2;

        Button arm = add(new Button(half - gap, "Arm one test", this::arm), 0, y);
        add(new Button(half - gap, "Cancel test", () -> diag.cancel("cancelled by user")), half, y);
        y += arm.sz.y + gap;
        statusLbl = add(new Label("Disabled.", W), 0, y);
        y += UI.scale(18);

        add(new Label("Your observation of the last test's arrow:"), 0, y);
        y += UI.scale(16);
        int bw = (W - 3 * gap) / 4;
        add(new Button(bw, "To original", () -> observe(Arrow.TOWARD_ORIGINAL, null)), 0, y);
        add(new Button(bw, "To correction", () -> observe(Arrow.TOWARD_CORRECTION, null)), bw + gap, y);
        add(new Button(bw, "Elsewhere", () -> observe(Arrow.ELSEWHERE, null)), 2 * (bw + gap), y);
        Button ns = add(new Button(bw, "Not seen", () -> observe(Arrow.NOT_SEEN, null)), 3 * (bw + gap), y);
        y += ns.sz.y + gap;
        int rw = (W - 2 * gap) / 3;
        add(new Button(rw, "Draw did not restart", () -> observe(null, Restart.NO)), 0, y);
        add(new Button(rw, "Draw restarted", () -> observe(null, Restart.YES)), rw + gap, y);
        Button un = add(new Button(rw, "Unsure", () -> observe(null, Restart.UNSURE)), 2 * (rw + gap), y);
        y += un.sz.y + gap;
        resultLbl = add(new Label("No test yet.", W), 0, y);
        y += UI.scale(18);

        log = add(new Textlog(Coord.of(W, UI.scale(600) - y - UI.scale(20))), 0, y);
        y += log.sz.y + gap;
        add(new Label("Log file: " + AimDiagnosticHub.logPath(gui), W), 0, y);
        refreshLabels();
    }

    private void cycleMethod() {
        method = Method.values()[(method.ordinal() + 1) % Method.values().length];
        riskOk = false;
        refreshLabels();
    }

    private void cycleTrigger() {
        trigger = (trigger == Trigger.DELAY) ? Trigger.PROGRESS : Trigger.DELAY;
        refreshLabels();
    }

    private void refreshLabels() {
        methodBtn.change("Method " + method.label);
        triggerBtn.change(trigger == Trigger.DELAY ? "Trigger: fixed delay" : "Trigger: progress %");
        switch (method) {
            case GROUND:
                riskLbl.settext("Risk: if Shoot is no longer active, this is a walk order to B.");
                break;
            case GOB:
                riskLbl.settext("Risk: if Shoot is no longer active, this walks to the gob. Never pick a player.");
                break;
            default:
                riskLbl.settext("Uses an existing combat relation only; never starts combat.");
                break;
        }
        riskLbl.setcolor(method == Method.FIGHT_PORTRAIT ? Color.WHITE : WARN);
    }

    private Gob player() {
        return (gui.map == null) ? null : gui.map.player();
    }

    private void setPoint() {
        Gob pl = player();
        if (pl == null) {
            statusLbl.settext("No player gob.");
            return;
        }
        point = pl.rc;
        pointLbl.settext(String.format("B: (%.1f, %.1f) - walk away before shooting", point.x, point.y));
    }

    /** Cycles through nearby named gobs, nearest first; players and the own character are left out. */
    private void nextGob() {
        Gob pl = player();
        if (pl == null)
            return;
        List<Gob> found = new ArrayList<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.id == pl.id || g.ngob == null || g.ngob.name == null || g.ngob.name.startsWith("gfx/borka"))
                    continue;
                if (g.rc.dist(pl.rc) <= GOB_RANGE)
                    found.add(g);
            }
        }
        found.sort(Comparator.comparingDouble(g -> g.rc.dist(pl.rc)));
        if (found.isEmpty()) {
            gobId = -1;
            gobLbl.settext("Gob: none within 20 tiles");
            return;
        }
        int next = 0;
        for (int i = 0; i < found.size(); i++)
            if (found.get(i).id == gobId)
                next = (i + 1) % found.size();
        Gob g = found.get(next);
        gobId = g.id;
        gobLbl.settext(String.format("Gob %d %s, %.0f units", g.id, g.ngob.name, g.rc.dist(pl.rc)));
    }

    private void nextRelation() {
        Fightview fv = gui.fv;
        List<Long> ids = new ArrayList<>();
        if (fv != null)
            for (Fightview.Relation r : fv.lsrel)
                ids.add(r.gobid);
        if (ids.isEmpty()) {
            relationId = -1;
            relLbl.settext("Relation: none (method D unavailable)");
            return;
        }
        int i = ids.indexOf(relationId);
        relationId = ids.get((i + 1) % ids.size());
        boolean cur = fv.current != null && fv.current.gobid == relationId;
        relLbl.settext("Relation gob " + relationId + (cur ? " (current target)" : " (not current)"));
    }

    private void arm() {
        long delay;
        int pct;
        try {
            delay = Long.parseLong(delayEntry.text().trim());
            pct = Integer.parseInt(progEntry.text().trim());
        } catch (NumberFormatException e) {
            statusLbl.settext("Delay and progress must be whole numbers.");
            return;
        }
        AimDiagnostic.Config c = new AimDiagnostic.Config(method, trigger, delay, pct, requireDraw, riskOk,
            point, gobId, relationId, cursorEntry.text());
        String err = diag.arm(c);
        if (err != null)
            statusLbl.settext(err);
    }

    private void observe(Arrow a, Restart r) {
        if (!diag.recordObservation(a, r))
            statusLbl.settext("Nothing to record: no finished test.");
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        String s;
        while ((s = pendingLog.poll()) != null)
            log.append(s);
        String st = pendingStatus;
        if (st != null) {
            pendingStatus = null;
            statusLbl.settext(st);
        }
        AimDiagnostic.Result r = diag.lastResult();
        String sum = (r == null) ? "No test yet." : ("Verdict: " + AimDiagnostic.verdict(r) + "  (arrow " + r.arrow + ", restart " + r.restart + ")");
        if (!sum.equals(resultLbl.text()))
            resultLbl.settext(sum);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && msg.equals("close")) {
            reqdestroy();
            return;
        }
        super.wdgmsg(sender, msg, args);
    }

    @Override
    public void destroy() {
        AimDiagnosticHub.detach(gui, "diagnostic window closed");
        super.destroy();
    }
}
