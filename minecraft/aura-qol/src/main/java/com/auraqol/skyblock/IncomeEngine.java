package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns purse readings, inventory snapshots, sack summaries and chat drops
 * into income, and refuses to count trades. Pure: the game side only feeds it
 * observations and a clock, which is what lets every rule below be a unit test.
 * Every decision - counted or not - is reported to a {@link Listener} with the
 * rule that made it, which is what {@code /money log} writes out.
 *
 * <h2>What counts</h2>
 * <ul>
 *   <li><b>Purse delta</b> - mob coins, dungeon coins, death penalties.</li>
 *   <li><b>Inventory delta</b>, valued per item - drops, crafted results,
 *       things you lost. SYMMETRIC on purpose: a compactor turning 160
 *       cobblestone into one enchanted cobblestone is worth ~0, not +1
 *       enchanted cobblestone, and only counting gains would call every
 *       craft profit.</li>
 *   <li><b>[Sacks] gains</b> - items picked up straight into sacks never touch
 *       the inventory. Sack <i>losses</i> ("Removed items:" lines) are never
 *       income or loss ({@link #sackRemoved}): they are compaction, Replenish,
 *       items moved out to the inventory or spent in a menu - all moves whose
 *       other side is either counted elsewhere or (correctly) not at all.</li>
 *   <li><b>Sackable items in the inventory are held, not counted</b>
 *       (2026-10-09 wheat log). On Hypixel a harvested crop lands in the
 *       inventory and ~0.5 s later is moved into the sack, so every crop shows
 *       up as an inventory "+N" and then a "-N", and 30 s later the [Sacks]
 *       summary reports it again. Counted per snapshot, the minus side
 *       dominated between summaries and the rate went NEGATIVE while farming.
 *       So for any item that goes to sacks (seen in a [Sacks] summary this
 *       session, a known sack count, or a {@link SackFamilies} member) the
 *       summary is the authority, and an inventory gain is counted only if it
 *       STAYS: the buffer is committed when a summary arrives (or every
 *       {@link #STAY_MS} without one) and credits the lowest level the item
 *       held above its baseline through the whole window - which transit
 *       churn always pulls back to zero. A sackable item that dips below its
 *       baseline while its family is flowing into sacks is reversed only up to
 *       what was credited for it ({@code held}); when nothing is flowing into
 *       sacks the old symmetric rule applies at the commit (a compactor eating
 *       160 cobble is -160, not 0).</li>
 *   <li><b>Chat drops</b> ({@link #drop}) - RARE DROP!, PET DROP!, RARE CROP!,
 *       pest kill loot, Jacob's Overclocker. Counted from chat because a drop
 *       can go to the item stash, a sack, or your inventory while a menu is
 *       open, where an inventory diff never sees it or must ignore it. The
 *       item is then recorded as ALREADY COUNTED, so when it does turn up in
 *       the inventory or a sack summary it is not counted twice - and if the
 *       inventory saw it a moment BEFORE the chat line, that credit is moved
 *       over to the drop instead.</li>
 * </ul>
 *
 * <h2>What does not</h2>
 * <ul>
 *   <li>Buying, selling, banking, trading. While a container menu is open, and
 *       for {@link #graceMs} after it closes or after a trade chat line,
 *       purse and inventory readings only move the BASELINE (Hypixel redraws
 *       the sidebar purse ~1 s after a menu closes).</li>
 *   <li><b>Deliveries</b> ({@link #acquired}) - bazaar claims and instant buys,
 *       NPC/Copper/Bits shop purchases, AH claims, items moved out of your
 *       sacks, stash pickups. Recorded per item; the next inventory or sack
 *       gain of that item is netted against it, and an inventory gain seen up
 *       to {@link #RECENT_MS} before the chat line is reversed.</li>
 *   <li>Items you put into sacks from a sack menu ({@link #expectSackDeposit}):
 *       the next [Sacks] "+N" of them is a move.</li>
 *   <li><b>Unexplained sack gains after a menu.</b> A [Sacks] summary covers
 *       the last 30 s, so a claim made in ANY menu (one whose chat line we did
 *       not parse) shows up there after the menu has closed. For
 *       {@link #TRADE_WINDOW_MS} after a non-sack menu was open, a sack gain
 *       only counts for an item you were already collecting (gained in a sack
 *       summary in the last {@link #STEADY_MS}, or in the "collecting" set the
 *       game side supplies - the crop/ore of the tool in hand). Everything
 *       else in that window is logged as ignored.</li>
 * </ul>
 */
public final class IncomeEngine {

    /** One item kind in an inventory snapshot: how many, and what one is worth (NaN = unknown). */
    public record Holding(int count, double unit) {
    }

    /** Sees every decision. {@code value} is coins (NaN when unpriced); {@code rule} says why. */
    public interface Listener {
        void event(long now, String channel, String what, double value, boolean counted, String rule);
    }

    /** An inventory gain seen this long before a chat line about the same item is assumed to be that item. */
    public static final long RECENT_MS = 3000;
    /** A [Sacks] summary covers 30 s and arrives at its end: a menu this recent may be in it. */
    public static final long TRADE_WINDOW_MS = 35_000;
    /** Items gained in a sack summary this recently count as "being collected". */
    public static final long STEADY_MS = 150_000;
    /** Held sackable inventory changes are committed at least this often (a [Sacks] summary commits sooner). */
    public static final long STAY_MS = 40_000;
    /** A family with a [Sacks] line this recently is "flowing into sacks": its inventory dips are transit. */
    public static final long FLOW_MS = 65_000;

    private record Pending(int count, long expires) {
    }

    /** One sackable item's inventory level relative to what is accounted for, since the last commit. */
    private static final class Buffered {
        /** Count now minus the accounted baseline (changes in a menu move the baseline, not this). */
        int excess;
        /** The lowest {@link #excess} since the last commit. */
        int low;
        double unit = Double.NaN;
    }

    private static final class Credit {
        final String key;
        int count;
        final double unit;
        final long at;
        final IncomeSource source;
        final boolean sack;

        Credit(String key, int count, double unit, long at, IncomeSource source, boolean sack) {
            this.key = key;
            this.count = count;
            this.unit = unit;
            this.at = at;
            this.source = source;
            this.sack = sack;
        }
    }

    private final RateTracker rate;
    private final long graceMs;
    private final long pendingMs;
    private final IncomeLedger ledger = new IncomeLedger(31 * 60_000L, 50_000);
    private Listener listener = (now, channel, what, value, counted, rule) -> { };

    private boolean menuOpen;
    private boolean sackMenu;
    private long excludeUntil = Long.MIN_VALUE;
    private long tradeWindowUntil = Long.MIN_VALUE;

    private Double lastPurse;
    private Map<String, Holding> lastInv;
    /** Sack-menu deposits: only the next SACK gain is netted. */
    private final Map<String, Pending> pendingSack = new HashMap<>();
    /** Deliveries and already-counted drops: the next inventory OR sack gain is netted. */
    private final Map<String, Pending> pendingAny = new HashMap<>();
    private final List<Credit> recent = new ArrayList<>();
    private final Map<String, Long> lastSackGain = new HashMap<>();
    private Set<String> collecting = Set.of();
    /** Sackable inventory changes waiting for a [Sacks] summary (or {@link #STAY_MS}). */
    private final Map<String, Buffered> buffer = new HashMap<>();
    /** Items credited from the inventory that are still assumed to be there (caps a transit reversal). */
    private final Map<String, Integer> held = new HashMap<>();
    /** Every item key a [Sacks] summary mentioned this session, added or removed. */
    private final Set<String> sackSeen = new HashSet<>();
    /** Family (or item) key -> when a [Sacks] line last mentioned it. */
    private final Map<String, Long> sackFlow = new HashMap<>();
    private Set<String> sackKnown = Set.of();
    private long lastCommit = Long.MIN_VALUE;
    private final Map<String, Integer> unpriced = new LinkedHashMap<>();
    private final Set<String> unpricedLogged = new HashSet<>();

    private double fromPurse;
    private double fromItems;
    private double fromSacks;
    private double fromDrops;
    private final double[] session = new double[IncomeSource.values().length];
    private double lastGain;
    private long lastGainAt = -1;

    public IncomeEngine(RateTracker rate, long graceMs, long pendingMs) {
        this.rate = rate;
        this.graceMs = Math.max(0, graceMs);
        this.pendingMs = Math.max(1000, pendingMs);
    }

    public RateTracker rate() {
        return rate;
    }

    public void setListener(Listener l) {
        listener = l == null ? (now, channel, what, value, counted, rule) -> { } : l;
    }

    /** Value keys of what you are collecting right now (the held tool's crop family, the ore being mined). */
    public void setCollecting(Set<String> keys) {
        collecting = keys == null ? Set.of() : Set.copyOf(keys);
    }

    /** Item keys with a known sack count (the Sacks panel's store). Read, not copied. */
    public void setKnownSackItems(Set<String> keys) {
        sackKnown = keys == null ? Set.of() : keys;
    }

    /**
     * Does this item go to sacks? Then its inventory changes are held for the
     * [Sacks] summary instead of counted as they happen.
     */
    public boolean sackable(String key) {
        return key != null && (sackSeen.contains(key) || sackKnown.contains(key) || SackFamilies.forItem(key) != null);
    }

    /** Family name for family members, else the key itself: the unit of "is this flowing into sacks". */
    private static String flowKey(String key) {
        SackFamilies.Family f = SackFamilies.forItem(key);
        return f == null ? key : "family:" + f.name();
    }

    private void sackLine(long now, String key) {
        sackSeen.add(key);
        sackFlow.put(flowKey(key), now);
    }

    private boolean flowing(String key, long now) {
        Long t = sackFlow.get(flowKey(key));
        return t != null && now - t <= FLOW_MS;
    }

    // ------------------------------------------------------------ exclusion

    /** A container menu (not your own inventory) is open or closed. Call every tick. */
    public void menu(long now, boolean open, boolean isSackMenu) {
        if (open) {
            if (!menuOpen) {
                listener.event(now, "menu", isSackMenu ? "sack menu opened" : "menu opened", 0, false,
                        "menu-open: purse/inventory only move the baseline");
            }
            menuOpen = true;
            sackMenu = isSackMenu;
            if (!isSackMenu) {
                tradeWindowUntil = Math.max(tradeWindowUntil, now + TRADE_WINDOW_MS);
            }
        } else if (menuOpen) {
            menuOpen = false;
            if (!sackMenu) {
                tradeWindowUntil = Math.max(tradeWindowUntil, now + TRADE_WINDOW_MS);
            }
            sackMenu = false;
            excludeUntil = Math.max(excludeUntil, now + graceMs);
        }
    }

    /** A chat line that means "this was a trade" arrived. */
    public void tradeMessage(long now) {
        tradeMessage(now, "trade", "");
    }

    public void tradeMessage(long now, String rule, String line) {
        excludeUntil = Math.max(excludeUntil, now + graceMs);
        listener.event(now, "chat", line, 0, false, "trade-line(" + rule + "): " + graceMs / 1000 + "s grace");
    }

    /** Items put into sacks from a sack menu: the next [Sacks] "+N" of them is a move. */
    public void expectSackDeposit(long now, String key, int count) {
        addPending(pendingSack, now, key, count);
    }

    /**
     * Items that arrived but are NOT income (a claim, a purchase, a move out
     * of your sacks). An inventory credit of the same item in the last
     * {@link #RECENT_MS} is reversed; the rest nets the next gain.
     */
    public void acquired(long now, String key, int count, String label, String rule) {
        if (key == null || count <= 0) {
            listener.event(now, "chat", label + " x" + count, Double.NaN, false,
                    "delivery(" + rule + "): item not in price data, cannot net it");
            return;
        }
        int left = count;
        for (int i = recent.size() - 1; i >= 0 && left > 0; i--) {
            Credit c = recent.get(i);
            if (!c.key.equals(key) || now - c.at > RECENT_MS || c.count <= 0) {
                continue;
            }
            int take = Math.min(left, c.count);
            c.count -= take;
            left -= take;
            double v = -take * c.unit;
            listener.event(now, c.sack ? "sacks" : "inv", label + " x" + take, v, true,
                    "reversed: it was a delivery (" + rule + "), not income");
            if (c.sack) {
                fromSacks += v;
            } else {
                fromItems += v;
            }
            credit(now, v, c.source);
        }
        if (left > 0) {
            addPending(pendingAny, now, key, left);
        }
        listener.event(now, "chat", label + " x" + count, 0, false,
                "delivery(" + rule + "): next gain of it is netted, not income");
    }

    /** New world/island/server: forget both baselines and hold off briefly. */
    public void rebaseline(long now) {
        lastPurse = null;
        lastInv = null;
        buffer.clear();
        menuOpen = false;
        sackMenu = false;
        excludeUntil = Math.max(excludeUntil, now + graceMs);
    }

    /** Item valuation switched off: drop the inventory baseline so switching it back on starts clean. */
    public void forgetInventory() {
        lastInv = null;
        buffer.clear();
    }

    public boolean excluded(long now) {
        return menuOpen || now < excludeUntil;
    }

    public boolean inTradeWindow(long now) {
        return now <= tradeWindowUntil;
    }

    // ------------------------------------------------------------ observations

    /** The purse as the sidebar shows it right now; null = not visible (ignored). */
    public void purse(long now, Double purse) {
        if (purse == null || purse.isNaN() || purse.isInfinite()) {
            return;
        }
        if (lastPurse != null) {
            double d = purse - lastPurse;
            if (Math.abs(d) >= 0.05) {
                if (excluded(now)) {
                    listener.event(now, "purse", "purse " + Fmt.signedCompact(d), d, false,
                            menuOpen ? "menu-open" : "grace after menu/trade line");
                } else {
                    fromPurse += d;
                    listener.event(now, "purse", "purse " + Fmt.signedCompact(d), d, true, "purse-delta");
                    credit(now, d, IncomeSource.PURSE);
                }
            }
        }
        lastPurse = purse;
    }

    /** A full snapshot of the player's inventory, keyed by {@link Pricing#valueKey}. */
    public void inventory(long now, Map<String, Holding> inv) {
        if (inv == null) {
            return;
        }
        if (lastInv != null) {
            boolean excl = excluded(now);
            Set<String> keys = new HashSet<>(lastInv.keySet());
            keys.addAll(inv.keySet());
            for (String k : keys) {
                Holding was = lastInv.get(k);
                Holding is = inv.get(k);
                int d = (is == null ? 0 : is.count()) - (was == null ? 0 : was.count());
                if (d == 0) {
                    continue;
                }
                double unit = is != null && !Double.isNaN(is.unit()) ? is.unit()
                        : was != null ? was.unit() : Double.NaN;
                // After a menu closes (the grace), crops keep pouring through the inventory into
                // their sack; for a family that is flowing that churn is transit, not a trade, and
                // must reach the buffer or it shifts the baseline (2026-10-09 log, after /sacks).
                // An open menu, or a family that is not flowing (a sale), still moves the baseline.
                boolean transit = excl && !menuOpen && sackable(k) && flowing(k, now);
                if (excl && !transit) {
                    if (sackMenu && d < 0) {
                        expectSackDeposit(now, k, -d);
                    }
                    listener.event(now, "inv", k + " " + signed(d), value(d, unit), false,
                            sackMenu && d < 0 ? "sack-menu deposit (next sack gain netted)"
                                    : menuOpen ? "menu-open" : "grace after menu/trade line");
                    continue;
                }
                if (sackable(k)) {
                    // On its way to (or from) a sack: the [Sacks] summary is the authority.
                    Buffered b = buffer.computeIfAbsent(k, x -> new Buffered());
                    b.excess += d;
                    b.low = Math.min(b.low, b.excess);
                    if (!Double.isNaN(unit) && !Double.isInfinite(unit)) {
                        b.unit = unit;
                    }
                    listener.event(now, "inv", k + " " + signed(d), value(d, unit), false,
                            "sackable: held for the [Sacks] summary, counted only if it stays");
                    continue;
                }
                if (d > 0) {
                    int netted = net(pendingAny, now, k, d);
                    if (netted > 0) {
                        listener.event(now, "inv", k + " +" + netted, value(netted, unit), false,
                                "netted: delivered/moved or already counted from chat");
                        d -= netted;
                        if (d == 0) {
                            continue;
                        }
                    }
                }
                if (Double.isNaN(unit) || Double.isInfinite(unit)) {
                    unpriced(now, "inv", k, d);
                    continue;
                }
                double v = d * unit;
                IncomeSource src = collecting.contains(k) ? IncomeSource.SACKS : IncomeSource.OTHER;
                fromItems += v;
                listener.event(now, "inv", k + " " + signed(d) + " @" + Fmt.coins(unit), v, true,
                        "inventory-delta (" + src.shortLabel() + ")");
                credit(now, v, src);
                held.put(k, Math.max(0, held.getOrDefault(k, 0) + d));
                if (d > 0) {
                    remember(new Credit(k, d, unit, now, src, false));
                }
            }
        }
        if (lastCommit == Long.MIN_VALUE || lastInv == null) {
            lastCommit = Math.max(lastCommit, now);
        }
        lastInv = Map.copyOf(inv);
        if (now - lastCommit >= STAY_MS) {
            commitHeld(now);
        }
    }

    /**
     * The held sackable inventory changes are settled: what STAYED counts,
     * what left after being counted is reversed. Called when a [Sacks] summary
     * arrives ({@link #sackSummary}) and every {@link #STAY_MS} without one.
     *
     * <p>While the item's family is flowing into sacks, only the lowest level
     * it held above its baseline through the whole window counts (transit
     * churn always returns to the floor), and a dip below the baseline is
     * reversed only up to what was credited for it - the rest went into a sack
     * and the summary counts it. Otherwise the current level is settled
     * symmetrically, like any other item.
     */
    private void commitHeld(long now) {
        lastCommit = now;
        for (Map.Entry<String, Buffered> en : buffer.entrySet()) {
            String k = en.getKey();
            Buffered b = en.getValue();
            boolean flow = flowing(k, now);
            int gain = flow ? b.low : b.excess;
            IncomeSource src = collecting.contains(k) ? IncomeSource.SACKS : IncomeSource.OTHER;
            if (gain > 0) {
                b.excess -= gain;
                int n = net(pendingAny, now, k, gain);
                if (n > 0) {
                    listener.event(now, "inv", k + " +" + n, value(n, b.unit), false,
                            "netted: delivered/moved or already counted from chat");
                }
                int c = gain - n;
                if (c > 0) {
                    if (Double.isNaN(b.unit) || Double.isInfinite(b.unit)) {
                        unpriced(now, "inv", k, c);
                    } else {
                        double v = c * b.unit;
                        fromItems += v;
                        held.merge(k, c, Integer::sum);
                        listener.event(now, "inv", k + " +" + c + " @" + Fmt.coins(b.unit), v, true,
                                "stayed in the inventory through a [Sacks] window (" + src.shortLabel() + ")");
                        credit(now, v, src);
                    }
                }
            } else {
                int left = flow ? -b.low : -b.excess;
                if (left > 0) {
                    b.excess += left;
                    int h = held.getOrDefault(k, 0);
                    int d = flow ? Math.min(left, h) : left;
                    held.put(k, Math.max(0, h - left));
                    if (d > 0 && !Double.isNaN(b.unit) && !Double.isInfinite(b.unit)) {
                        double v = -d * b.unit;
                        fromItems += v;
                        listener.event(now, "inv", k + " -" + d + " @" + Fmt.coins(b.unit), v, true,
                                flow ? "left the inventory after being counted (now in a sack)"
                                        : "inventory-delta, settled (" + src.shortLabel() + ")");
                        credit(now, v, src);
                    }
                    if (flow && left > d) {
                        listener.event(now, "inv", k + " -" + (left - d), value(left - d, b.unit), false,
                                "moved into a sack: the [Sacks] summary is the authority");
                    }
                }
            }
            b.low = b.excess;
        }
        buffer.values().removeIf(b -> b.excess == 0);
    }

    /**
     * A whole [Sacks] message has been read (all its "+N" and "-N" lines):
     * settle the held inventory changes against it.
     */
    public void sackSummary(long now) {
        commitHeld(now);
    }

    /**
     * One "-N item" line from a [Sacks] summary. NEVER income or loss:
     * compaction, Replenish, a move out to the inventory or a spend in a menu.
     * Only remembered as "this item goes to sacks".
     */
    public void sackRemoved(long now, String key, int delta, double unit, String label) {
        if (key != null) {
            sackLine(now, key);
        }
        int n = Math.abs(delta);
        listener.event(now, "sacks", label + " -" + n, Double.isNaN(unit) ? Double.NaN : -n * unit, false,
                "sack removal: compaction/Replenish/moved out - never income or loss");
    }

    /**
     * One "+N item" line from a [Sacks] summary. Netted against pending
     * deposits first; only positive leftovers with a known value count.
     *
     * @return the coins credited
     */
    public double sackGain(long now, String key, int delta, double unit) {
        return sackGain(now, key, delta, unit, key);
    }

    public double sackGain(long now, String key, int delta, double unit, String label) {
        if (key == null || delta <= 0) {
            if (key == null && delta > 0) {
                unpriced(now, "sacks", label, delta);
            }
            return 0;
        }
        sackLine(now, key);
        int n = net(pendingSack, now, key, delta);
        if (n > 0) {
            listener.event(now, "sacks", label + " +" + n, value(n, unit), false, "netted: put in from a sack menu");
            delta -= n;
        }
        n = net(pendingAny, now, key, delta);
        if (n > 0) {
            listener.event(now, "sacks", label + " +" + n, value(n, unit), false,
                    "netted: delivered/moved or already counted from chat");
            delta -= n;
        }
        if (delta <= 0) {
            return 0;
        }
        boolean steady = collecting.contains(key)
                || (lastSackGain.containsKey(key) && now - lastSackGain.get(key) <= STEADY_MS);
        if (inTradeWindow(now) && !steady) {
            listener.event(now, "sacks", label + " +" + delta, value(delta, unit), false,
                    "sack gain within " + TRADE_WINDOW_MS / 1000 + "s of a menu, item not being collected (likely a claim)");
            return 0;
        }
        lastSackGain.put(key, now);
        if (Double.isNaN(unit) || Double.isInfinite(unit) || unit <= 0) {
            unpriced(now, "sacks", label, delta);
            return 0;
        }
        double v = delta * unit;
        fromSacks += v;
        listener.event(now, "sacks", label + " +" + delta + " @" + Fmt.coins(unit), v, true, "sack-gain");
        credit(now, v, IncomeSource.SACKS);
        remember(new Credit(key, delta, unit, now, IncomeSource.SACKS, true));
        return v;
    }

    /**
     * A drop announced in chat. Counted now, at {@code unit} each, as
     * {@code source}; then recorded so the item's arrival is not counted
     * again. A credit for the same item from the last {@link #RECENT_MS}
     * (the inventory saw it first) is moved over rather than doubled.
     *
     * @param key  value key of the item, or null if the name is not in the price data
     * @return the coins credited
     */
    public double drop(long now, String key, int count, double unit, IncomeSource source, String label, String rule) {
        if (count <= 0) {
            return 0;
        }
        if (key == null || Double.isNaN(unit) || Double.isInfinite(unit) || unit <= 0) {
            // Unpriced: no pending either, so if the item reaches the
            // inventory under a key that IS priced, that still counts it.
            unpriced(now, "drop", label, count);
            return 0;
        }
        int left = count;
        for (int i = recent.size() - 1; i >= 0 && left > 0; i--) {
            Credit c = recent.get(i);
            if (!c.key.equals(key) || now - c.at > RECENT_MS || c.count <= 0) {
                continue;
            }
            int take = Math.min(left, c.count);
            c.count -= take;
            left -= take;
            double v = -take * c.unit;
            listener.event(now, c.sack ? "sacks" : "inv", label + " x" + take, v, true,
                    "moved to the drop line (" + rule + ") so it is not counted twice");
            if (c.sack) {
                fromSacks += v;
            } else {
                fromItems += v;
            }
            credit(now, v, c.source);
        }
        if (left > 0) {
            addPending(pendingAny, now, key, left);
        }
        double v = count * unit;
        fromDrops += v;
        listener.event(now, "drop", label + " x" + count + " @" + Fmt.coins(unit), v, true, rule);
        credit(now, v, source);
        return v;
    }

    private void unpriced(long now, String channel, String what, int count) {
        String k = what == null ? "?" : what;
        unpriced.merge(k, count, Integer::sum);
        if (unpriced.size() > 200) {
            unpriced.remove(unpriced.keySet().iterator().next());
        }
        if (!"inv".equals(channel) || unpricedLogged.add(k)) {
            listener.event(now, channel, k + " " + signed(count), Double.NaN, false, "unpriced: not in price data");
        }
    }

    private void remember(Credit c) {
        recent.add(c);
        if (recent.size() > 64) {
            recent.remove(0);
        }
    }

    private void addPending(Map<String, Pending> map, long now, String key, int count) {
        if (key == null || count <= 0) {
            return;
        }
        Pending p = map.get(key);
        int have = p == null || p.expires() < now ? 0 : p.count();
        map.put(key, new Pending(have + count, now + pendingMs));
    }

    /** Take up to {@code want} from a pending entry; returns how many were netted. */
    private static int net(Map<String, Pending> map, long now, String key, int want) {
        Pending p = map.get(key);
        if (p == null || want <= 0) {
            return 0;
        }
        if (p.expires() < now) {
            map.remove(key);
            return 0;
        }
        int take = Math.min(p.count(), want);
        int left = p.count() - take;
        if (left > 0) {
            map.put(key, new Pending(left, p.expires()));
        } else {
            map.remove(key);
        }
        return take;
    }

    private void credit(long now, double coins, IncomeSource source) {
        if (rate.paused() || coins == 0) {
            return;
        }
        rate.income(now, coins);
        session[source.ordinal()] += coins;
        ledger.add(rate.activeMs(now), coins, source);
        lastGain = (lastGainAt >= 0 && now - lastGainAt < 2500 ? lastGain : 0) + coins;
        lastGainAt = now;
    }

    /** Drop expired pending deposits and old credits; call occasionally. */
    public void prune(long now) {
        for (Map<String, Pending> map : List.of(pendingSack, pendingAny)) {
            Iterator<Map.Entry<String, Pending>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().expires() < now) {
                    it.remove();
                }
            }
        }
        recent.removeIf(c -> now - c.at > RECENT_MS * 2);
        lastSackGain.values().removeIf(t -> now - t > STEADY_MS * 2);
        ledger.prune(rate.activeMs(now));
    }

    private static double value(int count, double unit) {
        return Double.isNaN(unit) ? Double.NaN : count * unit;
    }

    private static String signed(int d) {
        return d > 0 ? "+" + d : Integer.toString(d);
    }

    // ------------------------------------------------------------ results

    public void reset() {
        rate.reset();
        ledger.clear();
        fromPurse = 0;
        fromItems = 0;
        fromSacks = 0;
        fromDrops = 0;
        java.util.Arrays.fill(session, 0);
        lastGain = 0;
        lastGainAt = -1;
        pendingSack.clear();
        pendingAny.clear();
        recent.clear();
        buffer.clear();
        held.clear();
        unpriced.clear();
        unpricedLogged.clear();
    }

    /** Coins from the purse channel (session). */
    public double fromPurse() {
        return fromPurse;
    }

    /** Coins from the inventory channel (session). */
    public double fromItems() {
        return fromItems;
    }

    /** Coins from the [Sacks] channel (session). */
    public double fromSacks() {
        return fromSacks;
    }

    /** Coins from chat drops (session). */
    public double fromDrops() {
        return fromDrops;
    }

    /** Session totals per {@link IncomeSource}, indexed by ordinal (a copy). */
    public double[] sessionBySource() {
        return session.clone();
    }

    /** Totals per source over the last {@code windowMs} of active time. */
    public double[] recentBySource(long now, long windowMs) {
        return ledger.bySource(rate.activeMs(now), windowMs);
    }

    /** Coins per hour over the last {@code windowMs} of active time (or less, early in a session). */
    public double recentPerHour(long now, long windowMs, long minMs) {
        return ledger.perHour(rate.activeMs(now), windowMs, minMs);
    }

    /** Items seen without a price this session: name -> count (newest last). */
    public Map<String, Integer> unpriced() {
        return Map.copyOf(unpriced);
    }

    /** The most recent gain (summed over a short burst) if it is under {@code showMs} old, else 0. */
    public double recentGain(long now, long showMs) {
        return lastGainAt >= 0 && now - lastGainAt <= showMs ? lastGain : 0;
    }

    public int pendingCount(String key) {
        Pending a = pendingSack.get(key);
        Pending b = pendingAny.get(key);
        return (a == null ? 0 : a.count()) + (b == null ? 0 : b.count());
    }
}
