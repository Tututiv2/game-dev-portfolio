package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Active potion effects: drinking, splashing, the God Potion, timers (online time only), the
 * "potions" StatProvider, the combat hooks, and {@link Features.Potions}.
 *
 * <p>Rules (research 08 section 2.2): one effect of each type; a new drink of the same type REPLACES
 * it; different types stack. The God Potion is a separate timer whose effects count as the higher of
 * it and any regular potion of the same type.
 */
final class EffectEngine implements Listener, Features.Potions {

    static final String KEY = "alchemy";

    private final EffectTable table;
    /** Mobs hit by a Weakness splash: uuid -> {expiry millis, flat damage removed}. */
    private final Map<UUID, double[]> mobWeakness = new java.util.concurrent.ConcurrentHashMap<>();
    /** Mobs hit by a Wounded splash: uuid -> {expiry millis, healing cut %, health at the last check}. */
    private final Map<UUID, double[]> mobWounded = new java.util.concurrent.ConcurrentHashMap<>();

    EffectEngine(EffectTable table) { this.table = table; }

    static AlchemyData data(Player p) { return Sky.profiles().member(p, KEY, AlchemyData.class); }

    // ---- what is active ------------------------------------------------------------------------

    boolean paused(Player p) {
        if (!AlchemyDials.PAUSE_IN_DUNGEONS.get() || !Sky.has(Features.Dungeons.class)) return false;
        try { return Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException e) { return false; }
    }

    /** effect id -> level actually in force right now (regular potions, then the God Potion on top). */
    Map<String, Integer> effective(Player p) {
        if (!Sky.profiles().isReady(p)) return Map.of();
        return effective(data(p), paused(p));
    }

