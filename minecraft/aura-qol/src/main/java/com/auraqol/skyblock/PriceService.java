package com.auraqol.skyblock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@link PriceData} fresh from three public, keyless endpoints, on its
 * own daemon thread. Nothing here can block a frame or crash the game.
 *
 * <h2>Sources (all checked live on 2026-10-07)</h2>
 * <ul>
 *   <li><b>Bazaar</b> - {@value #BAZAAR_URL}. Hypixel's own, keyless, ~490 KB
 *       gzipped, refreshed every {@value #BAZAAR_MIN} min.</li>
 *   <li><b>NPC sell prices + item names</b> - {@value #ITEMS_URL}. Hypixel's,
 *       keyless, ~1.9 MB gzipped, changes with game updates only, so every
 *       {@value #ITEMS_HOURS} h.</li>
 *   <li><b>Lowest BIN</b> - {@value #LBIN_URL}: hysky.de, the backend
 *       Skyblocker itself uses, so the number in our tooltip agrees with the
 *       one Skyblocker prints right next to it. 42 KB gzipped, ~4,900 items
 *       including pets by level bracket, runes, cakes and potions; the server
 *       re-computes it every couple of minutes ({@code max-age=30}).
 *       Fallback {@value #LBIN_FALLBACK_URL} (Coflnet, NEU-format keys,
 *       ~2,800 items) is tried only if hysky fails. Moulberry's
 *       {@code lowestbin.json}, the historical NEU source, returned
 *       Cloudflare 525 on the day this was written and is not used.</li>
 * </ul>
 * The client never pages the auction house itself: 4,900 lowest prices in
 * 42 KB is what a server that already scans it can give a client.
 *
 * <h2>Failure</h2>
 * Offline, a 5xx, a half-delivered body, JSON with {@code success:false} -
 * each leaves the previous snapshot in place and retries one source after
 * {@value #RETRY_SEC} s. The last good snapshot is cached to disk, so a game
 * started offline still has yesterday's prices (the tooltip says how old).
 */
public final class PriceService {

    public static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    public static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    public static final String LBIN_URL = "https://hysky.de/api/auctions/lowestbins";
    public static final String LBIN_FALLBACK_URL = "https://sky.coflnet.com/api/prices/neu";

    static final int BAZAAR_MIN = 3;
    static final int LBIN_MIN = 4;
    static final int ITEMS_HOURS = 12;
    static final int RETRY_SEC = 60;

    private static final Logger LOG = LoggerFactory.getLogger("auraqol");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile PriceData data = PriceData.EMPTY;
    private static ScheduledExecutorService pool;
    private static Path cacheFile;
    private static BooleanSupplier wanted = () -> false;

    /** Wall-clock time of the next allowed attempt per source. */
    private static volatile long nextBazaar;
    private static volatile long nextLbin;
    private static volatile long nextItems;
    private static volatile String lastError = "";

    private PriceService() {
    }

    /** The current snapshot. Never null; may be {@link PriceData#EMPTY}. */
    public static PriceData data() {
        return data;
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * Load the disk cache and start polling.
     *
     * @param cache  where the compact snapshot lives
     * @param wantFn asked before every fetch - false means "nobody needs prices
     *               right now" (feature off, or not on Hypixel), and nothing is fetched
     */
    public static synchronized void start(Path cache, BooleanSupplier wantFn) {
        if (pool != null) {
            return;
        }
        cacheFile = cache;
        wanted = wantFn == null ? () -> true : wantFn;
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auraqol-prices");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        pool.execute(PriceService::loadCache);
        pool.scheduleWithFixedDelay(PriceService::tick, 3, 15, TimeUnit.SECONDS);
    }

    /** Fetch everything on the next poll (the /aq prices refresh command). */
    public static void refreshSoon() {
        nextBazaar = 0;
        nextLbin = 0;
        nextItems = 0;
        ScheduledExecutorService p = pool;
        if (p != null) {
            p.execute(() -> tick(true));
        }
    }

    private static void loadCache() {
        try {
            if (cacheFile != null && Files.isRegularFile(cacheFile)) {
                PriceData d = PriceData.fromCacheJson(Files.readString(cacheFile, StandardCharsets.UTF_8));
                if (!d.isEmpty() && data.isEmpty()) {
                    data = d;
                    LOG.info("[auraqol] prices: loaded cache ({} bazaar, {} lbin, {} npc)",
                            d.bazaarSize(), d.lbinSize(), d.npcSize());
                }
            }
        } catch (Throwable t) {
            LOG.warn("[auraqol] prices: cache unreadable, starting empty: {}", t.toString());
        }
    }

    private static void tick() {
        tick(false);
    }

    private static void tick(boolean force) {
        try {
            if (!force && !wanted.getAsBoolean()) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean changed = false;
            if (now >= nextBazaar) {
                changed |= fetchBazaar(now);
            }
            if (now >= nextLbin) {
                changed |= fetchLbin(now);
            }
            if (now >= nextItems) {
                changed |= fetchItems(now);
            }
            if (changed) {
                saveCache();
            }
        } catch (Throwable t) {
            // A scheduled task that throws is cancelled for good by the
            // executor - so nothing may escape, ever.
            LOG.warn("[auraqol] prices: refresh failed: {}", t.toString());
        }
    }

    private static boolean fetchBazaar(long now) {
        String body = get(BAZAAR_URL);
        Map<String, PriceData.Bazaar> bz = body == null ? null : PriceData.parseBazaar(body);
        if (bz == null || bz.isEmpty()) {
            nextBazaar = now + RETRY_SEC * 1000L;
            note("bazaar unavailable");
            return false;
        }
        data = data.withBazaar(bz, now);
        nextBazaar = now + BAZAAR_MIN * 60_000L;
        return true;
    }

    private static boolean fetchLbin(long now) {
        for (String url : List.of(LBIN_URL, LBIN_FALLBACK_URL)) {
            String body = get(url);
            Map<String, Double> lb = body == null ? null : PriceData.parseLowestBin(body);
            if (lb != null && lb.size() > 100) {
                data = data.withLbin(lb, now, url.contains("hysky") ? "hysky.de" : "coflnet");
                nextLbin = now + LBIN_MIN * 60_000L;
                return true;
            }
        }
        nextLbin = now + RETRY_SEC * 1000L;
        note("lowest BIN unavailable");
        return false;
    }

    private static boolean fetchItems(long now) {
        String body = get(ITEMS_URL);
        PriceData.Items items = body == null ? null : PriceData.parseItems(body);
        if (items == null || items.npc().isEmpty()) {
            nextItems = now + RETRY_SEC * 1000L;
            note("item list unavailable");
            return false;
        }
        data = data.withItems(items, now);
        nextItems = now + ITEMS_HOURS * 3_600_000L;
        return true;
    }

    private static void note(String what) {
        if (!what.equals(lastError)) {
            LOG.info("[auraqol] prices: {} - keeping the last good data, retrying in {}s", what, RETRY_SEC);
        }
        lastError = what;
    }

    /** GET a URL as text, transparently un-gzipping. Null on any failure. */
    static String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", "AuraQoL/1.0 (Fabric client mod; price tooltip)")
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                return null;
            }
            byte[] bytes = res.body();
            boolean gz = res.headers().firstValue("Content-Encoding")
                    .map(v -> v.toLowerCase().contains("gzip")).orElse(false);
            return decode(bytes, gz);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Bytes to text; gunzips when told to, or when the gzip magic is there anyway. */
    static String decode(byte[] bytes, boolean gzip) throws IOException {
        if (bytes == null) {
            return null;
        }
        boolean magic = bytes.length > 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (gzip || magic) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void saveCache() {
        if (cacheFile == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, data.toCacheJson(), StandardCharsets.UTF_8);
            // Write-then-rename, so a crash mid-write leaves the old cache
            // whole instead of a truncated file the next start cannot read.
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            try {
                Files.writeString(cacheFile, data.toCacheJson(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e2) {
                LOG.warn("[auraqol] prices: could not write cache: {}", e2.toString());
            }
        }
    }
}
