/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import nurgling.*;
import nurgling.conf.*;
import nurgling.i18n.L10n;
import nurgling.widgets.*;

import java.awt.Color;
import java.awt.Font;
import java.util.*;
import java.text.Collator;
import static haven.PType.*;

public class BuddyWnd extends Widget implements Iterable<BuddyWnd.Buddy> {
    public List<Buddy> buddies = new ArrayList<Buddy>();
    private Map<Integer, Buddy> idmap = new HashMap<Integer, Buddy>();
    private BuddyList bl;
    private TextEntry pname, charpass, opass;
    private FlowerMenu menu;
    protected BuddyInfo info = null;
    private Widget infof;
    public int serial = 0;
    public static final int width = UI.scale(263);
    public static final int margin1 = UI.scale(5);
    public static final int margin2 = 2 * margin1;
    public static final int margin3 = 2 * margin2;
    public static final int offset = UI.scale(35);
    public static final Tex online = Resource.loadtex("gfx/hud/online");
    public static final Tex offline = Resource.loadtex("gfx/hud/offline");
    /**
     * Total number of distinct, assignable Kin/Village permission groups (0..{@code ncolors}-1).
     * Not the same concept as {@link #nquick} (how many of them get a one-click colour square on
     * the compact {@link GroupSelector} row) or {@code gc.length} (the safe server-range backing
     * table below) - keep those three distinct rather than substituting one for another.
     */
    public static final int ncolors = 40;
    /**
     * How many low-numbered groups get an always-visible, one-click colour square on the compact
     * {@link GroupSelector} row. Groups {@code nquick..ncolors-1} are still fully assignable, just
     * only reachable through the numeric companion control ({@code nurgling.widgets.NGroupSelectorAugmenter}),
     * not a colour square - this is what keeps the selector exactly one row tall regardless of how
     * many groups {@link #ncolors} grows to.
     */
    public static final int nquick = 8;
    /**
     * The server accepts kin groups beyond what the picker exposes; keep the backing table large
     * enough for server IDs and only expose {@link #ncolors} entries as selectable in the UI.
     */
    public static final Color[] gc = new Color[255];
    private static final Color[] named = {
	new Color(255, 255, 255),
	new Color(0, 255, 0),
	new Color(255, 0, 0),
	new Color(0, 0, 255),
	new Color(0, 255, 255),
	new Color(255, 255, 0),
	new Color(255, 0, 255),
	new Color(255, 0, 128),
	new Color(255, 128, 0),
	new Color(128, 255, 0),
	new Color(255, 128, 255),
	new Color(128, 128, 255),
	new Color(128, 255, 255),
	new Color(255, 200, 128),
	new Color(200, 255, 128),
	new Color(255, 128, 64),
	new Color(128, 255, 64),
	new Color(64, 255, 128),
	new Color(64, 128, 255),
	new Color(128, 64, 255),
	new Color(255, 64, 192),
	new Color(255, 64, 64),
	new Color(255, 192, 0),
	new Color(192, 255, 0),
	new Color(0, 255, 192),
	new Color(0, 192, 255),
	new Color(192, 0, 255),
	new Color(255, 0, 192),
	// Groups 28..39: added to grow the assignable range from 28 to 40 groups. Colours
	// 0..27 above are untouched so no existing buddy's on-screen colour changes. None of
	// these get a quick-square button (see nquick above) - they're reachable only through
	// the numeric companion control (nurgling.widgets.NGroupSelectorAugmenter/-Companion).
	new Color(255, 96, 0),
	new Color(96, 255, 0),
	new Color(0, 255, 150),
	new Color(0, 150, 255),
	new Color(150, 0, 255),
	new Color(255, 0, 150),
	new Color(255, 150, 200),
	new Color(150, 255, 200),
	new Color(200, 150, 255),
	new Color(255, 215, 90),
	new Color(180, 220, 255),
	new Color(255, 255, 200),
    };
    static {
	if(named.length != ncolors)
	    throw(new IllegalStateException("named.length (" + named.length + ") != ncolors (" + ncolors + ")"));
	System.arraycopy(named, 0, gc, 0, named.length);
	Arrays.fill(gc, named.length, gc.length, named[0]);
    }
    public static Color gcolor(int group) {
	return(((group >= 0) && (group < gc.length)) ? gc[group] : gc[0]);
    }