    Map<String, Integer> effective(AlchemyData d, boolean paused) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, AlchemyData.Active> e : d.effects.entrySet()) {
            AlchemyData.Active a = e.getValue();
            if (a == null || a.left <= 0) continue;
            if (paused && !"dungeon".equals(a.source)) continue;
            if (table.effect(e.getKey()) == null) continue;
            out.merge(e.getKey(), a.level, Math::max);
        }
        if (d.godLeft > 0 && !paused) {
            for (Map.Entry<String, Integer> g : table.god().entrySet()) {
                if (d.godOff.contains(g.getKey())) continue;
                out.merge(g.getKey(), g.getValue(), Math::max);
            }
        }
        return out;
    }

    private EffectTable.Level row(String effect, int level) {
        EffectTable.Effect e = table.effect(effect);
        if (e == null) return null;
        EffectTable.Level l = e.level(level);
        return l != null ? l : e.levelAtMost(level);
    }

    /** Sum of one param over everything active (dodge from Dodge and Agility...). */
    double param(Map<String, Integer> eff, String key) { return param(null, eff, key); }

    /** Same, with each potion's Basic Brew bonus (d = the player's data, null = none). */
    double param(AlchemyData d, Map<String, Integer> eff, String key) {
        double s = 0;
        for (Map.Entry<String, Integer> x : eff.entrySet()) {
            EffectTable.Level l = row(x.getKey(), x.getValue());
            if (l == null) continue;
            EffectTable.BasicBrew bb = d == null ? null : brewOf(d, x.getKey(), x.getValue());
            s += boosted(bb, key, l.param(key));
        }
        return s;
    }

    /**
     * The Basic Brew boosting the effect in force at this level: the regular potion's brew when it is the
     * one in force (a God Potion of a higher level wins and has no brew), else null.
     */
    EffectTable.BasicBrew brewOf(AlchemyData d, String effect, int level) {
        AlchemyData.Active a = d.effects.get(effect);
        if (a == null || a.brew == null || a.left <= 0 || a.level != level) return null;
        EffectTable.BasicBrew bb = table.brew(a.brew);
        return bb != null && bb.appliesTo(table.effect(effect)) ? bb : null;
    }

    private static double boosted(EffectTable.BasicBrew bb, String key, double v) { return bb == null ? v : bb.apply(key, v); }

    /** Independent chances combined: 1 - (1-a)(1-b). */
    double chance(Map<String, Integer> eff, String key) {
        double miss = 1;
        for (Map.Entry<String, Integer> x : eff.entrySet()) {
            EffectTable.Level l = row(x.getKey(), x.getValue());
            if (l != null && l.param(key) > 0) miss *= 1 - Math.min(100, l.param(key)) / 100.0;
        }
        return 1 - miss;
    }

    // ---- stats ---------------------------------------------------------------------------------

    void contribute(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p)) return;
        AlchemyData d = data(p);
        Map<String, Integer> eff = effective(p);
        double jelly = eff.isEmpty() ? 0 : jellyNow(p);
        for (Map.Entry<String, Integer> x : eff.entrySet()) {
            EffectTable.Level l = row(x.getKey(), x.getValue());
            if (l == null) continue;
            EffectTable.Effect e = table.effect(x.getKey());
            double mult = (e == null ? 1 : e.scale(x.getValue())) * strength(d, x.getKey(), jelly);
            EffectTable.BasicBrew bb = brewOf(d, x.getKey(), x.getValue());
            if (l.stats != null) for (Map.Entry<String, Double> s : l.stats.entrySet()) {
                Stat st = Stat.parse(s.getKey());
                if (st != null && s.getValue() != null) into.add(st, boosted(bb, s.getKey(), s.getValue()) * mult);
            }
            double cut = boosted(bb, "heal_reduce", l.param("heal_reduce"));
            if (cut > 0) into.addPercent(Stat.HEALTH_REGEN, -Math.min(100, cut));
        }
        // Refined Midnight Wit: +1 Intelligence for good per bottle drunk, at most the dial (reference: 5)
        int refined = Math.min(Math.max(0, d.jyrreRefined), AlchemyDials.JYRRE_REFINED_MAX.get());
        if (refined > 0) into.add(Stat.INTELLIGENCE, refined);
    }

    // ---- duration bonuses ----------------------------------------------------------------------

    /** Potion Affinity accessory bonus in percent (the best one owned). */
    double affinity(Player p) {
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        try {
            if (acc.has(p, "ARTIFACT_POTION_AFFINITY")) return AlchemyDials.AFFINITY_ARTIFACT.get();
            if (acc.has(p, "RING_POTION_AFFINITY")) return AlchemyDials.AFFINITY_RING.get();
            if (acc.has(p, "POTION_AFFINITY_TALISMAN")) return AlchemyDials.AFFINITY_TALISMAN.get();
        } catch (RuntimeException ignored) {}
        return 0;
    }

    /**
     * Pet duration multiplier for normal potions (1.0 without one): Parrot "Encore" ({@code parrot_repeat})
     * plus Bingo "Long Brew" ({@code bingo_consumer}), read from the pets module's ability numbers. When the
     * pets module gives no number but a Parrot is out, the old per-level dial is the fallback.
     */
    double parrot(Player p) {
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            double pct = Math.max(0, pets.abilityValue(p, "parrot_repeat", "a"))
                    + Math.max(0, pets.abilityValue(p, "bingo_consumer", "a"));
            if (pct <= 0 && "PARROT".equalsIgnoreCase(pets.activeType(p)))
                pct = pets.activeLevel(p) * AlchemyDials.PARROT_PCT_PER_LEVEL.get();
            return 1 + pct / 100.0;
        } catch (RuntimeException ignored) {}
        return 1.0;
    }

    /**
     * Pet multiplier for God Potions: the Parrot's "Feathered Brew" ({@code parrot_parrot_feather_infusion},
     * +20% at level 100 = the reference God Potion lore's x1.2). Fallback as {@link #parrot}.
     */
    double godParrot(Player p) {
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            double pct = Math.max(0, pets.abilityValue(p, "parrot_parrot_feather_infusion", "a"));
            if (pct <= 0 && "PARROT".equalsIgnoreCase(pets.activeType(p)))
                pct = pets.activeLevel(p) * AlchemyDials.PARROT_PCT_PER_LEVEL.get();
            return 1 + pct / 100.0;
        } catch (RuntimeException ignored) {}
        return 1.0;
    }

    /** Jellyfish "Strong Brew": % stronger dungeon potions while inside a dungeon (0 without it). */
    double jellyfish(Player p) {
        try { return Math.max(0, Sky.service(Features.Pets.class).abilityValue(p, "jellyfish_powerful_potions", "a")); }
        catch (RuntimeException e) { return 0; }
    }

    boolean inDungeon(Player p) {
        if (!Sky.has(Features.Dungeons.class)) return false;
        try { return Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException e) { return false; }
    }

    /**
     * Strength multiplier of one active effect: a dungeon potion (source "dungeon", not a special potion)
     * inside a dungeon is {@code jellyPct}% stronger. {@code jellyPct} = 0 when not in a dungeon.
     */
    double strength(AlchemyData d, String effect, double jellyPct) {
        if (jellyPct <= 0) return 1;
        AlchemyData.Active a = d.effects.get(effect);
        EffectTable.Effect e = table.effect(effect);
        if (a == null || !"dungeon".equals(a.source) || e == null || "special".equals(e.kind)) return 1;
        return 1 + jellyPct / 100.0;
    }

    /** Jellyfish % in force right now (0 outside dungeons). */
    double jellyNow(Player p) {
        double j = jellyfish(p);
        return j > 0 && inDungeon(p) ? j : 0;
    }

    static int alchemyLevel(Player p) {
        try { return Sky.service(SkillService.class).level(p, SkillType.ALCHEMY); } catch (RuntimeException e) { return 0; }
    }

    // ---- applying ------------------------------------------------------------------------------

    /**
     * Put an effect on a player: instant parts (absorption, healing burst, instant damage) happen now,
     * the timed part replaces any effect of the same type. Returns the label shown to the player.
     */
    String applyEffect(Player p, String effectId, int level, long seconds, String source) {
        return applyEffect(p, effectId, level, seconds, source, null);
    }

    /** {@code brew} = the Basic Brew the potion was made from (boosts its numbers while it lasts), or null. */
    String applyEffect(Player p, String effectId, int level, long seconds, String source, String brew) {
        EffectTable.Effect e = table.effect(effectId);
        if (e == null || !Sky.profiles().isReady(p)) return null;
        EffectTable.Level l = e.level(level);
        if (l == null) l = e.levelAtMost(level);
        if (l == null) return null;
        int lvl = e.scaled ? Math.max(1, level) : l.level;     // a scaled effect's level IS its size (Midnight Wit +120)
        EffectTable.BasicBrew bb = table.brew(brew);
        if (bb != null && !bb.appliesTo(e)) { bb = null; brew = null; }
        double abs = l.param("absorption");
        if (abs > 0) Sky.vitals().addAbsorption(p, abs);
        double heal = boosted(bb, "heal_instant", l.param("heal_instant"));
        if (heal > 0) Sky.vitals().heal(p, heal);
        double mana = boosted(bb, "mana_instant", l.param("mana_instant"));
        if (mana > 0) Sky.vitals().addMana(p, mana);
        double dmg = l.param("damage");
        if (dmg > 0) hurtSelf(p, dmg);
        if (l.seconds > 0 && seconds > 0) {
            AlchemyData d = data(p);
            AlchemyData.Active old = d.effects.get(e.id);
            if (old != null && old.level != lvl) clearVanilla(p, e);
            AlchemyData.Active a = new AlchemyData.Active(lvl, seconds, source);
            a.brew = brew;
            d.effects.put(e.id, a);
            applyVanilla(p, e, l);
            Sky.stats().invalidate(p);
        }
        return e.label(lvl);
    }

    /** Remove one effect (the /effects toggle). Turning off a God Potion effect hides it from the God Potion too. */
    boolean remove(Player p, String effectId) {
        AlchemyData d = data(p);
        boolean had = d.effects.remove(effectId) != null;
        if (d.godLeft > 0 && table.god().containsKey(effectId) && d.godOff.add(effectId)) had = true;
        EffectTable.Effect e = table.effect(effectId);
        if (e != null) clearVanilla(p, e);
        Sky.stats().invalidate(p);
        return had;
    }

    void clearAll(Player p) {
        AlchemyData d = data(p);
        for (String id : List.copyOf(d.effects.keySet())) {
            EffectTable.Effect e = table.effect(id);
            if (e != null) clearVanilla(p, e);
        }
        d.effects.clear();
        d.godLeft = 0;
        d.godOff.clear();
        Sky.stats().invalidate(p);
    }

    void addGod(Player p, long seconds) {
        AlchemyData d = data(p);
        if (d.godLeft <= 0) d.godOff.clear();
        d.godLeft = Math.max(0, d.godLeft) + Math.max(0, seconds);
        Sky.stats().invalidate(p);
    }

    private static void hurtSelf(Player p, double amount) {
        double hp = Sky.vitals().health(p) + Sky.vitals().absorption(p);
        double dealt = Math.min(amount, Math.max(0, hp - 1));
        if (dealt > 0) Sky.vitals().damage(p, dealt, "potion");
    }

    private static PotionEffectType vanillaType(String name) {
        if (name == null) return null;
        try { return Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))); }
        catch (RuntimeException e) { return null; }
    }

    private void applyVanilla(Player p, EffectTable.Effect e, EffectTable.Level l) {
        PotionEffectType type = vanillaType(e.vanilla);
        if (type == null) return;
        int amp = (int) Math.max(0, l.params != null && l.params.containsKey("amp") ? l.param("amp") : l.level - 1);
        int ticks = "night_vision".equals(e.id) ? 400 : 60;
        p.addPotionEffect(new PotionEffect(type, ticks, amp, true, false, true));
    }

    private void clearVanilla(Player p, EffectTable.Effect e) {
        PotionEffectType type = vanillaType(e.vanilla);
        if (type != null) p.removePotionEffect(type);
    }

    // ---- the one-second tick ---------------------------------------------------------------------

    /**
     * Mobs hit by a Wounded splash: core has no mob-healing hook, so once a second the health a mob GAINED
     * since the last check is cut by the potion's % (the reference "reduces healing"). Damage is untouched.
     */
    void tickWounded(long now) {
        if (mobWounded.isEmpty()) return;
        var it = mobWounded.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, double[]> en = it.next();
            double[] w = en.getValue();
            Entity ent = Bukkit.getEntity(en.getKey());
            if (w[0] < now || !(ent instanceof LivingEntity le) || !le.isValid() || le.isDead()) { it.remove(); continue; }
            double h = Sky.mobs().health(le);
            double kept = woundedHealth(w[2], h, w[1]);
            if (kept < h) Sky.mobs().setHealth(le, kept);
            w[2] = kept;
        }
    }

    /** Pure: the health a Wounded mob keeps when it went from {@code last} to {@code now} (a gain is cut by pct%). */
    static double woundedHealth(double last, double now, double pct) {
        if (!(now > last)) return now;
        return last + (now - last) * (1 - Math.max(0, Math.min(100, pct)) / 100.0);
    }

    void tickAll() {
        long now = System.currentTimeMillis();
        if (!mobWeakness.isEmpty()) mobWeakness.values().removeIf(w -> w[0] < now);
        tickWounded(now);
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (Sky.profiles().isReady(p)) tick(p, now);
            } catch (RuntimeException ex) {
                Sky.log().warning("[alchemy] effect tick failed for " + p.getName() + ": " + ex);
            }
        }
    }

    private void tick(Player p, long now) {
        AlchemyData d = data(p);
        d.lastTick = now;
        if (d.effects.isEmpty() && d.godLeft <= 0) return;
        boolean paused = paused(p);
        boolean changed = false;
        var it = d.effects.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AlchemyData.Active> x = it.next();
            AlchemyData.Active a = x.getValue();
            if (a == null) { it.remove(); changed = true; continue; }
            if (paused && !"dungeon".equals(a.source)) continue;
            a.left--;
            if (a.left <= 0) {
                it.remove();
                changed = true;
                EffectTable.Effect e = table.effect(x.getKey());
                if (e != null) {
                    clearVanilla(p, e);
                    Msg.send(p, "&7Your " + e.label(a.level) + " &7effect has worn off.");
                }
            }
        }
        if (d.godLeft > 0 && !paused) {
            d.godLeft--;
            if (d.godLeft <= 0) {
                d.godOff.clear();
                changed = true;
                Msg.send(p, "&7Your &cGod Potion &7has worn off.");
                for (String id : table.god().keySet()) {
                    EffectTable.Effect e = table.effect(id);
                    if (e != null && !d.effects.containsKey(id)) clearVanilla(p, e);
                }
            }
        }
        Map<String, Integer> inForce = effective(d, paused);
        double jelly = inForce.isEmpty() ? 0 : jellyNow(p);
        for (Map.Entry<String, Integer> x : inForce.entrySet()) {
            EffectTable.Effect e = table.effect(x.getKey());
            EffectTable.Level l = row(x.getKey(), x.getValue());
            if (e == null || l == null) continue;
            EffectTable.BasicBrew bb = brewOf(d, x.getKey(), x.getValue());
            double str = strength(d, x.getKey(), jelly);
            double h = boosted(bb, "heal_per_sec", l.param("heal_per_sec")) * str;
            if (h > 0) Sky.vitals().heal(p, h);
            double m = boosted(bb, "mana_per_sec", l.param("mana_per_sec")) * str;
            if (m > 0) Sky.vitals().addMana(p, m);
            double dps = l.param("poison_dps");
            if (dps > 0) hurtSelf(p, dps);
            applyVanilla(p, e, l);
        }
        if (changed) Sky.stats().invalidate(p);
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        AlchemyData d = data(p);
        if (AlchemyDials.TICK_OFFLINE.get() && d.lastTick > 0) {
            long gone = Math.max(0, (System.currentTimeMillis() - d.lastTick) / 1000);
            d.effects.values().removeIf(a -> a == null || (a.left -= gone) <= 0);
            d.godLeft = Math.max(0, d.godLeft - gone);
        }
        d.lastTick = System.currentTimeMillis();
        Sky.stats().invalidate(p);
    }

    // ---- drinking ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        String id = Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null || !def.has("alchemy")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (PotionItems.isBase(def)) { Msg.error(p, "That is a brewing base. Take it to a brewing stand!"); return; }
        if (!Sky.profiles().isReady(p)) return;
        EquipmentSlot hand = e.getHand();
        ItemStack held = p.getInventory().getItem(hand);
        if (held == null || !id.equals(Sky.items().idOf(held))) return;
        if (!drink(p, held, def)) return;
        if (held.getAmount() <= 1) p.getInventory().setItem(hand, null);
        else held.setAmount(held.getAmount() - 1);
    }

    /** Apply a drunk potion (normal, bundle). Returns false when it was refused (nothing consumed). */
    boolean drink(Player p, ItemStack stack, ItemDef def) {
        if (PotionItems.isBundle(def)) {
            if (AlchemyDials.DUNGEON_ONLY.get() && Sky.has(Features.Dungeons.class)
                    && !Sky.service(Features.Dungeons.class).inDungeon(p)) {
                Msg.error(p, "Dungeon potions only work inside a dungeon!");
                return false;
            }
            EffectTable.Bundle b = table.bundle(PotionItems.str(def.extra, "bundle"), PotionItems.integer(def.extra, "level", 1));
            if (b == null) return false;
            long secs = Math.round(b.seconds * (1 + affinity(p) / 100.0) * parrot(p));
            for (Map.Entry<String, Integer> x : b.effects.entrySet()) applyEffect(p, x.getKey(), x.getValue(), secs, "dungeon");
            announce(p, stack, def, null, secs);
            return true;
        }
        if (PotionItems.isJyrre(def)) return drinkJyrre(p, stack, def);
        Brew.Bottle b = PotionItems.bottleOf(stack);
        if (b == null || b.kind() != Brew.Kind.POTION) return false;
        long secs = Brew.duration(table, b, affinity(p), parrot(p));
        // a special potion may name its own timer source (Secret: "dungeon", so it keeps running inside dungeons)
        String src = PotionItems.str(def.extra, "source");
        String label = applyEffect(p, b.effect(), b.level(), secs, src != null ? src : "potion", b.brew());
        if (label == null) return false;
        announce(p, stack, def, label, secs);
        return true;
    }

    /**
     * Midnight Wit (reference Bottle of Jyrre): +Intelligence equal to the bottle's grown bonus for its
     * duration; a Refined bottle is always at its cap and leaves +1 Intelligence for good (first 5 only).
     */
    boolean drinkJyrre(Player p, ItemStack stack, ItemDef def) {
        String kind = PotionItems.str(def.extra, "jyrre");
        int bonus = PotionItems.jyrreBonus(def, Sky.items().data(stack));
        if (bonus <= 0) {
            Msg.error(p, "This bottle has no Intelligence in it yet! Keep it in your inventory and it grows.");
            return false;
        }
        long secs = Math.max(60, PotionItems.integer(def.extra, "minutes", 60) * 60L);
        String label = applyEffect(p, "jyrre", bonus, secs, "potion");
        if (label == null) return false;
        announce(p, stack, def, label, secs);
        if ("refined".equals(kind)) {
            AlchemyData d = data(p);
            int max = AlchemyDials.JYRRE_REFINED_MAX.get();
            if (d.jyrreRefined < max) {
                d.jyrreRefined++;
                Sky.stats().invalidate(p);
                Msg.send(p, "&bYou feel permanently sharper! &7+1 &b✎ Intelligence &7for good (&a"
                        + d.jyrreRefined + "&7/&a" + max + "&7).");
            } else {
                Msg.send(p, "&7This bottle has lost its lasting magic: you have already drunk &a" + max + "&7.");
            }
        }
        return true;
    }

    // ---- skull potions (Dread Draught) and the Recall Potion ---------------------------------

    /** A potion that is a skull (Dread Draught): right-click consumes it and applies its effect. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSkullPotion(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (!PotionItems.isSkullPotion(def)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!id.equals(Sky.items().idOf(held))) return;
        String eff = PotionItems.str(def.extra, "effect");
        int lvl = PotionItems.integer(def.extra, "level", 1);
        EffectTable.Effect ef = table.effect(eff);
        EffectTable.Level l = ef == null ? null : ef.levelAtMost(lvl);
        if (l == null) return;
        String label = applyEffect(p, eff, lvl, l.seconds, "potion");
        if (label == null) return;
        if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        announce(p, held, def, label, l.seconds);
    }

    /**
     * Recall Potion (reference Ancient Recall): inside the Crystal Caverns the first right-click (or any
     * sneak-right-click) saves your spot on the potion; the next right-click drinks it and takes you back.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onRecall(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (!PotionItems.isRecall(def)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        String island = Sky.worlds().islandOf(p.getWorld());
        if (!AlchemyDials.RECALL_ISLAND.get().equalsIgnoreCase(String.valueOf(island))) {
            Msg.error(p, "The Recall Potion only works in the Crystal Caverns!");
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!id.equals(Sky.items().idOf(held))) return;
        com.skyclone.api.item.ItemData data = Sky.items().data(held);
        String world = PotionItems.str(data.extra, "recall_world");
        Location here = p.getLocation();
        if (p.isSneaking() || world == null || !world.equals(p.getWorld().getName())) {
            Sky.items().update(held, d -> {
                d.extra.addProperty("recall_world", here.getWorld().getName());
                d.extra.addProperty("recall_x", Math.round(here.getX() * 10) / 10.0);
                d.extra.addProperty("recall_y", Math.round(here.getY() * 10) / 10.0);
                d.extra.addProperty("recall_z", Math.round(here.getZ() * 10) / 10.0);
            });
            p.getInventory().setItemInMainHand(held);
            Msg.send(p, "&5Location saved! &7Right-click again to drink the potion and return here"
                    + " &8(sneak + right-click saves a new spot).");
            Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            return;
        }
        Location to = new Location(p.getWorld(), PotionItems.number(data.extra, "recall_x", here.getX()),
                PotionItems.number(data.extra, "recall_y", here.getY()), PotionItems.number(data.extra, "recall_z", here.getZ()),
                here.getYaw(), here.getPitch());
        if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        data(p).potionsDrunk++;
        p.teleport(to);
        Msg.send(p, "&5Whoosh! &7The potion carried you back to your saved spot.");
        Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f);
    }

    // ---- Midnight Wit growth ----------------------------------------------------------------

    /**
     * Once a minute: every Midnight Wit bottle in an online player's inventory ages by a minute; a bottle
     * whose bonus reaches its cap and that evolves (Bottle -> Refined) turns into the evolved item.
     */
    void ageJyrre() {
        int perInt = Math.max(1, AlchemyDials.JYRRE_MINUTES_PER_INT.get());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            try {
                ItemStack[] inv = p.getInventory().getStorageContents();
                for (int i = 0; i < inv.length; i++) {
                    ItemStack st = inv[i];
                    if (st == null || st.getType().isAir()) continue;
                    String id = Sky.items().idOf(st);
                    ItemDef def = id == null ? null : Sky.items().def(id);
                    if (!PotionItems.isJyrre(def) || "refined".equals(PotionItems.str(def.extra, "jyrre"))) continue;
                    int cap = PotionItems.integer(def.extra, "cap", 300);
                    long min = PotionItems.integer(Sky.items().data(st).extra, "jmin", 0);
                    if (min / perInt >= cap) {
                        evolve(p, i, st, def);
                        continue;
                    }
                    long next = min + 1;
                    Sky.items().update(st, d -> d.extra.addProperty("jmin", next));
                    p.getInventory().setItem(i, st);
                    if (next / perInt >= cap) evolve(p, i, st, def);
                }
            } catch (RuntimeException ex) {
                Sky.log().warning("[alchemy] Midnight Wit tick failed for " + p.getName() + ": " + ex);
            }
        }
    }

    private void evolve(Player p, int slot, ItemStack st, ItemDef def) {
        String to = PotionItems.str(def.extra, "evolves");
        if (to == null || !Sky.items().exists(to)) return;
        ItemStack nw = PotionItems.create(to);
        if (nw == null) return;
        p.getInventory().setItem(slot, nw);
        ItemDef nd = Sky.items().def(to);
        Msg.send(p, "&bYour " + def.rarity.code() + def.name + " &bhas evolved into " + nd.rarity.code() + nd.name + "&b!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
    }

    private void announce(Player p, ItemStack stack, ItemDef def, String label, long secs) {
        AlchemyData d = data(p);
        d.potionsDrunk++;
        String name = Sky.items().rarity(stack).code() + Sky.items().def(def.id).name;
        Msg.send(p, "&aYou drank " + name + "&a!" + (label != null && secs > 0 ? " " + label + " &7for &a" + EffectTable.clock(secs) + "&7." : ""));
        Msg.sound(p, Sound.ENTITY_GENERIC_DRINK, 1f);
    }

    // ---- God Potion (a skull: right-click to consume) -----------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onGodPotion(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (!PotionItems.isGod(def)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        boolean legacy = PotionItems.bool(def.extra, "legacy");
        long secs = Brew.godSeconds(legacy, AlchemyDials.GOD_LEGACY_HOURS.get(), AlchemyDials.GOD_BASE_HOURS.get(),
                AlchemyDials.GOD_ALCHEMY_HOURS.get(), alchemyLevel(p), godParrot(p));
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!id.equals(Sky.items().idOf(held))) return;
        if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        addGod(p, secs);
        data(p).potionsDrunk++;
        Msg.box(p, "&c&lGOD POTION", List.of("&7You feel every blessing at once!",
                "&7Time added: &a" + Text.duration(secs), "&7Total left: &a" + Text.duration(data(p).godLeft)));
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
    }

    // ---- splash potions ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion tp = e.getPotion();
        ItemStack it = tp.getItem();
        String id = Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null || !def.has("alchemy")) return;
        for (LivingEntity le : e.getAffectedEntities()) e.setIntensity(le, 0);
        Brew.Bottle b = PotionItems.bottleOf(it);
        if (b == null || b.kind() != Brew.Kind.POTION) return;
        EffectTable.Effect eff = table.effect(b.effect());
        if (eff == null) return;
        ProjectileSource src = tp.getShooter();
        Player thrower = src instanceof Player pl ? pl : null;
        double r = b.splash() > 1 ? AlchemyDials.SPLASH_RADIUS_WIDE.get() : AlchemyDials.SPLASH_RADIUS.get();
        Location at = tp.getLocation();
        if (at.getWorld() == null) return;
        at.getWorld().spawnParticle(Particle.WITCH, at, 30, r / 3, 0.5, r / 3, 0.05);
        EffectTable.Level l = eff.level(b.level());
        if (l == null) return;
        boolean harmful = eff.isDebuff() || "damage".equals(eff.id) || "stun".equals(eff.id);
        if (!harmful) {
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(at) > r * r) continue;
                if (thrower != null && p != thrower && !AlchemyDials.SPLASH_SHARE.get()) continue;
                long secs = Brew.duration(table, b, affinity(p), parrot(p));
                String label = applyEffect(p, eff.id, b.level(), secs, "splash", b.brew());
                if (label != null) Msg.send(p, "&aA splash potion gave you " + label
                        + (secs > 0 ? " &7for &a" + EffectTable.clock(secs) : "") + "&a!");
            }
            return;
        }
        if (thrower == null) return;
        for (Entity en : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (!(en instanceof LivingEntity le) || en instanceof Player || !le.isValid()) continue;
            if (!Sky.mobs().isSkyMob(le)) continue;
            UUID owner = Sky.mobs().owner(le);
            if (owner != null && !owner.equals(thrower.getUniqueId())) continue;
            harm(thrower, le, eff, l, Brew.duration(table, b, 0, 1));
        }
    }

    private void harm(Player thrower, LivingEntity mob, EffectTable.Effect eff, EffectTable.Level l, long secs) {
        double dmg = l.param("damage");
        if (dmg > 0) Sky.combat().rawDamage(mob, dmg, thrower, DamageContext.Kind.MAGIC);
        double stun = l.param("stun_splash");
        if (stun > 0) stun(mob, stun);
        double sp = l.stat("SPEED");
        if (sp < 0) {
            PotionEffectType slow = vanillaType("slowness");
            if (slow != null) mob.addPotionEffect(new PotionEffect(slow, (int) Math.max(20, secs * 20),
                    (int) Math.min(6, Math.max(0, Math.abs(sp) / 40)), false, true));
        }
        double weak = l.param("weakness");
        if (weak > 0) {
            // the mob hits for less while it lasts (read back in modify())
            long until = System.currentTimeMillis() + Math.max(1, Math.min(secs, AlchemyDials.SPLASH_DOT_CAP.get().longValue())) * 1000L;
            mobWeakness.merge(mob.getUniqueId(), new double[]{until, weak}, (a, b) -> b[1] >= a[1] ? b : a);
            mob.getWorld().spawnParticle(Particle.SMOKE, mob.getLocation().add(0, mob.getHeight(), 0), 10, 0.3, 0.2, 0.3, 0.01);
        }
        if ("blindness".equals(eff.id)) {
            PotionEffectType blind = vanillaType("blindness");
            if (blind != null) mob.addPotionEffect(new PotionEffect(blind,
                    (int) Math.max(20, Math.min(secs, AlchemyDials.SPLASH_DOT_CAP.get().longValue()) * 20), 0, false, true));
            if (mob instanceof org.bukkit.entity.Mob m) m.setTarget(null);     // it loses track of you
        }
        double wound = l.param("heal_reduce");
        if (wound > 0) {
            // Wounded on a mob: every second, whatever it healed is cut (see tickWounded)
            long until = System.currentTimeMillis() + Math.max(1, Math.min(secs, AlchemyDials.SPLASH_DOT_CAP.get().longValue())) * 1000L;
            mobWounded.put(mob.getUniqueId(), new double[]{until, Math.min(100, wound), Sky.mobs().health(mob)});
            mob.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, mob.getLocation().add(0, mob.getHeight(), 0), 6, 0.3, 0.2, 0.3, 0.01);
        }
        double dps = l.param("poison_dps");
        if (dps > 0) {
            long cap = (long) Math.min(Math.max(1, secs), AlchemyDials.SPLASH_DOT_CAP.get());
            final int[] n = {0};
            Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
                if (!mob.isValid() || mob.isDead() || n[0]++ >= cap) { task.cancel(); return; }
                Sky.combat().rawDamage(mob, dps, thrower, DamageContext.Kind.MAGIC);
            }, 20L, 20L);
        }
    }

    private static void stun(LivingEntity mob, double seconds) {
        PotionEffectType slow = vanillaType("slowness");
        if (slow != null) mob.addPotionEffect(new PotionEffect(slow, (int) Math.round(seconds * 20), 9, false, true));
        mob.setVelocity(new Vector(0, Math.min(0, mob.getVelocity().getY()), 0));
        mob.getWorld().spawnParticle(Particle.CRIT, mob.getLocation().add(0, mob.getHeight(), 0), 8, 0.3, 0.2, 0.3, 0);
    }

    // ---- combat hooks ----------------------------------------------------------------------------

    /** DamageModifier (priority 350): Archery, Weakness, Dodge/Agility. */
    void modify(DamageContext ctx) {
        Player a = ctx.playerAttacker();
        if (a != null && Sky.profiles().isReady(a) && (ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.PROJECTILE)) {
            Map<String, Integer> eff = effective(a);
            if (!eff.isEmpty()) {
                if (ctx.kind == DamageContext.Kind.PROJECTILE) ctx.additive += param(eff, "archery");
                double weak = param(eff, "weakness");
                if (weak > 0) ctx.flatBonus -= weak;
            }
        }
        if (ctx.attacker != null && !(ctx.attacker instanceof Player) && !mobWeakness.isEmpty()) {
            double[] w = mobWeakness.get(ctx.attacker.getUniqueId());
            if (w != null) {
                if (w[0] < System.currentTimeMillis() || !ctx.attacker.isValid()) mobWeakness.remove(ctx.attacker.getUniqueId());
                else ctx.flatBonus -= w[1];
            }
        }
        Player v = ctx.playerVictim();
        if (v != null && ctx.attacker != null && !(ctx.attacker instanceof Player) && Sky.profiles().isReady(v)
                && (ctx.kind == DamageContext.Kind.MOB || ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.PROJECTILE)) {
            // Dodge makes mob ATTACKS miss; a mob's ability (tagged "ability", e.g. a slayer boss's Life Drain) is not dodged.
            if (ctx.tags.contains("ability")) return;
            Map<String, Integer> eff = effective(v);
            if (eff.isEmpty()) return;
            double c = chance(eff, "dodge");
            if (c > 0 && ThreadLocalRandom.current().nextDouble() < c) {
                ctx.cancelled = true;
                Sky.vitals().flash(v, "&a&lDODGED!", 20);
            }
        }
    }

    /** After a hit lands: Stun and Knockback. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player a = ctx.playerAttacker();
        if (a == null || ctx.victim == null || ctx.victim instanceof Player) return;
        if (ctx.kind != DamageContext.Kind.MELEE && ctx.kind != DamageContext.Kind.PROJECTILE) return;
        if (!Sky.profiles().isReady(a)) return;
        Map<String, Integer> eff = effective(a);
        if (eff.isEmpty()) return;
        LivingEntity victim = ctx.victim;
        double sc = param(eff, "stun_chance");
        if (sc > 0 && ThreadLocalRandom.current().nextDouble() * 100 < sc) stun(victim, Math.max(1, param(eff, "stun_secs")));
        double kb = param(eff, "knockback");
        if (kb > 0) {
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                if (!victim.isValid()) return;
                Vector v = victim.getVelocity();
                double f = 1 + kb / 100.0;
                victim.setVelocity(new Vector(v.getX() * f, v.getY(), v.getZ() * f));
            });
        }
    }

    /** Red Thornleaf Tea (a Resistance brew): a share of a monster's hit is thrown back at it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player v = ctx.playerVictim();
        if (v == null || ctx.attacker == null || ctx.attacker instanceof Player || !ctx.attacker.isValid()) return;
        if (ctx.kind == DamageContext.Kind.TRUE || ctx.kind == DamageContext.Kind.ENVIRONMENT) return;
        if (!(ctx.finalDamage > 0) || !Sky.profiles().isReady(v) || !Sky.mobs().isSkyMob(ctx.attacker)) return;
        AlchemyData d = data(v);
        Map<String, Integer> eff = effective(d, paused(v));
        if (eff.isEmpty()) return;
        double pct = Math.min(100, param(d, eff, "reflect"));
        if (pct <= 0) return;
        double back = ctx.finalDamage * pct / 100.0;
        LivingEntity mob = ctx.attacker;
        if (back >= 1) Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (mob.isValid() && !mob.isDead() && v.isOnline()) Sky.combat().rawDamage(mob, back, v, DamageContext.Kind.MAGIC);
        });
    }

    /** Tavern drinks that are not potions (Bitterclaw Brew): right-click to drink, purely for flavour. */
    @EventHandler(priority = EventPriority.LOW)
    public void onTavernDrink(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (!PotionItems.isTavernDrink(def)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!id.equals(Sky.items().idOf(held))) return;
        if (held.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        Msg.send(p, "&aYou drank " + def.rarity.code() + def.name + "&a. &7Sweet... then bitter... then it bites back.");
        Msg.sound(p, Sound.ENTITY_GENERIC_DRINK, 0.8f);
    }

    /** Water Breathing (chance to ignore drowning) and Fire Resistance (less fire/lava damage). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEnvironment(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !Sky.profiles().isReady(p)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        boolean drown = c == EntityDamageEvent.DamageCause.DROWNING;
        boolean fire = c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK
                || c == EntityDamageEvent.DamageCause.LAVA || c == EntityDamageEvent.DamageCause.HOT_FLOOR;
        if (!drown && !fire) return;
        Map<String, Integer> eff = effective(p);
        if (eff.isEmpty()) return;
        if (drown) {
            double ch = Math.min(100, param(eff, "drown_block"));
            if (ch > 0 && ThreadLocalRandom.current().nextDouble() * 100 < ch) e.setCancelled(true);
        } else {
            double cut = Math.min(100, param(eff, "fire_reduce"));
            if (cut > 0) e.setDamage(e.getDamage() * (1 - cut / 100.0));
        }
    }

    /** Burning: fire you set lasts longer. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent e) {
        Entity src = e.getCombuster();
        if (src instanceof Projectile pr && pr.getShooter() instanceof Entity sh) src = sh;
        if (!(src instanceof Player p) || !Sky.profiles().isReady(p)) return;
        double pct = param(effective(p), "burning");
        if (pct > 0) e.setDuration((float) (e.getDuration() * (1 + pct / 100.0)));
    }

    /** Experience: more from orbs. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onExp(PlayerExpChangeEvent e) {
        if (e.getAmount() <= 0 || !Sky.profiles().isReady(e.getPlayer())) return;
        double pct = param(data(e.getPlayer()), effective(e.getPlayer()), "exp");
        if (pct > 0) e.setAmount((int) Math.round(e.getAmount() * (1 + pct / 100.0)));
    }

    // ---- Features.Potions --------------------------------------------------------------------

    @Override
    public Map<String, Integer> active(Player p) { return new HashMap<>(effective(p)); }

    @Override
    public void apply(Player p, String effectId, int level, long seconds) {
        if (effectId == null) return;
        applyEffect(p, effectId.toLowerCase(Locale.ROOT), level, seconds, "api");
    }

    @Override
    public void openEffects(Player p) { AlchemyMenus.effects(p); }

    @Override
    public void openBag(Player p) { PotionBag.open(p); }
}
