package com.skyclone.core.items;

import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Item abilities. Detection (right/left click, sneak) is done by {@link AbilityListener} on the
 * HELD item; this class does requirements, cooldown, mana, the handler call, the charge and the
 * action-bar flash, in the genre's exact wording:
 * <pre>
 *   &cThis ability is on cooldown for 3s.
 *   &cYou do not have enough mana!            (+ fail sound, &c&lNOT ENOUGH MANA on the bar)
 *   &b-50 Mana (&6Instant Transmission&b)     (action bar, on success with a cost)
 * </pre>
 * An ability id with no registered handler does nothing at all (logged once per id).
 */
public final class AbilityServiceImpl implements AbilityService {

    private final ItemServiceImpl items;
    private final VitalsServiceImpl vitals;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final List<SetTick> setTicks = new CopyOnWriteArrayList<>();
    private final Set<String> missingLogged = ConcurrentHashMap.newKeySet();
    /** Last "not enough mana" chat line per player (3 s throttle; the action-bar flash is not throttled). */
    private final Map<UUID, Long> noManaMsgAt = new ConcurrentHashMap<>();
    private int ticks;

    private record SetTick(String setId, int minPieces, Consumer<Player> tick) {}

    public AbilityServiceImpl(ItemServiceImpl items, VitalsServiceImpl vitals) {
        this.items = items;
        this.vitals = vitals;
    }

    @Override
    public void register(String abilityId, Handler handler) {
        if (abilityId == null || handler == null) return;
        if (handlers.put(abilityId, handler) != null)
            items.plugin().getLogger().warning("[abilities] handler for '" + abilityId + "' registered twice; the later one wins");
    }

    public boolean hasHandler(String abilityId) { return abilityId != null && handlers.containsKey(abilityId); }

