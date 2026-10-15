package nurgling.widgets;

import haven.*;
import haven.res.ui.tt.gast.Gast;
import haven.res.ui.tt.wear.Wear;
import nurgling.*;
import nurgling.i18n.L10n;
import nurgling.iteminfo.bonus.EquipmentBonuses;
import nurgling.styles.UITheme;
import nurgling.tasks.GetItem;
import nurgling.tasks.WaitItemSpr;
import nurgling.tools.EquipStats;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

import static haven.Inventory.invsq;

public class NEquipory extends Equipory
{
    public static Text.Furnace fnd = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 12).aa(true), UI.scale(1), UI.scale(1), Color.BLACK);
    final TexI eye = new TexI(Resource.loadsimg("nurgling/hud/eye"));
    final TexI armor = new TexI(Resource.loadsimg("nurgling/hud/armor"));
    private final int eyeInset = statIconInset(eye.back);
    private final int armorInset = statIconInset(armor.back);

    /** First opaque column of a stat icon, so the New UI panel can pad against its visible edge. */
    private static int statIconInset(BufferedImage image) {
        for(int x = 0; x < image.getWidth(); x++)
            for(int y = 0; y < image.getHeight(); y++)
                if((image.getRGB(x, y) >>> 24) >= 128)
                    return x;
        return 0;
    }
    int percExp = -1;
    int hardArmor = -1;
    int softArmor = -1;
    // Subtlety (Intelligence x Stealth); -1 / null while either attribute is unavailable
    int subtlety = -1;
    BufferedImage subtletyText = null;
    private BufferedImage subtletyLabel = null;
    // Where the stat rows were last drawn, for the hover tooltip
    private Coord statsPos = null, statsSz = null;

    // Toggle button textures for quick slot configuration
    private static final Tex addUp = new TexI(Resource.loadsimg("nurgling/hud/buttons/add/u"));
    private static final Tex addDown = new TexI(Resource.loadsimg("nurgling/hud/buttons/add/d"));
    private static final Tex addHover = new TexI(Resource.loadsimg("nurgling/hud/buttons/add/h"));
    private static final Tex removeUp = new TexI(Resource.loadsimg("nurgling/hud/buttons/remove/u"));
    private static final Tex removeDown = new TexI(Resource.loadsimg("nurgling/hud/buttons/remove/d"));
    private static final Tex removeHover = new TexI(Resource.loadsimg("nurgling/hud/buttons/remove/h"));

    // Toggle buttons for each equipment slot
    private final SlotToggleButton[] toggleButtons = new SlotToggleButton[ecoords.length];

    // Queue for pending parasite checks
    private final ArrayList<NGItem> pendingParasiteChecks = new ArrayList<>();

    // Collapsible "Equipment Bonuses" sidebar, to the right of the slot area
    private final Coord baseSz;
    private final EquipBonusPanel bonusPanel;
    private final EquipBonusPanel.Toggle bonusToggle;
    private boolean bonusesExpanded;
    private long bonusSig = 0;
    private boolean bonusDirty = true;
    private double bonusRetryAt = 0;

    public NEquipory(long gobid)
    {
        super(gobid);
        initToggleButtons();
        baseSz = sz;
        int gap = UI.scale(4);
        bonusToggle = add(new EquipBonusPanel.Toggle(() -> bonusesExpanded, this::toggleBonuses), new Coord(baseSz.x + gap, UI.scale(4)));
        bonusPanel = add(new EquipBonusPanel(new Coord(UI.scale(EquipBonusPanel.WIDTH), baseSz.y)), new Coord(bonusToggle.c.x + bonusToggle.sz.x + gap, 0));
        bonusesExpanded = Boolean.TRUE.equals(NConfig.get(NConfig.Key.equipBonusesExpanded));
        applyBonusLayout();
    }

    private void toggleBonuses()
    {
        bonusesExpanded = !bonusesExpanded;
        NConfig.set(NConfig.Key.equipBonusesExpanded, bonusesExpanded);
        bonusDirty = true;
        applyBonusLayout();
        // the equipment window sized itself to this widget when it was first added
        if (parent instanceof haven.Window)
            parent.pack();
    }

    /** Show or hide the sidebar and size this widget (and so the window) to match. */
    private void applyBonusLayout()
    {
        bonusPanel.show(bonusesExpanded);
        int w = bonusToggle.c.x + bonusToggle.sz.x;
        if (bonusesExpanded)
            w = bonusPanel.c.x + bonusPanel.sz.x;
        resize(new Coord(w, baseSz.y));
    }

    /**
     * Recompute the totals only when the worn items or their tooltip data changed, or when a
     * previous pass could not finish because item data was still loading.
     */
    private void updateBonuses()
    {
        if (!bonusesExpanded)
            return;
        java.util.List<EquipmentBonuses.Source> sources = new ArrayList<>();
        for (GItem gi : wmap.keySet())
            sources.add(EquipmentBonuses.of(gi));
        long sig = EquipmentBonuses.signature(sources);
        double now = Utils.rtime();
        boolean changed = bonusDirty || sig != bonusSig;
        boolean retry = bonusRetryAt != 0 && now >= bonusRetryAt;
        if (!changed && !retry)
            return;
        EquipmentBonuses.Result res = EquipmentBonuses.compute(sources);
        bonusPanel.setResult(res);
        bonusSig = sig;
        bonusDirty = false;
        bonusRetryAt = res.retry() ? now + 0.5 : 0;
    }

    // Custom offset for STORE_HAT slot to avoid overlapping HEAD slot's button
    private static final int STORE_HAT_OFFSET = UI.scale(20);

    /**
     * Gets the display coordinate for a slot, applying any custom offsets
     */
    private Coord getSlotDisplayCoord(int slotIdx) {
        Coord baseCoord = ecoords[slotIdx];
        // Move STORE_HAT slot to the right to avoid covering HEAD's toggle button
        if (slotIdx == Slots.STORE_HAT.idx) {
            return new Coord(baseCoord.x + STORE_HAT_OFFSET, baseCoord.y);
        }
        return baseCoord;
    }

    /**
     * Initializes toggle buttons for each equipment slot
     */
    private void initToggleButtons() {
        // Button size and spacing from slot
        int btnSize = UI.scale(12);
        int spacing = UI.scale(4);  // Extra spacing from the slot edge

        for (int i = 0; i < ecoords.length; i++) {
            final int slotIdx = i;
            Coord slotCoord = getSlotDisplayCoord(i);

            // Determine if this is a right column slot by checking x position
            // Left column has x=0, right column has x > inventory slot width
            // STORE_HAT is a special middle slot - treat it like left column (button on right)
            boolean isRightColumn = slotCoord.x > invsq.sz().x && slotIdx != Slots.STORE_HAT.idx;

            // Calculate button position - center vertically next to the slot with extra spacing
            Coord btnPos;
            if (isRightColumn) {
                // Right column: button on left side of slot
                btnPos = new Coord(slotCoord.x - btnSize - spacing, slotCoord.y + (invsq.sz().y - btnSize) / 2);
            } else {
                // Left column or STORE_HAT: button on right side of slot
                btnPos = new Coord(slotCoord.x + invsq.sz().x + spacing, slotCoord.y + (invsq.sz().y - btnSize) / 2);
            }

            toggleButtons[i] = add(new SlotToggleButton(slotIdx), btnPos);
        }
    }

    /**
     * Gets the current equip proxy slots as a list of integers.
     * Handles both Integer and Long types that may come from JSON parsing.
     */
    @SuppressWarnings("unchecked")
    private static ArrayList<Integer> getEquipProxySlotsFromConfig() {
        Object configValue = NConfig.get(NConfig.Key.equipProxySlots);
        if (configValue == null) {
            return new ArrayList<>();
        }

        ArrayList<Integer> result = new ArrayList<>();
        if (configValue instanceof ArrayList) {
            for (Object item : (ArrayList<?>) configValue) {
                if (item instanceof Number) {
                    result.add(((Number) item).intValue());
                }
            }
        }
        return result;
    }

    /**
     * Check if a slot index is currently in the quick access bar config
     */
    private static boolean isSlotInQuickBar(int slotIdx) {
        ArrayList<Integer> slots = getEquipProxySlotsFromConfig();
        return slots.contains(slotIdx);
    }

    /**
     * Toggle a slot in the quick access bar config
     */
    private static void toggleSlotInQuickBar(int slotIdx) {
        ArrayList<Integer> slots = getEquipProxySlotsFromConfig();

        if (slots.contains(slotIdx)) {
            slots.remove(Integer.valueOf(slotIdx));
        } else {
            slots.add(slotIdx);
        }

        NConfig.set(NConfig.Key.equipProxySlots, slots);

        // Update the NEquipProxy widget if it exists
        if (NUtils.getGameUI() != null && NUtils.getGameUI().nep != null) {
            NUtils.getGameUI().nep.setSlots(NGameUI.getEquipProxySlotsFromConfig());
        }
    }

    /**
     * Custom button for toggling slot in/out of quick access bar
     */
    private class SlotToggleButton extends Widget {
        private final int slotIdx;
        private boolean hovering = false;
        private boolean pressed = false;
        private UI.Grab grab = null;

        public SlotToggleButton(int slotIdx) {
            super(UI.scale(new Coord(12, 12)));
            this.slotIdx = slotIdx;
        }

        @Override
        public void draw(GOut g) {
            boolean inQuickBar = isSlotInQuickBar(slotIdx);
            Tex img;

            if (inQuickBar) {
                // Show remove button
                if (pressed && hovering) {
                    img = removeDown;
                } else if (hovering) {
                    img = removeHover;
                } else {
                    img = removeUp;
                }
            } else {
                // Show add button
                if (pressed && hovering) {
                    img = addDown;
                } else if (hovering) {
                    img = addHover;
                } else {
                    img = addUp;
                }
            }

            g.image(img, Coord.z, sz);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                pressed = true;
                grab = ui.grabmouse(this);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseup(MouseUpEvent ev) {
            if (grab != null && ev.b == 1) {
                grab.remove();
                grab = null;
                if (pressed && hovering) {
                    toggleSlotInQuickBar(slotIdx);
                }
                pressed = false;
                return true;
            }
            return false;
        }

        @Override
        public void mousemove(MouseMoveEvent ev) {
            hovering = ev.c.isect(Coord.z, sz);
        }

        @Override
        public Object tooltip(Coord c, Widget prev) {
            boolean inQuickBar = isSlotInQuickBar(slotIdx);
            if (inQuickBar) {
                return L10n.get("equipment.remove_from_quickbar");
            } else {
                return L10n.get("equipment.add_to_quickbar");
            }
        }
    }

    BufferedImage percExpText = null;
    BufferedImage hardSoft = null;

    public enum Slots {
        HEAD(0),       //00: Headgear
        ACCESSORY(1),  //01: Main Accessory
        SHIRT(2),      //02: Shirt
        ARMOR_BODY(3), //03: Torso Armor
        GLOVES(4),     //04: Gloves
        BELT(5),       //05: Belt
        HAND_LEFT(6),  //06: Left Hand
        HAND_RIGHT(7), //07: Right Hand
        RING_LEFT(8),  //08: Left Hand Ring
        RING_RIGHT(9), //09: Right Hand Ring
        ROBE(10),      //10: Cloaks & Robes
        BACK(11),      //11: Backpack
        PANTS(12),     //12: Pants
        ARMOR_LEG(13), //13: Armor
        CAPE(14),      //14: Cape
        BOOTS(15),     //15: Shoes
        STORE_HAT(16), //16: Hat from store
        EYES(17),      //17: Eyes/Mask
        MOUTH(18),     //18: Mouth
        LFOOT(19),     //19: Left Foot
        RFOOT(20),     //20: Right Foot
        SHOULDER(21),  //21: Shoulder
        COAT(22);      //22: Coat

        public final int idx;
        Slots(int idx) {
            this.idx = idx;
        }
    }

    public WItem[] quickslots = new NWItem[ecoords.length];

    private void updatePercExpText() {
        int perceptionValue = 0;
        int explorationValue = 0;
        GameUI gui = getparent(GameUI.class);
        if (gui != null && gui.chrwdg != null) {
            if (gui.chrwdg.battr != null) {
                for (BAttrWnd.Attr attr : gui.chrwdg.battr.attrs) {
                    if (attr.nm.equals("prc")) {
                        perceptionValue = attr.attr.comp;
                        break;
                    }
                }
            }
            if (gui.chrwdg.sattr != null) {
                for (SAttrWnd.SAttr attr : gui.chrwdg.sattr.attrs) {
                    if (attr.nm.equals("explore")) {
                        explorationValue = attr.attr.comp;
                        break;
                    }
                }
            }

            int percExp = perceptionValue * explorationValue;
            if(this.percExp!=percExp) {
                this.percExp = percExp;
                percExpText = fnd.render(String.valueOf(percExp)).img;
            }
            updateSubtlety(gui);
        }
    }

    /**
     * Subtlety = Intelligence x Stealth, from the same computed attribute values as Detection. Shown
     * only when both attributes are actually present, never as a 0 that merely means "not loaded yet".
     */
    private void updateSubtlety(GameUI gui) {
        Integer intelligence = null, stealth = null;
        if (gui.chrwdg.battr != null) {
            for (BAttrWnd.Attr attr : gui.chrwdg.battr.attrs) {
                if (attr.nm.equals("int")) {
                    intelligence = attr.attr.comp;
                    break;
                }
            }
        }
        if (gui.chrwdg.sattr != null) {
            for (SAttrWnd.SAttr attr : gui.chrwdg.sattr.attrs) {
                if (attr.nm.equals("stealth")) {
                    stealth = attr.attr.comp;
                    break;
                }
            }
        }
        Integer product = EquipStats.subtlety(intelligence, stealth);
        if (product == null) {
            subtlety = -1;
            subtletyText = null;
            return;
        }
        int value = product;
        if (value != subtlety || subtletyText == null) {
            subtlety = value;
            subtletyText = fnd.render(String.valueOf(value)).img;
        }
    }

    public void updateTotalArmor() {
        java.util.List<EquipStats.Armored> worn = new ArrayList<>();
        for (Slots slot : Slots.values()) {
            WItem item = quickslots[slot.idx];
            if (item != null) {
                NGItem gitem = (NGItem) item.item;
                Wear wear = gitem.getInfo(Wear.class);
                if (wear != null) {
                    worn.add(new EquipStats.Armored() {
                        public Object id() {return gitem;}
                        public boolean intact() {return wear.d != wear.m;}
                        public int hard() {return gitem.hardArmor;}
                        public int soft() {return gitem.softArmor;}
                    });
                }
            }
        }
        // An item in several slots is one item: counted once (see EquipStats.armor)
        int[] armor = EquipStats.armor(worn);
        int hardArmor = armor[0];
        int softArmor = armor[1];
        if(hardArmor!=this.hardArmor || softArmor!=this.softArmor) {
            this.hardArmor = hardArmor;
            this.softArmor = softArmor;
            hardSoft = fnd.render(String.format("%d/%d",hardArmor,softArmor)).img;
        }
    }

    @Override
    public void addchild (
            Widget child,
            Object... args
    ) {
        if ( child instanceof NGItem ) {
            add ( child );
            NGItem g = ( NGItem ) child;
            WItem[] v = new NWItem[args.length];
            for ( int i = 0 ; i < args.length ; i++ ) {
                int ep = ( Integer ) args[i];
                v[i] = quickslots[ep] = add ( new NWItem(g), getSlotDisplayCoord(ep).add ( 1, 1 ) );
            }
            wmap.put ( g, Arrays.asList ( v.clone () ) );
            
            // Add to pending parasite checks if bot is enabled
            Boolean parasiteBotEnabled = (Boolean) NConfig.get(NConfig.Key.parasiteBotEnabled);
            if (parasiteBotEnabled != null && parasiteBotEnabled) {
                synchronized (pendingParasiteChecks) {
                    pendingParasiteChecks.add(g);
                }
            }
        }
        else {
            super.addchild ( child, args );
        }
    }

    @Override
    public void cdestroy ( Widget w ) {
        if ( w instanceof GItem ) {
            GItem i = ( GItem ) w;
            for ( WItem v : wmap.remove ( i ) ) {
                ui.destroy ( v );
                for ( int qsi = 0 ; qsi < ecoords.length ; qsi++ ) {
                    if ( quickslots[qsi] == v ) {
                        /// Снимаемый предмет удаляется из массива
                        quickslots[qsi] = null;
                        break;
                    }
                }
            }
        }
    }


    @Override
    public void tick(double dt) {
        super.tick(dt);
        updatePercExpText();
        updateTotalArmor();
        updateBonuses();
        checkPendingParasites();
    }
    
    private void checkPendingParasites() {
        if (pendingParasiteChecks.isEmpty()) {
            return;
        }
        
        ArrayList<NGItem> toRemove = new ArrayList<>();
        ArrayList<NGItem> toProcess = new ArrayList<>();
        
        synchronized (pendingParasiteChecks) {
            for (NGItem item : pendingParasiteChecks) {
                if (item.name() != null) {
                    toProcess.add(item);
                    toRemove.add(item);
                }
            }
            pendingParasiteChecks.removeAll(toRemove);
        }
        
        for (NGItem item : toProcess) {
            handleParasiteItem(item);
        }
    }
    
    private void handleParasiteItem(NGItem item) {
        String name = item.name();
        if (name == null) {
            return;
        }
        
        String action = null;
        
        if (name.equals("Leech")) {
            action = (String) NConfig.get(NConfig.Key.leechAction);
        } else if (name.equals("Tick")) {
            action = (String) NConfig.get(NConfig.Key.tickAction);
        }
        
        if (action == null) {
            return;
        }
        
        // Find the WItem for this NGItem
        WItem witem = null;
        for (WItem slot : quickslots) {
            if (slot != null && slot.item == item) {
                witem = slot;
                break;
            }
        }
        
        if (witem == null) {
            return;
        }
        
        if ("ground".equals(action)) {
            // Drop to ground
            NUtils.drop(witem);
        } else if ("inventory".equals(action)) {
            // Transfer to inventory
            item.wdgmsg("transfer", witem.c, 1);
        }
    }

    @Override
    public int epat(Coord c) {
        for(int i = 0; i < ecoords.length; i++) {
            if(c.isect(getSlotDisplayCoord(i), invsq.sz()))
                return(i);
        }
        return(-1);
    }

    @Override
    public void drawslots(GOut g) {
        int slots = 0;
        GameUI gui = getparent(GameUI.class);
        if((gui != null) && (gui.vhand != null)) {
            try {
                SlotInfo si = ItemInfo.find(SlotInfo.class, gui.vhand.item.info());
                if(si != null)
                    slots = si.slots();
            } catch(Loading l) {
            }
        }
        for(int i = 0; i < ecoords.length; i++) {
            Coord slotCoord = getSlotDisplayCoord(i);
            if((slots & (1 << i)) != 0) {
                g.chcolor(255, 255, 0, 64);
                g.frect(slotCoord.add(1, 1), invsq.sz().sub(2, 2));
                g.chcolor();
            }
            g.image(Inventory.slotsq, slotCoord);
            drawSlotHint(g, i, slotCoord);
        }
    }

    @Override
    public void draw(GOut g) {
        super.draw(g);
        if(UITheme.on()) {
            drawStatsPanel(g);
            return;
        }
        statsPos = null;
        Coord textCoord = new Coord(baseSz.x - UI.scale(85), UI.scale(3));
        Coord first = textCoord;
        int textW = 0;
        int rows = 0;
        if (percExpText != null) {
            textCoord = textCoord.sub(percExpText.getWidth(), 0);
            first = textCoord;
            g.image(eye, textCoord, UI.scale(20,20));
            g.image(percExpText, textCoord.add(UI.scale(21, -1)));
            textW = Math.max(textW, percExpText.getWidth());
            rows++;
        }
        if(hardSoft!=null) {
            textCoord = textCoord.add(UI.scale(0, 18));
            g.image(armor, textCoord, UI.scale(20, 20));
            g.image(hardSoft, textCoord.add(UI.scale(21, -1)));
            textW = Math.max(textW, hardSoft.getWidth());
            rows++;
        }
        if(subtletyText != null) {
            // Not an extra row when the others are missing: it then takes the first position
            textCoord = (rows == 0) ? textCoord : textCoord.add(UI.scale(0, 18));
            if (rows == 0) first = textCoord;
            g.image(subtletyLabel(), textCoord.add(UI.scale(1, 4)));
            g.image(subtletyText, textCoord.add(UI.scale(21, -1)));
            textW = Math.max(textW, subtletyText.getWidth());
            rows++;
        }
        if (rows > 0) {
            statsPos = first;
            statsSz = new Coord(UI.scale(21) + textW, UI.scale(18) * rows);
        }
    }

    /** The row label standing in for an icon, in the same style as the stat values. */
    private BufferedImage subtletyLabel() {
        if (subtletyLabel == null) {
            subtletyLabel = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 9).aa(true),
                    UI.scale(1), UI.scale(1), Color.BLACK).render(L10n.get("equipment.stats.subtlety_short")).img;
        }
        return subtletyLabel;
    }

    private Text statsTip = null;
    private String statsTipKey = null;

    /** Hovering the stat rows explains how each number is derived. */
    @Override
    public Object tooltip(Coord c, Widget prev) {
        if (statsPos != null && c.isect(statsPos, statsSz)) {
            StringBuilder sb = new StringBuilder();
            if (percExpText != null)
                sb.append(L10n.get("equipment.stats.detection")).append('\n');
            if (hardSoft != null)
                sb.append(L10n.get("equipment.stats.armor")).append('\n');
            if (subtletyText != null)
                sb.append(L10n.get("equipment.stats.subtlety")).append('\n');
            String text = sb.toString().trim();
            if (!text.isEmpty()) {
                if (statsTip == null || !text.equals(statsTipKey)) {
                    statsTip = RichText.render(text, 0);
                    statsTipKey = text;
                }
                return statsTip;
            }
        }
        return super.tooltip(c, prev);
    }

    /** New UI: perception/armor/subtlety stats on a flat panel, right-aligned against the shoulder toggle. */
    private void drawStatsPanel(GOut g) {
        statsPos = null;
        if(percExpText == null && hardSoft == null && subtletyText == null) return;
        int pad = UI.scale(4), gap = UI.scale(4), icon = UI.scale(20);
        int textW = Math.max(Math.max(percExpText == null ? 0 : percExpText.getWidth(), hardSoft == null ? 0 : hardSoft.getWidth()), subtletyText == null ? 0 : subtletyText.getWidth());
        int textH = Math.max(Math.max(percExpText == null ? 0 : percExpText.getHeight(), hardSoft == null ? 0 : hardSoft.getHeight()), subtletyText == null ? 0 : subtletyText.getHeight());
        int rowH = Math.max(icon, textH);
        int rows = (percExpText == null ? 0 : 1) + (hardSoft == null ? 0 : 1) + (subtletyText == null ? 0 : 1);
        Coord panelSize = new Coord(pad * 2 + icon + gap + textW, pad * 2 + rows * rowH);
        int right = toggleButtons[Slots.SHOULDER.idx].c.x - UI.scale(6);
        Coord panelPos = new Coord(right - panelSize.x, UI.scale(3));
        UITheme.panel(g, panelPos, panelSize, UITheme.PANEL, UITheme.LINE);
        statsPos = panelPos;
        statsSz = panelSize;
        int y = panelPos.y + pad;
        if(percExpText != null) {
            drawStat(g, eye, eyeInset, percExpText, panelPos.x + pad, right - pad, y, icon, rowH);
            y += rowH;
        }
        if(hardSoft != null) {
            drawStat(g, armor, armorInset, hardSoft, panelPos.x + pad, right - pad, y, icon, rowH);
            y += rowH;
        }
        if(subtletyText != null) {
            BufferedImage label = subtletyLabel();
            g.image(label, new Coord(panelPos.x + pad + (icon - label.getWidth()) / 2, y + (rowH - label.getHeight()) / 2));
            g.image(subtletyText, new Coord(right - pad - subtletyText.getWidth(), y + (rowH - subtletyText.getHeight()) / 2));
        }
    }

    private void drawStat(GOut g, Tex icon, int sourceInset, BufferedImage value, int left, int right, int y, int iconSize, int rowH) {
        // Match the text's right padding using the visible icon edge, not its transparent canvas.
        int inset = Math.round((float)sourceInset * iconSize / icon.sz().x);
        g.image(icon, new Coord(left - inset, y + (rowH - iconSize) / 2), new Coord(iconSize, iconSize));
        g.image(value, new Coord(right - value.getWidth(), y + (rowH - value.getHeight()) / 2));
    }

    public WItem findItem(int id) throws InterruptedException {
        if (quickslots[id] != null) {
            NUtils.getUI().core.addTask(new WaitItemSpr(quickslots[id]));
            return quickslots[id];
        }
        return null;
    }

    public WItem findItem(String name) throws InterruptedException {
        for(int i = 0; i < ecoords.length;i++) {
            if (quickslots[i] != null) {
                // name() can still be null right after the slot fills, before its item info has
                // finished loading - a real race, not a bug in the caller (hit live via
                // CoracleBot.findCoracleItem -> eq.findItem("Coracle") mid-ChunkNav bridge).
                String itemName = ((NGItem) quickslots[i].item).name();
                if (itemName != null && itemName.endsWith(name)) {
                    return quickslots[i];
                }
            }
        }
        return null;
    }

    public WItem findBucket (String content) throws InterruptedException {
        if (quickslots[Slots.HAND_RIGHT.idx] != null) {
            NUtils.getUI().core.addTask(new WaitItemSpr(quickslots[Slots.HAND_RIGHT.idx]));
            if (NParser.checkName("Bucket", ((NGItem) quickslots[Slots.HAND_RIGHT.idx].item).name())) {
                if (((NGItem) quickslots[Slots.HAND_RIGHT.idx].item).content().isEmpty() || NParser.checkName(((NGItem) quickslots[Slots.HAND_RIGHT.idx].item).content().get(0).name(), content))
                    return quickslots[Slots.HAND_RIGHT.idx];
            }
        }
        if (quickslots[Slots.HAND_LEFT.idx] != null) {
            if (NParser.checkName("Bucket", ((NGItem) quickslots[Slots.HAND_LEFT.idx].item).name())) {
                if (((NGItem) quickslots[Slots.HAND_LEFT.idx].item).content().isEmpty() || NParser.checkName(((NGItem) quickslots[Slots.HAND_LEFT.idx].item).content().get(0).name(), content))
                    return quickslots[Slots.HAND_LEFT.idx];
            }
        }
        return null;
    }
}
