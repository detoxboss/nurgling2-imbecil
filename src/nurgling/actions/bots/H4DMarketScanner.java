package nurgling.actions.bots;

import haven.GItem;
import haven.GSprite;
import haven.Gob;
import haven.ItemInfo;
import haven.Loading;
import haven.ResData;
import haven.Widget;
import haven.Window;
import haven.res.lib.itemtex.ItemTex;
import haven.res.ui.barterbox.Shopbox;
import haven.res.ui.tt.q.quality.Quality;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.CloseTargetWindow;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.market.MarketApiClient;
import nurgling.market.MarketScanSpool;
import nurgling.market.MarketStandOrdering;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans every barter stand in a market NArea and publishes one atomic H4D Market snapshot.
 * The existing Nurgling pathfinder, container opener and Shopbox model remain the source of
 * truth for all game interaction; this class adds route ordering and resumable HTTP delivery.
 */
public class H4DMarketScanner implements Action {
    public static final String BOT_ID = "h4d_market_scan";
    public static final String DEFAULT_ENDPOINT = "https://h4d.shop";
    public static final String DEFAULT_AREA_NAME = "H4D Market";
    public static final int DEFAULT_STAND_COUNT = 24;
    private static final double DEFAULT_ROW_TOLERANCE = 6.0;
    private static final long CAPTURE_TIMEOUT_MS = 15_000;
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
        gui.msg("H4D Market Scanner: navigating to " + area.name);
        if (NUtils.player() == null) {
            return Results.ERROR("H4D Market Scanner: player is not available");
        }
        if (!NUtils.navigateToArea(area, true)) {
            return Results.ERROR("H4D Market Scanner: could not navigate to NArea '" + area.name + "'");
        }

        List<Gob> found = Finder.findGobs(area, new NAlias("gfx/terobjs/barterstand"));
        if (found.size() != config.expectedStands) {
            return Results.ERROR("H4D Market Scanner: expected " + config.expectedStands
                    + " barter stands in NArea '" + area.name + "', but found " + found.size()
                    + ". Ensure the whole market is loaded and the NArea contains only its stalls.");
        }

        List<MarketStandOrdering.Stand<Gob>> points = new ArrayList<>();
        for (Gob stand : found) {
            points.add(new MarketStandOrdering.Stand<>(stand, stand.rc.x, stand.rc.y));
        }
        List<MarketStandOrdering.Stand<Gob>> ordered = MarketStandOrdering.order(
                points, config.firstCorner, config.rowTolerance);

        Path spoolPath = NUtils.getDataFilePath("h4d-market-scan-" + area.id + ".json");
        MarketApiClient client = new MarketApiClient(config.endpoint, config.token, 3);
        MarketScanSpool spool = MarketScanSpool.load(spoolPath);
        if (spool == null || !spool.matches(config.endpoint, area.id, ordered.size()) || !valid(spool, ordered.size())) {
            spool = begin(client, spoolPath, config, area.id, ordered.size());
        } else {
            gui.msg("H4D Market Scanner: resuming scan " + spool.scanId);
        }

