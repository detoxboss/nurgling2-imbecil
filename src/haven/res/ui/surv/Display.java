/* Preprocessed source code */
package haven.res.ui.surv;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import nurgling.survey.SurveySettings;
import nurgling.survey.SurveySettings.VState;
import java.util.*;
import java.nio.*;
import java.awt.Color;
import static haven.MCache.tilesz;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

@haven.FromResource(name = "ui/surv", version = 46)
public class Display implements RenderTree.Node, TickList.Ticking, TickList.TickNode {
    public static final Attribute v_elev = new Attribute(FLOAT);
    public static final Attribute v_flags = new Attribute(INT);
    public static final AutoVarying f_elev = new AutoVarying(FLOAT) {
	    public Expression root(VertexContext vctx) {return(v_elev.ref());}
	};
    public static final AutoVarying f_flags = new AutoVarying(INT) {
	    {ipol = Interpol.FLAT;}
	    public Expression root(VertexContext vctx) {return(v_flags.ref());}
	};
    public static final VertexArray.Layout fmt =
	new VertexArray.Layout(new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 16),
			       new VertexArray.Layout.Input(v_flags,       new VectorFormat(1, NumberFormat.UINT8),   0, 12, 16),
			       new VertexArray.Layout.Input(v_elev,        new VectorFormat(1, NumberFormat.UNORM8),  0, 13, 16));
    public final Data data;
    public final MapView mv;
    public final MCache map;
    public final VertexArray va;
    public final Model surface, vertices, wire;
    public Area tarea;
    public Coord selected = null;
    public boolean active = false, update;
    /* Terrain-constraint vertex markers (fork addition, Phase 2). Vertices touching a confirmed
     * violation or an unverifiable edge, rendered as a separate halo-style pass -- never alters
     * v_flags bits 1/2 (fill/dig), so the normal status dot colour stays intact underneath. */
    public Set<Coord> violatingVertices = Collections.emptySet();
    public Set<Coord> uncertainVertices = Collections.emptySet();
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    private final Map<RenderTree.Slot, RenderTree.Slot> selslots = new IdentityHashMap<>();
    private final Map<RenderTree.Slot, RenderTree.Slot> wireslots = new IdentityHashMap<>();
    private Model selection = null;
    private Area curSel = null;
    private int mapseq = -1, dataseq = -1;

    public Display(Data data, MapView mv) {
	this.data = data;
	this.mv = mv;
	this.map = mv.ui.sess.glob.map;
	Area varea = data.varea;
	this.va = new VertexArray(fmt, new VertexArray.Buffer(varea.area() * fmt.inputs[0].stride, DataBuffer.Usage.STREAM, this::vfill)).shared();
	this.tarea = Area.corn(varea.ul, varea.br.sub(1, 1));
	this.surface = new Model(Model.Mode.TRIANGLES, va, new Model.Indices(tarea.area() * 6, NumberFormat.UINT16, DataBuffer.Usage.STREAM, this::sfill));
	this.vertices = new Model(Model.Mode.POINTS, va, null);
	this.wire = new Model(Model.Mode.LINES, va, new Model.Indices(wirecount(varea), NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(wireidx(varea))));
    }

    private static int wirecount(Area varea) {
	int w = varea.br.x - varea.ul.x, h = varea.br.y - varea.ul.y;
	return(((w - 1) * h + w * (h - 1)) * 2);
    }

    private static short[] wireidx(Area varea) {
	short[] ind = new short[wirecount(varea)];
	int o = 0;
	for(int y = varea.ul.y; y < varea.br.y; y++) {
	    for(int x = varea.ul.x; x < varea.br.x - 1; x++) {
		ind[o++] = (short)varea.ridx(Coord.of(x, y));
		ind[o++] = (short)varea.ridx(Coord.of(x + 1, y));
	    }
	}
	for(int x = varea.ul.x; x < varea.br.x; x++) {
	    for(int y = varea.ul.y; y < varea.br.y - 1; y++) {
		ind[o++] = (short)varea.ridx(Coord.of(x, y));
		ind[o++] = (short)varea.ridx(Coord.of(x, y + 1));
	    }
	}
	return(ind);
    }

    private Coord3f orig() {
	return(Coord3f.of(data.varea.ul.x * (float)tilesz.x, data.varea.ul.y * (float)tilesz.y, 0));
    }

    private Coord3f vpos(Coord vc) {
	return(Coord3f.of((vc.x - data.varea.ul.x) * (float)tilesz.x,
			  (vc.y - data.varea.ul.y) * (float)tilesz.y,
			  data.dz[data.varea.ridx(vc)] / data.gran));
    }

    public float zsize(Coord vc) {
	Coord3f vpos = vpos(vc).add(orig());
	return(mv.screenxf(vpos).dist(mv.screenxf(vpos.add(0, 0, 1 / data.gran))));
    }

    private FillBuffer vfill(VertexArray.Buffer dst, Environment env) {
	float E = 0.001f;
	FillBuffer ret = env.fillbuf(dst);
	ByteBuffer buf = ret.push();
	Coord ul = data.varea.ul;
	data.eupdate();
	for(Coord vc : data.varea) {
	    float tz;
	    try {
		tz = (float)map.getfz(vc);
	    } catch(Loading l) {
		tz = 0;
	    }
	    float vz = data.dz[data.varea.ridx(vc)] / data.gran;
	    buf.putFloat((vc.x - ul.x) * (float)tilesz.x).putFloat(-(vc.y - ul.y) * (float)tilesz.y).putFloat(vz);
	    byte fl = 0;
	    if(vz - tz > E)
		fl |= 1;
	    else if(tz - vz > E)
		fl |= 2;
	    if(Utils.eq(vc, selected)) {
		fl |= 4;
		if(active)
		    fl |= 8;
	    }
	    if(violatingVertices.contains(vc))
		fl |= 16;
	    else if(uncertainVertices.contains(vc))
		fl |= 32;
	    buf.put(fl);
	    buf.put((byte)((255 * (vz - data.lo)) / (data.hi - data.lo)));
	    buf.put((byte)0).put((byte)0);
	}
	return(ret);
    }

    private FillBuffer sfill(Model.Indices dst, Environment env) {
	Area varea = data.varea;
	FillBuffer ret = env.fillbuf(dst);
	ShortBuffer buf = ret.push().asShortBuffer();
	float[] mz = new float[varea.area()];
	for(Coord vc : varea) {
	    try {
		mz[varea.ridx(vc)] = (float)map.getfz(vc);
	    } catch(Loading l) {}
	}
	for(Coord tc : tarea) {
	    if(Math.abs(mz[varea.ridx(tc.add(0, 0))] - mz[varea.ridx(tc.add(1, 1))]) > Math.abs(mz[varea.ridx(tc.add(1, 0))] - mz[varea.ridx(tc.add(0, 1))])) {
		buf.put((short)varea.ridx(tc.add(0, 0))); buf.put((short)varea.ridx(tc.add(0, 1))); buf.put((short)varea.ridx(tc.add(1, 0)));
		buf.put((short)varea.ridx(tc.add(1, 0))); buf.put((short)varea.ridx(tc.add(0, 1))); buf.put((short)varea.ridx(tc.add(1, 1)));
	    } else {
		buf.put((short)varea.ridx(tc.add(0, 0))); buf.put((short)varea.ridx(tc.add(0, 1))); buf.put((short)varea.ridx(tc.add(1, 1)));
		buf.put((short)varea.ridx(tc.add(0, 0))); buf.put((short)varea.ridx(tc.add(1, 1))); buf.put((short)varea.ridx(tc.add(1, 0)));
	    }
	}
	return(ret);
    }

    /* --- Fork-owned, settings-driven appearance. Every uniform below reads
     * nurgling.survey.SurveySettings (backed by NConfig) with zero Pipe dependency, the same
     * idiom as Glob.u_globtime: the value function runs fresh on every draw, so colour/opacity/
     * size/visibility changes made in Nurgling Settings -> Game environment -> Survey Settings
     * take effect immediately, with no shader recompilation and no vertex/index buffer rebuild. */
    public static final Uniform u_colMatch = new Uniform(VEC4, "survcolm", p -> SurveySettings.vertexFColor(VState.MATCH));
    public static final Uniform u_colFill  = new Uniform(VEC4, "survcolf", p -> SurveySettings.vertexFColor(VState.FILL));
    public static final Uniform u_colDig   = new Uniform(VEC4, "survcold", p -> SurveySettings.vertexFColor(VState.DIG));
    public static final Uniform u_szMatch  = new Uniform(FLOAT, "survszm", p -> SurveySettings.vertexSize(VState.MATCH));
    public static final Uniform u_szFill   = new Uniform(FLOAT, "survszf", p -> SurveySettings.vertexSize(VState.FILL));
    public static final Uniform u_szDig    = new Uniform(FLOAT, "survszd", p -> SurveySettings.vertexSize(VState.DIG));
    public static final Uniform u_haloCol  = new Uniform(VEC4, "survhcol", p -> SurveySettings.selectionHighlightFColor());
    public static final Uniform u_haloSz   = new Uniform(FLOAT, "survhsz", p -> SurveySettings.selectionHighlightSize());

    public static final Function vcol = new Function.Def(VEC4) {{
	code.add(new If(ne(bitand(f_flags.ref(), l(1)), l(0)), new Return(u_colFill.ref())));
	code.add(new If(ne(bitand(f_flags.ref(), l(2)), l(0)), new Return(u_colDig.ref())));
	code.add(new Return(u_colMatch.ref()));
    }};
    public static final Function vsize = new Function.Def(FLOAT) {{
	code.add(new If(ne(bitand(v_flags.ref(), l(1)), l(0)), new Return(u_szFill.ref())));
	code.add(new If(ne(bitand(v_flags.ref(), l(2)), l(0)), new Return(u_szDig.ref())));
	code.add(new Return(u_szMatch.ref()));
    }};
    public static final ShaderMacro vshader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> vcol.call(), 0);
	prog.vctx.ptsz.mod(in -> vsize.call(), 0);
	prog.vctx.ptsz.force();
    };
    public static final Pipe.Op vdraw = Pipe.Op.compose(new RUtils.AdHoc(vshader), States.Depthtest.none, States.maskdepth,
							new Rendered.Order.Default(20103));

    /* Halo pass redraws every vertex through the same point model, but only the flag-4
     * ("selected") vertex gets non-zero size; everything else collapses to a zero-size point.
     * This keeps the selected point's own status colour untouched (preserving what it's telling
     * you about terrain) and layers a separately configurable ring on top instead of overriding
     * it, per the Survey Settings "selected/hovered point highlight" requirement. */
    public static final Function halocol = new Function.Def(VEC4) {{
	code.add(new If(eq(bitand(f_flags.ref(), l(4)), l(0)), new Return(vec4(l(0.0), l(0.0), l(0.0), l(0.0)))));
	code.add(new Return(u_haloCol.ref()));
    }};
    public static final Function halosize = new Function.Def(FLOAT) {{
	code.add(new If(eq(bitand(v_flags.ref(), l(4)), l(0)), new Return(l(0.0))));
	code.add(new Return(u_haloSz.ref()));
    }};
    public static final ShaderMacro haloshader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> halocol.call(), 0);
	prog.vctx.ptsz.mod(in -> halosize.call(), 0);
	prog.vctx.ptsz.force();
    };
    public static final Pipe.Op halodraw = Pipe.Op.compose(new RUtils.AdHoc(haloshader), States.Depthtest.none, States.maskdepth,
							   new Rendered.Order.Default(20106));

    /* Terrain-constraint vertex markers (fork addition, Phase 2): a third halo-style pass, same
     * idiom as the selection halo above -- it never touches the status dot's own colour (bits
     * 1/2/flags), just layers a ring on top when bit 16 (confirmed violation, checked first so
     * it takes priority) or bit 32 (unverifiable) is set. */
    public static final Uniform u_violCol = new Uniform(VEC4, "survvcol",
	p -> SurveySettings.fcolor(SurveySettings.TERRAIN_VIOLATION_COLOR, 100));
    public static final Uniform u_uncertainCol = new Uniform(VEC4, "survucol",
	p -> SurveySettings.fcolor(SurveySettings.TERRAIN_UNKNOWN_COLOR, 100));
    public static final Uniform u_violSz = new Uniform(FLOAT, "survvsz", p -> SurveySettings.TERRAIN_MARKER_SIZE);
    public static final Function violcol = new Function.Def(VEC4) {{
	code.add(new If(ne(bitand(f_flags.ref(), l(16)), l(0)), new Return(u_violCol.ref())));
	code.add(new If(ne(bitand(f_flags.ref(), l(32)), l(0)), new Return(u_uncertainCol.ref())));
	code.add(new Return(vec4(l(0.0), l(0.0), l(0.0), l(0.0))));
    }};
    public static final Function violsize = new Function.Def(FLOAT) {{
	code.add(new If(eq(bitand(v_flags.ref(), l(48)), l(0)), new Return(l(0.0))));
	code.add(new Return(u_violSz.ref()));
    }};
    public static final ShaderMacro violshader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> violcol.call(), 0);
	prog.vctx.ptsz.mod(in -> violsize.call(), 0);
	prog.vctx.ptsz.force();
    };
    public static final Pipe.Op violdraw = Pipe.Op.compose(new RUtils.AdHoc(violshader), States.Depthtest.none, States.maskdepth,
							    new Rendered.Order.Default(20107));

    public static final Uniform u_fillLow   = new Uniform(VEC4, "survflo", p -> SurveySettings.fcolor(SurveySettings.fillLowColor(), 100));
    public static final Uniform u_fillHigh  = new Uniform(VEC4, "survfhi", p -> SurveySettings.fcolor(SurveySettings.fillHighColor(), 100));
    public static final Uniform u_fillAlpha = new Uniform(FLOAT, "survfa", p -> SurveySettings.fillVisible() ? (SurveySettings.fillOpacity() / 100f) : 0f);
    public static final Uniform u_heatmap   = new Uniform(FLOAT, "survhm", p -> SurveySettings.heatmapEnabled() ? 1f : 0f);
    public static final Uniform u_hmMatch   = new Uniform(VEC4, "survhmm", p -> SurveySettings.vertexFColor(VState.MATCH));
    public static final Uniform u_hmFill    = new Uniform(VEC4, "survhmf", p -> SurveySettings.vertexFColor(VState.FILL));
    public static final Uniform u_hmDig     = new Uniform(VEC4, "survhmd", p -> SurveySettings.vertexFColor(VState.DIG));

    /* Default (non-heatmap) mode keeps the original two-stop gradient idea (now configurable,
     * default orange->pink) rather than the original's exact three-colour piecewise curve; the
     * visual difference is a smoother single mix instead of a sharp midpoint, called out in the
     * manual test checklist. Heatmap mode (opt-in) instead colours each surface fragment by the
     * same dig/fill/match classification as the vertex dots, flat per status. */
    public static final Function scol = new Function.Def(VEC4) {{
	code.add(new If(ne(u_heatmap.ref(), l(0.0)),
			new Block(
			    new If(ne(bitand(f_flags.ref(), l(1)), l(0)), new Return(u_hmFill.ref())),
			    new If(ne(bitand(f_flags.ref(), l(2)), l(0)), new Return(u_hmDig.ref())),
			    new Return(u_hmMatch.ref())
			)));
	code.add(new Return(mix(u_fillLow.ref(), u_fillHigh.ref(), f_elev.ref())));
    }};
    public static final ShaderMacro sshader = prog -> {
	FragColor.fragcol(prog.fctx).mod(in -> mul(mul(in, scol.call()), vec4(l(1.0), l(1.0), l(1.0), u_fillAlpha.ref())), 0);
    };
    public static final Pipe.Op sdraw1 = Pipe.Op.compose(FragColor.slot.nil, new States.DepthBias(-2, -2), new Rendered.Order.Default(20100), States.facecull.nil);
    public static final Pipe.Op sdraw2 = Pipe.Op.compose(new RUtils.AdHoc(sshader), new States.DepthBias(-2, -2), States.facecull.nil,
							 new Rendered.Order.Default(20101));

    private static Pipe.Op lineOp(Color col, int opacitypct, int width, int order) {
	return(Pipe.Op.compose(new BaseColor(SurveySettings.fcolor(col, opacitypct)), new States.LineWidth(Math.max(1, width)),
			       States.Depthtest.none, States.maskdepth, new Rendered.Order.Default(order)));
    }

    public void setTerrainMarkers(Set<Coord> violating, Set<Coord> uncertain) {
	this.violatingVertices = violating;
	this.uncertainVertices = uncertain;
	update = true;
    }

    public void select(Area area) {
	curSel = area;
	if(selection != null) {
	    selslots.values().forEach(RenderTree.Slot::remove);
	    selslots.clear();
	    selection.dispose();
	    selection = null;
	}
	if(area != null) {
	    short[] ind = new short[(((area.br.x - area.ul.x) + (area.br.y - area.ul.y)) * 2) + 1];
	    int o = 0;
	    for(int x = area.ul.x; x < area.br.x; x++)
		ind[o++] = (short)data.varea.ridx(Coord.of(x, area.ul.y));
	    for(int y = area.ul.y; y < area.br.y; y++)
		ind[o++] = (short)data.varea.ridx(Coord.of(area.br.x, y));
	    for(int x = area.br.x; x > area.ul.x; x--)
		ind[o++] = (short)data.varea.ridx(Coord.of(x, area.br.y));
	    for(int y = area.br.y; y >= area.ul.y; y--)
		ind[o++] = (short)data.varea.ridx(Coord.of(area.ul.x, y));
	    selection = new Model(Model.Mode.LINE_STRIP, va, new Model.Indices(ind.length, NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(ind)));
	    if(SurveySettings.outlineVisible()) {
		Pipe.Op seldraw = lineOp(SurveySettings.outlineColor(), SurveySettings.outlineOpacity(), SurveySettings.outlineWidth(), 20104);
		for(RenderTree.Slot slot : slots)
		    selslots.put(slot, slot.add(selection, seldraw));
	    }
	}
    }

    private void addWire(RenderTree.Slot slot) {
	if(SurveySettings.wireVisible()) {
	    Pipe.Op wiredraw = lineOp(SurveySettings.wireColor(), SurveySettings.wireOpacity(), SurveySettings.wireWidth(), 20102);
	    wireslots.put(slot, slot.add(wire, wiredraw));
	}
    }

    /**
     * Rebuilds the outline/wireframe render passes from current settings. Colour/opacity/size of
     * the vertex dots, halo and surface fill never need this -- they read live through the
     * zero-dependency uniforms above -- but a visibility toggle or a line-width change for the
     * outline/wireframe has to add or remove a whole render pass, which this does on demand
     * (called by the Survey Settings panel's save()). Cheap: no shader recompilation, just
     * Pipe.Op/Slot bookkeeping for two small static line models.
     */
    public void refreshStyle() {
	select(curSel);
	wireslots.values().forEach(RenderTree.Slot::remove);
	wireslots.clear();
	for(RenderTree.Slot slot : slots)
	    addWire(slot);
    }

    public void added(RenderTree.Slot slot) {
	slot.ostate(Location.xlate(Coord3f.of(data.varea.ul.x * (float)tilesz.x, -data.varea.ul.y * (float)tilesz.y, 0)));
	slot.add(surface, sdraw1);
	slot.add(surface, sdraw2);
	addWire(slot);
	slot.add(vertices, vdraw);
	slot.add(vertices, halodraw);
	slot.add(vertices, violdraw);
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
	selslots.remove(slot);
	wireslots.remove(slot);
    }

    public void autogtick(Render g) {
	if((mapseq != map.chseq) || (dataseq != data.seq) || update) {
	    g.update(va.bufs[0], this::vfill);
	    g.update(surface.ind, this::sfill);
	    mapseq = map.chseq;
	    dataseq = data.seq;
	    update = false;
	}
    }

    public TickList.Ticking ticker() {return(this);}

    public Coord mousetest(Coord mc, boolean any) {
	Coord sel = null;
	float minz = 0;
	float mind = 0;
	Coord3f orig = orig();
	for(Coord vc : data.varea) {
	    Coord3f sc = mv.screenxf(vpos(vc).add(orig));
	    if(!any) {
		if(Math.hypot(sc.x - mc.x, sc.y - mc.y) < UI.scale(10)) {
		    if((sel == null) || (sc.z < minz)) {
			sel = vc;
			minz = sc.z;
		    }
		}
	    } else {
		float d = (float)Math.hypot(sc.x - mc.x, sc.y - mc.y);
		if((sel == null) || (d < mind)) {
		    sel = vc;
		    mind = d;
		}
	    }
	}
	return(sel);
    }
}

/* >wdg: LandSurvey */
