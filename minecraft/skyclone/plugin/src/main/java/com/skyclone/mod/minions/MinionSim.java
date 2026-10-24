package com.skyclone.mod.minions;

import com.skyclone.mod.minions.MinionCatalog.Drop;
import com.skyclone.mod.minions.MinionCatalog.FuelDef;
import com.skyclone.mod.minions.MinionCatalog.Rule;
import com.skyclone.mod.minions.MinionCatalog.Specialty;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Placed;
import com.skyclone.mod.minions.MinionStore.Slot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The production model, pure Java so it is unit tested against the shipped data.
 *
 * <p>Research 02 section 4.1: a minion alternates "place" and "harvest" actions, so it yields one harvest
 * every {@code 2 x TBA}; speed boosts ADD and divide the TBA ({@code TBA / (1 + sum%)}); multiplier fuels do
 * not change the TBA, they multiply each drop. Production is computed from elapsed real time, so the same
 * code runs a 5-second live tick and a 3-day offline catch-up (section 4.7), stopping when storage is full
 * unless a shipping item sells the overflow.
 *
 * <p>Fractional drops (Rabbit's Foot x0.35, Diamond Spreading 1 per 10) accumulate in {@code Placed.frac},
 * so the long-run average is exact and the result is deterministic (testable) rather than random.
 */
public final class MinionSim {

    private MinionSim() {}

    /** What the world around the minion contributes; the Bukkit side implements it. */
    public interface Env {
        /** Speed bonus in percent from everything except fuel (upgrades, infusion, crystals, pets, mayor...). */
        double speedBonus(Placed m, TypeDef t);
        /** Output multiplier from everything except fuel (mayor perk, Soulstream Engine...). */
        double outputMult(Placed m, TypeDef t);
        /** Is a Solar Panel powered right now (daytime, island live)? */
        boolean solarActive();
        int maxStack(String itemId);
        double npcPrice(String itemId);
        /** Is the item registered? Unknown items are never put in storage (counted as lost). */
        boolean known(String itemId);
    }

    public static final class Result {
        /** Items produced this step (after smelting, before compaction), by id. */
        public final Map<String, Long> produced = new LinkedHashMap<>();
        public long harvests;
        public double coins;
        public long lost;
        public boolean full;
        public double seconds;
        public final List<String> unknown = new ArrayList<>();

        public long total() {
            long n = 0;
            for (long v : produced.values()) n += v;
            return n;
        }
    }

    // ---- the step --------------------------------------------------------------------------------

    /**
     * Advance one minion to {@code now}. {@code paused} = the island is live and the work area is blocked
     * ("This location isn't perfect"): time passes and fuel burns, nothing is made.
     */
    public static Result advance(MinionCatalog cat, Placed m, long now, Env env, boolean paused) {
        Result r = new Result();
        if (m.lastUpdate <= 0) { m.lastUpdate = now; return r; }
        long dtMs = now - m.lastUpdate;
        if (dtMs <= 0) return r;
        m.lastUpdate = now;
        // A minion sitting on a Minion Chair does not work (research 4.3): no harvests, no fuel burnt, no timers.
        if (m.seated) return r;
        double dt = dtMs / 1000.0;
        r.seconds = dt;
        TypeDef t = cat.type(m.type);
        TierDef td = t == null ? null : t.tier(m.tier);
        if (td == null) return r;

        FuelDef f = activeFuel(cat, m, t);
        double fuelActive = burnFuel(cat, m, dt, env.solarActive());
        if (f == null) fuelActive = 0;
        if (paused) { r.full = false; return r; }

        int cap = capacity(cat, m);
        boolean sells = shippingRate(cat, m) > 0;
        if (!sells && isFull(cat, m, t, cap, env)) {
            m.progress = 0;
            r.full = true;
            return r;
        }

        double base = env.speedBonus(m, t);
        double fs = 0, fm = 1;
        if (f != null) {
            fs = f.speed + f.categoryBonus.getOrDefault(t.category, 0.0);
            fm = f.mult <= 0 ? 1 : f.mult;
        }
        double perHarvest = td.tba * Math.max(1, t.actionsPerHarvest);
        double u1 = fuelActive * (1 + (base + fs) / 100.0) / perHarvest;
        double u2 = Math.max(0, dt - fuelActive) * (1 + base / 100.0) / perHarvest;
        double total = m.progress + u1 + u2;
        long h = (long) Math.floor(total);
        m.progress = total - h;
        // A timed output (Soulflow Engine: one per N seconds) accrues with TIME, not with harvests: a 5 s live
        // tick usually completes no harvest, and returning here dropped ~80% of the engine's output.
        boolean timed = f != null && f.perSecond != null && f.perSecond.every > 0 && fuelActive > 0;
        for (UpgradeDef u : installed(cat, m)) if (u.appliesTo(t) && u.perSecond != null && u.perSecond.every > 0) timed = true;
        if (h <= 0 && !timed) return r;
        r.harvests = h;
        double share1 = (u1 + u2) > 0 ? u1 / (u1 + u2) : 0;
        double h1 = h * share1, h0 = h - h1;
        double out = env.outputMult(m, t);

        Map<String, Double> amounts = new LinkedHashMap<>();
        Specialty sp = f != null ? f.specialty : null;
        double regular = h0 + h1 * fm * (sp != null ? (1 - sp.replace) : 1);
        for (Drop d : effectiveDrops(cat, m, t)) add(amounts, d.item, d.avg * out * regular);
        if (sp != null) add(amounts, sp.item, sp.avg * out * h1 * fm * sp.replace);
        if (f != null) for (Drop d : f.extras) add(amounts, d.item, d.avg * h1);
        double raw = 0;
        for (double v : amounts.values()) raw += v;
        for (UpgradeDef u : installed(cat, m)) {
            if (!u.appliesTo(t)) continue;
            if (u.spread != null) add(amounts, u.spread.item, raw * u.spread.perItem);
            if (u.perSecond != null && u.perSecond.every > 0) add(amounts, u.perSecond.item, dt / u.perSecond.every);
        }
        // a fuel's timed output (Harvest Fair Participation Medal) accrues only while that fuel is really burning
        if (f != null && f.perSecond != null && f.perSecond.every > 0) add(amounts, f.perSecond.item, fuelActive / f.perSecond.every);

        boolean smelt = hasSmelter(cat, m);
        Map<String, Long> whole = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : amounts.entrySet()) {
            double v = m.frac.getOrDefault(e.getKey(), 0.0) + e.getValue();
            long w = (long) Math.floor(v + 1e-9);
            double rest = v - w;
            if (rest < 1e-9) m.frac.remove(e.getKey()); else m.frac.put(e.getKey(), rest);
            if (w <= 0) continue;
            String id = smelt ? cat.smelt.getOrDefault(e.getKey(), e.getKey()) : e.getKey();
            if (!env.known(id)) { r.lost += w; r.unknown.add(id); continue; }
            whole.merge(id, w, Long::sum);
        }
        r.produced.putAll(whole);
        deliver(cat, m, whole, cap, env, r);
        return r;
    }

    /** Put items into storage (compacting as it goes); what cannot fit is sold by the hopper or lost. */
    public static void deliver(MinionCatalog cat, Placed m, Map<String, Long> items, int cap, Env env, Result r) {
        String mode = compactMode(cat, m);
        Map<String, Long> rem = new LinkedHashMap<>(items);
        long stored = 0;
        while (!rem.isEmpty()) {
            boolean any = false;
            for (Iterator<Map.Entry<String, Long>> it = rem.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                int chunk = (int) Math.min(e.getValue(), 64);
                int added = addToStorage(m, e.getKey(), chunk, cap, env);
                if (added > 0) { any = true; stored += added; }
                long left = e.getValue() - added;
                if (left <= 0) it.remove(); else e.setValue(left);
            }
            if (mode != null && compact(cat, m, cap, env, mode)) any = true;
            if (!any) break;
        }
        double rate = shippingRate(cat, m);
        for (Map.Entry<String, Long> e : rem.entrySet()) {
            if (rate > 0) r.coins += e.getValue() * env.npcPrice(e.getKey()) * rate;
            else { r.lost += e.getValue(); r.full = true; }
        }
        if (rate > 0 && !rem.isEmpty()) r.full = true;
        long made = 0;
        for (long v : items.values()) made += v;
        m.generated += made;
        m.heldCoins += r.coins;
    }

    // ---- storage --------------------------------------------------------------------------------

    /** Storage slots: the tier's own plus the attached storage chest. */
    public static int capacity(MinionCatalog cat, Placed m) {
        TypeDef t = cat.type(m.type);
        TierDef td = t == null ? null : t.tier(m.tier);
        int own = td == null ? 1 : td.slots();
        return own + chestSlots(cat, m);
    }

    public static int ownSlots(MinionCatalog cat, Placed m) {
        TypeDef t = cat.type(m.type);
        TierDef td = t == null ? null : t.tier(m.tier);
        return td == null ? 1 : td.slots();
    }

    public static int chestSlots(MinionCatalog cat, Placed m) {
        UpgradeDef c = cat.upgrade(m.chest);
        return c != null && "chest".equals(c.slot) ? c.slots : 0;
    }

    public static int addToStorage(Placed m, String id, int n, int cap, Env env) {
        if (n <= 0) return 0;
        int max = Math.max(1, env.maxStack(id));
        int added = 0;
        for (Slot s : m.storage) {
            if (added >= n) break;
            if (!s.id.equals(id) || s.amount >= max) continue;
            int put = Math.min(n - added, max - s.amount);
            s.amount += put;
            added += put;
        }
        while (added < n && m.storage.size() < cap) {
            int put = Math.min(n - added, max);
            m.storage.add(new Slot(id, put));
            added += put;
        }
        return added;
    }

    /** Remove up to n of an item (from the last slots first); returns how many were removed. */
    public static int removeFromStorage(Placed m, String id, int n) {
        int removed = 0;
        for (int i = m.storage.size() - 1; i >= 0 && removed < n; i--) {
            Slot s = m.storage.get(i);
            if (!s.id.equals(id)) continue;
            int take = Math.min(s.amount, n - removed);
            s.amount -= take;
            removed += take;
            if (s.amount <= 0) m.storage.remove(i);
        }
        return removed;
    }

    public static int spaceFor(Placed m, String id, int cap, Env env) {
        int max = Math.max(1, env.maxStack(id));
        int space = 0;
        for (Slot s : m.storage) if (s.id.equals(id)) space += Math.max(0, max - s.amount);
        space += Math.max(0, cap - m.storage.size()) * max;
        return space;
    }

    /** Full = nothing the minion makes would fit (a hopper turns full into "selling"). */
    public static boolean isFull(MinionCatalog cat, Placed m, TypeDef t, int cap, Env env) {
        if (m.storage.size() < cap) return false;
        boolean smelt = hasSmelter(cat, m);
        for (Drop d : effectiveDrops(cat, m, t)) {
            String id = smelt ? cat.smelt.getOrDefault(d.item, d.item) : d.item;
            if (spaceFor(m, id, cap, env) > 0) return false;
        }
        return true;
    }

    /** Compact everything compactable; true if anything changed. */
    public static boolean compact(MinionCatalog cat, Placed m, int cap, Env env, String mode) {
        boolean block = mode.contains("block");
        boolean ench = mode.contains("enchanted");
        boolean changed = false;
        boolean again = true;
        int guard = 0;
        while (again && guard++ < 64) {
            again = false;
            List<String> ids = new ArrayList<>();
            for (Slot s : m.storage) if (!ids.contains(s.id)) ids.add(s.id);
            for (String id : ids) {
                Rule rule = ench ? cat.enchantedRules.get(id) : null;
                if (rule == null && block) rule = cat.blockRules.get(id);
                if (rule == null && ench) {
                    // An Ultra Compactor also takes the block step when only the block form compresses further
                    // (Snowball -> Snow Block -> Enchanted Snow Block).
                    Rule b = cat.blockRules.get(id);
                    if (b != null && cat.enchantedRules.containsKey(b.to)) rule = b;
                }
                if (rule == null || rule.amount <= 0 || !env.known(rule.to)) continue;
                int count = Math.max(1, rule.count);
                int k = m.count(id) / rule.amount;
                // Compact k units; if the result does not fit, put storage back EXACTLY as it was and try fewer.
                // (The old "re-add what did not fit" could itself not fit, and the difference vanished.)
                while (k > 0) {
                    List<Slot> snapshot = copy(m.storage);
                    removeFromStorage(m, id, k * rule.amount);
                    int want = k * count;
                    int got = addToStorage(m, rule.to, want, cap, env);
                    if (got == want) break;
                    m.storage.clear();
                    m.storage.addAll(snapshot);
                    k = Math.min(k - 1, got / count);
                }
                if (k > 0) { changed = true; again = true; }
            }
        }
        return changed;
    }

    // ---- what the minion is equipped with --------------------------------------------------------

    public static List<UpgradeDef> installed(MinionCatalog cat, Placed m) {
        List<UpgradeDef> l = new ArrayList<>(2);
        UpgradeDef a = cat.upgrade(m.upgrade1), b = cat.upgrade(m.upgrade2);
        if (a != null) l.add(a);
        if (b != null) l.add(b);
        return l;
    }

    public static List<Drop> effectiveDrops(MinionCatalog cat, Placed m, TypeDef t) {
        List<Drop> drops = new ArrayList<>(t.drops);
        for (UpgradeDef u : installed(cat, m)) {
            if (!u.appliesTo(t)) continue;
            if (u.setDrops != null && !u.setDrops.isEmpty()) drops = new ArrayList<>(u.setDrops);
        }
        for (UpgradeDef u : installed(cat, m)) {
            if (!u.appliesTo(t)) continue;
            if (u.addDrops != null) drops.addAll(u.addDrops);
        }
        return drops;
    }

    public static boolean hasSmelter(MinionCatalog cat, Placed m) {
        for (UpgradeDef u : installed(cat, m)) if (Boolean.TRUE.equals(u.smelt)) return true;
        return false;
    }

    /** "block", "enchanted", "block+enchanted" or null. */
    public static String compactMode(MinionCatalog cat, Placed m) {
        boolean b = false, e = false;
        for (UpgradeDef u : installed(cat, m)) {
            if ("block".equals(u.compact)) b = true;
            if ("enchanted".equals(u.compact)) e = true;
        }
        if (!b && !e) return null;
        return b && e ? "block+enchanted" : b ? "block" : "enchanted";
    }

    public static double shippingRate(MinionCatalog cat, Placed m) {
        UpgradeDef s = cat.upgrade(m.shipping);
        return s != null && "shipping".equals(s.slot) ? s.sell : 0;
    }

    /** The fuel if it powers this type (Inferno fuels only the Inferno Minion, the medal only farming ones), else null. */
    public static FuelDef activeFuel(MinionCatalog cat, Placed m, TypeDef t) {
        FuelDef f = cat.fuel(m.fuel);
        if (f == null || t == null) return null;
        return f.powers(t) ? f : null;
    }

    /**
     * Wave 5: the pets module's ability key whose value is "minions of this kind work X% faster on your island"
     * (docs/handoff/pets.md request 10), read with {@code Features.Pets.abilityValue(p, key, "a")}: the value
     * follows rarity and level exactly as the pet's tooltip says (Legendary Rabbit 30% at L100, Common Pigman 10%)
     * and is 0 when the summoned pet lacks the ability. A type can match two (a Pig minion is a farming minion:
     * the Rabbit's farming perk and the Pigman's pig perk both apply; one pet is active at a time, so the best
     * one counts). Mooshroom Cow and Chicken have no minion ability in the pets data, so they add no key.
     */
    public static List<String> petAbilityKeys(TypeDef t) {
        List<String> keys = new ArrayList<>(2);
        if (t == null) return keys;
        switch (t.id) {
            case "PIG" -> keys.add("pigman_bacon_farmer");
            case "SLIME", "MAGMA_CUBE" -> keys.add("magma_cube_slimy_minions");
            case "SPIDER", "CAVESPIDER", "TARANTULA" -> keys.add("spider_spider_whisperer");
            case "RED_SAND" -> keys.add("snail_red_sand_enjoyer");
            default -> { }
        }
        if ("FARMING".equals(t.category)) keys.add("rabbit_efficient_farming");
        if ("FORAGING".equals(t.category)) keys.add("ocelot_tree_hugger");
        return keys;
    }

    /**
     * Free Will's loyalty chance in percent (reference lore: 50% at tier I, +4% per tier above), capped at 100.
     * The base and step are dials ({@code minions.free-will-base}, {@code minions.free-will-per-tier}).
     */
    public static double freeWillChance(int tier, double base, double perTier) {
        return Math.max(0, Math.min(100, base + perTier * Math.max(0, tier - 1)));
    }

    /** Burns {@code dt} seconds of fuel (timed fuels tick in real time, offline too); returns powered seconds. */
    public static double burnFuel(MinionCatalog cat, Placed m, double dt, boolean solarActive) {
        if (m.fuel == null) return 0;
        FuelDef f = cat.fuel(m.fuel);
        if (f == null) { m.fuel = null; m.fuelCount = 0; m.fuelLeftMs = 0; return 0; }
        if (f.infinite()) return f.solar ? (solarActive ? dt : 0) : dt;
        double left = fuelSecondsLeft(cat, m);
        double active = Math.min(dt, left);
        double rest = left - dt;
        if (rest <= 0) {
            m.fuel = null;
            m.fuelCount = 0;
            m.fuelLeftMs = 0;
        } else {
            int count = (int) Math.ceil(rest / f.seconds);
            m.fuelCount = Math.max(1, count);
            m.fuelLeftMs = Math.round((rest - (m.fuelCount - 1) * (double) f.seconds) * 1000.0);
        }
        return active;
    }

    /** Seconds of fuel left in total (infinite fuels: Double.POSITIVE_INFINITY, none: 0). */
    public static double fuelSecondsLeft(MinionCatalog cat, Placed m) {
        FuelDef f = cat.fuel(m.fuel);
        if (f == null) return 0;
        if (f.infinite()) return Double.POSITIVE_INFINITY;
        return m.fuelLeftMs / 1000.0 + Math.max(0, m.fuelCount - 1) * (double) f.seconds;
    }

    // ---- rates (menus, guides, self-tests) ---------------------------------------------------------

    /** Effective seconds between actions with a total speed bonus in percent. */
    public static double effectiveTba(double tba, double speedPct) {
        return tba / (1 + speedPct / 100.0);
    }

    /** Items per hour of each drop for a type/tier at a speed bonus and output multiplier. */
    public static Map<String, Double> perHour(TypeDef t, int tier, double speedPct, double mult) {
        Map<String, Double> out = new LinkedHashMap<>();
        TierDef td = t.tier(tier);
        if (td == null) return out;
        double harvestsPerHour = 3600.0 / (effectiveTba(td.tba, speedPct) * Math.max(1, t.actionsPerHarvest));
        for (Drop d : t.drops) out.merge(d.item, harvestsPerHour * d.avg * mult, Double::sum);
        return out;
    }

    static List<Slot> copy(List<Slot> storage) {
        List<Slot> l = new ArrayList<>(storage.size());
        for (Slot s : storage) l.add(new Slot(s.id, s.amount));
        return l;
    }

    private static void add(Map<String, Double> m, String id, double v) {
        if (v <= 0 || id == null) return;
        m.merge(id, v, Double::sum);
    }
}