        // One recovery is allowed if the VPS was rebuilt and no longer knows the persisted scan.
        for (int recovery = 0; recovery < 2; recovery++) {
            try {
                executeRoute(gui, client, spool, ordered);
                client.complete(spool.scanId);
                spool.clear();
                gui.msg("H4D Market Scanner: published " + ordered.size() + " stands");
                return Results.SUCCESS();
            } catch (MarketApiClient.ApiException apiFailure) {
                if (apiFailure.status != 404 || recovery > 0) {
                    throw apiFailure;
                }
                gui.msg("H4D Market Scanner: server lost the open scan; restarting it");
                spool = begin(client, spoolPath, config, area.id, ordered.size());
            }
        }
        return Results.ERROR("H4D Market Scanner: scan could not be recovered");
    }

    private static MarketScanSpool begin(MarketApiClient client, Path spoolPath, Config config,
                                         int areaId, int standCount) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("scannerId", "nurgling2-h4d-market");
        payload.put("startedAt", Instant.now().toString());
        String scanId = client.begin(payload);
        MarketScanSpool spool = MarketScanSpool.fresh(spoolPath, config.endpoint, areaId, standCount, scanId);
        spool.save();
        return spool;
    }

    private static void executeRoute(NGameUI gui, MarketApiClient client, MarketScanSpool spool,
                                     List<MarketStandOrdering.Stand<Gob>> ordered) throws Exception {
        if (spool.pendingHouseId != null && spool.pendingStand != null) {
            gui.msg("H4D Market Scanner: retrying stall #" + spool.pendingHouseId);
            client.putStand(spool.scanId, spool.pendingHouseId, spool.pendingStand);
            spool.acknowledgePending();
        }

        Set<String> checkedIcons = new HashSet<>();
        for (int index = 0; index < ordered.size(); index++) {
            int houseId = index + 1;
            if (spool.uploaded.contains(houseId)) {
                continue;
            }
            gui.msg("H4D Market Scanner: scanning stall #" + houseId + " of " + ordered.size());
            CapturedStand captured = captureStand(gui, ordered.get(index).value);
            for (Map.Entry<String, byte[]> icon : captured.icons.entrySet()) {
                if (checkedIcons.add(icon.getKey())) {
                    client.ensureIcon(icon.getKey(), icon.getValue());
                }
            }
            spool.setPending(houseId, captured.json);
            client.putStand(spool.scanId, houseId, captured.json);
            spool.acknowledgePending();
        }
        if (spool.uploaded.size() != ordered.size()) {
            throw new IOException("Only " + spool.uploaded.size() + " of " + ordered.size() + " stands were acknowledged");
        }
    }

    private static CapturedStand captureStand(NGameUI gui, Gob stand) throws Exception {
        Results path = new PathFinder(stand).run(gui);
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
            return waitForCapture(window, stand);
        } finally {
            new CloseTargetWindow(window).run(gui);
        }
    }

    private static CapturedStand waitForCapture(Window window, Gob stand) throws Exception {
        long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
        Loading lastLoading = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return capture(window, stand);
            } catch (Loading loading) {
                lastLoading = loading;
                Thread.sleep(100);
            }
        }
        throw new IOException("Timed out waiting for barter stand item resources", lastLoading);
    }

    private static CapturedStand capture(Window window, Gob stand) throws Exception {
        List<Shopbox> boxes = new ArrayList<>();
        for (Widget child = window.child; child != null; child = child.next) {
            if (child instanceof Shopbox) {
                boxes.add((Shopbox) child);
            }
        }
        if (boxes.size() != 5) {
            throw new Loading("Waiting for all five Shopbox widgets");
        }
        boxes.sort(Comparator.comparingInt((Shopbox box) -> box.c.y).thenComparingInt(box -> box.c.x));

        JSONArray offers = new JSONArray();
        Map<String, byte[]> icons = new LinkedHashMap<>();
        for (int slot = 0; slot < boxes.size(); slot++) {
            Shopbox box = boxes.get(slot);
            if (box.res == null || box.price == null || box.spr == null || box.pnum <= 0) {
                continue;
            }
            ItemInfo.Name offerName = ItemInfo.find(ItemInfo.Name.class, box.info());
            String productName = offerName == null ? null : offerName.str.text;
            String priceName = box.price.name();
            if (productName == null || productName.trim().isEmpty() || priceName == null || priceName.trim().isEmpty()) {
                throw new Loading("Waiting for barter stand item names");
            }

            CapturedItem product = captureItem(productName, box.res, box.spr, box.info(),
                    Math.max(0, box.leftNum), quality(box.info()));
            double priceQuality = box.pq > 0 ? box.pq : quality(box.price.info());
            CapturedItem price = captureItem(priceName, box.price.res, box.price.spr(), box.price.info(),
                    box.pnum, priceQuality);
            icons.put(product.resource, product.png);
            icons.put(price.resource, price.png);

            JSONObject offer = new JSONObject();
            offer.put("slot", slot);
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

    private static boolean valid(MarketScanSpool spool, int standCount) {
        if (spool.scanId == null || spool.scanId.isEmpty()) {
            return false;
        }
        for (Integer houseId : spool.uploaded) {
            if (houseId == null || houseId < 1 || houseId > standCount) {
                return false;
            }
        }
        return spool.pendingHouseId == null
                || (spool.pendingHouseId >= 1 && spool.pendingHouseId <= standCount && spool.pendingStand != null);
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
        final int expectedStands;
        final double rowTolerance;
        final MarketStandOrdering.FirstCorner firstCorner;

        private Config(Integer areaId, String endpoint, String token, int expectedStands,
                       double rowTolerance, MarketStandOrdering.FirstCorner firstCorner) {
            this.areaId = areaId;
            this.endpoint = endpoint;
            this.token = token;
            this.expectedStands = expectedStands;
            this.rowTolerance = rowTolerance;
            this.firstCorner = firstCorner;
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
            int expected = Math.max(1, Math.min(500,
                    integer(settings.get("expectedStands"), DEFAULT_STAND_COUNT)));
            double tolerance = decimal(settings.get("rowTolerance"), DEFAULT_ROW_TOLERANCE);
            return new Config(areaId, endpoint, token, expected, Math.max(0, tolerance),
                    MarketStandOrdering.FirstCorner.parse(settings.get("firstCorner")));
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