    @Override
    public double cooldownLeft(Player p, String abilityId) {
        if (p == null || abilityId == null) return 0;
        Map<String, Long> m = cooldowns.get(p.getUniqueId());
        if (m == null) return 0;
        Long until = m.get(abilityId);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) { m.remove(abilityId); return 0; }
        return left / 1000.0;
    }

    @Override
    public void setCooldown(Player p, String abilityId, double seconds) {
        if (p == null || abilityId == null) return;
        Map<String, Long> m = cooldowns.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        if (seconds <= 0) m.remove(abilityId);
        else m.put(abilityId, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    @Override
    public void registerSetTick(String setId, int minPieces, Consumer<Player> tick) {
        if (setId == null || tick == null) return;
        setTicks.add(new SetTick(setId, Math.max(1, Math.min(4, minPieces)), tick));
    }

    void forget(Player p) { cooldowns.remove(p.getUniqueId()); consumedAt.remove(p.getUniqueId()); }

    // ---- wave 5: consumed clicks ---------------------------------------------------------------

    private final java.util.Map<java.util.UUID, Integer> consumedAt = new java.util.HashMap<>();

    @Override
    public void consumeClick(Player p) {
        if (p != null) consumedAt.put(p.getUniqueId(), org.bukkit.Bukkit.getCurrentTick());
    }

    /** True when a module consumed this player's click in the current tick. */
    boolean clickConsumed(Player p) {
        Integer t = consumedAt.get(p.getUniqueId());
        return t != null && t == org.bukkit.Bukkit.getCurrentTick();
    }

    /** Mana Disintegrator: -1% ability mana cost per one applied, at most 10 (research 01 section 19). */
    static double disintegrated(double cost, int applied) {
        int n = Math.max(0, Math.min(10, applied));
        return cost * (1 - n / 100.0);
    }

    // ---- triggering ----------------------------------------------------------------------------

    /** The ability on this def for a trigger; SNEAK_RIGHT_CLICK falls back to RIGHT_CLICK. */
    static AbilityDef find(ItemDef def, AbilityDef.Trigger t) {
        if (def == null || def.abilities == null || def.abilities.isEmpty()) return null;
        for (AbilityDef a : def.abilities) if (a != null && a.trigger == t) return a;
        if (t == AbilityDef.Trigger.SNEAK_RIGHT_CLICK) {
            for (AbilityDef a : def.abilities) if (a != null && a.trigger == AbilityDef.Trigger.RIGHT_CLICK) return a;
        }
        return null;
    }

    /**
     * Try the held item's ability for a trigger. Returns true when an ability MATCHED (whether or
     * not it fired), so the caller can stop the vanilla use of the item.
     */
    private final java.util.Map<String, CostModifier> costModifiers = new java.util.LinkedHashMap<>();

    @Override
    public void addCostModifier(String id, CostModifier modifier) {
        if (id == null || modifier == null) return;
        costModifiers.put(id, modifier);
    }

    boolean trigger(Player p, ItemStack item, AbilityDef.Trigger t) {
        if (!ItemsDials.flag(ItemsDials.ABILITIES_ENABLED, true)) return false;
        String id = items.idOf(item);
        ItemDef def = items.def(id);
        AbilityDef a = find(def, t);
        if (a == null) return false;
        Handler h = handlers.get(a.id);
        if (h == null) {
            if (missingLogged.add(a.id))
                items.plugin().getLogger().warning("[abilities] no handler registered for ability '" + a.id + "' on " + def.id + " (does nothing)");
            return false;
        }
        if (!items.meetsRequirements(p, item, true)) return true;
        double cd = cooldownLeft(p, a.id);
        if (cd > 0) {
            Msg.send(p, "&cThis ability is on cooldown for " + (long) Math.ceil(cd) + "s.");
            return true;
        }
        double cost = VitalsMath.manaCost(a.mana, vitals.maxMana(p));
        if (cost > 0) {
            // reforges request 11 (wave 5): ItemData.extra.mana_disintegrator_count, the reference's own key
            try {
                com.google.gson.JsonObject ex = items.data(item).extra;
                if (ex != null && ex.has("mana_disintegrator_count"))
                    cost = disintegrated(cost, ex.get("mana_disintegrator_count").getAsInt());
            } catch (RuntimeException ignored) { }
        }
        if (!costModifiers.isEmpty()) {
            Use probe = new Use(p, item, def, a);
            for (var e : costModifiers.entrySet()) {
                try { cost = Math.max(0, e.getValue().modify(probe, cost)); }
                catch (Throwable ex) { items.warnThrottled("costmod:" + e.getKey(), "ability cost modifier '" + e.getKey() + "' threw " + ex); }
            }
        }
        if (cost > 0 && vitals.mana(p) + 1e-9 < cost) {
            // wave-4 endgame playtest: one chat line per click flooded chat while an ability was spammed; the
            // action-bar flash and the sound still answer every click.
            long nowMs = System.currentTimeMillis();
            Long last = noManaMsgAt.get(p.getUniqueId());
            if (last == null || nowMs - last >= 3000) { noManaMsgAt.put(p.getUniqueId(), nowMs); Msg.send(p, "&cYou do not have enough mana!"); }
            Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f);
            vitals.flash(p, "&c&lNOT ENOUGH MANA", 30);
            return true;
        }
        boolean ok;
        try {
            ok = h.use(new Use(p, item, def, a));
        } catch (Throwable ex) {
            items.warnThrottled("ability:" + a.id, "ability '" + a.id + "' threw " + ex);
            return true;
        }
        if (!ok) return true;
        if (cost > 0) vitals.useMana(p, cost);
        if (a.cooldown > 0) setCooldown(p, a.id, a.cooldown);
        if (cost > 0) {
            vitals.flash(p, "&b-" + Text.commasInt(Math.round(cost)) + " Mana (&6" + a.name + "&b)",
                    ItemsDials.integer(ItemsDials.ABILITY_FLASH_TICKS, 40));
        }
        try {
            Bukkit.getPluginManager().callEvent(new SkyEvents.AbilityUsed(p, item, a));
        } catch (RuntimeException ex) {
            items.warnThrottled("ability-event", "AbilityUsed listener threw " + ex);
        }
        return true;
    }

    /** Every tick from ItemsCore; set ticks every 20. */
    void tick() {
        if (++ticks % 20 != 0 || setTicks.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<String, Integer> pieces = null;
            for (SetTick st : setTicks) {
                if (pieces == null) pieces = pieceCounts(p);
                if (pieces.getOrDefault(st.setId(), 0) < st.minPieces()) continue;
                try {
                    st.tick().accept(p);
                } catch (Throwable ex) {
                    items.warnThrottled("settick:" + st.setId(), "set tick for '" + st.setId() + "' threw " + ex);
                }
            }
        }
    }

    private Map<String, Integer> pieceCounts(Player p) {
        Map<String, Integer> m = new java.util.HashMap<>();
        for (ItemStack s : p.getInventory().getArmorContents()) {
            ItemDef d = items.def(items.idOf(s));
            if (d != null && d.setId != null && !d.setId.isEmpty()) m.merge(d.setId, 1, Integer::sum);
        }
        return m;
    }

    /** Every non-passive ability id used by some item that has no handler. */
    List<String> missingHandlers() {
        List<String> out = new ArrayList<>();
        for (ItemDef d : items.all()) {
            if (d.abilities == null) continue;
            for (AbilityDef a : d.abilities) {
                if (a == null || a.trigger == null) continue;
                switch (a.trigger) {
                    case PASSIVE, ON_HIT, ON_KILL -> { continue; }
                    default -> {}
                }
                if (!handlers.containsKey(a.id) && !out.contains(a.id)) out.add(a.id);
            }
        }
        return out;
    }
}
