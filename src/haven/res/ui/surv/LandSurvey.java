/* Preprocessed source code */
package haven.res.ui.surv;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import nurgling.NStyle;
import nurgling.conf.NDragProp;
import nurgling.survey.SurveyConstraintResult;
import nurgling.survey.SurveyConstraintValidator;
import nurgling.survey.SurveyDragConstraintSolver;
import nurgling.survey.SurveyEditHistory;
import nurgling.survey.SurveySettings;
import nurgling.survey.SurveyTerrainSnapshot;
import nurgling.survey.SurveyVertexDiagnostics;
import nurgling.widgets.NDraggableWidget;

import java.util.*;
import java.util.Collections;
import java.nio.*;
import java.awt.Color;
import java.awt.event.KeyEvent;
import static haven.MCache.tilesz;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

@haven.FromResource(name = "ui/surv", version = 46)
public class LandSurvey extends Window {
    public final Area area;
    public final Data data;
    public final Label zdlbl, wlbl, dlbl, errlbl;
    public final Label terrlbl;
    public Area selection;
    private boolean inited = false;
    private final boolean isNew;
    private MapView mv;
    private Display dsp;
    private RenderTree.Slot s_dsp;
    private final SurveyEditHistory history = new SurveyEditHistory();
    private Button btnUndo, btnRedo;
    /* Investigation diagnostic: optional, read-only vertex inspector (hover a vertex, same
     * selection dsp.selected already tracks in Idle mode -- no new mouse listener). Never
     * affects editing; purely for comparing the current edge-only rule (Model A) against an
     * all-corners-per-tile rule (Model B) using real terrain + target data side by side. */
    private boolean diagEnabled = false;
    private Button btnDiag;
    private Label[] diagLines;
    /** The raw, pre-"/11" value of mkwidget's args[2], kept purely for the diagnostics panel's
     *  unit-provenance line (Follow-up #4) -- never used in any arithmetic itself, since
     *  data.gran (computed once in mkwidget) is already the authoritative derived value. */
    private final float rawGranArg;

    private ICheckBox btnLock;

    /* Fork addition: lets the Survey Settings panel push outline/wireframe changes to every
     * currently open survey window without a client restart. Membership only, no state. */
    private static final Set<LandSurvey> open = Collections.newSetFromMap(new WeakHashMap<>());
    public static void refreshAllStyles() {
	for(LandSurvey s : open)
	    if(s.dsp != null)
		s.dsp.refreshStyle();
    }