    public static int pcolor(Color color) {
	/* Marker colors outside the selectable palette map back to the default selectable group. */
	for(int i = 0; i < ncolors; i++) {
	    if(Objects.equals(gc[i], color))
		return(i);
	}
	return(0);
    }

	private Comparator<Buddy> bcmp;
    private Comparator<Buddy> alphacmp = new Comparator<Buddy>() {
	private Collator c = Collator.getInstance();
	public int compare(Buddy a, Buddy b) {
	    return(c.compare(a.name, b.name));
	}
    };
    private Comparator<Buddy> groupcmp = new Comparator<Buddy>() {
	public int compare(Buddy a, Buddy b) {
	    if(a.group == b.group) return(alphacmp.compare(a, b));
	    else                   return(a.group - b.group);
	}
    };
    private Comparator<Buddy> statuscmp = new Comparator<Buddy>() {
	public int compare(Buddy a, Buddy b) {
	    return Double.compare(b.atime,a.atime);
	}
    };
    
    @RName("buddy")
    public static class $_ implements Factory {
	public Widget create(UI ui, Object[] args) {
	    return(new NBuddyWnd());
	}
    }
    
    public class Buddy {
	public int id;
	public String name;
	public long atime;
	public Text lastOnline = null;

	public double upTime = 0;

	public int online;
	public int group;
	public boolean seen;
	public Map<Object, Object> notes = Collections.emptyMap();

	public Buddy(int id, String name, int online, int group, boolean seen) {
	    this.id = id;
	    this.name = name;
	    this.online = online;
	    this.group = group;
	    this.seen = seen;
	}

	public void forget() {
	    wdgmsg("rm", id);
	}

	public void endkin() {
	    wdgmsg("rm", id);
	}

	public void chat() {
	    wdgmsg("chat", id);
	}

	public void invite() {
	    wdgmsg("inv", id);
	}

	public void describe() {
	    wdgmsg("desc", id);
	}

	public void chname(String name) {
	    wdgmsg("nick", id, name);
	}

	public void chgrp(int grp) {
	    wdgmsg("grp", id, grp);
	}

	public void note(Object key, Object val) {
	    Map<Object, Object> nn = new HashMap<>(notes);
	    nn.put(key, val);
	    MessageBuf buf = new MessageBuf();
	    buf.addmap(nn);
	    wdgmsg("notes", id, buf.fin());
	}

	private void chstatus(int status) {
	    online = status;
	    GameUI gui = getparent(GameUI.class);
	    if(gui != null) {
		if(status == 1)
		    ui.msg(L10n.get("kin.msg_online", name));
	    }
	}

	private Text rname = null;
	public Text rname() {
	    if((rname == null) || !rname.text.equals(name))
		rname = Text.render(name);
	    return(rname);
	}

	/** "[N]" suffix shown next to the name in the buddy list; presentation only, never touches {@link #name}. */
	private Text grouptag = null;
	private int grouptagGroup = -1;
	public Text grouptag() {
	    if((grouptag == null) || (grouptagGroup != group)) {
		grouptag = Text.render("[" + group + "]");
		grouptagGroup = group;
	    }
	    return(grouptag);
	}

	public Map<String, Runnable> opts() {
	    Map<String, Runnable> opts = new LinkedHashMap<>();
	    if(online >= 0) {
		opts.put(L10n.get("kin.opt_chat"), this::chat);
		if(online == 1)
		    opts.put(L10n.get("kin.opt_invite"), this::invite);
		opts.put(L10n.get("kin.opt_end_kinship"), this::endkin);
	    } else {
		opts.put(L10n.get("kin.opt_forget"), this::forget);
	    }
	    if(seen)
		opts.put(L10n.get("kin.opt_describe"), this::describe);
	    return(opts);
	}
    }
    
    public Iterator<Buddy> iterator() {
	synchronized(buddies) {
	    return(new ArrayList<Buddy>(buddies).iterator());
	}
    }
    
