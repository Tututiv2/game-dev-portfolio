package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The owner's real `/money log` from a wheat session on the Garden
 * (2026-10-09, 534 lines, copied verbatim to src/test/resources), replayed
 * through the engine. In that log every crop was counted twice in opposite
 * directions - WHEAT +134 into the inventory, -40 a moment later into the
 * sack - and the [Sacks] summary then counted it again, so between summaries
 * the minus side dominated and the rate went negative.
 *
 * <p>The replay rebuilds the engine's inputs from the decisions it logged:
 * inventory snapshots from every inventory line (counted or not), the purse
 * from every purse line, each [Sacks] summary from its sack lines, pest drops,
 * and the chat lines themselves through {@link MoneyRouter#line}. Menus are
 * logged when they open but not when they close, so a close is inferred: after
 * the last line the menu was still open for, and late enough that the last
 * "grace after menu" line before the next menu was inside its 3 s.
 */
class MoneyLogReplayTest {

    private static final Pattern LINE = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(COUNTED|ignored)\\s+(\\S+)\\s+(\\S+)\\s+(.*?)\\s+\\| (.*)$");
    private static final Pattern INV = Pattern.compile("^(\\S+) ([+-]\\d+)(?: @(\\S+))?$");
    private static final Pattern SACK = Pattern.compile("^(.+?) \\+(\\d+)(?: @\\S+)?$");
    private static final Pattern DROP = Pattern.compile("^(.+?) x(\\d+)(?: @\\S+)?$");

    private record Entry(long t, boolean counted, double value, String channel, String what, String rule) {
    }

    private static double num(String v) {
        if (v.equals("unpriced")) {
            return Double.NaN;
        }
        return Double.parseDouble(v.replace(",", "").replace("+", ""));
    }

    private static List<Entry> read() throws IOException {
        List<Entry> out = new ArrayList<>();
        try (InputStream in = MoneyLogReplayTest.class.getResourceAsStream("money-log-2026-10-09-wheat.txt")) {
            assertNotNull(in, "fixture missing");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                long t = ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60
                        + Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
                out.add(new Entry(t, m.group(5).equals("COUNTED"), num(m.group(6)), m.group(7), m.group(8), m.group(9)));
            }
        }
        return out;
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static PriceData prices() {
        Map<String, List<String>> names = new HashMap<>();
        names.put("enchanted golden carrot", List.of("ENCHANTED_GOLDEN_CARROT"));
        names.put("fossil essence", List.of("ESSENCE_FOSSIL"));
        return PriceData.EMPTY
                .withItems(new PriceData.Items(Map.of(), names), 1)
                .withBazaar(Map.of("ENCHANTED_GOLDEN_CARROT", new PriceData.Bazaar(77_500, 75_000)), 1);
    }

    @Test
    void theRealWheatSessionNeverGoesNegativeAndCountsTheSacksAndDropsOnce() throws IOException {
        List<Entry> log = read();
        assertEquals(533, log.size(), "every decision line parsed");

        // Menu closes, inferred (see the class comment).
        TreeMap<Long, Boolean> menuClose = new TreeMap<>();
        for (int i = 0; i < log.size(); i++) {
            Entry o = log.get(i);
            if (!o.channel().equals("menu")) {
                continue;
            }
            long close = o.t() + 1;
            long next = Long.MAX_VALUE;
            for (int j = i + 1; j < log.size(); j++) {
                Entry x = log.get(j);
                if (x.channel().equals("menu")) {
                    next = x.t();
                    break;
                }
                boolean io = x.channel().equals("inv") || x.channel().equals("purse");
                if (io && x.rule().startsWith("menu-open")) {
                    close = Math.max(close, x.t() + 1);
                } else if (io && x.rule().startsWith("grace")) {
                    close = Math.max(close, x.t() - 2999);
                }
            }
            menuClose.put(Math.min(close, next - 1), Boolean.TRUE);
        }

        PriceData data = prices();
        IncomeEngine e = new IncomeEngine(new RateTracker(60_000), 3000, 90_000);
        e.setCollecting(Set.of("WHEAT", "ENCHANTED_WHEAT", "ENCHANTED_HAY_BALE"));
        long t0 = log.get(0).t() - 1000;
        e.rate().activity(t0);
        Map<String, IncomeEngine.Holding> inv = new HashMap<>();
        inv.put("WHEAT", new IncomeEngine.Holding(300, 2.6));          // enough that the churn never goes below 0
        inv.put("SEEDS", new IncomeEngine.Holding(300, 0.9));
        inv.put("VINYL_IMAGINE_DRAGONFLIES", new IncomeEngine.Holding(1, 90_000));
        e.inventory(t0, Map.copyOf(inv));
        double purse = 10_000_000;
        e.purse(t0, purse);

        double sackCounted = 0;
        double drops = 0;
        double purseCounted = 0;
        double minTotal = 0;
        Map<String, Integer> lowest = new HashMap<>(Map.of("WHEAT", 300, "SEEDS", 300));
        int i = 0;
        while (i < log.size()) {
            Entry x = log.get(i);
            long t = x.t();
            while (!menuClose.isEmpty() && menuClose.firstKey() < t) {
                long c = menuClose.pollFirstEntry().getKey();
                e.menu(c, false, false);
            }
            e.rate().activity(t);
            switch (x.channel()) {
                case "menu" -> {
                    e.menu(t, true, x.what().startsWith("sack menu"));
                    i++;
                }
                case "purse" -> {
                    purse += x.value();
                    e.purse(t, purse);
                    if (x.counted()) {
                        purseCounted += x.value();
                    }
                    i++;
                }
                case "inv" -> {
                    // One snapshot per timestamp.
                    while (i < log.size() && log.get(i).t() == t && log.get(i).channel().equals("inv")) {
                        Matcher m = INV.matcher(log.get(i).what());
                        assertTrue(m.matches(), log.get(i).what());
                        int d = Integer.parseInt(m.group(2));
                        double unit = m.group(3) != null ? num(m.group(3)) : Math.abs(log.get(i).value() / d);
                        IncomeEngine.Holding h = inv.get(m.group(1));
                        int have = (h == null ? 0 : h.count()) + d;
                        assertTrue(have >= 0, "inventory went below zero at " + log.get(i));
                        inv.put(m.group(1), new IncomeEngine.Holding(have, unit));
                        lowest.computeIfPresent(m.group(1), (k, v) -> Math.min(v, have));
                        i++;
                    }
                    e.inventory(t, Map.copyOf(inv));
                }
                case "sacks" -> {
                    // One [Sacks] message: every line within a few ms.
                    while (i < log.size() && log.get(i).channel().equals("sacks") && log.get(i).t() - t <= 5) {
                        Entry s = log.get(i);
                        Matcher m = SACK.matcher(s.what());
                        assertTrue(m.matches(), s.what());
                        int n = Integer.parseInt(m.group(2));
                        double got = e.sackGain(s.t(), key(m.group(1)), n, s.value() / n, m.group(1));
                        if (s.counted()) {
                            sackCounted += s.value();
                            assertEquals(s.value(), got, 1e-6, "same verdict as the real run: " + s.what());
                        } else {
                            assertEquals(0, got, 1e-9, "same verdict as the real run: " + s.what());
                        }
                        i++;
                    }
                    e.sackSummary(log.get(i - 1).t());
                }
                case "drop" -> {
                    Matcher m = DROP.matcher(x.what());
                    assertTrue(m.matches(), x.what());
                    int n = Integer.parseInt(m.group(2));
                    drops += e.drop(t, key(m.group(1)), n, x.value() / n, IncomeSource.PEST, m.group(1), x.rule());
                    i++;
                }
                case "chat" -> {
                    if (x.rule().startsWith("trade-line")) {
                        MoneyRouter.line(e, data, t, x.what(), null, true);
                    }
                    i++;                                               // "delivery(...)" lines are the engine's own output
                }
                default -> i++;
            }
            minTotal = Math.min(minTotal, e.rate().total());
            assertTrue(e.rate().total() >= -1e-9, "session went negative at " + x);
            assertTrue(e.recentPerHour(t, 10 * 60_000L, 15_000) >= -1e-9, "rate went negative at " + x);
            double crops = e.recentBySource(t, 10 * 60_000L)[IncomeSource.SACKS.ordinal()];
            assertTrue(crops >= -1e-9, "crops went negative at " + x);
        }

        assertEquals(312_863, sackCounted, 1);                          // the 20 counted sack lines
        assertEquals(81_800, drops, 1);                                  // 3 pest kills, Enchanted Sunflower x44
        assertEquals(19, purseCounted, 1e-9);
        assertEquals(sackCounted, e.fromSacks(), 1e-6);
        assertEquals(drops, e.fromDrops(), 1e-6);
        assertEquals(purseCounted, e.fromPurse(), 1e-6);
        // 433 WHEAT/SEEDS lines of churn (the log's own inventory sum: -223 coins) are transit into
        // the sack and count nothing. What the inventory DOES count is what physically stayed: when he
        // stopped farming at 02:34:58 the compactor's leftover sat in the inventory for the last 90 s,
        // 4 wheat and 32 seeds above the lowest level of the session - and exactly that, no more.
        int wheatStayed = inv.get("WHEAT").count() - lowest.get("WHEAT");
        int seedsStayed = inv.get("SEEDS").count() - lowest.get("SEEDS");
        assertEquals(4, wheatStayed);
        assertEquals(32, seedsStayed);
        double stayed = wheatStayed * 2.7 + seedsStayed * 1.1;
        assertEquals(stayed, e.fromItems(), 1e-6);
        assertEquals(sackCounted + drops + purseCounted + stayed, e.rate().total(), 1e-6);
        assertEquals(394_728, e.rate().total(), 1);
        assertEquals(0, minTotal, 1e-9);
    }
}