    public LandSurvey(Area area, Data data, boolean isNew, float rawGranArg) {
	super(Coord.z, "Land survey", true);
	this.area = area;
	this.data = data;
	this.isNew = isNew;
	this.rawGranArg = rawGranArg;
	Widget prev = add(new Label(String.format("Area: %d m²", area.area())), 0, 0);
	add(btnLock = new ICheckBox(NStyle.locki[0], NStyle.locki[1], NStyle.locki[2], NStyle.locki[3])
	{
		/* The lock icon's transparent centre otherwise rejects clicks there (alpha-based
		 * hit test in ICheckBox.checkhit): the whole visible button rectangle should
		 * register, survey-lock-specific rather than a blanket ICheckBox change. */
		@Override
		public boolean checkhit(Coord c) {
		    return(c.isect(Coord.z, sz));
		}

		@Override
		public Object tooltip(Coord c, Widget prev) {
		    return(a ? "Survey editing is locked. Click to unlock."
			     : "Survey editing is unlocked. Click to lock.");
		}

	    @Override
	    public void changed(boolean val)
	    {
		super.changed(val);
		lock(val);
	    }
	}, prev.pos("ur").add(UI.scale(200) - NStyle.locki[0].sz().x - NStyle.locki[0].sz().x / 2, 0));
	/* Optimistic client-side default only -- the server's own "lock" message (handled in
	 * uimsg below) always wins once it arrives. A brand-new survey defaults to unlocked (per
	 * the "Survey Settings -> new surveys start unlocked" preference) so the creator isn't
	 * stuck clicking through a locked icon; an existing survey being reopened defaults to
	 * locked, the safe guess, until the server confirms otherwise. */
	btnLock.a = isNew ? !SurveySettings.newSurveyUnlocked() : true;
	zdlbl = add(new Label("..."), prev.pos("bl").adds(0, 1));
	wlbl = add(new Label("..."), zdlbl.pos("bl").adds(0, 1));
	dlbl = add(new Label("..."), wlbl.pos("bl").adds(0, 1));
	errlbl = add(new Label(""), dlbl.pos("bl").adds(0, 1));
	terrlbl = add(new Label(""), errlbl.pos("bl").adds(0, 1));
	prev = add(new Button(UI.scale(125), "Ground level", false).action(this::initsurf),
		   terrlbl.pos("bl").adds(0, 10));
	add(new Button(UI.scale(125), "Ground plane", false).action(this::initplane),
	    prev.pos("ur").adds(10, 0));
	prev = add(new Button(UI.scale(125), "Dig", false).action(() -> wdgmsg("lvl")),
		   prev.pos("bl").adds(0, 10));
	add(new Button(UI.scale(125), "Remove", false).action(()-> wdgmsg("rm")),
	    prev.pos("ur").adds(10, 0));
	prev = add(btnUndo = new Button(UI.scale(60), "Undo", false).action(this::undo),
		   prev.pos("bl").adds(0, 10));
	add(btnRedo = new Button(UI.scale(60), "Redo", false).action(this::redo),
	    prev.pos("ur").adds(10, 0));
	prev = add(btnDiag = new Button(UI.scale(260), "Diagnostics: off", false).action(this::toggleDiag),
		   prev.pos("bl").adds(0, 10));
	diagLines = new Label[SurveyVertexDiagnostics.LINE_COUNT];
	for(int i = 0; i < diagLines.length; i++) {
	    diagLines[i] = add(new Label(""), prev.pos("bl").adds(0, (i == 0) ? 10 : 2));
	    prev = diagLines[i];
	}

	pack();
    }

    public static Widget mkwidget(UI ui, Object... args) {
	Area area = Area.corn((Coord)args[0], (Coord)args[1]);
	float rawGranArg = ((Number)args[2]).floatValue();
	float gran = rawGranArg / 11;
	Data data = new Data(Area.corni(area.ul, area.br), gran);
	boolean isNew = (args[3] == null);
	LandSurvey srv = new LandSurvey(area, data, isNew, rawGranArg);
	if(!isNew) {
	    data.decode(Utils.iv(args[3]), (byte[])args[4]);
	    srv.inited = true;
	}
	return(srv);
    }

    protected void attached() {
	super.attached();
	this.mv = getparent(GameUI.class).map;
	this.dsp = new Display(data, mv);
	s_dsp = mv.drawadd(dsp);
	select(area);
	mode(new Idle());
	open.add(this);
    }

    private void initsurf() {
	MCache map = mv.ui.sess.glob.map;
	float[] before = data.wz.clone();
	for(Coord vc : data.varea)
	    data.wz[data.varea.ridx(vc)] = data.dz[data.varea.ridx(vc)] = (int)Math.round(map.getfz(vc) * data.gran);
	commit(before);
    }

    private void initplane() {
	MCache map = mv.ui.sess.glob.map;
	float[] before = data.wz.clone();
	double zs = 0;
	int nv = 0;
	for(Coord vc : data.varea) {
	    zs += map.getfz(vc);
	    nv++;
	}
	int z = Math.round((float)(zs / nv) * data.gran);
	for(int i = 0; i < data.wz.length; i++)
	    data.wz[i] = data.dz[i] = z;
	commit(before);
    }

    /** Pushes a whole-array edit (Ground Level/Ground Plane) and sends it. Fixes a verified bug:
     * neither action previously called send(), so the server never learned about the new
     * target heights until some unrelated drag triggered a send(). */
    private void commit(float[] before) {
	int[] idx = new int[data.wz.length];
	for(int i = 0; i < idx.length; i++)
	    idx[i] = i;
	history.push(idx, before, data.wz.clone());
	data.seq++;
	upd = true;
	send();
    }

