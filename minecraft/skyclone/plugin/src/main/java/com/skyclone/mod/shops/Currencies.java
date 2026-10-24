package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Currencies that are not coins or bits, and how each one is paid (wave 5).
 *
 * <ol>
 *   <li><b>Carried as an item</b> ({@link Shops#currencyItem}): North Stars are the {@code NORTH_STAR} item, so they
 *       are paid like any item cost (inventory, then sacks). Handled by {@link Trade} as an item.</li>
 *   <li><b>Owned by a service</b>: Motes through {@code Features.Rift} once the rift module provides it; Copper is
 *       readable through {@code Features.Garden.copper} (shown as a balance) but no API spends it.</li>
 *   <li><b>A generic currency service</b> ({@code Features.Currencies}, requested in the hand-off for pelts): looked up
 *       by name so this module lights up the day core adds the interface and its owner provides it, with no change
 *       here. The expected shape is {@code long balance(Player, String)} and
 *       {@code boolean spend(Player, String, long, String)} (all-or-nothing). Until then a pelt line says where the
 *       real pelt shops are (farming runs them at the same two spots, so they are never missed in play).</li>
 * </ol>
 * Everything fails closed: an unknown currency is not spendable, a throwing provider spends nothing.
 */
final class Currencies {

    private Currencies() {}

    enum Kind { ITEM, SERVICE, NONE }

    static Kind kind(String id) {
        if (id == null || id.isBlank()) return Kind.NONE;
        if (Shops.currencyItem(id) != null) return Kind.ITEM;
        if (serviceSupports(id)) return Kind.SERVICE;
        return Kind.NONE;
    }

    static boolean supported(String id) { return kind(id) != Kind.NONE; }

    /** True when a SERVICE (not an item) can spend this currency right now. */
    static boolean serviceSupports(String id) {
        String c = norm(id);
        if (c.equals("mote")) return Sky.has(Features.Rift.class);
        Generic g = generic();
        return g != null && g.supports(c);
    }

    /**
     * The player's balance of a currency, or -1 when nobody can tell (shown as "?" nowhere: callers skip the line).
     * Item-carried currencies are counted by {@link Trade#have}.
     */
    static long balance(Player p, String id) {
        if (p == null) return -1;
        String c = norm(id);
        try {
            if (c.equals("mote") && Sky.has(Features.Rift.class)) return Math.max(0, Sky.service(Features.Rift.class).motes(p));
            if (c.equals("copper") && Sky.has(Features.Garden.class)) return Math.max(0, Sky.service(Features.Garden.class).copper(p));
            Generic g = generic();
            if (g != null) return g.balance(p, c);
        } catch (Throwable t) {
            return -1;
        }
        return -1;
    }

    /** Take {@code amount} of a SERVICE currency, all or nothing. False (nothing taken) when short or unsupported. */
    static boolean spend(Player p, String id, long amount, String reason) {
        if (p == null || amount <= 0) return amount == 0;
        String c = norm(id);
        try {
            if (c.equals("mote")) {
                if (!Sky.has(Features.Rift.class)) return false;
                Features.Rift rift = Sky.service(Features.Rift.class);
                return rift.motes(p) >= amount && rift.spendMotes(p, amount, reason);
            }
            Generic g = generic();
            return g != null && g.balance(p, c) >= amount && g.spend(p, c, amount, reason);
        } catch (Throwable t) {
            return false;
        }
    }

    /** What a pelt / unsupported line says instead of "Click to trade!". */
    static String unsupportedHint(String id) {
        String c = norm(id);
        if (c.equals("pelt")) return "&7Pelts are spent with the trapper's own merchants in the Mushroom Desert.";
        if (c.equals("copper")) return "&7Copper is spent at your Garden's copper market.";
        if (c.equals("mote")) return "&7Motes are spent inside the Rift.";
        return null;
    }

    private static String norm(String id) { return id == null ? "" : id.toLowerCase(Locale.ROOT); }

    // ---- the generic service, found by name -----------------------------------------------------------

    private static final class Generic {
        final Class<?> type;
        final Method balance;
        final Method spend;
        /** Optional {@code boolean supports(String)}; without it every currency is asked about by balance. */
        final Method supports;
        Generic(Class<?> type, Method balance, Method spend, Method supports) {
            this.type = type; this.balance = balance; this.spend = spend; this.supports = supports;
        }

        boolean canSpend() {
            try { return Sky.has(type); } catch (Throwable t) { return false; }
        }

        boolean supports(String c) {
            if (!canSpend()) return false;
            try {
                if (supports != null) return Boolean.TRUE.equals(supports.invoke(Sky.service(type), c));
                return true;
            } catch (Throwable t) { return false; }
        }

        long balance(Player p, String c) throws Exception {
            if (!canSpend()) return -1;
            Object v = balance.invoke(Sky.service(type), p, c);
            return v instanceof Number n ? n.longValue() : -1;
        }

        boolean spend(Player p, String c, long n, String reason) throws Exception {
            if (!canSpend()) return false;
            Object v = spend.invoke(Sky.service(type), p, c, n, reason);
            return Boolean.TRUE.equals(v);
        }
    }

    private static Generic generic;
    private static boolean looked;

    /** {@code Features.Currencies} if core has added it (with the requested shape), else null. Looked up once. */
    private static Generic generic() {
        if (looked) return generic;
        looked = true;
        try {
            Class<?> c = Class.forName("com.skyclone.api.feature.Features$Currencies");
            Method b = c.getMethod("balance", Player.class, String.class);
            Method s = c.getMethod("spend", Player.class, String.class, long.class, String.class);
            Method sup = null;
            try { sup = c.getMethod("supports", String.class); } catch (NoSuchMethodException ignored) { }
            if ((b.getReturnType() == long.class || b.getReturnType() == Long.class)
                    && (s.getReturnType() == boolean.class || s.getReturnType() == Boolean.class)) {
                generic = new Generic(c, b, s, sup);
            } else {
                Sky.log().warning("[shops] Features.Currencies has an unexpected shape - pelts stay unspendable");
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            generic = null;
        } catch (Throwable t) {
            Sky.log().warning("[shops] Features.Currencies lookup failed: " + t);
            generic = null;
        }
        return generic;
    }

    /** For /sb shops info: how each currency is paid right now. */
    static String describe(String id) {
        return switch (kind(id)) {
            case ITEM -> "item " + Shops.currencyItem(id);
            case SERVICE -> norm(id).equals("mote") ? "Features.Rift" : "Features.Currencies";
            case NONE -> "NOT spendable (no owner API)";
        };
    }
}
