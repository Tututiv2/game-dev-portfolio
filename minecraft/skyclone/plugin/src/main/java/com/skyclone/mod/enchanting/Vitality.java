package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Vitality pool (research 01 section 6.2): core has no Vitality resource yet (core-wave5 "Not done": a larger
 * design), and every enchant that names Vitality needs one, so enchanting runs a module-local pool behind dial
 * {@code enchanting.vitality.pool}:
 * <ul>
 *   <li>max = the {@code VITALITY} stat (base 100), regenerates 5% of max per second (dial);</li>
 *   <li>healing abilities SPEND it (wands 25, Mend Flesh 25, power orbs 50, flares 10, Nasty Bite 10, Grimoire 60;
 *       dial {@code enchanting.vitality.costs}): with too little, the click is swallowed ({@code consumeClick}) and
 *       "Not enough Vitality!" shows, like mana; {@code soft-costs} abilities are charged but never refused;</li>
 *   <li><b>Drain</b> refills +0.5..3 per hit (1 s cooldown), <b>Last Stand</b> refills its second number;</li>
 *   <li>the converters turn what was spent into a 10 s buff: Hardened -> Defense (max 25/piece), Strong ->
 *       Strength (max 30/piece), Vivacious -> Attack Speed (max 5/piece); Vampiric heals per point spent.</li>
 * </ul>
 * The pool is shown on the action bar only while it is not full. If core ever ships a pool, turn the dial off
 * (Drain then falls back to healing % of max health, the pre-wave-5 behaviour).
 */
final class Vitality implements Listener {

    /** player -> current pool. Absent = full. Main thread only. */
    private static final Map<UUID, Double> POOL = new HashMap<>();
    private static final Map<UUID, Long> NO_VIT_MSG = new HashMap<>();
    private static String costsText;
    private static Map<String, Double> costs = Map.of();
    private static String softText;
    private static Map<String, Double> soft = Map.of();
    private static int task = -1;

    /** Converter enchant id, buff key, stat, cap per piece (the books' "Max N"). */
    private record Converter(String enchant, String buff, Stat stat, double capPerPiece) {}

    private static final Converter[] CONVERTERS = {
            new Converter("hardened_mana", "vit_defense", Stat.DEFENSE, 25),
            new Converter("strong_mana", "vit_strength", Stat.STRENGTH, 30),
            new Converter("ferocious_mana", "vit_attack_speed", Stat.ATTACK_SPEED, 5),
    };