    private void updmap() {
	MCache map = mv.ui.sess.glob.map;
	int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
	int sd = 0, hn = 0;
	for(Coord vc : data.varea) {
	    int vz = Math.round((float)map.getfz(vc) * data.gran);
	    int tz = data.dz[data.varea.ridx(vc)];
	    min = Math.min(min, vz); max = Math.max(max, vz);
	    sd += tz - vz;
	    if(vz > tz)
		hn += vz - tz;
	}
	/* Follow-up #4 fix: this used to hardcode "/ 10.0", silently assuming gran is always
	 * 10f/11f (so gran*tilesz.x == 10) -- an assumption the diagnostic investigation found
	 * false for at least one live survey (gran observed == 1). Over actual terrain (vz), same
	 * conversion the target side already uses via metersPerDzUnit. */
	zdlbl.settext(String.format("Peak to trough: %.2f m", (max - min) * SurveyConstraintValidator.metersPerDzUnit(data.gran)));
	if(sd >= 0)
	    wlbl.settext(String.format("Units of soil required: %d", sd));
	else
	    wlbl.settext(String.format("Units of soil left over: %d", -sd));
	dlbl.settext(String.format("Units of soil to dig: %d", hn));
	refreshTerrainStatus(map);
    }

    /* --- Phase 2: terrain slope constraint engine integration --- */

    private SurveyTerrainSnapshot terrainSnapshot;
    private int terrainSnapshotMapSeq = -1;
    /** Diagnostic only: the most recent Constrain-mode clamp result, surfaced on the status
     *  line so a clamp's limiting edge/amount can be checked in-game without guessing. Cleared
     *  whenever a gesture ends (mode(new Idle())), not persisted, never affects behavior. */
    private SurveyDragConstraintSolver.ClampResult lastClamp;

    /** Snapshot is cheap to rebuild but not free (probes a one-vertex ring around the whole
     *  survey); cached per gesture/tick and only rebuilt when the map actually changed. */
    SurveyTerrainSnapshot terrainSnapshot(MCache map) {
	if((terrainSnapshot == null) || (terrainSnapshotMapSeq != map.chseq)) {
	    terrainSnapshot = SurveyTerrainSnapshot.capture(map, data.varea);
	    terrainSnapshotMapSeq = map.chseq;
	}
	return terrainSnapshot;
    }

