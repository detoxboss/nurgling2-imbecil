package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.GSprite;
import haven.Gob;
import haven.ItemInfo;
import haven.Loading;
import haven.MCache;
import haven.OCache;
import haven.ResData;
import haven.Widget;
import haven.Window;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.barterbox.Shopbox;
import haven.res.ui.tt.q.quality.Quality;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.market.AreaSweep;
import nurgling.market.AreaSweepPlanner;
import nurgling.market.MarketApiClient;
import nurgling.market.MarketDiscoveryReport;
import nurgling.market.MarketLayout;
import nurgling.market.MarketOffers;
import nurgling.market.MarketRoutePlanner;
import nurgling.market.MarketScanSpool;
import nurgling.market.PhysicalRef;
import nurgling.market.ShopMessageLog;
import nurgling.market.ShopboxReadiness;
import nurgling.market.StandMatcher;
import nurgling.market.StandReacquirer;
import nurgling.plugins.UiEvents;
import nurgling.navigation.ChunkNavManager;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans the barter stands of a market NArea and publishes one H4D Market snapshot.
 *
 * Identity comes from the server: every stand has a permanent id, a cluster/stand number and a saved
 * physical reference (map grid id + offset). The bot sweeps the whole NArea in sections, identifies
 * what it finds only by that physical reference, reports everything it cannot identify for human
 * commissioning, and scans the stands it does know. Unknown, missing or unreachable stands never stop
 * the known ones from being scanned, and never erase their last published listing.
 *
 * Game interaction is reused unchanged: ChunkNav / {@link PathFinder} movement, container opening and
 * the live {@link Shopbox} widgets.
 */
public class H4DMarketScanner implements Action {
    public static final String BOT_ID = "h4d_market_scan";
    public static final String DEFAULT_ENDPOINT = "https://h4d.shop";
    public static final String DEFAULT_AREA_NAME = "H4D Market";
    private static final String STAND_RESOURCE = "gfx/terobjs/barterstand";
    private static final NAlias STAND_ALIAS = new NAlias(STAND_RESOURCE);
    private static final long CAPTURE_TIMEOUT_MS = 15_000;
    private static final long WAYPOINT_SETTLE_MS = 1_500;
    private static final long REACQUIRE_TIMEOUT_MS = 8_000;
    /** A waypoint or saved stand position counts as reached within this distance of the requested spot. */
    private static final double ARRIVAL_UNITS = MCache.tilesz.x * 6;
    /** Farther than this from a loaded target, local pathing is not trusted; ChunkNav goes first. */
    private static final double LOCAL_RANGE_UNITS = MCache.tilesz.x * 25;
    private static final Map<Integer, Object> AREA_LOCKS = new ConcurrentHashMap<>();

    private final Map<String, Object> settings;

    public H4DMarketScanner() {
        this(new HashMap<>());
    }

    public H4DMarketScanner(Map<String, Object> settings) {
        this.settings = settings == null ? new HashMap<>() : new HashMap<>(settings);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        try {
            Config config = Config.from(settings);
            NArea area = resolveArea(gui, config.areaId);
            if (area == null) {
                return Results.ERROR(config.areaId == null
                        ? "H4D Market Scanner: select a market NArea (or name it '" + DEFAULT_AREA_NAME + "')"
                        : "H4D Market Scanner: area not found: " + config.areaId);
            }
            if (area.isDisabled()) {
                return Results.ERROR("H4D Market Scanner: selected NArea is disabled");
            }
            if (config.token.isEmpty()) {
                return Results.ERROR("H4D Market Scanner: no ingest token; set it on the Scenario step or H4D_MARKET_INGEST_TOKEN");
            }

            Object lock = AREA_LOCKS.computeIfAbsent(area.id, ignored -> new Object());
            synchronized (lock) {
                return runLocked(gui, area, config);
            }
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception failure) {
            return Results.ERROR("H4D Market Scanner: " + safeMessage(failure));
        }
    }

    private Results runLocked(NGameUI gui, NArea area, Config config) throws Exception {
        // Records the server's Shopbox messages for the whole run, so a Barter Stand window's first
        // messages are never missed (they arrive right as the window opens).
        ShopMessageLog messages = new ShopMessageLog();
        UiEvents.addListener(messages);
        try {
            return runWithMessages(gui, area, config, messages);
        } finally {
            UiEvents.removeListener(messages);
        }
    }

