package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Player-level enchant effects: the "enchanting" StatProvider (Respite, Riposte, Legion, Wisdom,
 * Bobber's Chorus, Cayenne, Quantum, Flowstate, Fatal Tempo, Refrigerate...), vanilla attributes
 * for Respiration / Depth Strider / Aqua Affinity / Feather Falling, bows (Infinite Quiver, Rend),
 * Bank on death, Ultimate Wise, Magnet, experience from ores and bottles, and the book-id normaliser.
 */
final class PlayerEnchants implements Listener {

    private static final Stat[] COMBAT = {Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.TRUE_DEFENSE, Stat.MAGIC_FIND};
    private static final Stat[] FISHING = {Stat.SEA_CREATURE_CHANCE, Stat.FISHING_SPEED, Stat.TREASURE_CHANCE,
            Stat.DOUBLE_HOOK_CHANCE, Stat.TROPHY_FISH_CHANCE, Stat.MAGIC_FIND};
    private static final Stat[] WISDOMS = {Stat.COMBAT_WISDOM, Stat.MINING_WISDOM, Stat.FARMING_WISDOM, Stat.FORAGING_WISDOM,
            Stat.FISHING_WISDOM, Stat.ENCHANTING_WISDOM, Stat.ALCHEMY_WISDOM, Stat.TAMING_WISDOM};

    private static NamespacedKey key(String k) { return new NamespacedKey(Sky.plugin(), "ench_" + k); }

    void register() {
        Sky.stats().addProvider("enchanting", this::stats);
        Sky.abilities().addCostModifier("enchanting_ultimate_wise", (use, cost) -> {
            if (!EnchantingModule.dials().effects.get() || use == null || use.item() == null) return cost;
            EnchantSpec s = EnchantingModule.reg().spec("ultimate_wise");
            int lvl = Gear.level(use.item(), "ultimate_wise");
            return s == null || lvl <= 0 ? cost : cost * Math.max(0, 1 - s.v(lvl) / 100.0);
        });
        Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), this::second, 20L, 20L);
    }

    // ---- stats ---------------------------------------------------------------------------------------

    private void stats(Player p, StatMap m) {
        Gear.refresh(p);
        if (!EnchantingModule.dials().effects.get()) return;
        UUID u = p.getUniqueId();
        EnchantRegistry reg = EnchantingModule.reg();

        if (DefenseEnchants.outOfCombat(p, 5000)) m.add(Stat.HEALTH_REGEN, Gear.wornSum(p, "respite"));
        m.add(Stat.DEFENSE, Buffs.get(u, "counter_strike"));
        m.add(Stat.DEFENSE, Buffs.get(u, "refrigerate"));
        double tempo = Buffs.get(u, "fatal_tempo");
        if (tempo > 0) m.addPercent(Stat.FEROCITY, tempo);

        double legion = Gear.wornSum(p, "ultimate_legion");
        if (legion > 0) {
            int n = 0;
            for (Player o : p.getWorld().getPlayers()) if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= 900) n++;
            n = Math.min(20, n);
            if (n > 0) for (Stat s : COMBAT) m.addPercent(s, legion * n);
        }
        double bobbin = Gear.wornSum(p, "ultimate_bobbin_time");
        if (bobbin > 0) {
            int n = 0;
            for (Entity e : p.getNearbyEntities(30, 30, 30)) if (e instanceof FishHook) n++;
            n = Math.min(5, n);
            if (n > 0) for (Stat s : FISHING) m.addPercent(s, bobbin * n);
        }
        EnchantSpec wis = reg.spec("ultimate_wisdom");
        if (wis != null) {
            double total = 0;
            for (Map<String, Integer> piece : Gear.worn(p)) {
                Integer l = piece.get("ultimate_wisdom");
                if (l != null && l > 0) total += Math.min(wis.v(1, l), wis.v(0, l) * Math.floor(p.getLevel() / 5.0));
            }
            m.add(Stat.INTELLIGENCE, total);
        }
        double quantum = Gear.wornSum(p, "quantum");
        if (quantum > 0) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            boolean weekend = today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!weekend) m.add(Stat.VITALITY, quantum);
            else m.add(WISDOMS[Math.floorMod(today.getDayOfYear() * 31 + u.hashCode(), WISDOMS.length)], Gear.wornSum(p, "quantum", 1));
        }
        double cayH = Gear.wornSum(p, "cayenne");
        if (cayH > 0) {
            int mp = Sky.service(Features.Accessories.class).magicalPower(p);
            int digits = mp <= 0 ? 0 : String.valueOf(mp).length();
            m.add(Stat.HEALTH, cayH * digits);
            m.add(Stat.TRUE_DEFENSE, Gear.wornSum(p, "cayenne", 1) * digits);
        }
        double heca = Gear.wornSum(p, "hecatomb", 2);
        if (heca > 0) {
            int cata = Sky.service(Features.Dungeons.class).catacombsLevel(p);
            m.add(Stat.HEALTH, heca * Math.floor(cata / 10.0));
        }
        // held-tool effects
        ItemStack held = Gear.held(p);
        Map<String, Integer> he = Gear.enchants(held);
        if (!he.isEmpty()) {
            int flow = he.getOrDefault("ultimate_flowstate", 0);
            if (flow > 0) {
                EnchantSpec fs = reg.spec("ultimate_flowstate");
                double blocks = Math.min(200, Buffs.get(u, "flowstate"));
                if (fs != null && blocks > 0) m.add(Stat.MINING_SPEED, fs.v(flow) * blocks);
            }
            m.add(Stat.SWEEP, Buffs.get(u, "first_impression"));
        }
        double pepper = Gear.wornSum(p, "ultimate_habanero_tactics", 2);
        if (pepper > 0 && CombatEnchants.isSlayerWeapon(held)) m.add(Stat.COMBAT_WISDOM, pepper);
    }

    // ---- once a second: vanilla attributes + Sanguine ----------------------------------------------

    private void second() {
        boolean on = EnchantingModule.dials().effects.get();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EnchantingModule.ready(p)) continue;
            double resp = on ? Gear.wornMax(p, "respiration") : 0;
            double depth = on ? Gear.wornSum(p, "depth_strider") / 100.0 : 0;
            double aqua = on && Gear.wears(p, "aqua_affinity") ? 0.8 : 0;
            double fall = on ? Gear.wornSum(p, "feather_falling") : 0;
            double fallCut = on ? Math.min(0.9, Gear.wornSum(p, "feather_falling", 1) / 100.0) : 0;
            attr(p, Attribute.OXYGEN_BONUS, "respiration", resp, AttributeModifier.Operation.ADD_NUMBER);
            attr(p, Attribute.WATER_MOVEMENT_EFFICIENCY, "depth_strider", Math.min(1.0, depth), AttributeModifier.Operation.ADD_NUMBER);
            attr(p, Attribute.SUBMERGED_MINING_SPEED, "aqua_affinity", aqua, AttributeModifier.Operation.ADD_NUMBER);
            attr(p, Attribute.SAFE_FALL_DISTANCE, "feather_falling", fall, AttributeModifier.Operation.ADD_NUMBER);
            attr(p, Attribute.FALL_DAMAGE_MULTIPLIER, "feather_falling_cut", -fallCut, AttributeModifier.Operation.ADD_SCALAR);
            if (!on) continue;
            // Sanguine (Transylvanian): heal per nearby enemy, capped
            double per = Gear.wornSum(p, "transylvanian");
            if (per > 0) {
                int n = 0;
                for (Entity e : p.getNearbyEntities(10, 10, 10)) if (e instanceof LivingEntity le && !(e instanceof Player) && Sky.mobs().isSkyMob(le)) n++;
                double cap = Gear.wornSum(p, "transylvanian", 1);
                if (n > 0) Sky.vitals().heal(p, Math.min(cap, per * Math.min(10, n)));
            }
        }
    }

    private static void attr(Player p, Attribute a, String id, double value, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        NamespacedKey k = key(id);
        AttributeModifier cur = null;
        for (AttributeModifier m : inst.getModifiers()) if (k.equals(m.getKey())) { cur = m; break; }
        if (cur != null && Math.abs(cur.getAmount() - value) < 1e-9) return;
        if (cur != null) inst.removeModifier(cur);
        if (Math.abs(value) > 1e-9) inst.addModifier(new AttributeModifier(k, value, op));
    }

    // ---- bows ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !EnchantingModule.dials().effects.get()) return;
        EnchantSpec s = EnchantingModule.reg().spec("infinite_quiver");
        int lvl = Gear.level(e.getBow(), "infinite_quiver");
        if (s != null && lvl > 0 && ThreadLocalRandom.current().nextDouble() * 100 < s.v(lvl)) e.setConsumeItem(false);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.LEFT_CLICK_AIR && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!EnchantingModule.dials().effects.get()) return;
        int lvl = Gear.level(Gear.held(p), "ultimate_rend");
        if (lvl <= 0) return;
        EnchantSpec s = EnchantingModule.reg().spec("ultimate_rend");
        if (s == null || !Buffs.cooldown(p.getUniqueId(), "rend", 2000)) return;
        Rend.use(p, s.v(lvl));   // wave 5: your arrows stuck in each enemy x your last crit on THAT enemy
    }

    // ---- death, abilities, fishing, gathering -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(SkyEvents.SkyDeath e) {
        if (!EnchantingModule.dials().effects.get()) return;
        double keep = Math.min(100, Gear.wornSum(e.player, "ultimate_bank"));
        if (keep > 0 && e.coinsLost > 0) e.coinsLost *= (1 - keep / 100.0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbility(SkyEvents.AbilityUsed e) {
        if (!EnchantingModule.dials().effects.get() || e.ability == null) return;
        double pct = Gear.wornSum(e.player, "ultimate_refrigerate");
        if (pct <= 0) return;
        AbilityDef a = e.ability;
        double mana = a.mana > 0 && a.mana < 1 ? a.mana * Sky.vitals().maxMana(e.player) : a.mana;
        int pieces = 0;
        for (Map<String, Integer> m : Gear.worn(e.player)) if (m.getOrDefault("ultimate_refrigerate", 0) > 0) pieces++;
        double def = Math.min(150.0 * Math.max(1, pieces), mana * pct / 100.0);
        if (def > 0) { Buffs.set(e.player.getUniqueId(), "refrigerate", def, 10_000); Sky.stats().invalidate(e.player); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(SkyEvents.FishCaught e) {
        if (!EnchantingModule.ready(e.player)) return;
        EnchantSpec s = EnchantingModule.reg().spec("magnet");
        int lvl = Gear.level(Gear.held(e.player), "magnet");
        int exp = 1 + ThreadLocalRandom.current().nextInt(3);   // TUNE: a catch drops a few orbs, like vanilla fishing
        if (s != null && lvl > 0 && EnchantingModule.dials().effects.get()) exp += (int) Math.round(s.v(lvl));
        EnchantingModule.giveExp(e.player, exp);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (!EnchantingModule.ready(p)) return;
        boolean on = EnchantingModule.dials().effects.get();
        if (e.kind == LootSource.MINING) {
            int exp = EnchantingModule.dials().oreExp.get() ? oreExp(e.what) : 0;
            ItemStack held = Gear.held(p);
            if (on && exp > 0) {
                EnchantSpec s = EnchantingModule.reg().spec("experience");
                int lvl = Gear.level(held, "experience");
                if (s != null && lvl > 0 && ThreadLocalRandom.current().nextDouble() * 100 < s.v(lvl)) exp *= 2;
            }
            if (exp > 0) EnchantingModule.giveExp(p, exp);
            if (on && Gear.level(held, "ultimate_flowstate") > 0) {
                double blocks = Buffs.get(p.getUniqueId(), "flowstate");
                Buffs.set(p.getUniqueId(), "flowstate", Math.min(200, blocks + 1), 10_000);
            }
        } else if (e.kind == LootSource.FORAGING && on) {
            EnchantSpec s = EnchantingModule.reg().spec("ultimate_first_impression");
            int lvl = Gear.level(Gear.held(p), "ultimate_first_impression");
            if (s != null && lvl > 0) {
                // TUNE: the reference's "first log of a tree" bonus is applied when no streak is running
                double v = Buffs.active(p.getUniqueId(), "first_impression") ? s.v(lvl) : s.v(1, lvl);
                Buffs.set(p.getUniqueId(), "first_impression", v, 5000);
            }
        }
    }

    /** Vanilla-like experience per ore (TUNE: vanilla averages). */
    static int oreExp(String what) {
        if (what == null) return 0;
        String w = what.toUpperCase(Locale.ROOT);
        if (w.contains("DIAMOND") || w.contains("EMERALD")) return 5;
        if (w.contains("TITANIUM")) return 4;
        if (w.contains("LAPIS") || w.contains("QUARTZ") || w.contains("REDSTONE")) return 3;
        if (w.contains("MITHRIL") || w.contains("GEM") || w.contains("RUBY") || w.contains("AMBER") || w.contains("JADE")
                || w.contains("SAPPHIRE") || w.contains("AMETHYST") || w.contains("TOPAZ") || w.contains("JASPER") || w.contains("OPAL")) return 2;
        if (w.contains("COAL") || w.contains("GOLD") || w.contains("IRON") || w.contains("GLACITE") || w.contains("UMBER")
                || w.contains("TUNGSTEN")) return 1;
        return 0;
    }

    // ---- experience bottles ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onBottle(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        if (id == null) return;
        EDials d = EnchantingModule.dials();
        int exp = switch (id) {
            case "GRAND_EXP_BOTTLE" -> d.grandBottleExp.get();
            case "TITANIC_EXP_BOTTLE" -> d.titanicBottleExp.get();
            case "COLOSSAL_EXP_BOTTLE" -> d.colossalBottleExp.get();
            default -> -1;
        };
        if (exp < 0) return;
        e.setCancelled(true);
        if (!EnchantingModule.ready(p)) return;
        if (Sky.items().remove(p, id, 1) < 1) return;
        EnchantingModule.giveExp(p, exp);
        Msg.sound(p, Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        ItemDef def = Sky.items().def(id);
        Sky.vitals().flash(p, "&a+" + exp + " Experience &7(" + (def == null ? id : def.name) + "&7)", 40);
    }

    // ---- book-id normaliser (SHARPNESS;5 -> ENCHANTMENT_SHARPNESS_5) ---------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGain(SkyEvents.ItemGain e) {
        if (e.itemId == null || e.itemId.indexOf(';') < 0 || !EnchantingModule.dials().normaliseAliases.get()) return;
        EnchantRegistry.BookRef b = EnchantingModule.reg().parseBook(e.itemId);
        if (b == null) return;
        e.setCancelled(true);
        Sky.loot().give(e.player, EnchantRegistry.bookId(b.enchant(), b.level()), Math.max(1, e.amount), e.source);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p && EnchantingModule.dials().normaliseAliases.get()) normalise(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        if (EnchantingModule.dials().normaliseAliases.get()) normalise(e.player);
    }

    static void normalise(Player p) {
        PlayerInventory inv = p.getInventory();
        EnchantRegistry reg = EnchantingModule.reg();
        for (int i = 0; i < 41; i++) {   // 0-35 storage, 36-39 armour, 40 off hand
            ItemStack s = inv.getItem(i);
            String id = s == null ? null : Sky.items().idOf(s);
            if (id == null) continue;
            if (i < 36 && id.indexOf(';') >= 0) {
                EnchantRegistry.BookRef b = reg.parseBook(id);
                if (b != null) { inv.setItem(i, Sky.items().create(EnchantRegistry.bookId(b.enchant(), b.level()), s.getAmount())); continue; }
            }
            // an enchant stored under an alias id ("bank") only renders; the effects read the canonical id
            ItemData d = Sky.items().data(s);
            if (d == null || d.enchants == null || d.enchants.isEmpty()) continue;
            boolean any = false;
            for (String k : d.enchants.keySet()) if (reg.isAlias(k)) { any = true; break; }
            if (!any) continue;
            Sky.items().update(s, x -> canonicalise(reg, x.enchants));
            inv.setItem(i, s);
        }
    }

    /** Rewrites alias keys to canonical ids in place; on a clash the higher level wins. Pure. */
    static void canonicalise(EnchantRegistry reg, Map<String, Integer> enchants) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> en : enchants.entrySet()) {
            String k = reg.isAlias(en.getKey()) ? reg.canonical(en.getKey()) : en.getKey();
            out.merge(k, en.getValue(), Math::max);
        }
        enchants.clear();
        enchants.putAll(out);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Gear.forget(u);
        Buffs.forget(u);
        CombatEnchants.forget(u);
        DefenseEnchants.forget(u);
    }
}