    void register() {
        Sky.stats().addProvider("enchanting_vitality", Vitality::stats);
        Sky.vitals().actionBarSegment("enchanting_vitality", 40, Vitality::actionBar);
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), Vitality::second, 20L, 20L);
    }

    static void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        POOL.clear();
        NO_VIT_MSG.clear();
    }

    static boolean on() {
        EDials d = EnchantingModule.dials();
        return d != null && d.effects.get() && d.vitalityPool.get();
    }

    // ---- the pool ----------------------------------------------------------------------------------------

    static double max(Player p) { return Math.max(0, Sky.stats().get(p, Stat.VITALITY)); }

    static double current(Player p) {
        Double c = POOL.get(p.getUniqueId());
        double max = max(p);
        return c == null ? max : VitalityMath.clamp(c, max);
    }

    static void set(Player p, double value) {
        double max = max(p);
        double v = VitalityMath.clamp(value, max);
        if (v >= max) POOL.remove(p.getUniqueId());
        else POOL.put(p.getUniqueId(), v);
    }

    /** Add Vitality (Drain, Last Stand); returns what actually went in. */
    static double restore(Player p, double amount) {
        if (amount <= 0 || !on()) return 0;
        double before = current(p);
        set(p, before + amount);
        return current(p) - before;
    }

    /** Spend up to {@code cost}; returns what was spent, and runs the converter enchants on it. */
    static double spend(Player p, double cost) {
        if (cost <= 0 || !on()) return 0;
        double cur = current(p);
        double spent = VitalityMath.spendable(cur, cost);
        if (spent <= 0) return 0;
        set(p, cur - spent);
        converters(p, spent);
        return spent;
    }

    private static void converters(Player p, double spent) {
        UUID u = p.getUniqueId();
        long millis = Math.max(1, EnchantingModule.dials().vitalityConverterSeconds.get()) * 1000L;
        boolean changed = false;
        for (Converter c : CONVERTERS) {
            double pct = Gear.wornSum(p, c.enchant());
            if (pct <= 0) continue;
            double cap = c.capPerPiece() * pieces(p, c.enchant());
            double next = VitalityMath.convert(Buffs.get(u, c.buff()), spent, pct, cap);
            if (next > 0) { Buffs.set(u, c.buff(), next, millis); changed = true; }
        }
        double heal = Gear.wornSum(p, "mana_vampire");   // Vampiric Vitality: HP per point spent
        if (heal > 0) Sky.vitals().heal(p, heal * spent);
        if (changed) Sky.stats().invalidate(p);
    }

    static int pieces(Player p, String enchant) {
        int n = 0;
        for (Map<String, Integer> m : Gear.worn(p)) if (m.getOrDefault(enchant, 0) > 0) n++;
        return Math.max(1, n);
    }

    private static void stats(Player p, StatMap m) {
        if (!on()) return;
        UUID u = p.getUniqueId();
        for (Converter c : CONVERTERS) {
            double v = Buffs.get(u, c.buff());
            if (v > 0) m.add(c.stat(), v);
        }
    }

    private static String actionBar(Player p) {
        if (!on() || !EnchantingModule.dials().vitalityActionBar.get()) return null;
        Double c = POOL.get(p.getUniqueId());
        if (c == null) return null;
        double max = max(p);
        if (max <= 0 || c >= max) return null;
        return VitalityMath.bar(c, max);
    }

    private static void second() {
        if (POOL.isEmpty()) return;
        if (!on()) { POOL.clear(); return; }
        double pct = EnchantingModule.dials().vitalityRegen.get();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Double c = POOL.get(p.getUniqueId());
            if (c == null) continue;
            set(p, VitalityMath.regen(c, max(p), pct, 1));
        }
        POOL.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    /** {@code /vitality}: the pool, its regen, and the enchants on you that touch it. */
    static void tell(Player p) {
        if (!on()) { Msg.send(p, "&7The &4♨ Vitality &7pool is switched off on this server."); return; }
        double max = max(p);
        Msg.send(p, "&4♨ Vitality: &f" + EnchantSpec.fmt(Math.floor(current(p))) + "&7/&f" + EnchantSpec.fmt(Math.floor(max))
                + " &7(refills &a" + EnchantSpec.fmt(max * EnchantingModule.dials().vitalityRegen.get() / 100.0) + "&7/s)");
        Msg.send(p, "&7Spent by healing abilities: wands &425&7, Mend Flesh &425&7, power orbs &450&7, flares &410&7.");
        int drain = Gear.level(Gear.held(p), "syphon");
        EnchantSpec ds = EnchantingModule.reg().spec("syphon");
        if (drain > 0 && ds != null) Msg.send(p, "&9Drain " + EnchantSpec.roman(drain) + "&7: &4+" + EnchantSpec.fmt(ds.v(drain)) + "♨ &7per hit.");
        for (Converter c : CONVERTERS) {
            double pct = Gear.wornSum(p, c.enchant());
            EnchantSpec s = EnchantingModule.reg().spec(c.enchant());
            if (pct > 0 && s != null) Msg.send(p, "&9" + s.name + "&7: &a" + EnchantSpec.fmt(pct) + "% &7of what you spend -> "
                    + c.stat().display + " &8(now +" + EnchantSpec.fmt(Math.round(Buffs.get(p.getUniqueId(), c.buff()) * 10) / 10.0) + ")");
        }
        double vamp = Gear.wornSum(p, "mana_vampire");
        if (vamp > 0) Msg.send(p, "&9Vampiric Vitality&7: heal &c" + EnchantSpec.fmt(vamp) + "❤ &7per point spent.");
    }

    // ---- costs -------------------------------------------------------------------------------------------

    static Map<String, Double> costs() {
        String t = EnchantingModule.dials().vitalityCosts.get();
        if (t == null) t = "";
        if (!t.equals(costsText)) { costs = VitalityMath.parseCosts(t); costsText = t; }
        return costs;
    }

    static Map<String, Double> softCosts() {
        String t = EnchantingModule.dials().vitalitySoftCosts.get();
        if (t == null) t = "";
        if (!t.equals(softText)) { soft = VitalityMath.parseCosts(t); softText = t; }
        return soft;
    }

    static double costOf(String abilityId) {
        if (abilityId == null) return 0;
        String id = abilityId.toLowerCase(Locale.ROOT);
        Double c = costs().get(id);
        if (c == null) c = softCosts().get(id);
        return c == null ? 0 : c;
    }

    /** A healing ability went off: charge its Vitality. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(SkyEvents.AbilityUsed e) {
        if (!on() || e.ability == null || e.player == null) return;
        double cost = costOf(e.ability.id);
        if (cost <= 0) return;
        double spent = spend(e.player, cost);
        if (spent > 0) Sky.vitals().flash(e.player, "&4-" + EnchantSpec.fmt(spent) + "♨ Vitality (&6" + e.ability.name + "&4)", 30);
    }

    /**
     * Refuse a healing ability the pool cannot pay for, BEFORE core's AbilityListener (HIGH) fires it. Runs at LOW
     * and only stamps {@code consumeClick} (core then skips the held item's ability for this tick): no mana, no
     * cooldown is taken, just like "You do not have enough mana!".
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !on() || !EnchantingModule.dials().vitalityEnforce.get()) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        Player p = e.getPlayer();
        ItemStack held = e.getItem();
        if (!Gear.isSky(held)) return;
        ItemDef d = Gear.def(held);
        if (d == null || d.abilities == null || d.abilities.isEmpty()) return;
        AbilityDef hit = null;
        for (AbilityDef ab : d.abilities) {
            if (ab == null || ab.trigger == null) continue;
            boolean fires = switch (ab.trigger) {
                case RIGHT_CLICK -> right && !p.isSneaking();
                case SNEAK_RIGHT_CLICK -> right && p.isSneaking();
                case LEFT_CLICK -> left;
                default -> false;
            };
            if (fires && costs().containsKey(ab.id == null ? "" : ab.id.toLowerCase(Locale.ROOT))) { hit = ab; break; }
        }
        if (hit == null) return;
        if (Sky.abilities().cooldownLeft(p, hit.id) > 0) return;   // core answers with its cooldown line
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        double cost = costOf(hit.id);
        if (current(p) + 1e-9 >= cost) return;
        Sky.abilities().consumeClick(p);
        Sky.vitals().flash(p, "&c&lNOT ENOUGH VITALITY", 30);
        long now = System.currentTimeMillis();
        Long last = NO_VIT_MSG.get(p.getUniqueId());
        if (last == null || now - last >= 3000) {
            NO_VIT_MSG.put(p.getUniqueId(), now);
            Msg.send(p, "&cYou do not have enough Vitality! &7(" + EnchantSpec.fmt(Math.floor(current(p))) + "/"
                    + EnchantSpec.fmt(cost) + "&4♨&7)");
        }
        Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(SkyEvents.SkyDeath e) { if (e.player != null) POOL.remove(e.player.getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        POOL.remove(e.getPlayer().getUniqueId());
        NO_VIT_MSG.remove(e.getPlayer().getUniqueId());
    }
}