    private Results runWithMessages(NGameUI gui, NArea area, Config config, ShopMessageLog messages) throws Exception {
        gui.msg("H4D Market Scanner: navigating to " + area.name);
        if (NUtils.player() == null) {
            return Results.ERROR("H4D Market Scanner: player is not available");
        }
        if (!navigateToMarket(gui, area)) {
            return Results.ERROR("H4D Market Scanner: could not navigate to NArea '" + area.name + "'");
        }

        MarketApiClient client = new MarketApiClient(config.endpoint, config.token, 3);
        MarketLayout layout = client.layout();

        // 1. Discover physical stands across the WHOLE area, section by section.
        GameSource source = new GameSource(gui, area);
        List<AreaSweepPlanner.Waypoint> waypoints = AreaSweepPlanner.plan(source.pieces(), config.sweepSpacingTiles);
        gui.msg("H4D Market Scanner: searching " + waypoints.size() + " section(s) of '" + area.name + "'");
        AreaSweep.Outcome<Gob> sweep = AreaSweep.run(waypoints, source);
        StandMatcher.Result<Gob> match = StandMatcher.match(layout, sweep.stands,
                config.standTolerance, StandMatcher.DEFAULT_MOVE_RADIUS);

        // 2. A stand that is merely not loaded right now is unknown, not gone: look once more where it was.
        if (!match.missing.isEmpty()) {
            List<StandMatcher.Detected<Gob>> recovered = recoverMissing(gui, area, source, match.missing, sweep.stands);
            if (!recovered.isEmpty()) {
                match = StandMatcher.match(layout, recovered, config.standTolerance, StandMatcher.DEFAULT_MOVE_RADIUS);
            }
        }

        // 3. Publish the discovery so unknown stands can be registered; nothing is guessed or auto-assigned.
        JSONObject discovery = MarketDiscoveryReport.build(layout.version, area.name, match,
                sweep.waypoints, sweep.reached);
        exportDiscovery(area, discovery);
        try {
            client.reportDiscovery(discovery);
        } catch (MarketApiClient.ApiException failure) {
            gui.msg("H4D Market Scanner: could not upload the discovery report (" + safeMessage(failure) + ")");
        }
        gui.msg("H4D Market Scanner: " + match.matched.size() + " known, " + match.unknown.size() + " unknown, "
                + match.doubts.size() + " ambiguous/moved, " + match.missing.size() + " not seen");
        if (!sweep.complete()) {
            gui.msg("H4D Market Scanner: " + sweep.unreached.size() + " section(s) could not be reached");
        }
        if (match.matched.isEmpty()) {
            return Results.ERROR("H4D Market Scanner: no stand matched a commissioned position ("
                    + layout.unboundCount() + " stands still need commissioning; "
                    + match.unknown.size() + " detected stands were uploaded for registration at "
                    + config.endpoint + "/admin)");
        }

        // 4. Route over the known stands only, from where the walk starts.
        Coord2d here = NUtils.player().rc;
        List<MarketRoutePlanner.Stop<StandMatcher.Matched<Gob>>> stops = new ArrayList<>();
        for (StandMatcher.Matched<Gob> matched : match.matched) {
            stops.add(new MarketRoutePlanner.Stop<>(matched.stand.clusterId, matched.detected.rcX,
                    matched.detected.rcY, matched));
        }
        List<StandMatcher.Matched<Gob>> route = MarketRoutePlanner.order(stops, here.x, here.y,
                MarketRoutePlanner.DEFAULT_ROW_GAP);

        Path spoolPath = NUtils.getDataFilePath("h4d-market-scan-" + area.id + ".json");
        MarketScanSpool spool = MarketScanSpool.load(spoolPath);
        if (spool == null || !spool.matches(config.endpoint, area.id, layout.version)
                || !spool.isValidFor(layout.boundIds())) {
            if (spool != null) {
                gui.msg("H4D Market Scanner: layout or area changed since the saved scan; starting a new scan");
            }
            spool = begin(client, spoolPath, config, area.id, layout.version);
        } else {
            gui.msg("H4D Market Scanner: resuming scan " + spool.scanId);
        }

        // One recovery is allowed if the VPS was rebuilt and no longer knows the persisted scan.
        for (int recovery = 0; recovery < 2; recovery++) {
            try {
                Set<Integer> unreachable = executeRoute(gui, client, spool, route, area, config, messages);
                return publish(gui, client, spool, layout, match, unreachable, config);
            } catch (MarketApiClient.ApiException apiFailure) {
                if (apiFailure.status != 404 || recovery > 0) {
                    throw apiFailure;
                }
                gui.msg("H4D Market Scanner: server lost the open scan; restarting it");
                spool = begin(client, spoolPath, config, area.id, layout.version);
            }
        }
        return Results.ERROR("H4D Market Scanner: scan could not be recovered");
    }

