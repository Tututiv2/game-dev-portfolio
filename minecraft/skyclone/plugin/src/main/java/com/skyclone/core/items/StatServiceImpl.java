package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.stat.StatProvider;
import com.skyclone.api.stat.StatService;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player stats from providers. Built-in providers, registered first:
 * <ul>
 *   <li>{@code armor} — the sheet of each worn sky armour piece the wearer meets the requirements of;</li>
 *   <li>{@code held} — the main-hand item's sheet when it is a weapon / tool / rod / wand (never a
 *       material, an armour piece or an accessory held in hand);</li>
 *   <li>{@code equipment} — {@code Features.Equipment.worn(p)} sheets (null-safe).</li>
 * </ul>
 * {@code base} is not a provider: {@link Stat#base} is applied by {@link StatMap#total}; it shows
 * up in {@link #breakdown} as the "base" row.
 *
 * <p>Everything is recomputed every {@code stats.recompute-ticks} (10) ticks and the tick after an
 * {@link #invalidate}. A provider that throws is skipped for that pass and logged at most every
 * five minutes. Side effects after each compute: walk speed, swing range, no attack cooldown.
 */
public final class StatServiceImpl implements StatService {

    private final ItemServiceImpl items;
    private final Map<String, StatProvider> providers = new LinkedHashMap<>();
    private volatile List<Map.Entry<String, StatProvider>> providerSnapshot = List.of();
    private final Map<UUID, StatMap> totals = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, StatMap>> perProvider = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, Long> errorLog = new HashMap<>();
    private VitalsServiceImpl vitals;
    private int tickCounter;

    public StatServiceImpl(ItemServiceImpl items) {
        this.items = items;
        addProvider("armor", this::armor);
        addProvider("held", this::held);
        addProvider("equipment", this::equipment);
    }

    void attachVitals(VitalsServiceImpl v) { this.vitals = v; }

    // ---- API -------------------------------------------------------------------------------

    @Override
    public synchronized void addProvider(String id, StatProvider provider) {
        if (id == null || provider == null) return;
        providers.put(id, provider);
        providerSnapshot = List.copyOf(providers.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
        dirty.addAll(onlineIds());
    }

    @Override
    public synchronized void removeProvider(String id) {
        if (providers.remove(id) != null) {
            providerSnapshot = List.copyOf(providers.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
            dirty.addAll(onlineIds());
        }
    }

    @Override
    public double get(Player p, Stat stat) {
        if (p == null || stat == null) return 0;
        StatMap m = totals.get(p.getUniqueId());
        if (m == null) m = compute(p);
        return m.total(stat);
    }

    @Override
    public StatMap snapshot(Player p) {
        StatMap m = totals.get(p.getUniqueId());
        if (m == null) m = compute(p);
        return new StatMap().addAll(m);
    }

    @Override
    public Map<String, Double> breakdown(Player p, Stat stat) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (p == null || stat == null) return out;
        if (!perProvider.containsKey(p.getUniqueId())) compute(p);
        if (stat.base != 0) out.put("base", stat.base);
        Map<String, StatMap> pp = perProvider.getOrDefault(p.getUniqueId(), Map.of());
        for (Map.Entry<String, StatMap> e : pp.entrySet()) {
            double f = e.getValue().flat(stat);
            if (f != 0) out.put(e.getKey(), f);
            double pc = e.getValue().percent(stat);
            if (pc != 0) out.put(e.getKey() + " (%)", pc);
            double mu = e.getValue().multiplier(stat);
            if (mu != 1.0) out.put(e.getKey() + " (x)", mu);
            Double set = e.getValue().override(stat);
            if (set != null) out.put(e.getKey() + " (=)", set);   // replaces the final value (StatMap.set)
        }
        return out;
    }

    @Override
    public void invalidate(Player p) {
        if (p != null) dirty.add(p.getUniqueId());
    }

    // ---- engine ----------------------------------------------------------------------------

    /** Every tick from ItemsCore. */
    void tick() {
        tickCounter++;
        int every = Math.max(1, ItemsDials.integer(ItemsDials.STAT_TICKS, 10));
        if (tickCounter % every == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) recompute(p);
            dirty.clear();
            return;
        }
        if (dirty.isEmpty()) return;
        UUID[] ids;
        synchronized (dirty) { ids = dirty.toArray(new UUID[0]); dirty.clear(); }
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) recompute(p);
        }
    }

    void recompute(Player p) {
        compute(p);
        applySideEffects(p);
        if (vitals != null) vitals.onStatsChanged(p);
    }

    void forget(Player p) {
        totals.remove(p.getUniqueId());
        perProvider.remove(p.getUniqueId());
        dirty.remove(p.getUniqueId());
    }

    StatMap compute(Player p) {
        StatMap total = new StatMap();
        Map<String, StatMap> pp = new LinkedHashMap<>();
        for (Map.Entry<String, StatProvider> e : providerSnapshot) {
            StatMap part = new StatMap();
            try {
                e.getValue().contribute(p, part);
            } catch (Throwable t) {
                logError(e.getKey(), t);
                continue;
            }
            if (!part.isEmpty()) {
                pp.put(e.getKey(), part);
                total.addAll(part);
            }
        }
        totals.put(p.getUniqueId(), total);
        perProvider.put(p.getUniqueId(), pp);
        return total;
    }

    private void logError(String provider, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = errorLog.get(provider);
        if (last != null && now - last < 300_000) return;
        errorLog.put(provider, now);
        items.plugin().getLogger().warning("[stats] provider '" + provider + "' threw " + t + " (skipped; repeats hidden for 5 min)");
    }

    private static List<UUID> onlineIds() {
        List<UUID> l = new ArrayList<>();
        try { for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getUniqueId()); } catch (RuntimeException ignored) {}
        return l;
    }

    // ---- built-in providers ---------------------------------------------------------------------

    private void addSheet(ItemStatSheet sheet, StatMap into) {
        for (Map.Entry<Stat, java.util.EnumMap<ItemStatSheet.Source, Double>> e : sheet.view().entrySet()) {
            double t = 0;
            for (double v : e.getValue().values()) t += v;
            into.add(e.getKey(), t);
        }
    }

    private void armor(Player p, StatMap into) {
        for (ItemStack s : p.getInventory().getArmorContents()) {
            ItemDef d = items.def(items.idOf(s));
            // only real armour counts: a talisman (a player head) worn as a hat grants nothing
            if (d == null || d.category == null || !d.category.isArmor()) continue;
            if (!items.meetsRequirements(p, s, false)) continue;
            addSheet(items.sheet(s), into);
        }
    }

    static boolean heldCounts(ItemCategory c) {
        return c != null && (c.isWeapon() || c.isTool() || c == ItemCategory.FISHING_ROD || c == ItemCategory.WAND
                || c == ItemCategory.FISHING_WEAPON);
    }

    private void held(Player p, StatMap into) {
        ItemStack s = p.getInventory().getItemInMainHand();
        ItemDef d = items.def(items.idOf(s));
        if (d == null || !heldCounts(d.category)) return;
        if (!items.meetsRequirements(p, s, false)) return;
        addSheet(items.sheet(s), into);
    }

    private void equipment(Player p, StatMap into) {
        List<ItemStack> worn;
        try {
            worn = Sky.service(Features.Equipment.class).worn(p);
        } catch (RuntimeException e) {
            return;
        }
        if (worn == null) return;
        for (ItemStack s : worn) {
            if (s == null || items.idOf(s) == null) continue;
            if (!items.meetsRequirements(p, s, false)) continue;
            addSheet(items.sheet(s), into);
        }
    }

    // ---- side effects ---------------------------------------------------------------------------

    void applySideEffects(Player p) {
        if (!ItemsDials.flag(ItemsDials.APPLY_ATTRIBUTES, true)) return;
        StatMap m = totals.get(p.getUniqueId());
        if (m == null) return;
        double speed = Math.max(0, Math.min(ItemsDials.num(ItemsDials.SPEED_CAP, 400), m.total(Stat.SPEED)));
        // The PLAYER walk speed is 0.1; Attribute#getDefaultValue() is the registry default (0.7),
        // which made every player ~7x vanilla speed (found by TestPilot, 2026-10-02).
        setBase(p, Attribute.MOVEMENT_SPEED, PLAYER_WALK_SPEED * speed / 100.0);
        double reach = Math.max(1, Math.min(ItemsDials.num(ItemsDials.SWING_RANGE_CAP, 15), m.total(Stat.SWING_RANGE)));
        setBase(p, Attribute.ENTITY_INTERACTION_RANGE, reach);
        setBase(p, Attribute.ATTACK_SPEED, ItemsDials.num(ItemsDials.ATTACK_SPEED_BASE, 1024));
    }

    /** Vanilla player base movement speed (blocks/tick factor) at 100 Speed. */
    static final double PLAYER_WALK_SPEED = 0.1;

    static void setBase(Player p, Attribute a, double v) {
        try {
            AttributeInstance ai = p.getAttribute(a);
            if (ai != null && Math.abs(ai.getBaseValue() - v) > 1e-9) ai.setBaseValue(v);
        } catch (RuntimeException ignored) {}
    }

    static double defaultOf(Player p, Attribute a, double fallback) {
        try {
            AttributeInstance ai = p.getAttribute(a);
            return ai == null ? fallback : ai.getDefaultValue();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Put the vanilla attributes back (plugin disable). */
    void resetAttributes(Player p) {
        for (Attribute a : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.ENTITY_INTERACTION_RANGE, Attribute.ATTACK_SPEED}) {
            setBase(p, a, a == Attribute.MOVEMENT_SPEED ? PLAYER_WALK_SPEED : a == Attribute.ATTACK_SPEED ? 4.0 : 3.0);
        }
    }

    List<String> providerIds() {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, StatProvider> e : providerSnapshot) l.add(e.getKey());
        return l;
    }
}