    /** Idle (non-dragging) status: are there any over-limit edges in the currently committed
     *  shape at all (new or pre-existing), and is any nearby terrain unverifiable. Skips the
     *  check entirely in Off mode. */
    private void refreshTerrainStatus(MCache map) {
	SurveyConstraintValidator.Mode mode = SurveySettings.terrainConstraintMode();
	if(mode == SurveyConstraintValidator.Mode.OFF) {
	    terrlbl.settext("");
	    dsp.setTerrainMarkers(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
	    return;
	}
	/* Follow-up #6: the shared, gran-aware policy limit (RAW_TICK_LIMIT ticks converted through
	 * this survey's own live gran) -- no longer the old fixed TERRAIN_MAX_SLOPE_METERS, which
	 * was found to disagree with the real in-game boundary at gran=1. */
	double limit = SurveyConstraintValidator.limitMetersFor(data.gran);
	SurveyConstraintResult res = SurveyConstraintValidator.validateAbsolute(data.varea, data.gran, data.dz,
										 terrainSnapshot(map), limit);
	/* Marker colour and the idle status line are driven purely by Severity (the edge's
	 * absolute relationship to the real limit), not Status (the worsening-aware blocking
	 * decision) -- a pre-existing, unworsened cliff is still genuinely over the limit and
	 * should show red, same as a freshly-created one; see SurveyConstraintResult.Severity's
	 * class doc for why the two are kept orthogonal. */
	Set<Coord> over = new HashSet<>(), unk = new HashSet<>(), atLimit = new HashSet<>();
	int overCount = 0, atLimitCount = 0, unkCount = 0;
	double worst = 0;
	for(SurveyConstraintResult.EdgeFinding f : res.findings) {
	    switch(f.severity) {
	    case OVER_LIMIT:
		over.add(f.a); over.add(f.b);
		overCount++;
		worst = Math.max(worst, f.candidateDiffMeters);
		break;
	    case AT_LIMIT:
		atLimit.add(f.a); atLimit.add(f.b);
		atLimitCount++;
		break;
	    case UNKNOWN:
		unk.add(f.a); unk.add(f.b);
		unkCount++;
		break;
	    default:
		break;
	    }
	}
	dsp.setTerrainMarkers(over, unk, atLimit);
	String base;
	if(overCount > 0)
	    base = String.format("Terrain: %d edge(s) over %.2fm limit (worst %.2fm)",
				  overCount, limit, worst);
	else if(atLimitCount > 0)
	    base = String.format("Terrain: %d edge(s) at the %.2fm limit (further raising blocked, lowering still OK)",
				  atLimitCount, limit);
	else if(unkCount > 0)
	    base = String.format("Terrain: %d nearby edge(s) unverifiable (unloaded)", unkCount);
	else
	    base = "Terrain: OK";
	if((mode == SurveyConstraintValidator.Mode.CONSTRAIN) && (lastClamp != null) && (lastClamp.limitingEdge != null)) {
	    SurveyConstraintValidator.Edge le = lastClamp.limitingEdge;
	    SurveyTerrainSnapshot terrain = terrainSnapshot(map);
	    double mpd = SurveyConstraintValidator.metersPerDzUnit(data.gran);
	    Double targetDiff = edgeTargetDiffMeters(le, terrain, mpd);
	    Double ha = terrain.terrainHeight(le.a), hb = terrain.terrainHeight(le.b);
	    Double actualDiff = ((ha != null) && (hb != null)) ? Math.abs(ha - hb) : null;
	    base += String.format(" [clamped at %s-%s%s%s]", le.a, le.b,
				   (targetDiff != null) ? String.format(", target %.2fm", targetDiff) : "",
				   (actualDiff != null) ? String.format(", actual %.2fm", actualDiff) : "");
	}
	terrlbl.settext(base);
    }

    /** Diagnostic helper: the limiting edge's current target-height difference in metres, for
     *  whichever endpoint(s) are inside the survey (dz) vs. just outside it (actual terrain). */
    private Double edgeTargetDiffMeters(SurveyConstraintValidator.Edge e, SurveyTerrainSnapshot terrain, double mpd) {
	boolean inA = data.varea.contains(e.a), inB = data.varea.contains(e.b);
	if(inA && inB)
	    return Math.abs(data.dz[data.varea.ridx(e.a)] - data.dz[data.varea.ridx(e.b)]) * mpd;
	Coord insideC = inA ? e.a : (inB ? e.b : null);
	Coord outsideC = inA ? e.b : (inB ? e.a : null);
	if(insideC == null)
	    return null;
	Double oh = terrain.terrainHeight(outsideC);
	if(oh == null)
	    return null;
	return Math.abs((data.dz[data.varea.ridx(insideC)] * mpd) - oh);
    }

    /** Clamps a Mover/CMover gesture's requested diff to the largest amount that doesn't newly
     *  violate (or worsen) the slope rule, in Constrain mode only -- Off/Warn never alter the
     *  drag itself; Warn's live feedback instead comes from updmap()'s per-tick absolute check
     *  above, which already runs every tick during a drag since it sets upd=true. */
    float clampDrag(int[] gidx, float[] sz, float[] coeff, int[] priorDz, float requestedDiff) {
	if((SurveySettings.terrainConstraintMode() != SurveyConstraintValidator.Mode.CONSTRAIN) || (requestedDiff == 0))
	    return requestedDiff;
	SurveyTerrainSnapshot terrain = terrainSnapshot(mv.ui.sess.glob.map);
	SurveyDragConstraintSolver.ClampResult cr = SurveyDragConstraintSolver.clamp(
	    data.varea, data.gran, priorDz, gidx, sz, coeff, requestedDiff, terrain,
	    SurveyConstraintValidator.limitMetersFor(data.gran));
	lastClamp = cr;
	return cr.diff;
    }

    private void toggleDiag() {
	diagEnabled = !diagEnabled;
	btnDiag.change(diagEnabled ? "Diagnostics: on" : "Diagnostics: off");
	if(!diagEnabled)
	    for(Label l : diagLines)
		l.settext("");
    }

    /** Fills the diagnostic panel from the currently hovered/selected vertex (dsp.selected,
     *  already tracked by Idle's mouse-move handling for the existing highlight), or clears it
     *  with a hint if nothing is selected. Read-only: never touches data/dz, history, or
     *  clamping. */
    private void updateDiag(MCache map) {
	Coord vc = dsp.selected;
	if(vc == null) {
	    diagLines[0].settext("Hover over a vertex to inspect it.");
	    for(int i = 1; i < diagLines.length; i++)
		diagLines[i].settext("");
	    pack(); // re-fit the window now that long diagnostic lines may have shrunk back down
	    return;
	}
	SurveyTerrainSnapshot terrain = terrainSnapshot(map);
	double limit = SurveyConstraintValidator.limitMetersFor(data.gran);
	SurveyVertexDiagnostics.Report r = SurveyVertexDiagnostics.compute(
	    data.varea, data.gran, rawGranArg, data.dz, terrain, limit, vc);
	SurveyConstraintValidator.ModelComparison cmp = SurveyConstraintValidator.compareModels(
	    data.varea, data.gran, data.dz, terrain, limit);
	String[] lines = SurveyVertexDiagnostics.formatLines(r, cmp);
	for(int i = 0; i < diagLines.length; i++)
	    diagLines[i].settext(lines[i]);
	/* Follow-up #4 fix: Label.settext() resizes the Label itself (haven.Label#settext calls
	 * resize()), but nothing previously re-packed the Window around it -- these lines start
	 * as "" at construction time, so the window's initial pack() sized itself far too narrow
	 * for the real diagnostic text, which then rendered clipped at the window's edge. pack()
	 * is cheap when nothing actually changed size (Widget#resize no-ops on an unchanged
	 * Coord), so calling it every tick the panel is open is fine. */
	pack();
    }

    private void lock(boolean enabled) {
	wdgmsg("lock", enabled ? 0 : 1);
    }

    /**
     * Sends the current target heights. Height safety (fork addition): Data.encode() now
     * rejects spans that no longer fit the protocol's unsigned-byte relative encoding instead of
     * silently wrapping. On rejection the submission is dropped, the editable state and undo
     * history are left intact, and the user gets a visible error -- per spec, this does not
     * clamp or otherwise alter the data.
     */
    private void send() {
	try {
	    wdgmsg("data", data.encode());
	    errlbl.settext("");
	} catch(HeightRangeExceededException e) {
	    errlbl.settext(String.format("Height span too large to submit (%d > %d). Undo to continue.",
					  e.hi - e.lo, Data.MAX_SPAN));
	}
    }

    private void undo() {
	if(history.undo(data)) {
	    data.dupdate();
	    data.seq++;
	    upd = true;
	    send();
	}
    }

    private void redo() {
	if(history.redo(data)) {
	    data.dupdate();
	    data.seq++;
	    upd = true;
	    send();
	}
    }

    /* Ctrl+Z/Ctrl+Y (and Ctrl+Shift+Z) only fire here: Window.keydown is only reached once no
     * focused child (chat, a text field, inventory, ...) has already consumed the key, so this
     * never steals input meant elsewhere. */
    public boolean keydown(KeyDownEvent ev) {
	if(ui.modctrl) {
	    if((ev.code == KeyEvent.VK_Z) && !ui.modshift) {
		undo();
		return(true);
	    }
	    if((ev.code == KeyEvent.VK_Y) || ((ev.code == KeyEvent.VK_Z) && ui.modshift)) {
		redo();
		return(true);
	    }
	}
	return(super.keydown(ev));
    }

    private EventHandler<MouseEvent> mode = null;
    public void mode(EventHandler<MouseEvent> nmode) {
	if(mode != null)
	    mv.deafen(mode);
	if(nmode != null)
	    mv.listen(MouseEvent.class, nmode);
	mode = nmode;
    }

    public class Idle implements EventHandler<Widget.MouseEvent> {
	public boolean handle(MouseEvent ev) {
		if(btnLock.a)
			return false;
	    if(ev instanceof MouseMoveEvent) {
		Coord sel = dsp.mousetest(ev.c, false);
		if(!Utils.eq(sel, dsp.selected)) {
		    dsp.selected = sel;
		    dsp.update = true;
		}
	    } else if(ev instanceof MouseDownEvent) {
		if(((MouseDownEvent)ev).b == 1) {
		    Coord sel = dsp.mousetest(ev.c, false);
		    if(sel != null) {
			if(ui.modshift) {
			    mode(new Selector(sel));
			} else {
			    Area vsel = (selection == null) ? null : Area.corn(selection.ul, selection.br.add(1, 1));
			    if((selection != null) && (sel.x == selection.ul.x) && (sel.y == selection.ul.y)) {
				mode(new CMover(sel, vsel, "ul", ev.c));
			    } else if((selection != null) && (sel.x == selection.br.x) && (sel.y == selection.ul.y)) {
				mode(new CMover(sel, vsel, "ur", ev.c));
			    } else if((selection != null) && (sel.x == selection.br.x) && (sel.y == selection.br.y)) {
				mode(new CMover(sel, vsel, "br", ev.c));
			    } else if((selection != null) && (sel.x == selection.ul.x) && (sel.y == selection.br.y)) {
				mode(new CMover(sel, vsel, "bl", ev.c));
			    } else if((vsel != null) && vsel.contains(sel)) {
				mode(new Mover(sel, vsel, ev.c));
			    } else {
				mode(new Mover(sel, Area.sized(sel, Coord.of(1, 1)), ev.c));
			    }
			}
			return(true);
		    }
		}
	    }
	    return(false);
	}
    }

    public void select(Area area) {
	dsp.select(this.selection = area);
    }

    public class Selector implements EventHandler<Widget.MouseEvent> {
	public final Coord sv;
	public final UI.Grab grab;

	public Selector(Coord vc) {
	    this.sv = vc;
	    dsp.select(Area.sized(vc.min(area.br.sub(1, 1)), Coord.z));
	    grab = mv.ui.grabmouse(mv);
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		Coord sel = dsp.mousetest(ev.c, true);
		dsp.select(Area.corn(sel.min(sv), sel.max(sv)));
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    Coord v = dsp.mousetest(ev.c, true);
		    Area sel = Area.corn(v.min(sv), v.max(sv));
		    if(sel.area() == 0)
			select(null);
		    else
			select(sel);
		    grab.remove();
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    public class Mover implements EventHandler<Widget.MouseEvent> {
	public final Coord vc, sc;
	public final Area area;
	public final UI.Grab grab;
	public final float[] sz;
	public final int[] gidx;
	public final float[] coeff;
	public final int[] priorDz;
	public final float zsz;

	public Mover(Coord vc, Area area, Coord sc) {
	    this.vc = vc;
	    this.sc = sc;
	    this.area = area;
	    this.zsz = dsp.zsize(vc);
	    this.sz = new float[area.area()];
	    this.gidx = new int[area.area()];
	    this.coeff = new float[area.area()];
	    for(Coord iv : area) {
		gidx[area.ridx(iv)] = data.varea.ridx(iv);
		this.sz[area.ridx(iv)] = data.wz[data.varea.ridx(iv)];
		this.coeff[area.ridx(iv)] = 1f;
	    }
	    this.priorDz = data.dz.clone();
	    grab = mv.ui.grabmouse(mv);
	    dsp.active = true;
	    dsp.update = true;
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		float diff = Math.round((sc.y - ev.c.y) / zsz);
		diff = clampDrag(gidx, sz, coeff, priorDz, diff);
		for(Coord mv : area)
		    data.wz[data.varea.ridx(mv)] = sz[area.ridx(mv)] + diff;
		data.dupdate();
		data.seq++;
		upd = true;
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    dsp.active = false;
		    dsp.update = true;
		    grab.remove();
		    float[] after = new float[gidx.length];
		    for(int i = 0; i < gidx.length; i++)
			after[i] = data.wz[gidx[i]];
		    history.push(gidx, sz, after);
		    send();
		    lastClamp = null;
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    public class CMover implements EventHandler<Widget.MouseEvent> {
	public final Coord vc, sc;
	public final String corn;
	public final Area area;
	public final UI.Grab grab;
	public final float[] sz;
	public final int[] gidx;
	public final float[] coeff;
	public final int[] priorDz;
	public final float zsz;

	public CMover(Coord vc, Area area, String corn, Coord sc) {
	    this.vc = vc;
	    this.sc = sc;
	    this.area = area;
	    this.corn = corn;
	    this.zsz = dsp.zsize(vc);
	    this.sz = new float[area.area()];
	    this.gidx = new int[area.area()];
	    this.coeff = new float[area.area()];
	    float ul = corn.equals("ul") ? 1 : 0;
	    float ur = corn.equals("ur") ? 1 : 0;
	    float br = corn.equals("br") ? 1 : 0;
	    float bl = corn.equals("bl") ? 1 : 0;
	    float iw = 1f / (area.br.x - 1 - area.ul.x), ih = 1f / (area.br.y - 1 - area.ul.y);
	    for(Coord iv : area) {
		gidx[area.ridx(iv)] = data.varea.ridx(iv);
		this.sz[area.ridx(iv)] = data.wz[data.varea.ridx(iv)];
		float fx = (iv.x - area.ul.x) * iw, fy = (iv.y - area.ul.y) * ih;
		this.coeff[area.ridx(iv)] = (((ul * (1 - fx)) + (ur * fx)) * (1 - fy)) +
					    (((bl * (1 - fx)) + (br * fx)) * fy);
	    }
	    this.priorDz = data.dz.clone();
	    grab = mv.ui.grabmouse(mv);
	    dsp.active = true;
	    dsp.update = true;
	}

	public boolean handle(MouseEvent ev) {
	    if(ev instanceof MouseMoveEvent) {
		float diff = Math.round((sc.y - ev.c.y) / zsz);
		diff = clampDrag(gidx, sz, coeff, priorDz, diff);
		for(Coord mv : area)
		    data.wz[data.varea.ridx(mv)] = sz[area.ridx(mv)] + (coeff[area.ridx(mv)] * diff);
		data.dupdate();
		data.seq++;
		upd = true;
	    } else if(ev instanceof MouseUpEvent) {
		if(((MouseButtonEvent)ev).b == 1) {
		    dsp.active = false;
		    dsp.update = true;
		    grab.remove();
		    float[] after = new float[gidx.length];
		    for(int i = 0; i < gidx.length; i++)
			after[i] = data.wz[gidx[i]];
		    history.push(gidx, sz, after);
		    send();
		    lastClamp = null;
		    mode(new Idle());
		    return(true);
		}
	    }
	    return(false);
	}
    }

    private boolean upd = true;
    private int mapseq = -1;
    public void tick(double dt) {
	if(!inited) {
	    try {
		initplane();
		inited = true;
	    } catch(Loading l) {}
	}
	if(inited) {
	    if(upd || (mapseq != mv.ui.sess.glob.map.chseq)) {
		try {
		    updmap();
		    mapseq = mv.ui.sess.glob.map.chseq;
		    upd = false;
		} catch(Loading l) {
		}
	    }
	}
	if(btnUndo != null) {
	    btnUndo.disable(!history.canUndo());
	    btnRedo.disable(!history.canRedo());
	}
	if(diagEnabled && inited) {
	    try {
		updateDiag(mv.ui.sess.glob.map);
	    } catch(Loading l) {
	    }
	}
	super.tick(dt);
    }

    public void uimsg(String name, Object... args) {
	if(name == "data") {
	    float[] pre = data.wz.clone();
	    data.decode(Utils.iv(args[0]), (byte[])args[1]);
	    /* If this echoes our own just-sent edit, wz comes back unchanged and history survives
	     * intact. If it reflects someone (or something) else's change instead, replaying our
	     * local undo/redo entries over it would clobber that change, so drop the history. */
	    history.onExternalData(pre, data.wz);
	    upd = true;
	} else if(name == "lock") {
	    btnLock.a = !Utils.bv(args[0]);
	} else {
	    super.uimsg(name, args);
	}
    }

    public void destroy() {
	open.remove(this);
	mode(null);
	if(s_dsp != null)
	    s_dsp.remove();
	super.destroy();
    }
}