    private Results publish(NGameUI gui, MarketApiClient client, MarketScanSpool spool, MarketLayout layout,
                            StandMatcher.Result<Gob> match, Set<Integer> unreachable, Config config) throws Exception {
        Set<Integer> notRead = new LinkedHashSet<>(unreachable);
        for (MarketLayout.Stand stand : match.missing) {
            notRead.add(stand.standId);
        }
        for (StandMatcher.Doubt<Gob> doubt : match.doubts) {
            for (MarketLayout.Stand candidate : doubt.candidates) {
                if (!spool.uploaded.contains(candidate.standId)) {
                    notRead.add(candidate.standId);
                }
            }
        }
        notRead.removeAll(spool.uploaded);
        if (!notRead.isEmpty() && !config.publishPartial) {
            return Results.ERROR("H4D Market Scanner: " + notRead.size() + " commissioned stand(s) were not read ("
                    + describe(layout, notRead) + "); scan left open so the next run can finish it "
                    + "(publishPartial is off)");
        }
        String note = notRead.isEmpty() ? null : "not read: " + describe(layout, notRead);
        client.complete(spool.scanId, !notRead.isEmpty(), notRead, note);
        spool.clear();
        if (notRead.isEmpty()) {
            gui.msg("H4D Market Scanner: published " + spool.uploaded.size() + " stands");
        } else {
            gui.msg("H4D Market Scanner: published " + spool.uploaded.size() + " stands; kept last known listings for "
                    + notRead.size() + " (" + describe(layout, notRead) + ")");
        }
        return Results.SUCCESS();
    }