    public Buddy find(int id) {
	synchronized(buddies) {
	    return(idmap.get(id));
	}
    }

    /** Small stroked digit label drawn inside a {@link GroupRect}; white-on-black stays readable over any {@link #gc} colour. */
    private static final Text.Foundry numfnd = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 9).aa(true);
    private static final Map<Integer, Tex> numtexcache = new HashMap<>();
    /** Shared so other selector-style pickers (e.g. {@code NKinSettings}) can draw the same numbers without duplicating the font/cache. */
    public static Tex numtex(int group) {
	return(numtexcache.computeIfAbsent(group, g -> Text.renderstroked(Integer.toString(g), Color.WHITE, Color.BLACK, numfnd).tex()));
    }

    public static class GroupRect extends Widget {
	final private static Coord offset = UI.scale(new Coord(2, 2));
	final private static Coord selsz = UI.scale(new Coord(19, 19));
	final private static Coord colsz = selsz.sub(offset.mul(2));
	final private GroupSelector selector;
	final private int group;
	private boolean selected;

	public GroupRect(GroupSelector selector, int group, boolean selected) {
	    super(new Coord(margin3, margin3));
	    this.selector = selector;
	    this.group = group;
	    this.selected = selected;
	}

	public void draw(GOut g) {
	    if (selected) {
		g.chcolor(Color.LIGHT_GRAY);
		g.frect(Coord.z, selsz);
	    }
	    g.chcolor(gcolor(group));
	    g.frect(offset, colsz);
	    g.chcolor();
	    g.aimage(numtex(group), offset.add(colsz.div(2)), 0.5, 0.5);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    selector.select(group);
	    return(true);
	}

	public void select() {
	    selected = true;
	}

	public void unselect() {
	    selected = false;
	}

	public Object tooltip(Coord c, Widget prev) {
	    return(Text.render(selector.grouptip(group)).tex());
	}
    }

    /**
     * Compact, one-row quick-colour picker for groups {@code 0..nquick-1}. Kept byte-for-byte close to
     * its original upstream shape, because it is not only Kin that embeds it: the Village and Realm
     * permission windows and the personal-claim ("Stake") permission window are all server-distributed
     * resource code with no source in this repository, and each constructs and lays out an instance of
     * this exact class directly (not through any {@code @RName} factory - see the reverted {@code grp}
     * factory below) assuming it stays roughly one row tall. Growing this class itself to show more than
     * {@link #nquick} groups previously broke those resource windows' own Banish/Forget/permission-row
     * layouts.
     *
     * <p>Access to the full 0..{@link #ncolors}-1 range (or a narrower range, for the claim window - see
     * {@code nurgling.widgets.NGroupSelectorAugmenter}) is added without touching this class's shape or
     * API, via the two narrow lifecycle hooks below: whichever code constructs and {@code add()}s an
     * instance, {@link #attached()} lets a Nurgling-owned companion detect it once it is genuinely part
     * of a live widget tree (not merely constructed - a resource window commonly finishes building its
     * whole child tree in its own constructor before that constructor's own instance is itself attached
     * to anything, so classifying by ancestor before {@link #attached()} fires would be unreliable), and
     * {@link #dispose()} lets that companion clean itself up when this selector's subtree is torn down,
     * however that teardown was triggered.
     */
    public static class GroupSelector extends Widget {
	private static final int cols = Math.min(10, nquick);
	private static final int rows = Math.max(1, (nquick + cols - 1) / cols);
	/** This class's own footprint (currently {@code cols x rows} one row), so a client-side
	 *  extension can size itself to fit wherever a plain instance of this class already fits,
	 *  without duplicating the cols/rows formula above. */
	public static final Coord basesz = new Coord(cols * margin3, rows * margin3);
	public int group;
	public GroupRect[] groups = new GroupRect[nquick];

	public GroupSelector(int group) {
	    super(new Coord(cols * margin3, rows * margin3));
	    this.group = group;
	    for (int i = 0; i < nquick; ++i) {
		groups[i] = new GroupRect(this, i, group == i);
		add(groups[i], new Coord((i % cols) * margin3, (i / cols) * margin3));
	    }
	}

	protected void changed(int group) {
	}

	/** Overridden by {@code NLabeledGroupSelector} to append a custom label; plain group number here. */
	protected String grouptip(int group) {
	    return(L10n.get("group.tooltip", group));
	}

	public void update(int group) {
	    if(group == this.group)
		return;
	    if((this.group >= 0) && (this.group < groups.length))
		groups[this.group].unselect();
	    this.group = group;
	    if((group >= 0) && (group < groups.length))
		groups[group].select();
	}

	public void select(int group) {
	    update(group);
	    changed(group);
	}

	protected void attached() {
	    super.attached();
	    NGroupSelectorAugmenter.attached(this);
	}

	public void dispose() {
	    NGroupSelectorAugmenter.detached(this);
	    super.dispose();
	}
    }

	static public String lastOnline(long checktime, Buddy b, BuddyInfo bi)
	{
		String text;
		if(b.online == 1) {
			if(bi!=null)
				bi.utime = 0;
			text = L10n.get("kin.online_now");
		} else {
			int au, atime = (int)((long)Utils.ntime() - checktime);
			String unitSingular;
			String unitPlural;
			if(atime >= (604800 * 2)) {
				au = 604800;
				unitSingular = L10n.get("kin.time_week");
				unitPlural = L10n.get("kin.time_weeks");
			} else if(atime >= 86400) {
				au = 86400;
				unitSingular = L10n.get("kin.time_day");
				unitPlural = L10n.get("kin.time_days");
			} else if(atime >= 3600) {
				au = 3600;
				unitSingular = L10n.get("kin.time_hour");
				unitPlural = L10n.get("kin.time_hours");
			} else if(atime >= 60) {
				au = 60;
				unitSingular = L10n.get("kin.time_minute");
				unitPlural = L10n.get("kin.time_minutes");
			} else {
				au = 1;
				unitSingular = L10n.get("kin.time_second");
				unitPlural = L10n.get("kin.time_seconds");
			}
			int am = atime / au;
			if(bi!=null)
				bi.utime = checktime + ((am + 1) * au);
			String unit = (am > 1) ? unitPlural : unitSingular;
			text = am + " " + unit + " " + L10n.get("kin.time_ago");
		}
		return text;
	}

    @RName("grp")
    public static class $grp implements Factory {
	public Widget create(UI ui, Object[] args) {
	    /* This factory is how the (server-resource-driven) Village permission UI asks for a
	     * group selector by name, for both its top-level and per-member pickers, so the label
	     * editor added here covers both of those Village states as well as Kin below. */
	    return(new NLabeledGroupSelector(INT.of(args[0]), NGroupLabels.Scope.VILLAGE) {
		    public void changed(int group) {
			wdgmsg("ch", group);
		    }
		});
	}
    }

    public class BuddyInfo extends Widget {
	public final Buddy buddy;
	private final Avaview ava;
	private final TextEntry nick;
	private final GroupSelector grp;
	private long atime, utime;
	private Label atimel = null;
	private Button[] opts = {};

	public BuddyInfo(Coord sz, Buddy buddy) {
	    super(sz);
	    this.buddy = buddy;
	    this.ava = adda(new Avaview(Avaview.dasz, -1, "avacam"), sz.x / 2, margin2, 0.5, 0);
	    Frame.around(this, this.ava);
	    this.nick = add(new TextEntry(sz.x - margin3, buddy.name) {
		    {dshow = true;}
		    public void activate(String text) {
			buddy.chname(text);
		    }
		}, margin2, ava.c.y + ava.sz.y + margin2);
	    this.grp = add(new NLabeledGroupSelector(buddy.group, NGroupLabels.Scope.KIN) {
		    public void changed(int group) {
			buddy.chgrp(group);
		    }
		}, margin2, nick.c.y + nick.sz.y + margin2);
	    setopts();
	}

	public void draw(GOut g) {
	    g.chcolor(0, 0, 0, 128);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	    super.draw(g);
	}

	public void tick(double dt) {
	    if((utime != 0) && (Utils.ntime() >= utime))
		setatime();
	}

	private void setatime() {
	    if(atimel != null)
		ui.destroy(atimel);
	    atimel = add(new Label(L10n.get("kin.last_seen") + " " + lastOnline(atime, buddy, this)), margin2, grp.c.y + grp.sz.y + margin2);
	}

	private void setopts() {
	    for(Button opt : this.opts)
		ui.destroy(opt);
	    Map<String, Runnable> bopts = buddy.opts();
	    List<Button> opts = new ArrayList<>(bopts.size());
	    int y = grp.c.y + grp.sz.y + offset;
	    for(Map.Entry<String, Runnable> opt : bopts.entrySet()) {
		Button btn = add(new Button(sz.x - margin3, opt.getKey(), false, opt.getValue()), margin2, y);
		y = btn.c.y + btn.sz.y + margin1;
		opts.add(btn);
	    }
	    this.opts = opts.toArray(new Button[0]);
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "i-ava") {
		Composited.Desc desc = Composited.Desc.decode(ui.sess, OBJS.of(args[0]));
		Resource.Resolver map = new Resource.Resolver.ResourceMap(ui.sess, OBJS.of(args[1]));
		ava.pop(desc, map);
	    } else if(msg == "i-atime") {
		atime = (long)Utils.ntime() - NUM.of(args[0]).longValue();
		setatime();
	    } else {
		super.uimsg(msg, args);
	    }
	}

	public void update() {
	    nick.settext(buddy.name);
	    nick.commit();
	    grp.update(buddy.group);
	    setatime();
	    setopts();
	}
    }

    /** Extension point: nurgling's NBuddyWnd returns a subclass that adds the kin note box. */
    protected BuddyInfo makeinfo(Coord sz, Buddy buddy) {
	return(new BuddyInfo(sz, buddy));
    }

    private class BuddyList extends SSearchBox<Buddy, Widget> {
	public BuddyList(Coord sz) {
	    super(sz, margin3);
	}

	public List<Buddy> allitems() {
		List<Buddy> visbuddies = new ArrayList<>();
		for(Buddy b: buddies)
		{
			if(!NKinProp.get(b.group).hideinlist)
				visbuddies.add(b);
		}
		return(visbuddies);
	}
	public boolean searchmatch(Buddy b, String txt) {return(b.name.toLowerCase().indexOf(txt.toLowerCase()) >= 0);}

	public Widget makeitem(Buddy b, int idx, Coord sz) {
	    return(new ItemWidget<Buddy>(this, sz, b) {
		    public void draw(GOut g) {
			if(item.online == 1)
			    g.aimage(online, Coord.of(sz.y / 2), 0.5, 0.5);
			else if(item.online == 0)
			    g.aimage(offline, Coord.of(sz.y / 2), 0.5, 0.5);
			g.chcolor(gcolor(b.group));
			Tex nametex = b.rname().tex();
			Coord namec = Coord.of(sz.y + margin1, sz.y / 2);
			g.aimage(nametex, namec, 0.0, 0.5);
			g.chcolor(Color.LIGHT_GRAY);
			g.aimage(b.grouptag().tex(), Coord.of(namec.x + nametex.sz().x + margin1, namec.y), 0.0, 0.5);
			if(b.lastOnline!=null)
				g.aimage(b.lastOnline.tex(), Coord.of(sz.x - b.lastOnline.tex().sz().x - margin1,sz.y / 2), 0.0, 0.5);
			g.chcolor();
		    }

		    public boolean mousedown(MouseDownEvent ev) {
			if(ev.b == 1)
			    change(item);
			else if(ev.b == 3)
			    opts(b, ui.mc);
			return(true);
		    }
		});
	}

		protected void drawbg(GOut g) {
	    g.chcolor(0, 0, 0, 128);
	    g.frect(Coord.z, sz);
	    g.chcolor();
	}

	protected void drawbg(GOut g, Buddy item, int idx, Area area) {
	}

	public void draw(GOut g) {
	    super.draw(g);
	    if(buddies.size() == 0)
		g.atext(L10n.get("kin.alone_in_world"), sz.div(2), 0.5, 0.5);
	}
	
	public void change(Buddy b) {
	    if(b == null) {
		BuddyWnd.this.wdgmsg("ch", (Object)null);
	    } else {
		BuddyWnd.this.wdgmsg("ch", b.id);
	    }
	}

	public void opts(final Buddy b, Coord c) {
	    if(menu == null) {
		Map<String, Runnable> bopts = b.opts();
		menu = new NFlowerMenu(bopts.keySet().toArray(new String[0])) {
			public void destroy() {
			    menu = null;
			    super.destroy();
			}
			
			public void nchoose(NPetal opt) {
			    if(opt != null) {
				Runnable act = bopts.get(opt.name);
				if(act != null)
				    act.run();
				uimsg("act", opt.num);
			    } else {
				uimsg("cancel");
			    }
			}
		    };
		ui.root.add(menu, c);
	    }
	}
    }

    public BuddyWnd() {
	super(new Coord(width, 0));
	setfocustab(true);
	Widget prev;
        prev = add(new Img(CharWnd.catf.render(L10n.get("kin.window_title")).tex()));

	bl = add(new BuddyList(Coord.of(sz.x - Window.wbox.bisz().x, UI.scale(140))), prev.pos("bl").add(Window.wbox.btloff()));
	prev = Frame.around(this, Collections.singletonList(bl));

	prev = add(new Label(L10n.get("kin.sort_by")), prev.pos("bl").adds(0, 5));
	int sbw = ((sz.x + margin1) / 3) - margin1;
	addhl(prev.pos("bl").adds(0, 2), sz.x,
	      prev = new Button(sbw, L10n.get("kin.sort_status")).action(() -> { setcmp(statuscmp); }),
	             new Button(sbw, L10n.get("kin.sort_group") ).action(() -> { setcmp(groupcmp); }),
	             new Button(sbw, L10n.get("kin.sort_name")  ).action(() -> { setcmp(alphacmp); })
	      );
	String sort = Utils.getpref("buddysort", "");
	if(sort.equals("")) {
	    bcmp = statuscmp;
	} else {
	    if(sort.equals("alpha"))  bcmp = alphacmp;
	    if(sort.equals("group"))  bcmp = groupcmp;
	    if(sort.equals("status")) bcmp = statuscmp;
	}

	prev = add(new Label(L10n.get("kin.presentation_name")), prev.pos("bl").adds(0, 10));
	pname = add(new TextEntry(sz.x, "") {
		{dshow = true;}
		public void activate(String text) {
		    setpname(text);
		}
	    }, prev.pos("bl").adds(0, 2));
	prev = add(new Button(sbw, L10n.get("kin.btn_set")).action(() -> {
		    setpname(pname.text());
	}), pname.pos("bl").adds(0, 5));

	prev = add(new Label(L10n.get("kin.hearth_secret")), prev.pos("bl").adds(0, 10));
	charpass = add(new TextEntry(sz.x, "") {
		{dshow = true;}
		public void activate(String text) {
		    setpwd(text);
		}
	    }, prev.pos("bl").adds(0, 2));
        addhl(charpass.pos("bl").adds(0, 5), sz.x,
	      prev = new Button(sbw, L10n.get("kin.btn_set")   ).action(() -> { setpwd(charpass.text()); }),
	             new Button(sbw, L10n.get("kin.btn_clear") ).action(() -> { setpwd(""); }),
	             new Button(sbw, L10n.get("kin.btn_random")).action(() -> {setpwd(randpwd());})
	      );

	prev = add(new Label(L10n.get("kin.make_kin_by_secret")), prev.pos("bl").adds(0, 10));
	opass = add(new TextEntry(sz.x, "") {
		public void activate(String text) {
		    BuddyWnd.this.wdgmsg("bypwd", text);
		    settext("");
		}
	    }, prev.pos("bl").adds(0, 2));
	prev = add(new Button(sbw, L10n.get("kin.btn_add_kin")).action(() -> {
		    BuddyWnd.this.wdgmsg("bypwd", opass.text());
		    opass.settext("");
	}), opass.pos("bl").adds(0, 5));
    }

    private String randpwd() {
	String charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	StringBuilder buf = new StringBuilder();
	for(int i = 0; i < 8; i++)
	    buf.append(charset.charAt((int)(Math.random() * charset.length())));
	return(buf.toString());
    }
    
    public void setpwd(String pass) {
	wdgmsg("pwd", pass);
	charpass.settext(pass);
	charpass.commit();
    }

    public void setpname(String name) {
	wdgmsg("pname", name);
	pname.settext(name);
	pname.commit();
    }

    private void setcmp(Comparator<Buddy> cmp) {
	bcmp = cmp;
	String val = "";
	if(cmp == alphacmp)  val = "alpha";
	if(cmp == groupcmp)  val = "group";
	if(cmp == statuscmp) val = "status";
	Utils.setpref("buddysort", val);
	synchronized(buddies) {
	    Collections.sort(buddies, bcmp);
	}
    }

    public void uimsg(String msg, Object... args) {
	if(msg == "add") {
	    int id = INT.of(args[0]);
	    String name = STR.of(args[1]).intern();
	    int online = INT.of(args[2]);
	    int group = INT.of(args[3]);
	    boolean seen = BOOL.of(args[4]);
	    Buddy b = new Buddy(id, name, online, group, seen);
	    if(args.length > 5) {
		try {
		    b.notes = new MessageBuf(BYTES.of(args[5])).map();
		} catch(Exception e) {
		    new Warning(e, "could not decode buddy notes").issue();
		}
	    }
	    synchronized(buddies) {
		buddies.add(b);
		idmap.put(b.id, b);
		Collections.sort(buddies, bcmp);
	    }
	    serial++;
	} else if(msg == "rm") {
	    int id = INT.of(args[0]);
	    Buddy b;
	    synchronized(buddies) {
		b = idmap.get(id);
		if(b != null) {
		    buddies.remove(b);
		    idmap.remove(id);
		}
	    }
	    serial++;
	} else if(msg == "chst") {
	    int id = INT.of(args[0]);
	    int online = INT.of(args[1]);
	    Buddy b = find(id);
	    b.chstatus(online);
	    if((info != null) && (info.buddy == b))
		info.update();
	} else if(msg == "upd") {
	    int id = INT.of(args[0]);
	    String name = STR.of(args[1]);
	    int online = INT.of(args[2]);
	    int grp = INT.of(args[3]);
	    boolean seen = BOOL.of(args[4]);
	    Buddy b = find(id);
	    if(args.length > 5)
		b.notes = new MessageBuf(BYTES.of(args[5])).map();
	    synchronized(b) {
		b.name = name;
		b.online = online;
		b.group = grp;
		b.seen = seen;
	    }
	    if((info != null) && (info.buddy == b))
		info.update();
	    serial++;
	} else if(msg == "sel") {
	    int id = INT.of(args[0]);
	    Window p = getparent(Window.class);
	    if(p != null) {
		p.show();
		p.raise();
	    }
	    bl.change(find(id));
	} else if(msg == "pwd") {
	    charpass.settext(STR.of(args[0]));
	    charpass.buf.point(charpass.buf.length());
	    charpass.commit();
	} else if(msg == "pname") {
	    pname.settext(STR.of(args[0]));
	    pname.buf.point(pname.buf.length());
	    pname.commit();
	} else if(msg == "i-set") {
	    Buddy b = (args[0] == null) ? null : find(INT.of(args[0]));
	    bl.sel = b;
	    if((info == null) || (info.buddy != b)) {
		if(info != null) {
		    ui.destroy(info);
		    ui.destroy(infof);
		    info = null;
		    pack();
		}
		if(b != null) {
		    info = add(makeinfo(new Coord(UI.scale(225), sz.y - offset - Window.wbox.bisz().y), b), width + margin3, offset);
		    infof = Frame.around(this, Collections.singletonList(info));
		}
		pack();
	    }
	} else if(msg.substring(0, 2).equals("i-")) {
	    if(info != null)
		info.uimsg(msg, args);
	} else {
	    super.uimsg(msg, args);
	}
    }
    
    public void hide() {
	if(menu != null) {
	    ui.destroy(menu);
	    menu = null;
	}
	super.hide();
    }
    
    public void destroy() {
	if(menu != null) {
	    ui.destroy(menu);
	    menu = null;
	}
	super.destroy();
    }
}
