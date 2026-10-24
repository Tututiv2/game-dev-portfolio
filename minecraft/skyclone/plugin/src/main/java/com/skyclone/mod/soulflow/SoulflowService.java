package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link Features.Soulflow}: the pool, the all-or-nothing charge and the grants. Main thread only.
 * Whole units throughout; the player-facing name is Soulstream (⸎).
 */
final class SoulflowService implements Features.Soulflow {

    static final String RAW = "RAW_SOULFLOW";
    static final String KEY = "soulflow";

    private final SoulflowConfig cfg;
    /** Last change shown on the action bar: text + expiry tick. */
    private final Map<UUID, Object[]> delta = new HashMap<>();

    SoulflowService(SoulflowConfig cfg) { this.cfg = cfg; }

    SoulflowConfig config() { return cfg; }

    /** Null until the profile is ready (never write to a throwaway object). */
    SoulflowData data(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, KEY, SoulflowData.class);
    }

    // ---- accessory tier --------------------------------------------------------------------------

    /** The best Soulstream accessory the player carries or has bagged, or null. */
    SoulflowConfig.Tier tier(Player p) {
        if (p == null) return null;
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        List<SoulflowConfig.Tier> ts = cfg.tiers;
        for (int i = ts.size() - 1; i >= 0; i--) {
            SoulflowConfig.Tier t = ts.get(i);
            try {
                if (acc.has(p, t.item)) return t;
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    boolean poolUsable(Player p) {
        return !SoulflowDials.POOL_NEEDS_ACCESSORY.get() || tier(p) != null;
    }

    long raw(Player p) {
        if (!SoulflowDials.INVENTORY_FALLBACK.get()) return 0;
        try { return Math.max(0, Sky.items().count(p, RAW)); } catch (RuntimeException e) { return 0; }
    }

    long pool(Player p) {
        SoulflowData d = data(p);
        return d == null ? 0 : d.pool;
    }

    // ---- Features.Soulflow -----------------------------------------------------------------------

    @Override public long soulflow(Player p) {
        SoulflowData d = data(p);
        if (d == null) return 0;
        boolean usable = poolUsable(p);
        return SoulflowMath.available(d.pool, usable, raw(p), true);
    }

    @Override public boolean consume(Player p, long amount, String reason) {
        if (p == null || amount < 0) return false;
        if (amount == 0) return true;
        if (!SoulflowDials.on()) return true;   // system off: costs are not charged
        SoulflowData d = data(p);
        if (d == null) return false;
        long raw = raw(p);
        SoulflowMath.Plan plan = SoulflowMath.plan(d.pool, poolUsable(p), raw, true, amount);
        if (!plan.ok()) return false;
        if (plan.fromRaw() > 0) {
            int removed = Sky.items().remove(p, RAW, (int) Math.min(Integer.MAX_VALUE, plan.fromRaw()));
            if (removed < plan.fromRaw()) {
                // the inventory changed under us: keep what was taken as internalized, refuse the charge
                if (removed > 0) d.pool = SoulflowMath.addCapped(d.pool, removed, SoulflowDials.POOL_MAX.get());
                return false;
            }
        }
        d.pool -= plan.fromPool();
        d.spent += amount;
        show(p, "&3-" + SoulflowMath.units(amount));
        return true;
    }

    @Override public void add(Player p, long amount, String reason) {
        if (p == null || amount <= 0) return;
        SoulflowData d = data(p);
        if (d == null) return;
        long before = d.pool;
        d.pool = SoulflowMath.addCapped(d.pool, amount, SoulflowDials.POOL_MAX.get());
        d.gained += d.pool - before;
        if (d.pool > before) show(p, "&3+" + SoulflowMath.units(d.pool - before));
    }

    // ---- internalizing -----------------------------------------------------------------------------

    /** Why internalizing is refused right now, or null. */
    String internalizeBlock(Player p) {
        if (!SoulflowDials.on()) return "Soulstream is switched off on this server.";
        if (data(p) == null) return "Your profile is still loading.";
        if (SoulflowDials.INTERNALIZE_NEEDS_ACCESSORY.get() && tier(p) == null)
            return "You need a Soulstream accessory (Pile, Battery or Supercell) to internalize Soulstream!";
        return null;
    }

    /** Internalize up to {@code max} items of one Soulstream item from the inventory; returns the units gained. */
    long internalizeInventory(Player p, String itemId, int max) {
        long per = cfg.unitsOf(itemId);
        if (per <= 0 || max <= 0) return 0;
        SoulflowData d = data(p);
        if (d == null) return 0;
        int have = Sky.items().count(p, itemId);
        int n = Math.min(have, max);
        n = (int) Math.min(n, roomFor(d, per));
        if (n <= 0) return 0;
        int removed = Sky.items().remove(p, itemId, n);
        long units = removed * per;
        add(p, units, "internalize:" + itemId);
        return units;
    }

    /** Same from the sacks (through Features.Sacks). */
    long internalizeSacks(Player p, String itemId, int max) {
        long per = cfg.unitsOf(itemId);
        if (per <= 0 || max <= 0) return 0;
        SoulflowData d = data(p);
        if (d == null) return 0;
        Features.Sacks sacks = Sky.service(Features.Sacks.class);
        int have;
        try { have = sacks.count(p, itemId); } catch (RuntimeException e) { return 0; }
        int n = (int) Math.min(Math.min(have, max), roomFor(d, per));
        if (n <= 0) return 0;
        int removed;
        try { removed = sacks.remove(p, itemId, n); } catch (RuntimeException e) { return 0; }
        long units = (long) Math.max(0, removed) * per;
        add(p, units, "internalize_sack:" + itemId);
        return units;
    }

    /** How many more items of {@code per} units the pool can take before its cap. */
    private static long roomFor(SoulflowData d, long per) {
        int capDial = SoulflowDials.POOL_MAX.get();
        long cap = capDial <= 0 ? SoulflowMath.HARD_CAP : Math.min(capDial, SoulflowMath.HARD_CAP);
        long room = Math.max(0, cap - d.pool);
        return per <= 0 ? 0 : room / per;
    }

    // ---- toggles -----------------------------------------------------------------------------------

    boolean toggle(Player p, String id, boolean def) {
        SoulflowData d = data(p);
        if (d == null) return def;
        Boolean b = d.toggles.get(id);
        return b == null ? def : b;
    }

    void setToggle(Player p, String id, boolean on) {
        SoulflowData d = data(p);
        if (d != null) d.toggles.put(id, on);
    }

    // ---- action bar delta --------------------------------------------------------------------------

    void show(Player p, String text) {
        delta.put(p.getUniqueId(), new Object[]{text, org.bukkit.Bukkit.getCurrentTick() + 40});
    }

    String delta(Player p) {
        Object[] o = delta.get(p.getUniqueId());
        if (o == null) return null;
        if (org.bukkit.Bukkit.getCurrentTick() > (Integer) o[1]) { delta.remove(p.getUniqueId()); return null; }
        return (String) o[0];
    }

    void forget(Player p) { delta.remove(p.getUniqueId()); }
}