    private static String describe(MarketLayout layout, Set<Integer> standIds) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (MarketLayout.Stand stand : layout.stands) {
            if (standIds.contains(stand.standId)) {
                if (shown++ > 0) text.append(", ");
                if (shown > 12) { text.append("..."); break; }
                text.append(stand.standKey);
            }
        }
        return text.toString();
    }

    private static MarketScanSpool begin(MarketApiClient client, Path spoolPath, Config config,
                                         int areaId, int layoutVersion) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("scannerId", "nurgling2-h4d-market");
        payload.put("startedAt", Instant.now().toString());
        payload.put("layoutVersion", layoutVersion);
        String scanId = client.begin(payload);
        MarketScanSpool spool = MarketScanSpool.fresh(spoolPath, config.endpoint, areaId, layoutVersion, scanId);
        spool.save();
        return spool;
    }

    /** @return stand ids that stayed unreachable after the bounded retries. */
    private static Set<Integer> executeRoute(NGameUI gui, MarketApiClient client, MarketScanSpool spool,
                                             List<StandMatcher.Matched<Gob>> route, NArea area,
                                             Config config, ShopMessageLog messages) throws Exception {
        if (spool.pendingStandId != null && spool.pendingStand != null) {
            gui.msg("H4D Market Scanner: retrying stand " + spool.pendingStandId);
            client.putStand(spool.scanId, spool.pendingStandId, spool.pendingStand);
            spool.acknowledgePending();
        }

        Set<Integer> unreachable = new LinkedHashSet<>();
        Set<String> checkedIcons = new HashSet<>();
        List<Coord2d> learnedRouteBlocks = new ArrayList<>();
        int done = spool.uploaded.size();
        for (StandMatcher.Matched<Gob> match : route) {
            int standId = match.stand.standId;
            if (spool.uploaded.contains(standId)) {
                continue;
            }
            gui.msg("H4D Market Scanner: scanning " + match.stand.standKey + " (" + (done + 1) + " of "
                    + (route.size() + unreachable.size()) + ")");
            CapturedStand captured = captureWithRetries(gui, area, match, learnedRouteBlocks, config, messages);
            if (captured == null) {
                unreachable.add(standId);
                gui.msg("H4D Market Scanner: giving up on " + match.stand.standKey + " after "
                        + config.maxStandAttempts + " attempts; its last listing will be kept");
                continue;
            }
            for (Map.Entry<String, byte[]> icon : captured.icons.entrySet()) {
                if (checkedIcons.add(icon.getKey())) {
                    client.ensureIcon(icon.getKey(), icon.getValue());
                }
            }
            spool.setPending(standId, captured.json);
            client.putStand(spool.scanId, standId, captured.json);
            spool.acknowledgePending();
            done++;
        }
        return unreachable;
    }

    /**
     * Bounded attempts per stand. Every attempt starts by re-acquiring the live stand from its SAVED physical
     * reference (the Gob collected during the sweep may have been unloaded since): navigate toward the grid and
     * offset (ChunkNav when needed), wait for the section to load, then require exactly one stand within
     * tolerance. Only then does local obstacle-aware navigation and opening happen. Returns null (never loops
     * forever) when every attempt failed, so the caller records the stand as unread and its last listing survives.
     */
    private static CapturedStand captureWithRetries(NGameUI gui, NArea area, StandMatcher.Matched<Gob> match,
                                                     List<Coord2d> learnedRouteBlocks, Config config,
                                                     ShopMessageLog messages) throws Exception {
        for (int attempt = 1; attempt <= config.maxStandAttempts; attempt++) {
            String reason;
            try {
                StandReacquirer.Result<Gob> live = StandReacquirer.reacquire(match.stand.physical,
                        config.standTolerance, new LiveWorld(gui, area), REACQUIRE_TIMEOUT_MS, 250,
                        ShopboxReadiness.SYSTEM);
                if (live.found()) {
                    return captureStand(gui, live.stand.value, learnedRouteBlocks, messages, config);
                }
                reason = live.failure;
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (IOException | Loading failure) {
                reason = safeMessage(failure);
            }
            gui.msg("H4D Market Scanner: " + match.stand.standKey + " attempt " + attempt + "/"
                    + config.maxStandAttempts + " failed: " + reason);
            if (attempt < config.maxStandAttempts) {
                Thread.sleep(2_000L * attempt);
            }
        }
        return null;
    }

    private static CapturedStand captureStand(NGameUI gui, Gob stand, List<Coord2d> learnedRouteBlocks,
                                              ShopMessageLog messages, Config config) throws Exception {
        PathFinder pathFinder = new PathFinder(stand);
        // Re-plan around live gob hitboxes and remember any unmodelled stall point for the rest
        // of this route. This is especially important for players and bill-of-sale cargo left in aisles.
        pathFinder.learnedBlocks = learnedRouteBlocks;
        pathFinder.maxStalls = 6;
        Results path = pathFinder.run(gui);
        if (!path.IsSuccess()) {
            throw new IOException("Could not path to barter stand at " + stand.rc);
        }
        Results opened = new OpenTargetContainer("Barter Stand", stand).run(gui);
        if (!opened.IsSuccess()) {
            throw new IOException("Could not open barter stand at " + stand.rc);
        }

        Window window = gui.getWindow("Barter Stand");
        if (window == null) {
            throw new IOException("Barter Stand window did not open");
        }
        try {
            return waitForCapture(gui, window, stand, messages, config);
        } finally {
            new CloseTargetWindow(window).run(gui);
        }
    }

    private static List<Shopbox> shopboxes(Window window) {
        List<Shopbox> boxes = new ArrayList<>();
        for (Widget child = window.child; child != null; child = child.next) {
            if (child instanceof Shopbox) {
                boxes.add((Shopbox) child);
            }
        }
        boxes.sort(Comparator.comparingInt((Shopbox box) -> box.c.y).thenComparingInt(box -> box.c.x));
        return boxes;
    }

    /**
     * Waits (bounded) until the window's five Shopboxes have finished loading according to their own message
     * lifecycle, then captures. A stand that never becomes ready is NOT published as empty: the timeout is
     * reported as a failed attempt so the stand ends up unread and its previous listing is kept.
     */
    private static CapturedStand waitForCapture(NGameUI gui, Window window, Gob stand, ShopMessageLog messages,
                                                Config config) throws Exception {
        boolean silenceFallback = config.emptySlotSilenceFallback;
        long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
        ShopboxReadiness.Verdict verdict = null;
        while (System.currentTimeMillis() < deadline) {
            List<Shopbox> boxes = shopboxes(window);
            if (boxes.size() == 5) {
                List<ShopSlot> slots = new ArrayList<>();
                long last = 0;
                for (Shopbox box : boxes) {
                    slots.add(new ShopSlot(box, messages));
                    last = Math.max(last, messages.lastMessageAt(box));
                }
                long quiet = last == 0 ? 0 : System.currentTimeMillis() - last;
                verdict = ShopboxReadiness.assess(slots, quiet, silenceFallback);
                if (verdict.ready()) {
                    if (config.traceShopbox) {
                        gui.msg("H4D Market Scanner [trace] " + verdict.describe() + " " + traces(boxes, messages));
                    }
                    try {
                        return capture(boxes, slots, verdict, stand);
                    } catch (Loading loading) {
                        // a sprite or name regressed to loading between assessment and capture: keep waiting
                    }
                }
            }
            Thread.sleep(100);
        }
        List<Shopbox> lastBoxes = shopboxes(window);
        // Diagnostic for an in-game trace: which messages each slot did (not) receive.
        throw new IOException("Barter stand did not finish loading ("
                + (verdict == null ? "fewer than five Shopbox widgets" : verdict.pending() + " slot(s) pending "
                + verdict.describe()) + "; messages " + traces(lastBoxes, messages)
                + "); keeping its previous listing");
    }

    private static String traces(List<Shopbox> boxes, ShopMessageLog messages) {
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < boxes.size(); slot++) {
            text.append(slot > 0 ? " | " : "").append("slot").append(slot).append(": ").append(messages.trace(boxes.get(slot)));
        }
        return text.toString();

    }

    private static CapturedStand capture(List<Shopbox> boxes, List<ShopSlot> slots, ShopboxReadiness.Verdict verdict,
                                         Gob stand) throws Exception {
        JSONArray offers = new JSONArray();
        Map<String, byte[]> icons = new LinkedHashMap<>();
        for (int slot = 0; slot < boxes.size(); slot++) {
            if (verdict.states.get(slot) != ShopboxReadiness.State.READY) {
                continue; // confirmed empty or confirmed incomplete (not a purchasable offer)
            }
            Shopbox box = boxes.get(slot);
            ItemInfo.Name offerName = ItemInfo.find(ItemInfo.Name.class, box.info());
            String productName = offerName == null ? null : offerName.str.text;
            String priceName = box.price.name();
            if (productName == null || productName.trim().isEmpty() || priceName == null || priceName.trim().isEmpty()) {
                throw new Loading("Waiting for barter stand item names");
            }

            GSprite productSprite = slots.get(slot).sprite();
            if (productSprite == null) {
                throw new Loading("Waiting for product sprite");
            }
            CapturedItem product = captureItem(productName, box.res, productSprite, box.info(),
                    Math.max(0, box.leftNum), quality(box.info()));
            double priceQuality = box.pq > 0 ? box.pq : quality(box.price.info());
            CapturedItem price = captureItem(priceName, box.price.res, box.price.spr(), box.price.info(),
                    box.pnum, priceQuality);
            icons.put(product.resource, product.png);
            icons.put(price.resource, price.png);

            JSONObject offer = new JSONObject();
            offer.put("slot", slot);
            // Buy-button availability and the displayed stock counter are separate facts.
            offer.put("available", MarketOffers.buyAvailable(box.res != null, box.price != null, box.pnum));
            offer.put("product", product.json);
            offer.put("price", price.json);
            offers.put(offer);
        }

        JSONObject json = new JSONObject();
        json.put("capturedAt", Instant.now().toString());
        json.put("worldX", stand.rc.x);
        json.put("worldY", stand.rc.y);
        json.put("offers", offers);
        return new CapturedStand(json, icons);
    }

    /** One Shopbox seen through the readiness rules. The product sprite is built here exactly as Shopbox.draw does. */
    private static final class ShopSlot implements ShopboxReadiness.SlotView {
        private final Shopbox box;
        private final ShopMessageLog messages;

        ShopSlot(Shopbox box, ShopMessageLog messages) {
            this.box = box;
            this.messages = messages;
        }

        @Override
        public boolean hasProduct() {
            return box.res != null;
        }

        @Override
        public boolean hasPrice() {
            return box.price != null;
        }

        @Override
        public boolean heardProduct() {
            return messages.heard(box, "res");
        }

        @Override
        public boolean heardPrice() {
            return messages.heard(box, "price");
        }

        /** The product sprite, constructing it like Shopbox.draw() does when the widget has not been drawn yet. */
        GSprite sprite() {
            GSprite spr = box.spr;
            if (spr == null && box.res != null) {
                try {
                    spr = box.spr = GSprite.create(box, box.res.res.get(), box.res.sdt.clone());
                } catch (Loading stillLoading) {
                    return null;
                }
            }
            return spr;
        }

        @Override
        public boolean productSpriteReady() {
            return sprite() != null;
        }

        @Override
        public boolean priceSpriteReady() {
            try {
                return box.price != null && box.price.spr() != null;
            } catch (Loading stillLoading) {
                return false;
            }
        }

        @Override
        public boolean namesReady() {
            try {
                ItemInfo.Name offerName = ItemInfo.find(ItemInfo.Name.class, box.info());
                String productName = offerName == null ? null : offerName.str.text;
                String priceName = box.price == null ? null : box.price.name();
                return productName != null && !productName.trim().isEmpty()
                        && priceName != null && !priceName.trim().isEmpty();
            } catch (Loading stillLoading) {
                return false;
            }
        }
    }

    private static CapturedItem captureItem(String name, ResData resource, GSprite sprite,
                                            List<ItemInfo> info, int amount, double quality) throws Exception {
        String baseResource = resource.res.get().name;
        BufferedImage image = ItemTex.sprimg(sprite);
        if (image == null) {
            throw new Loading("Waiting for rendered item sprite");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG encoder is available");
        }
        byte[] png = output.toByteArray();
        String iconResource = iconResource(baseResource, png);

        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("res1", iconResource);
        json.put("amount", amount);
        json.put("quantity", quantity(info));
        json.put("quality", Math.max(0, quality));
        return new CapturedItem(iconResource, png, json);
    }

    static String iconResource(String baseResource, byte[] png) throws IOException {
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(png);
            StringBuilder encoded = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                encoded.append(String.format("%02x", bytes[index]));
            }
            hash = encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is not available", impossible);
        }
        String suffix = "-h4d-" + hash;
        int maxBaseLength = 240 - suffix.length();
        String base = baseResource.length() > maxBaseLength
                ? baseResource.substring(0, maxBaseLength)
                : baseResource;
        return base + suffix;
    }

    private static int quantity(List<ItemInfo> info) {
        GItem.NumberInfo number = ItemInfo.find(GItem.NumberInfo.class, info);
        return number == null ? 1 : Math.max(1, number.itemnum());
    }

    private static double quality(List<ItemInfo> info) {
        Quality quality = ItemInfo.find(Quality.class, info);
        return quality == null ? 0 : quality.q;
    }

    // ---------------------------------------------------------------- discovery plumbing

    /** Physical reference of a loaded gob: its map grid's persistent id plus the offset inside that grid. */
    static PhysicalRef refOf(MCache map, Coord2d rc) {
        Coord tile = rc.floor(MCache.tilesz);
        MCache.Grid grid = map.getgridt(tile);
        Coord2d origin = grid.ul.mul(MCache.tilesz);
        return new PhysicalRef(Long.toString(grid.id), rc.x - origin.x, rc.y - origin.y);
    }

    /** True when the point lies in one of the NArea's per-grid rectangles (works for partly unloaded areas). */
    static boolean insideArea(NArea area, MCache map, Coord2d rc) {
        Coord tile = rc.floor(MCache.tilesz);
        MCache.Grid grid;
        try {
            grid = map.getgridt(tile);
        } catch (Loading unloaded) {
            return false;
        }
        NArea.VArea piece = area.space.space.get(grid.id);
        return piece != null && piece.area.contains(tile.sub(grid.ul));
    }

    private static List<StandMatcher.Detected<Gob>> detectLoaded(NGameUI gui, NArea area) throws InterruptedException {
        MCache map = gui.map.glob.map;
        List<StandMatcher.Detected<Gob>> found = new ArrayList<>();
        List<Gob> candidates = new ArrayList<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (!(gob instanceof OCache.Virtual) && NParser.isIt(gob, STAND_ALIAS)) {
                    candidates.add(gob);
                }
            }
        }
        for (Gob gob : candidates) {
            try {
                if (insideArea(area, map, gob.rc)) {
                    found.add(new StandMatcher.Detected<>(gob, refOf(map, gob.rc), gob.rc.x, gob.rc.y));
                }
            } catch (Loading unloaded) {
                // Its grid is not loaded yet; a later waypoint will see it.
            }
        }
        return found;
    }

    private static Gob findCurrentGob(NGameUI gui, PhysicalRef ref, double tolerance) throws InterruptedException {
        for (StandMatcher.Detected<Gob> candidate : detectAll(gui)) {
            if (candidate.ref.distanceTo(ref) <= tolerance) {
                return candidate.value;
            }
        }
        return null;
    }

    private static List<StandMatcher.Detected<Gob>> detectAll(NGameUI gui) throws InterruptedException {
        MCache map = gui.map.glob.map;
        List<StandMatcher.Detected<Gob>> found = new ArrayList<>();
        List<Gob> candidates = new ArrayList<>();
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (!(gob instanceof OCache.Virtual) && NParser.isIt(gob, STAND_ALIAS)) {
                    candidates.add(gob);
                }
            }
        }
        for (Gob gob : candidates) {
            try {
                found.add(new StandMatcher.Detected<>(gob, refOf(map, gob.rc), gob.rc.x, gob.rc.y));
            } catch (Loading unloaded) {
                // not mapped yet
            }
        }
        return found;
    }

    /** One bounded, targeted look at the saved position of every stand the sweep did not see. */
    private static List<StandMatcher.Detected<Gob>> recoverMissing(NGameUI gui, NArea area, GameSource source,
                                                                    List<MarketLayout.Stand> missing,
                                                                    List<StandMatcher.Detected<Gob>> already)
            throws InterruptedException {
        List<StandMatcher.Detected<Gob>> all = new ArrayList<>(already);
        boolean changed = false;
        for (MarketLayout.Stand stand : missing) {
            if (!goNear(gui, area, stand.physical.gridId, stand.physical.offX, stand.physical.offY)) {
                continue;
            }
            Thread.sleep(500);
            for (StandMatcher.Detected<Gob> seen : source.detect()) {
                boolean known = false;
                for (StandMatcher.Detected<Gob> existing : all) {
                    if (existing.ref.distanceTo(seen.ref) <= 1.0) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    all.add(seen);
                    changed = true;
                }
            }
        }
        return changed ? all : new ArrayList<>();
    }

    private static MCache.Grid findGrid(NGameUI gui, String gridId) {
        try {
            return gui.map.glob.map.findGrid(Long.parseLong(gridId));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** World position of a grid-relative point, or null while that grid is not loaded. */
    private static Coord2d spotOf(NGameUI gui, String gridId, double offX, double offY) {
        MCache.Grid grid = findGrid(gui, gridId);
        return grid == null ? null : grid.ul.mul(MCache.tilesz).add(offX, offY);
    }

    private static boolean arrivedAt(Coord2d spot) {
        return spot != null && NUtils.player() != null && NUtils.player().rc.dist(spot) <= ARRIVAL_UNITS;
    }

    /**
     * Moves toward a grid-relative position and VERIFIES arrival before reporting success. ChunkNav goes first
     * when the grid is not loaded or the target is far; some ChunkNav paths end at the overall NArea rather than
     * the requested point, so afterwards local obstacle-aware navigation finishes the leg and the distance is
     * checked. Returns false (never loops) when the spot cannot be reached.
     */
    static boolean goNear(NGameUI gui, NArea area, String gridId, double offX, double offY) throws InterruptedException {
        Coord2d spot = spotOf(gui, gridId, offX, offY);
        if (arrivedAt(spot)) {
            return true;
        }
        if (spot == null || NUtils.player().rc.dist(spot) > LOCAL_RANGE_UNITS) {
            chunkNavigate(gui, area, gridId, offX, offY);
            long deadline = System.currentTimeMillis() + 8_000;
            while (spot == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
                spot = spotOf(gui, gridId, offX, offY);
            }
            if (spot == null) {
                return false;
            }
            if (arrivedAt(spot)) {
                return true;
            }
        }
        new PathFinder(spot).run(gui);
        return arrivedAt(spot);
    }

    private static boolean chunkNavigate(NGameUI gui, NArea area, String gridId, double offX, double offY)
            throws InterruptedException {
        if (!(gui.map instanceof NMapView)) {
            return false;
        }
        ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
        if (chunkNav == null || !chunkNav.isInitialized()) {
            return false;
        }
        int tileX = (int) Math.max(0, Math.min(MCache.cmaps.x - 1, Math.floor(offX / MCache.tilesz.x)));
        int tileY = (int) Math.max(0, Math.min(MCache.cmaps.y - 1, Math.floor(offY / MCache.tilesz.y)));
        nurgling.navigation.ChunkPath path = chunkNav.planToGridCoord(Long.parseLong(gridId), new Coord(tileX, tileY));
        return path != null && chunkNav.navigateWithPath(path, area, gui).IsSuccess();
    }

    /** Live game access for re-acquiring one stand from its saved physical reference. */
    private static final class LiveWorld implements StandReacquirer.World<Gob> {
        private final NGameUI gui;
        private final NArea area;

        LiveWorld(NGameUI gui, NArea area) {
            this.gui = gui;
            this.area = area;
        }

        @Override
        public boolean approach(PhysicalRef saved) throws InterruptedException {
            return goNear(gui, area, saved.gridId, saved.offX, saved.offY);
        }

        @Override
        public List<StandMatcher.Detected<Gob>> loaded() throws InterruptedException {
            return detectAll(gui);
        }
    }

    private static void exportDiscovery(NArea area, JSONObject discovery) {
        try {
            nurgling.tools.NFileUtils.writeAtomically(
                    NUtils.getDataFilePath("h4d-market-discovery-" + area.id + ".json").toString(),
                    discovery.toString(2));
        } catch (Exception failure) {
            System.err.println("[H4DMarketScanner] Could not write discovery export: " + failure.getMessage());
        }
    }

    /** Live game access for the sweep. */
    private static final class GameSource implements AreaSweep.Source<Gob> {
        private final NGameUI gui;
        private final NArea area;

        GameSource(NGameUI gui, NArea area) {
            this.gui = gui;
            this.area = area;
        }

        List<AreaSweepPlanner.Piece> pieces() {
            List<AreaSweepPlanner.Piece> pieces = new ArrayList<>();
            List<Long> ids = new ArrayList<>(area.space.space.keySet());
            ids.sort(Long::compare);
            for (Long id : ids) {
                haven.Area rect = area.space.space.get(id).area;
                pieces.add(new AreaSweepPlanner.Piece(id, rect.ul.x, rect.ul.y, rect.br.x, rect.br.y));
            }
            return pieces;
        }

        /** Reached only when the player is verifiably near the requested waypoint (not merely somewhere in the NArea). */
        @Override
        public boolean visit(AreaSweepPlanner.Waypoint waypoint) throws InterruptedException {
            double offX = (waypoint.tileX + 0.5) * MCache.tilesz.x;
            double offY = (waypoint.tileY + 0.5) * MCache.tilesz.y;
            if (!goNear(gui, area, Long.toString(waypoint.gridId), offX, offY)) {
                return false;
            }
            settle();
            return true;
        }

        private void settle() throws InterruptedException {
            // Objects arrive shortly after the grid; wait until the loaded stand count stops changing.
            long deadline = System.currentTimeMillis() + WAYPOINT_SETTLE_MS;
            int last = -1;
            while (System.currentTimeMillis() < deadline) {
                int count = detectAll(gui).size();
                if (count == last) {
                    return;
                }
                last = count;
                Thread.sleep(300);
            }
        }

        @Override
        public List<StandMatcher.Detected<Gob>> detect() throws InterruptedException {
            return detectLoaded(gui, area);
        }
    }

    // ------------------------------------------------------------------------- misc

    private static NArea resolveArea(NGameUI gui, Integer configuredId) {
        if (configuredId != null && configuredId > 0) {
            return gui.map.glob.map.areas.get(configuredId);
        }
        NArea partial = null;
        for (NArea area : gui.map.glob.map.areas.values()) {
            if (DEFAULT_AREA_NAME.equalsIgnoreCase(area.name)) {
                return area;
            }
            if (area.name != null && area.name.toLowerCase(java.util.Locale.ROOT).contains("market")) {
                if (partial != null) {
                    partial = null;
                    break;
                }
                partial = area;
            }
        }
        return partial;
    }

    private static boolean navigateToMarket(NGameUI gui, NArea area) throws InterruptedException {
        if (gui.map instanceof NMapView) {
            ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
            if (chunkNav != null && chunkNav.isInitialized()) {
                return chunkNav.navigateToArea(area, gui).IsSuccess();
            }
        }
        return NUtils.navigateToArea(area, true);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static final class CapturedItem {
        final String resource;
        final byte[] png;
        final JSONObject json;

        CapturedItem(String resource, byte[] png, JSONObject json) {
            this.resource = resource;
            this.png = png;
            this.json = json;
        }
    }

    private static final class CapturedStand {
        final JSONObject json;
        final Map<String, byte[]> icons;

        CapturedStand(JSONObject json, Map<String, byte[]> icons) {
            this.json = json;
            this.icons = icons;
        }
    }

    private static final class Config {
        final Integer areaId;
        final String endpoint;
        final String token;
        final double standTolerance;
        final int sweepSpacingTiles;
        final int maxStandAttempts;
        final boolean publishPartial;
        final boolean emptySlotSilenceFallback;
        final boolean traceShopbox;

        private Config(Integer areaId, String endpoint, String token, double standTolerance,
                       int sweepSpacingTiles, int maxStandAttempts, boolean publishPartial,
                       boolean emptySlotSilenceFallback, boolean traceShopbox) {
            this.areaId = areaId;
            this.endpoint = endpoint;
            this.token = token;
            this.standTolerance = standTolerance;
            this.sweepSpacingTiles = sweepSpacingTiles;
            this.maxStandAttempts = maxStandAttempts;
            this.publishPartial = publishPartial;
            this.emptySlotSilenceFallback = emptySlotSilenceFallback;
            this.traceShopbox = traceShopbox;
        }

        static Config from(Map<String, Object> settings) {
            Integer areaId = integer(settings.get("areaId"), null);
            String endpoint = string(settings.get("endpoint"), env("H4D_MARKET_ENDPOINT", DEFAULT_ENDPOINT));
            while (endpoint.endsWith("/")) {
                endpoint = endpoint.substring(0, endpoint.length() - 1);
            }
            String token = string(settings.get("token"), "");
            if (token.isEmpty()) {
                token = env("H4D_MARKET_INGEST_TOKEN", System.getProperty("h4d.market.ingestToken", ""));
            }
            // An old Scenario may still carry coordinateTolerance=8 from the map-matching era; stands are
            // only ~11 units apart, so physical matching never accepts more than 4.
            double tolerance = Math.min(4.0, Math.max(0.5,
                    decimal(settings.get("standTolerance"), StandMatcher.DEFAULT_TOLERANCE)));
            int spacing = Math.max(8, integer(settings.get("sweepSpacingTiles"), AreaSweepPlanner.DEFAULT_SPACING_TILES));
            int attempts = Math.max(1, Math.min(6, integer(settings.get("maxStandAttempts"), 3)));
            boolean partial = !"false".equalsIgnoreCase(String.valueOf(settings.getOrDefault("publishPartial", "true")));
            boolean silence = "true".equalsIgnoreCase(String.valueOf(settings.getOrDefault("emptySlotSilenceFallback", "false")));
            boolean trace = "true".equalsIgnoreCase(String.valueOf(settings.getOrDefault("traceShopbox", "false")));
            return new Config(areaId, endpoint, token, tolerance, spacing, attempts, partial, silence, trace);
        }

        private static String string(Object value, String fallback) {
            if (value == null || value.toString().trim().isEmpty()) {
                return fallback;
            }
            return value.toString().trim();
        }

        private static Integer integer(Object value, Integer fallback) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }

        private static double decimal(Object value, double fallback) {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
            if (value != null) {
                try {
                    return Double.parseDouble(value.toString().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }
    }
}
